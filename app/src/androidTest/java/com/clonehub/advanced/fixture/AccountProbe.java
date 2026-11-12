package com.clonehub.advanced.fixture;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * What does this app see when it asks Android for a Google account?
 *
 * The same code answers that question whether it runs as the plain fixture or inside the virtual
 * container as a clone. Everything a "Sign in with Google" button depends on is in the answer: the
 * account list the process is allowed to see, and the result of a real token request.
 *
 * The answer is published one key per log line ({@code GOOGLE_SIGNIN_KV}) and mirrored into a file
 * named after the process it ran in, so the two answers — normal and clone — can never be confused.
 * Logcat is the transport because it is the one channel the host test, the fixture process and the
 * clone's agent process all share without storage permissions.
 *
 * Deliberately dependency-free Java: inside the clone the host's Kotlin runtime is not the entry
 * point, so only framework APIs are used.
 */
final class AccountProbe {

    private static final String TAG = "GOOGLE_SIGNIN";
    private static final String KV_PREFIX = "GOOGLE_SIGNIN_KV";
    private static final String GOOGLE_ACCOUNT_TYPE = "com.google";
    private static final String TOKEN_TYPE =
            "oauth2:https://www.googleapis.com/auth/userinfo.email";
    private static final long TOKEN_TIMEOUT_SECONDS = 20L;

    private AccountProbe() {
    }

    /**
     * Runs both probes on every start, from a background thread.
     *
     * A request flag cannot be used to gate this: inside the clone the app's data directory is
     * virtualised, so a marker written by the host is not visible there — the probe would then be
     * silent exactly where it matters. This is a test-only fixture, so answering every time is the
     * cheaper trade.
     */
    static void run(final Activity activity) {
        final Context context = activity.getApplicationContext();
        new Thread(() -> {
            String kind = processKind();
            try {
                publish(context, "account_probe-" + kind + ".txt", describeAccounts(context, kind));
                publish(context, "token_probe-" + kind + ".txt", requestToken(context, kind));
            } catch (Throwable error) {
                Log.e(TAG, "Account probe failed in " + kind, error);
            }
        }, "AccountProbe").start();
    }

    /**
     * "clone" inside an agent process, "normal" in the app's own process.
     *
     * The kernel's own view (`/proc/self/cmdline`) decides this, not
     * {@link Application#getProcessName()}: measured inside the container, the virtualised API reports
     * the clone's own package name while the process really is `com.clonehub.pro:agent0`, so the
     * clone's answer was filed as if it came from the un-cloned app. Both values are reported, so the
     * evidence shows what each process believed about itself.
     */
    private static String processKind() {
        return realProcessName().contains(":agent") ? "clone" : "normal";
    }

