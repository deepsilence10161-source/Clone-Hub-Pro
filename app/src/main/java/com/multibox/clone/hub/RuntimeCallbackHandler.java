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
            SettingsCompatibility.apply((android.app.Application) args[1]);
        }
        return null;
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        android.util.Log.e("RuntimeCallback", "Consumed runtime callback failure on " + thread,
                throwable);
    }
}
