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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * What does this app see when it asks Android for a Google account?
 *
 * The same code answers that question whether it runs as the plain fixture or inside the virtual
 * container as a clone, and it writes the answer to a file named after the process it ran in. The
 * host suite compares the two files: everything a "Sign in with Google" button depends on is in
 * here — the account list the process is allowed to see, and the result of a real token request.
 *
 * This is deliberately dependency-free Java: inside the clone the host's Kotlin runtime is not the
 * entry point, so only framework APIs are used.
 */
final class AccountProbe {

    private static final String TAG = "GOOGLE_SIGNIN";
    private static final String GOOGLE_ACCOUNT_TYPE = "com.google";
    private static final String TOKEN_TYPE =
            "oauth2:https://www.googleapis.com/auth/userinfo.email";
    private static final String REQUEST_FLAG = "account_probe_request";
    private static final long TOKEN_TIMEOUT_SECONDS = 20L;

    private AccountProbe() {
    }

    /** Runs both probes when the suite has asked for them, from a background thread. */
    static void runIfRequested(final Activity activity) {
        File flag = new File(activity.getFilesDir(), REQUEST_FLAG);
        if (!flag.exists()) {
            return;
        }
        final Context context = activity.getApplicationContext();
        new Thread(() -> {
            String kind = processKind();
            try {
                write(context, "account_probe-" + kind + ".txt", describeAccounts(context, kind));
                write(context, "token_probe-" + kind + ".txt", requestToken(context, kind));
            } catch (Throwable error) {
                Log.e(TAG, "Account probe failed in " + kind, error);
            }
        }, "AccountProbe").start();
    }

    /** "clone" inside an agent process, "normal" in the app's own process. */
    private static String processKind() {
        String processName;
        try {
            processName = Build.VERSION.SDK_INT >= 28
                    ? Application.getProcessName()
                    : "unavailable";
        } catch (Throwable error) {
            processName = "unavailable";
        }
        if (processName == null) {
            processName = "unavailable";
        }
        return processName.contains(":agent") ? "clone" : "normal";
    }

    private static String describeAccounts(Context context, String kind) {
        StringBuilder out = new StringBuilder();
        out.append("mode=").append(kind).append('\n');
        try {
            AccountManager manager = AccountManager.get(context);
            Account[] google = manager.getAccountsByType(GOOGLE_ACCOUNT_TYPE);
            Account[] all = manager.getAccounts();
            out.append("ok=true\n");
            out.append("google_accounts=").append(google.length).append('\n');
            out.append("all_accounts=").append(all.length).append('\n');
            out.append("names=").append(join(names(google))).append('\n');
        } catch (Throwable error) {
            // This is what the user experiences as "the sign-in button does nothing": the call
            // itself throws inside the clone. The reason is recorded, not an empty list.
            out.append("ok=false\n");
            out.append("error=").append(error.getClass().getSimpleName())
                    .append(": ").append(error.getMessage()).append('\n');
            Log.e(TAG, "Account list failed in mode " + kind, error);
        }
        return out.toString();
    }

    private static String requestToken(Context context, String kind) {
        StringBuilder out = new StringBuilder("mode=").append(kind).append('\n');
        AccountManager manager = AccountManager.get(context);
        Account[] google;
        try {
            google = manager.getAccountsByType(GOOGLE_ACCOUNT_TYPE);
        } catch (Throwable error) {
            return out.append("ok=false\nresult=EXCEPTION\nerror=")
                    .append(error.getClass().getSimpleName()).append(": ")
                    .append(error.getMessage()).append('\n').toString();
        }
        if (google.length == 0) {
            out.append("ok=true\nresult=NO_ACCOUNT\ntoken_length=0\n");
            return out.toString();
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
                                    // answered with its consent/picker screen. That screen belongs
                                    // to Play services; this records that it was asked for.
                                    result[0] = "CONSENT_REQUIRED";
                                    result[2] = String.valueOf(consent.getComponent());
                                } else {
                                    result[0] = "EMPTY_TOKEN";
                                }
                            } catch (Throwable error) {
                                result[0] = "EXCEPTION";
                                result[2] = error.getClass().getSimpleName() + ": " + error.getMessage();
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
        out.append("ok=true\n");
        out.append("result=").append(result[0]).append('\n');
        out.append("token_length=").append(result[1]).append('\n');
        if (result[2] != null) {
            out.append("detail=").append(result[2].replace('\n', ' ')).append('\n');
        }
        return out.toString();
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

    private static void write(Context context, String fileName, String content) {
        Log.i(TAG, "GOOGLE_SIGNIN_PROBE_REPORT file=" + fileName + " "
                + content.replace('\n', ' '));
        try {
            FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), fileName));
            try {
                out.write(content.getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
        } catch (Throwable error) {
            Log.e(TAG, "Could not write " + fileName, error);
        }
    }
}
