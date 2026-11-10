package diagnostic;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;

/** Read-only reference-runtime inspection; injected only into the temporary probe APK. */
public final class DiagnosticProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        try {
            Class<?> bridge = Class.forName("com.multibox.clone.hub.p2");
            ClassLoader loader = (ClassLoader) bridge.getField("i").get(null);
            String host = getContext().getPackageName();
            Class<?> qj = Class.forName("com.multibox.clone.hub.qj", true, loader);
            Object packages = qj.getDeclaredMethod("o000o0oo").invoke(null);
            Object settings = qj.getDeclaredMethod("o000ooO", String.class).invoke(packages, host);
            Log.i("ReferenceDiagnostic", "Host package settings=" + settings);
            dumpFields(settings, "HostSetting");

            Class<?> processClass = Class.forName("com.multibox.clone.hub.o000OO0O", true, loader);
            Object processes = processClass.getDeclaredMethod("OooO0Oo").invoke(null);
            for (Field field : processClass.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object value = field.get(processes);
                if (value instanceof Map) {
                    Map<?, ?> map = (Map<?, ?>) value;
                    Log.i("ReferenceDiagnostic", "Process field " + field.getName()
                            + " size=" + map.size() + ", keys=" + map.keySet());
                    for (Object item : map.values()) dumpFields(item, "ProcessValue");
                } else if (value instanceof android.util.SparseArray) {
                    android.util.SparseArray<?> array = (android.util.SparseArray<?>) value;
                    Log.i("ReferenceDiagnostic", "Process field " + field.getName()
                            + " size=" + array.size());
                    for (int i = 0; i < array.size(); i++) dumpFields(array.valueAt(i), "ProcessValue");
                } else {
                    Log.i("ReferenceDiagnostic", "Process field " + field.getName() + "=" + value);
                }
            }
        } catch (Throwable th) {
            Log.e("ReferenceDiagnostic", "Inspection failed", th);
        }
        return Bundle.EMPTY;
    }

    private static void dumpFields(Object object, String prefix) {
        if (object == null) return;
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(object);
                    Log.i("ReferenceDiagnostic", prefix + " " + type.getSimpleName()
                            + "." + field.getName() + "=" + value);
                    if (value != null && value.getClass().getName().equals(
                            "com.multibox.clone.hub.g6")) {
                        for (Field nested : value.getClass().getDeclaredFields()) {
                            if (java.lang.reflect.Modifier.isStatic(nested.getModifiers())) continue;
                            nested.setAccessible(true);
                            Object nestedValue = nested.get(value);
                            if (nestedValue instanceof Object[]) {
                                nestedValue = java.util.Arrays.deepToString((Object[]) nestedValue);
                            }
                            Log.i("ReferenceDiagnostic", prefix + " g6." + nested.getName()
                                    + "=" + nestedValue);
                        }
                    }
                } catch (Throwable th) {
                    Log.e("ReferenceDiagnostic", "Field inspection failed: " + field, th);
                }
            }
        }
    }
}
