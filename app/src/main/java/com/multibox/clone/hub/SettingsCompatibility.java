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

    private static void exposeUnmanagedUserRestrictions(Application application) {
        try {
            UserManager userManager = application.getSystemService(UserManager.class);
            Field serviceField = UserManager.class.getDeclaredField("mService");
            serviceField.setAccessible(true);
            Object original = serviceField.get(userManager);
            Class<?> serviceInterface = Class.forName("android.os.IUserManager");
            Object compatible = Proxy.newProxyInstance(
                    serviceInterface.getClassLoader(), new Class<?>[]{serviceInterface},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("getUserRestrictionSources".equals(name)) {
                            return 0; // UserManager.RESTRICTION_NOT_SET
                        }
                        if (name.startsWith("hasUserRestriction")
                                || "hasBaseUserRestriction".equals(name)) {
                            return false;
                        }
                        if ("getUserRestrictions".equals(name)) {
                            return new Bundle();
                        }
                        try {
                            return method.invoke(original, args);
                        } catch (InvocationTargetException error) {
                            throw error.getCause();
                        }
                    });
            serviceField.set(userManager, compatible);
            Log.i(TAG, "Exposed unmanaged user restrictions to cloned Settings");
        } catch (Throwable error) {
            Log.e(TAG, "Could not apply Settings user restriction compatibility", error);
        }
    }
}
