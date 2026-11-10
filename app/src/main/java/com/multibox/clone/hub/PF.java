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
        super.attachInfo(context, providerInfo);
        try {
            AbstractC2211qP.l = AbstractC2211qP.g(context);
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
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        try {
            Bundle result = AbstractC2211qP.c(method, arg, extras);
            if ("995".equals(method)) {
                inspectNativePackageManager();
            }
            return result;
        } catch (Throwable th) {
            th.printStackTrace();
            return Bundle.EMPTY;
        }
    }

    private void inspectNativePackageManager() {
        try {
            Class<?> managerClass = Class.forName("com.multibox.clone.hub.qj", true,
                    AbstractC2211qP.l);
            Object manager = managerClass.getDeclaredMethod("o000o0oo").invoke(null);
            Log.i("RuntimeProvider", "Native package manager=" + manager);
            if (manager != null) {
                Object spaces = managerClass.getDeclaredMethod("o000oOoo").invoke(manager);
                Log.i("RuntimeProvider", "Native spaces=" + spaces);
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
