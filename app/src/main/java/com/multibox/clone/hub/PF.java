package com.multibox.clone.hub;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public abstract class PF extends ContentProvider {
    @Override
    public void attachInfo(Context context, ProviderInfo providerInfo) {
        tc.a = 0;
        super.attachInfo(context, providerInfo);
        try {
            AbstractC2211qP.l = AbstractC2211qP.g(context);
            // The runtime probes the optional Apache HTTP legacy jar from gb's static
            // initializer. On the translated x86_64 runtime, doing that after WJW.XY()
            // installs its ART hooks crashes while constructing the ENOENT exception.
            // Initialize this data-only class first, with the same decoder marker that
            // RXT 1000 receives below; no runtime code or native library is replaced.
            Class<?> decoder = Class.forName("com.multibox.clone.hub.o0O00o0", true,
                    AbstractC2211qP.l);
            decoder.getDeclaredField("OooO00o").setByte(null, (byte) 0);
            Class.forName("com.multibox.clone.hub.gb", true, AbstractC2211qP.l);
            Method declaredMethod = Class.forName("com.multibox.clone.hub.RXT", true, AbstractC2211qP.l)
                    .getDeclaredMethod("eakt", Integer.TYPE, Object[].class);
            AbstractC2211qP.k = declaredMethod;
            Object mode = declaredMethod.invoke(null, 1000, new Object[]{context, providerInfo, -1,
                    RuntimeCallbackHandler.INSTANCE, (byte) 0});
            Log.i("RuntimeProvider", "Initialized authority=" + providerInfo.authority
                    + ", process=" + android.os.Process.myPid() + ", mode=" + mode);
        } catch (Throwable th) {
            th.printStackTrace();
            throw new RuntimeException("Virtual container provider initialization error", th);
        }
    }

    @Override
    public boolean onCreate() {
        Log.i("AgentDiagnostic", "Provider onCreate; authority="
                + (getContext() == null ? null : getContext().getPackageName()));
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
