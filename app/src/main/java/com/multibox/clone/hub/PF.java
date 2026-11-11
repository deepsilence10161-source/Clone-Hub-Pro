package com.multibox.clone.hub;

import android.content.ContentProvider;
import android.content.ContentProviderClient;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.lang.reflect.Method;

public abstract class PF extends ContentProvider {
    /** Runtime service call that keeps the agent lifecycle alive. */
    private static final String METHOD_SERVICE = "995";
    private static final long HEARTBEAT_ACTIVE_MS = 10_000L;
    private static final long HEARTBEAT_IDLE_MS = 60_000L;
    private static final String TAG_DIAGNOSTIC = "AgentDiagnostic";
    private static final String TAG_RUNTIME = "RuntimeProvider";
    private static final String SERVICE_AUTHORITY =
            "com.clonehub.pro.virtual.container.server0001.Jrhl$Service";
    private static final String LEGACY_RUNTIME_SERVER_AUTHORITY =
            "com.multibox.clone.hub.com.multibox.clone.hub.Jrhl$Service";
    private String providerAuthority;
    private ContentProviderClient stableSelfClient;
    private int runtimeMode = -1;
    private int heartbeatCount;
    private boolean hostProcessRegistered;
    private ContentProviderClient heartbeatClient;
    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    /**
     * Keeps the agent process from being reaped by the platform.
     *
     * This used to run every 5 seconds, acquire a fresh provider client on every tick and dump
     * /proc state each time. It now keeps one client, re-acquires only when the binder died, and
     * backs off to [HEARTBEAT_IDLE_MS] while the engine is unreachable.
     */
    private final Runnable runtimeHeartbeat = new Runnable() {
        @Override
        public void run() {
            Context context = getContext();
            boolean healthy = false;
            if (context != null && runtimeMode == 1) {
                try {
                    if (heartbeatClient == null) {
                        heartbeatClient = context.getContentResolver()
                                .acquireUnstableContentProviderClient(SERVICE_AUTHORITY);
                    }
                    if (heartbeatClient != null) {
                        heartbeatClient.call(METHOD_SERVICE, null, Bundle.EMPTY);
                        healthy = true;
                        heartbeatCount++;
                    }
                } catch (Throwable error) {
                    Log.e(TAG_DIAGNOSTIC, "Runtime heartbeat failed", error);
                    closeHeartbeatClient();
                }
            }
            long nextDelay = healthy ? HEARTBEAT_ACTIVE_MS : HEARTBEAT_IDLE_MS;
            heartbeatHandler.removeCallbacks(this);
            heartbeatHandler.postDelayed(this, nextDelay);
        }
    };

    private void closeHeartbeatClient() {
        ContentProviderClient client = heartbeatClient;
        heartbeatClient = null;
        if (client != null) {
            try {
                client.close();
            } catch (Throwable ignored) {
                // Nothing to recover; a new client is acquired on the next tick.
            }
        }
    }

    @Override
    public void attachInfo(Context context, ProviderInfo providerInfo) {
        tc.a = 0;
        providerAuthority = providerInfo.authority;
        super.attachInfo(context, providerInfo);
        try {
            AbstractC2211qP.l = AbstractC2211qP.g(context);
            ClassLoader runtimeLoader = AbstractC2211qP.l;
            Method declaredMethod = Class.forName("com.multibox.clone.hub.RXT", true, runtimeLoader)
                    .getDeclaredMethod("eakt", Integer.TYPE, Object[].class);
            AbstractC2211qP.k = declaredMethod;
            ProviderInfo runtimeProviderInfo = providerInfo;
            boolean serverProvider = SERVICE_AUTHORITY.equals(providerAuthority);
            if (serverProvider) {
                // The bundled engine selects server mode from its legacy authority.
                // Give it a temporary descriptor only during mode selection; Android
                // sees and registers exclusively our non-conflicting Pro authority.
                runtimeProviderInfo = new ProviderInfo(providerInfo);
                runtimeProviderInfo.authority = LEGACY_RUNTIME_SERVER_AUTHORITY;
            }
            Object mode = declaredMethod.invoke(null, 1000, new Object[]{context, runtimeProviderInfo, -1,
                    RuntimeCallbackHandler.INSTANCE, (byte) 0});
            if (serverProvider) {
                runtimeProviderInfo.authority = SERVICE_AUTHORITY;
            }
            // Every provider initialization recomputes this field. Always restore the
            // Pro transport so later runtime calls never cross into the real Clone Hub.
            Class.forName("com.multibox.clone.hub.r8", true, runtimeLoader)
                    .getDeclaredField("OooO00o").set(null, SERVICE_AUTHORITY);
            runtimeMode = mode instanceof Number ? ((Number) mode).intValue() : -1;
            Log.i("RuntimeProvider", "Initialized authority=" + providerInfo.authority
                    + ", process=" + android.os.Process.myPid() + ", mode=" + mode);
        } catch (Throwable th) {
            th.printStackTrace();
            throw new RuntimeException("Virtual container provider initialization error", th);
        }
    }

