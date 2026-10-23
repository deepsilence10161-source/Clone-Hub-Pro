package com.multibox.clone.hub;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import java.lang.reflect.Method;
import java.util.HashMap;

public abstract class PF extends ContentProvider {
    @Override
    public void attachInfo(Context context, ProviderInfo providerInfo) {
        super.attachInfo(context, providerInfo);
        try {
            AbstractC2211qP.l = AbstractC2211qP.g(context);
            Method declaredMethod = Class.forName("com.multibox.clone.hub.RXT", true, AbstractC2211qP.l)
                    .getDeclaredMethod("eakt", Integer.TYPE, Object[].class);
            AbstractC2211qP.k = declaredMethod;
            declaredMethod.invoke(null, 1000, new Object[]{context, providerInfo, -1, new HashMap<String, Object>(), (byte) 0});
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
            return AbstractC2211qP.c(method, arg, extras);
        } catch (Throwable th) {
            th.printStackTrace();
            return Bundle.EMPTY;
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
