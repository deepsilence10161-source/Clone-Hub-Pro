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
        return null;
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        // The original host deliberately consumes runtime callback thread failures.
    }
}
