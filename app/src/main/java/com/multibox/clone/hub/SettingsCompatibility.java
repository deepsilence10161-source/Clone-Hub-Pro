package com.multibox.clone.hub;

import android.app.Application;
import android.os.Bundle;
import android.os.UserManager;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;

/** Narrow compatibility adaptations for AOSP Settings running without its privileged UID. */
final class SettingsCompatibility {
    private static final String TAG = "SettingsCompat";
    private static final String SETTINGS_PACKAGE = "com.android.settings";

    private SettingsCompatibility() {
    }

    static void apply(Application application) {
        if (!SETTINGS_PACKAGE.equals(application.getPackageName())) {
            return;
        }
        disablePrivilegedMetrics(application);
        exposeUnmanagedUserRestrictions(application);
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

    private static Object createCompatibleUserService(Object original,
            android.os.IBinder originalBinder, Class<?> serviceInterface) {
        return Proxy.newProxyInstance(serviceInterface.getClassLoader(),
                new Class<?>[]{serviceInterface, android.os.IBinder.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("queryLocalInterface".equals(name) && args != null
                            && args.length == 1
                            && "android.os.IUserManager".equals(args[0])) {
                        return proxy;
                    }
                    if ("getUserRestrictionSources".equals(name)) {
                        // Internal IUserManager returns EnforcingUser records; the public
                        // UserManager facade converts an empty list to RESTRICTION_NOT_SET.
                        return java.util.Collections.emptyList();
                    }
                    if (name.startsWith("hasUserRestriction")
                            || "hasBaseUserRestriction".equals(name)) {
                        return false;
                    }
                    if ("getUserRestrictions".equals(name)) {
                        return new Bundle();
                    }
                    Object target = method.getDeclaringClass() == android.os.IBinder.class
                            ? originalBinder : original;
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
    }

    private static void exposeUnmanagedUserRestrictions(Application application) {
        try {
            UserManager userManager = application.getSystemService(UserManager.class);
            Field serviceField = UserManager.class.getDeclaredField("mService");
            serviceField.setAccessible(true);
            Object original = serviceField.get(userManager);
            Class<?> serviceInterface = Class.forName("android.os.IUserManager");
            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            android.os.IBinder originalBinder = (android.os.IBinder) serviceManagerClass
                    .getMethod("getService", String.class).invoke(null, "user");
            Object compatible = createCompatibleUserService(
                    original, originalBinder, serviceInterface);
            serviceField.set(userManager, compatible);
            // Activity and PreferenceController contexts create their own UserManager wrappers.
            // Patch the cloned process's cached binder too, not just the Application's instance.
            Field cacheField = serviceManagerClass.getDeclaredField("sCache");
            cacheField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, android.os.IBinder> cache =
                    (java.util.Map<String, android.os.IBinder>) cacheField.get(null);
            cache.put("user", (android.os.IBinder) compatible);
            Log.i(TAG, "Exposed unmanaged user restrictions to all cloned Settings contexts");
        } catch (Throwable error) {
            Log.e(TAG, "Could not apply Settings user restriction compatibility", error);
        }
    }
}
