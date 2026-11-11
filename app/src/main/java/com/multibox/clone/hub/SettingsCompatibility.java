package com.multibox.clone.hub;

import android.app.Application;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserManager;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

/**
 * Narrow compatibility adaptations for AOSP Settings running without its privileged UID.
 *
 * <p>Settings is written for the platform build: it calls system-server APIs that only the
 * privileged Settings package may use. Inside a cloned, unprivileged process those calls throw
 * {@link SecurityException} from framework helpers that never expect it, which kills the process.
 * Each guard below only replaces read-only calls that have a meaningful "nothing to report"
 * answer, and forwards every other call to the original binder.</p>
 */
final class SettingsCompatibility {
    private static final String TAG = "SettingsCompat";
    private static final String SETTINGS_PACKAGE = "com.android.settings";

    /** Sentinel telling the guard to forward the call to the real binder. */
    private static final Object NOT_HANDLED = new Object();

    private interface CallOverride {
        Object handle(String methodName, Object[] args) throws Throwable;
    }

    private SettingsCompatibility() {
    }

    static void apply(Application application) {
        if (!SETTINGS_PACKAGE.equals(application.getPackageName())) {
            return;
        }
        disablePrivilegedMetrics(application);
        Object guardedUserService = installGuardedBinder(
                "user", "android.os.IUserManager", SettingsCompatibility::guardUserRestrictions);
        installGuardedBinder(
                "usb", "android.hardware.usb.IUsbManager", SettingsCompatibility::guardUsbPorts);
        patchApplicationUserManager(application, guardedUserService);
    }

    private static void disablePrivilegedMetrics(Application application) {
        try {
            ClassLoader loader = application.getClassLoader();
            Class<?> factoryClass = Class.forName(
                    "com.android.settings.overlay.FeatureFactory", true, loader);
            Object factory = factoryClass.getMethod("getFactory", android.content.Context.class)
                    .invoke(null, application);
            Object metrics = factoryClass.getMethod("getMetricsFeatureProvider").invoke(factory);
            Field writersField = null;
            Class<?> metricsClass = metrics.getClass();
            while (metricsClass != null && writersField == null) {
                try {
                    writersField = metricsClass.getDeclaredField("mLoggerWriters");
                } catch (NoSuchFieldException ignored) {
                    metricsClass = metricsClass.getSuperclass();
                }
            }
            if (writersField == null) {
                throw new NoSuchFieldException("mLoggerWriters");
            }
            writersField.setAccessible(true);
            ((List<?>) writersField.get(metrics)).clear();
            Log.i(TAG, "Disabled Settings analytics requiring READ_DEVICE_CONFIG");
        } catch (Throwable error) {
            Log.e(TAG, "Could not apply Settings metrics compatibility", error);
        }
    }

    private static Object guardUserRestrictions(String name, Object[] args) {
        if ("getUserRestrictionSources".equals(name)) {
            // The internal IUserManager method returns EnforcingUser records; the public
            // UserManager facade turns an empty list into RESTRICTION_NOT_SET.
            return java.util.Collections.emptyList();
        }
        if (name.startsWith("hasUserRestriction") || "hasBaseUserRestriction".equals(name)) {
            return false;
        }
        if ("getUserRestrictions".equals(name)) {
            return new Bundle();
        }
        return NOT_HANDLED;
    }

    private static Object guardUsbPorts(String name, Object[] args) {
        if ("getPorts".equals(name)) {
            // Reading USB port status needs MANAGE_USB; an unprivileged Settings cannot and
            // does not need to report incompatible chargers.
            return java.util.Collections.emptyList();
        }
        return NOT_HANDLED;
    }

    /**
     * Replaces one cached system-service binder with a proxy that answers the guarded calls
     * locally and forwards everything else, including binder identity methods.
     */
    private static Object installGuardedBinder(String serviceName, String interfaceName,
            CallOverride override) {
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            IBinder original = (IBinder) serviceManager.getMethod("getService", String.class)
                    .invoke(null, serviceName);
            if (original == null) {
                Log.w(TAG, "No binder for " + serviceName);
                return null;
            }
            Class<?> iface = Class.forName(interfaceName);
            String descriptor = interfaceDescriptor(iface, interfaceName);
            ClassLoader loader = iface.getClassLoader();

            Object interfaceProxy = Proxy.newProxyInstance(loader,
                    new Class<?>[]{iface, IBinder.class},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == IBinder.class) {
                            return forward(original, method, args);
                        }
                        Object result = override.handle(method.getName(), args);
                        return result == NOT_HANDLED ? forward(original, method, args) : result;
                    });

            // Any AIDL Stub.asInterface() call in this process resolves to the guarded interface,
            // including the ones made later by Activity contexts through SystemServiceRegistry.
            IBinder binderProxy = (IBinder) Proxy.newProxyInstance(loader,
                    new Class<?>[]{IBinder.class},
                    (proxy, method, args) -> {
                        if ("queryLocalInterface".equals(method.getName())) {
                            return interfaceProxy;
                        }
                        return forward(original, method, args);
                    });

            serviceCache(serviceManager).put(serviceName, binderProxy);
            Log.i(TAG, "Guarded " + serviceName + " binder for unprivileged Settings ("
                    + descriptor + ")");
            return interfaceProxy;
        } catch (Throwable error) {
            Log.e(TAG, "Could not guard " + serviceName + " compatibility", error);
            return null;
        }
    }

    /** AIDL keeps DESCRIPTOR on Stub in some builds and on the interface in others. */
    private static String interfaceDescriptor(Class<?> iface, String fallback) {
        for (String holder : new String[]{iface.getName(), iface.getName() + "$Stub"}) {
            try {
                Object value = Class.forName(holder, false, iface.getClassLoader())
                        .getField("DESCRIPTOR").get(null);
                if (value instanceof String) {
                    return (String) value;
                }
            } catch (Throwable ignored) {
                // Try the next holder.
            }
        }
        return fallback;
    }

    /** Repoints the already created Application UserManager at the guarded binder. */
    private static void patchApplicationUserManager(Application application,
            Object guardedUserService) {
        try {
            UserManager userManager = application.getSystemService(UserManager.class);
            if (userManager == null || guardedUserService == null) {
                Log.w(TAG, "Nothing to repoint for user service");
                return;
            }
            Field serviceField = UserManager.class.getDeclaredField("mService");
            serviceField.setAccessible(true);
            serviceField.set(userManager, guardedUserService);
            Log.i(TAG, "Exposed unmanaged user restrictions to all cloned Settings contexts");
        } catch (Throwable error) {
            Log.e(TAG, "Could not apply Settings user restriction compatibility", error);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, IBinder> serviceCache(Class<?> serviceManager) throws Exception {
        Field cacheField = serviceManager.getDeclaredField("sCache");
        cacheField.setAccessible(true);
        return (Map<String, IBinder>) cacheField.get(null);
    }

    private static Object forward(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }
}
