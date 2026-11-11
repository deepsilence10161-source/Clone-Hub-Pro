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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public abstract class PF extends ContentProvider {
    private static final String SERVICE_AUTHORITY =
            "com.clonehub.pro.virtual.container.server0001.Jrhl$Service";
    private static final String LEGACY_RUNTIME_SERVER_AUTHORITY =
            "com.multibox.clone.hub.com.multibox.clone.hub.Jrhl$Service";
    private String providerAuthority;
    private ContentProviderClient stableSelfClient;
    private int runtimeMode = -1;
    private int heartbeatCount;
    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private final Runnable runtimeHeartbeat = new Runnable() {
        @Override
        public void run() {
            Context context = getContext();
            if (context != null && runtimeMode == 1) {
                ContentProviderClient client = null;
                try {
                    client = context.getContentResolver().acquireUnstableContentProviderClient(
                            SERVICE_AUTHORITY);
                    if (client != null) {
                        Bundle heartbeat = client.call("995", null, Bundle.EMPTY);
                        Log.d("AgentDiagnostic", "Runtime heartbeat; pid="
                                + android.os.Process.myPid() + ", connected=" + (heartbeat != null));
                        heartbeatCount++;
                        if (heartbeatCount == 2) {
                            dumpAgentProcessDiagnostics();
                        }
                    }
                } catch (Throwable error) {
                    Log.e("AgentDiagnostic", "Runtime heartbeat failed", error);
                } finally {
                    if (client != null) {
                        client.close();
                    }
                }
            }
            heartbeatHandler.postDelayed(this, 5_000L);
        }
    };

    private void dumpAgentProcessDiagnostics() {
        try {
            java.io.File taskDirectory = new java.io.File("/proc/self/task");
            java.io.File[] tasks = taskDirectory.listFiles();
            if (tasks != null) {
                java.util.Arrays.sort(tasks, java.util.Comparator.comparing(java.io.File::getName));
                for (java.io.File task : tasks) {
                    String name = new String(java.nio.file.Files.readAllBytes(
                            new java.io.File(task, "comm").toPath())).trim();
                    String wait = new String(java.nio.file.Files.readAllBytes(
                            new java.io.File(task, "wchan").toPath())).trim();
                    String syscall;
                    try {
                        syscall = new String(java.nio.file.Files.readAllBytes(
                                new java.io.File(task, "syscall").toPath())).trim();
                    } catch (Throwable denied) {
                        syscall = "denied";
                    }
                    Log.i("AgentNative", "task=" + task.getName() + ", name=" + name
                            + ", wait=" + wait + ", syscall=" + syscall);
                }
            }
            String status = new String(java.nio.file.Files.readAllBytes(
                    new java.io.File("/proc/self/status").toPath()));
            for (String line : status.split("\\n")) {
                if (line.startsWith("TracerPid:") || line.startsWith("State:")
                        || line.startsWith("PPid:")) {
                    Log.i("AgentNative", line);
                }
            }
            String maps = new String(java.nio.file.Files.readAllBytes(
                    new java.io.File("/proc/self/maps").toPath()));
            for (String line : maps.split("\\n")) {
                if (line.contains("libwnublp") || line.contains("libclonehub")
                        || line.contains("libwnublpl")) {
                    Log.i("AgentNative", line);
                }
            }
        } catch (Throwable error) {
            Log.e("AgentNative", "Could not read agent process diagnostics", error);
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
        if (runtimeMode == 1) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Log.e("AgentDiagnostic", "Agent shutdown hook; pid=" + android.os.Process.myPid());
                for (java.util.Map.Entry<Thread, StackTraceElement[]> entry
                        : Thread.getAllStackTraces().entrySet()) {
                    StringBuilder stack = new StringBuilder(entry.getKey().getName());
                    for (StackTraceElement element : entry.getValue()) {
                        stack.append("\n  at ").append(element);
                    }
                    Log.e("AgentDiagnostic", stack.toString());
                }
            }, "CloneHubAgentShutdown"));
        }
        heartbeatHandler.postDelayed(runtimeHeartbeat, 5_000L);
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        try {
            Bundle result = AbstractC2211qP.c(method, arg, extras);
            if ("995".equals(method)) {
                inspectNativePackageManager(extras);
            }
            return result;
        } catch (Throwable th) {
            th.printStackTrace();
            return Bundle.EMPTY;
        }
    }

    private void inspectNativePackageManager(Bundle diagnostic) {
        try {
            Class<?> managerClass = Class.forName("com.multibox.clone.hub.qj", true,
                    AbstractC2211qP.l);
            Object manager = managerClass.getDeclaredMethod("o000o0oo").invoke(null);
            Log.i("RuntimeProvider", "Native package manager=" + manager);
            if (manager != null) {
                Object spaces = managerClass.getDeclaredMethod("o000oOoo").invoke(manager);
                Log.i("RuntimeProvider", "Native spaces=" + spaces);
            }
            if (diagnostic == null || !diagnostic.containsKey("diagnostic_package")) {
                return;
            }

            Class<?> serviceClass = Class.forName("com.multibox.clone.hub.ni", true,
                    AbstractC2211qP.l);
            Object service = serviceClass.getDeclaredMethod("o000o0oO").invoke(null);
            for (java.lang.reflect.Field field : serviceClass.getDeclaredFields()) {
                if (field.getType().getName().startsWith("com.multibox.clone.hub.")) {
                    field.setAccessible(true);
                    Log.i("RuntimeProvider", "Service field " + field.getName()
                            + "=" + field.get(service));
                }
            }

            Class<?> processClass = Class.forName("com.multibox.clone.hub.o000OO0O", true,
                    AbstractC2211qP.l);
            String hostPackage = getContext().getPackageName();
            android.content.pm.ApplicationInfo directHostInfo = getContext().getPackageManager()
                    .getApplicationInfo(hostPackage, 0);
            Log.i("RuntimeProvider", "Direct host ApplicationInfo=" + directHostInfo
                    + ", uid=" + directHostInfo.uid + ", process=" + directHostInfo.processName
                    + ", source=" + directHostInfo.sourceDir);
            try {
                Class<?> lookupClass = Class.forName("com.multibox.clone.hub.fb", true,
                        AbstractC2211qP.l);
                Object runtimeHostInfo = lookupClass.getDeclaredMethod("OooO00o",
                        android.content.pm.PackageManager.class, String.class).invoke(null,
                        getContext().getPackageManager(), hostPackage);
                Log.i("RuntimeProvider", "Runtime fb host ApplicationInfo=" + runtimeHostInfo);
            } catch (InvocationTargetException e) {
                Log.e("RuntimeProvider", "Runtime fb host lookup failed", e.getTargetException());
            }
            try {
                Object settings = managerClass.getDeclaredMethod("o000ooO", String.class)
                        .invoke(manager, hostPackage);
                Log.i("RuntimeProvider", "Runtime host package settings=" + settings);
            } catch (InvocationTargetException e) {
                Log.e("RuntimeProvider", "Runtime host package-settings lookup failed",
                        e.getTargetException());
            }

            Object processes = processClass.getDeclaredMethod("OooO0Oo").invoke(null);
            for (java.lang.reflect.Field field : processClass.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    Object value = field.get(processes);
                    String summary = String.valueOf(value);
                    if (value instanceof java.util.Map) {
                        java.util.Map<?, ?> map = (java.util.Map<?, ?>) value;
                        summary = "size=" + map.size() + ", keys="
                                + new java.util.ArrayList<>(map.keySet()).subList(0,
                                        Math.min(8, map.size()));
                    } else if (value instanceof android.util.SparseArray) {
                        summary = "size=" + ((android.util.SparseArray<?>) value).size();
                    }
                    Log.i("RuntimeProvider", "Process field " + field.getName()
                            + ": " + summary);
                }
            }

            try {
                Class<?> processServiceClass = Class.forName("com.multibox.clone.hub.qf", true,
                        AbstractC2211qP.l);
                Object holder = processServiceClass.getField("OooO00o").get(null);
                Object processService = holder.getClass().getDeclaredMethod("OooO0O0").invoke(holder);
                Class<?> hostRecordClass = Class.forName("com.multibox.clone.hub.o000O", true,
                        AbstractC2211qP.l);
                Object hostRecord = hostRecordClass.getDeclaredConstructor(
                        android.os.IBinder.class, String.class)
                        .newInstance(processService, hostPackage);
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> registry =
                        (java.util.Map<String, Object>) processClass.getField("OooO00o").get(processes);
                registry.put(hostPackage, hostRecord);
                Log.i("RuntimeProvider", "Host process record constructed with "
                        + processService.getClass().getName() + "; slots="
                        + ((java.util.Map<?, ?>) hostRecord).size());
            } catch (InvocationTargetException e) {
                Log.e("RuntimeProvider", "Host process record construction failed",
                        e.getTargetException());
            }

            String packageName = diagnostic.getString("diagnostic_package");
            int spaceId = diagnostic.getInt("diagnostic_space");
            Object activity = managerClass.getDeclaredMethod("o000oo0o",
                    Integer.TYPE, String.class).invoke(manager, spaceId, packageName);
            String processName = activity == null ? null
                    : ((android.content.pm.ActivityInfo) activity).processName;
            Log.i("RuntimeProvider", "Service ActivityInfo=" + activity
                    + ", processName=" + processName + ", package=" + packageName);
            try {
                Object record = processClass.getDeclaredMethod("OooO0OO", String.class)
                        .invoke(processes, processName);
                Log.i("RuntimeProvider", "Service direct process lookup=" + record);
            } catch (InvocationTargetException e) {
                Log.e("RuntimeProvider", "Service direct process lookup failed for "
                        + processName, e.getTargetException());
            }
        } catch (InvocationTargetException e) {
            Log.e("RuntimeProvider", "Native package-manager diagnostic failed", e.getTargetException());
        } catch (Throwable th) {
            Log.e("RuntimeProvider", "Native package-manager reflection failed", th);
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
