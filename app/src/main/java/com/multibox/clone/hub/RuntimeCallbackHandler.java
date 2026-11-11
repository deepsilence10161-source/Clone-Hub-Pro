package com.multibox.clone.hub;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Callback map/handler required by the bundled runtime during provider setup.
 * This mirrors the original runtime host's singleton contract.
 *
 * Crashes inside a cloned agent process cannot be rethrown safely, but they must stay
 * observable: every uncaught failure is timestamped, logged and appended to a crash journal that
 * the host UI can read instead of presenting a frozen clone as healthy.
 */
public final class RuntimeCallbackHandler extends ConcurrentHashMap<Object, Object>
        implements InvocationHandler, Thread.UncaughtExceptionHandler {

    public static final RuntimeCallbackHandler INSTANCE = new RuntimeCallbackHandler();
    public final ConcurrentHashMap<Object, Object> callbacks = new ConcurrentHashMap<>();

    private static final String TAG = "RuntimeCallback";
    private static final String JOURNAL_NAME = "engine_crashes.log";
    private static final long JOURNAL_MAX_BYTES = 64 * 1024;

    private static volatile File journalFile;
    private static volatile String lastCrash;

    private RuntimeCallbackHandler() {
    }

    /** Registers where crash records are appended. Called once from the host application. */
    public static void setJournalDirectory(File directory) {
        if (directory == null) {
            return;
        }
        directory.mkdirs();
        journalFile = new File(directory, JOURNAL_NAME);
    }

    /** Most recent crash summary, or null when no crash has been recorded in this process. */
    public static String getLastCrash() {
        return lastCrash;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        android.util.Log.i(TAG, "invoke proxy=" + proxy + ", method=" + method
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
        String summary = throwable.toString();
        lastCrash = summary;
        android.util.Log.e(TAG, "Consumed runtime callback failure on " + thread, throwable);
        appendToJournal(thread, throwable);
    }

    private void appendToJournal(Thread thread, Throwable throwable) {
        File file = journalFile;
        if (file == null) {
            return;
        }
        try {
            if (file.exists() && file.length() > JOURNAL_MAX_BYTES) {
                // Keep one rotation: a runaway crash loop must not fill user storage.
                File previous = new File(file.getParentFile(), JOURNAL_NAME + ".1");
                if (previous.exists() && !previous.delete()) {
                    android.util.Log.w(TAG, "Could not rotate crash journal");
                }
                if (!file.renameTo(previous)) {
                    android.util.Log.w(TAG, "Could not archive crash journal");
                }
            }
            StringWriter stack = new StringWriter();
            throwable.printStackTrace(new PrintWriter(stack));
            String record = "time=" + System.currentTimeMillis()
                    + " pid=" + android.os.Process.myPid()
                    + " thread=" + thread.getName()
                    + "\n" + stack + "\n";
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(record.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Throwable error) {
            android.util.Log.e(TAG, "Could not write crash journal", error);
        }
    }
}