    /** Kernel process name of the current process, as the device sees it. */
    private static String realProcessName() {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream("/proc/self/cmdline");
            try {
                byte[] buffer = new byte[128];
                int read = in.read(buffer);
                if (read <= 0) {
                    return "unknown";
                }
                int end = 0;
                while (end < read && buffer[end] != 0) {
                    end++;
                }
                return new String(buffer, 0, end, "UTF-8").trim();
            } finally {
                in.close();
            }
        } catch (Throwable error) {
            return "unknown";
        }
    }

    /** What the virtualised API believes this process is called, kept for the evidence. */
    private static String virtualProcessName() {
        try {
            String name = Build.VERSION.SDK_INT >= 28 ? Application.getProcessName() : "unavailable";
            return name == null ? "unavailable" : name;
        } catch (Throwable error) {
            return "unavailable";
        }
    }

    private static Map<String, String> describeAccounts(Context context, String kind) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("mode", kind);
        out.put("process", realProcessName());
        out.put("virtual_process", virtualProcessName());
        try {
            AccountManager manager = AccountManager.get(context);
            Account[] google = manager.getAccountsByType(GOOGLE_ACCOUNT_TYPE);
            Account[] all = manager.getAccounts();
            out.put("ok", "true");
            out.put("google_accounts", String.valueOf(google.length));
            out.put("all_accounts", String.valueOf(all.length));
            out.put("names", join(names(google)));
        } catch (Throwable error) {
            // This is what a user experiences as "the sign-in button does nothing": the call itself
            // throws inside the clone. The reason is recorded, never an empty list.
            out.put("ok", "false");
            out.put("error", error.getClass().getSimpleName() + ": " + error.getMessage());
            Log.e(TAG, "Account list failed in mode " + kind, error);
        }
        return out;
    }

    private static Map<String, String> requestToken(Context context, String kind) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("mode", kind);
        out.put("process", realProcessName());
        out.put("virtual_process", virtualProcessName());
        AccountManager manager = AccountManager.get(context);
        Account[] google;
        try {
            google = manager.getAccountsByType(GOOGLE_ACCOUNT_TYPE);
        } catch (Throwable error) {
            out.put("ok", "false");
            out.put("result", "EXCEPTION");
            out.put("error", error.getClass().getSimpleName() + ": " + error.getMessage());
            return out;
        }
        if (google.length == 0) {
            out.put("ok", "true");
            out.put("result", "NO_ACCOUNT");
            out.put("token_length", "0");
            return out;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] result = {"PENDING", "0", null};
        try {
            manager.getAuthToken(google[0], TOKEN_TYPE, null, false,
                    new AccountManagerCallback<Bundle>() {
                        @Override
                        public void run(AccountManagerFuture<Bundle> future) {
                            try {
                                Bundle bundle = future.getResult();
                                String token = bundle == null
                                        ? null : bundle.getString(AccountManager.KEY_AUTHTOKEN);
                                Intent consent = bundle == null ? null
                                        : (Intent) bundle.getParcelable(AccountManager.KEY_INTENT);
                                if (token != null) {
                                    result[0] = "TOKEN";
                                    result[1] = String.valueOf(token.length());
                                } else if (consent != null) {
                                    // The request reached the real Google account manager, which
                                    // answered with its consent/picker screen. That screen belongs to
                                    // Play services; this records that it was asked for.
                                    result[0] = "CONSENT_REQUIRED";
                                    result[2] = String.valueOf(consent.getComponent());
                                } else {
                                    result[0] = "EMPTY_TOKEN";
                                }
                            } catch (Throwable error) {
                                result[0] = "EXCEPTION";
                                result[2] = error.getClass().getSimpleName()
                                        + ": " + error.getMessage();
                            } finally {
                                latch.countDown();
                            }
                        }
                    }, null);
            latch.await(TOKEN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Throwable error) {
            result[0] = "EXCEPTION";
            result[2] = error.getClass().getSimpleName() + ": " + error.getMessage();
        }
        out.put("ok", "true");
        out.put("result", result[0]);
        out.put("token_length", result[1]);
        if (result[2] != null) {
            out.put("detail", result[2].replace('\n', ' '));
        }
        return out;
    }

    private static String[] names(Account[] accounts) {
        String[] values = new String[accounts.length];
        for (int i = 0; i < accounts.length; i++) {
            values[i] = accounts[i].name;
        }
        java.util.Arrays.sort(values);
        return values;
    }

    private static String join(String[] values) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                joined.append(',');
            }
            joined.append(values[i]);
        }
        return joined.toString();
    }

    private static void publish(Context context, String fileName, Map<String, String> answer) {
        for (Map.Entry<String, String> entry : answer.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue();
            Log.i(TAG, KV_PREFIX + " file=" + fileName + " key=" + entry.getKey()
                    + " value=" + value.replace('\n', ' '));
        }
        StringBuilder content = new StringBuilder();
        for (Map.Entry<String, String> entry : answer.entrySet()) {
            content.append(entry.getKey()).append('=')
                    .append(entry.getValue() == null ? "" : entry.getValue()).append('\n');
        }
        try {
            FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), fileName));
            try {
                out.write(content.toString().getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
        } catch (Throwable error) {
            Log.e(TAG, "Could not write " + fileName, error);
        }
    }
}
