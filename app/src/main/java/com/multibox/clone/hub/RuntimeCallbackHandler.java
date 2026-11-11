package com.multibox.clone.hub;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Callback map/handler required by the bundled runtime during provider setup.
 * This mirrors the original runtime host's singleton contract.
 */
public final class RuntimeCallbackHandler extends ConcurrentHashMap<Object, Object>
        implements InvocationHandler, Thread.UncaughtExceptionHandler {

    public static final RuntimeCallbackHandler INSTANCE = new RuntimeCallbackHandler();
    public final ConcurrentHashMap<Object, Object> callbacks = new ConcurrentHashMap<>();

    private RuntimeCallbackHandler() {
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        android.util.Log.i("RuntimeCallback", "invoke proxy=" + proxy + ", method=" + method
                + ", args=" + java.util.Arrays.toString(args));
        if (Integer.valueOf(100).equals(proxy) && args != null && args.length >= 2
                && Integer.valueOf(103).equals(args[0])
                && args[1] instanceof android.app.Application) {
            disablePrivilegedSettingsMetrics((android.app.Application) args[1]);
        }
        return null;
    }

    private void disablePrivilegedSettingsMetrics(android.app.Application application) {
        if (!"com.android.settings".equals(application.getPackageName())) {
            return;
        }
        try {
            ClassLoader loader = application.getClassLoader();
            Class<?> factoryClass = Class.forName(
                    "com.android.settings.overlay.FeatureFactory", true, loader);
            Object factory = factoryClass.getMethod("getFactory", android.content.Context.class)
                    .invoke(null, application);
            Object metrics = factoryClass.getMethod("getMetricsFeatureProvider").invoke(factory);
            java.lang.reflect.Field writersField = null;
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
            ((java.util.List<?>) writersField.get(metrics)).clear();
            android.util.Log.i("RuntimeCallback",
                    "Disabled privileged analytics inside cloned Settings");
        } catch (Throwable error) {
            android.util.Log.e("RuntimeCallback",
                    "Could not apply cloned Settings metrics compatibility", error);
        }
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        android.util.Log.e("RuntimeCallback", "Consumed runtime callback failure on " + thread,
                throwable);
    }
}