    @Override
    public boolean onCreate() {
        Context context = getContext();
        Log.i("AgentDiagnostic", "Provider onCreate; authority="
                + (context == null ? null : context.getPackageName()));
        if (context != null && providerAuthority != null) {
            // Android expires an otherwise idle published provider after roughly 20 seconds.
            // Agent processes host the real cloned Activity, so allowing that expiry kills a
            // foreground clone. Acquire after publication (the main loop runs after onCreate)
            // and retain the stable client for the provider/process lifetime.
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    stableSelfClient = context.getContentResolver()
                            .acquireContentProviderClient(providerAuthority);
                    Log.i("AgentDiagnostic", "Stable provider client acquired; authority="
                            + providerAuthority + ", client=" + stableSelfClient);
                } catch (Throwable error) {
                    Log.e("AgentDiagnostic", "Unable to retain agent provider", error);
                }
            });
        }
        heartbeatHandler.postDelayed(runtimeHeartbeat, HEARTBEAT_ACTIVE_MS);
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        try {
            Bundle result = AbstractC2211qP.c(method, arg, extras);
            if (METHOD_SERVICE.equals(method)) {
                ensureHostProcessRecord();
            }
            return result;
        } catch (Throwable th) {
            // The runtime expects a Bundle, so an empty one stays the fallback; the failure is
            // logged at error level instead of being silently swallowed.
            Log.e(TAG_DIAGNOSTIC, "Runtime call failed: method=" + method, th);
            return Bundle.EMPTY;
        }
    }

    /**
     * Registers this host package in the runtime's process registry.
     *
     * This is not a diagnostic: the bundled runtime looks the *host* package up in its process
     * registry before it can create an agent process for a clone. Without the entry the launch
     * transaction still answers "success" (code 0) while no cloned process is ever forked, so the
     * host must publish the record once the runtime has handled its first service call.
     *
     * It used to run inside a large reflection dump; only the state-changing part is kept, it is
     * idempotent, and a failure is reported with its root cause instead of being swallowed (the
     * earlier dump hid an InvocationTargetException here, which then surfaced as an unexplained
     * NullPointerException inside the runtime's launch path).
     */
    @SuppressWarnings("unchecked")
    private synchronized void ensureHostProcessRecord() {
        if (hostProcessRegistered) {
            return;
        }
        try {
            Context context = getContext();
            if (context == null) {
                return;
            }
            ClassLoader loader = AbstractC2211qP.l;
            Class<?> processClass = Class.forName("com.multibox.clone.hub.o000OO0O", true, loader);
            Object processes = processClass.getDeclaredMethod("OooO0Oo").invoke(null);
            Class<?> processServiceClass = Class.forName("com.multibox.clone.hub.qf", true, loader);
            Object holder = processServiceClass.getField("OooO00o").get(null);
            Object processService = holder.getClass().getDeclaredMethod("OooO0O0").invoke(holder);
            Class<?> hostRecordClass = Class.forName("com.multibox.clone.hub.o000O", true, loader);
            Object hostRecord = hostRecordClass
                    .getDeclaredConstructor(android.os.IBinder.class, String.class)
                    .newInstance(processService, context.getPackageName());
            Object registry = processClass.getField("OooO00o").get(processes);
            if (registry instanceof java.util.Map) {
                java.util.Map<String, Object> map = (java.util.Map<String, Object>) registry;
                map.put(context.getPackageName(), hostRecord);
                hostProcessRegistered = true;
                Log.i(TAG_RUNTIME, "Host process record registered for " + context.getPackageName()
                        + "; registrySize=" + map.size());
            } else {
                Log.e(TAG_RUNTIME, "Runtime process registry has unexpected type: "
                        + (registry == null ? "null" : registry.getClass().getName()));
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            Log.e(TAG_RUNTIME, "Host process registration failed inside the runtime",
                    e.getTargetException());
        } catch (Throwable th) {
            Log.e(TAG_RUNTIME, "Host process registration failed", th);
        }
    }

    @Override
    public Bundle call(String authority, String method, String arg, Bundle extras) {
        return call(method, arg, extras);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
