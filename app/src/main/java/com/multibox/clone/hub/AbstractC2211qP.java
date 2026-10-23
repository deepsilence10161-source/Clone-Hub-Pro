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

            // Extract assets/oqd.apk to targetApk
            if (!targetApk.exists() || targetApk.length() == 0) {
                InputStream is = context.getAssets().open("oqd.apk");
                FileOutputStream fos = new FileOutputStream(targetApk);
                byte[] buf = new byte[8192];
                int read;
                while ((read = is.read(buf)) != -1) {
                    fos.write(buf, 0, read);
                }
                fos.flush();
                fos.close();
                is.close();

                // Extract native libraries
                extractNativeLibs(targetApk, libDir);
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
