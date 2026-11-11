package com.multibox.clone.hub;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public abstract class AbstractC2211qP {
    public static ClassLoader l;
    public static Method k;
    public static int j = 73;
    private static String cachedExpectedMarker;

    public static synchronized ClassLoader g(Context context) {
        if (l != null) {
            return l;
        }
        try {
            File baseDir = new File(context.getFilesDir().getParentFile(), "wpsx");
            File sdkDir = new File(baseDir, "sdk/" + j);
            sdkDir.mkdirs();
            File targetApk = new File(sdkDir, "base.apk");
            File libDir = new File(sdkDir, "lib");

            // The extracted runtime must match the copy shipped inside this APK. An app update can
            // change the bundled engine while an old extraction stays on disk; silently loading
            // that stale copy produces copy-dependent crashes. The marker also makes an interrupted
            // extraction self-healing, because it is written last and only for a verified install.
            File marker = new File(sdkDir, "extraction.ok");
            String installedMarker = readMarker(marker);
            boolean extractionValid = extractionIsUsable(context, targetApk, libDir, installedMarker);
            if (!extractionValid) {
                android.util.Log.i("RuntimeExtraction", "Extracting bundled runtime into " + sdkDir);
                boolean replaced = extractRuntime(context, targetApk, libDir, marker);
                if (!replaced) {
                    // A previous good extraction is still better than a half-written one.
                    throw new IOException("Bundled runtime could not be extracted into " + sdkDir);
                }
            }

            // Android 14 rejects DEX loading from a writable APK. The bundled
            // runtime's original loader marks this extracted file read-only too.
            if (Build.VERSION.SDK_INT >= 34 && !targetApk.setReadOnly()) {
                throw new IOException("Could not make virtual runtime APK read-only: " + targetApk);
            }

            // Determine ABI search paths
            ArrayList<String> libPaths = new ArrayList<>();
            if (Process.is64Bit()) {
                for (String abi : Build.SUPPORTED_64_BIT_ABIS) {
                    File abiFolder = new File(libDir, abi);
                    if (abiFolder.exists()) {
                        libPaths.add(abiFolder.getAbsolutePath());
                    }
                }
            } else {
                for (String abi : Build.SUPPORTED_32_BIT_ABIS) {
                    File abiFolder = new File(libDir, abi);
                    if (abiFolder.exists()) {
                        libPaths.add(abiFolder.getAbsolutePath());
                    }
                }
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < libPaths.size(); i++) {
                sb.append(libPaths.get(i));
                if (i < libPaths.size() - 1) {
                    sb.append(File.pathSeparator);
                }
            }

            l = new DexClassLoader(
                    targetApk.getAbsolutePath(),
                    targetApk.getParent(),
                    sb.toString(),
                    Context.class.getClassLoader()
            );
            return l;
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Virtual core engine extraction failed", e);
        }
    }

    /** Marker content: "<versionCode>:<size>:<sha256-of-oqd.apk>:<fileCount>". */
    private static String readMarker(File marker) {
        if (!marker.exists()) {
            return null;
        }
        try {
            byte[] data = new byte[(int) Math.min(marker.length(), 512)];
            java.io.FileInputStream in = new java.io.FileInputStream(marker);
            try {
                int read = in.read(data);
                return read <= 0 ? null : new String(data, 0, read, "UTF-8").trim();
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static String expectedMarker(Context context, File apk)
            throws IOException, android.content.pm.PackageManager.NameNotFoundException {
        String cached = cachedExpectedMarker;
        if (cached != null) {
            return cached;
        }
        long versionCode;
        android.content.pm.PackageInfo info = context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0);
        if (Build.VERSION.SDK_INT >= 28) {
            versionCode = info.getLongVersionCode();
        } else {
            versionCode = info.versionCode;
        }
        cachedExpectedMarker = versionCode + ":" + apk.length() + ":" + sha256(apk) + ":" + j;
        return cachedExpectedMarker;
    }

    /**
     * The extraction is reused only when the marker matches the runtime bundled in *this* build
     * exactly. Anything else — missing marker, changed APK, removed ABI folder — forces a redo.
     */
    private static boolean extractionIsUsable(Context context, File apk, File libDir, String markerValue) {
        if (markerValue == null || !apk.exists() || apk.length() == 0) {
            return false;
        }
        String[] libs = libDir.list();
        if (libs == null || libs.length == 0) {
            return false;
        }
        try {
            return markerValue.equals(expectedMarker(context, apk));
        } catch (IOException e) {
            android.util.Log.w("RuntimeExtraction", "Could not verify extraction marker", e);
            return false;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            android.util.Log.w("RuntimeExtraction", "Own package info unavailable", e);
            return false;
        }
    }

    /**
     * Copies assets/oqd.apk and its native libraries into the extraction directory, then writes
     * the verification marker. The marker is written last so an interrupted run always leaves an
     * invalid state that will be retried on the next start.
     */
    private static boolean extractRuntime(Context context, File apk, File libDir, File marker)
            throws IOException, android.content.pm.PackageManager.NameNotFoundException {
        if (marker.exists() && !marker.delete()) {
            android.util.Log.w("RuntimeExtraction", "Could not clear stale extraction marker");
        }
        File tempApk = new File(apk.getParentFile(), apk.getName() + ".part");
        InputStream is = null;
        FileOutputStream fos = null;
        try {
            is = context.getAssets().open("oqd.apk");
            fos = new FileOutputStream(tempApk);
            byte[] buf = new byte[8192];
            int read;
            while ((read = is.read(buf)) != -1) {
                fos.write(buf, 0, read);
            }
            fos.flush();
        } finally {
            if (fos != null) {
                fos.close();
            }
            if (is != null) {
                is.close();
            }
        }
        if (tempApk.length() == 0) {
            tempApk.delete();
            return false;
        }
        // Directory replacement: clear the old library tree so removed ABIs do not linger.
        deleteRecursively(libDir);
        libDir.mkdirs();
        extractNativeLibs(tempApk, libDir);
        if (apk.exists() && !apk.delete()) {
            throw new IOException("Could not replace stale runtime APK: " + apk);
        }
        if (!tempApk.renameTo(apk)) {
            throw new IOException("Could not move extracted runtime into place: " + apk);
        }
        FileOutputStream markerOut = new FileOutputStream(marker);
        try {
            markerOut.write(expectedMarker(context, apk).getBytes("UTF-8"));
            markerOut.flush();
            markerOut.getFD().sync();
        } finally {
            markerOut.close();
        }
        android.util.Log.i("RuntimeExtraction", "Runtime extraction verified; " + marker.getName()
                + "=" + readMarker(marker));
        return true;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete()) {
            android.util.Log.w("RuntimeExtraction", "Could not delete " + file);
        }
    }

    private static String sha256(File file) throws IOException {
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        java.io.FileInputStream in = new java.io.FileInputStream(file);
        try {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                digest.update(buf, 0, read);
            }
        } finally {
            in.close();
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static void extractNativeLibs(File apkFile, File outLibDir) {
        try {
            ZipFile zip = new ZipFile(apkFile);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().startsWith("lib/") && !entry.isDirectory()) {
                    File outFile = new File(outLibDir, entry.getName().substring(4));
                    outFile.getParentFile().mkdirs();
                    InputStream is = zip.getInputStream(entry);
                    FileOutputStream fos = new FileOutputStream(outFile);
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = is.read(buf)) != -1) {
                        fos.write(buf, 0, r);
                    }
                    fos.flush();
                    fos.close();
                    is.close();
                }
            }
            zip.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static Bundle c(String str, String str2, Bundle bundle) {
        try {
            if (k != null) {
                return (Bundle) k.invoke(null, 10402, new Object[]{str, str2, bundle});
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return Bundle.EMPTY;
    }
}
