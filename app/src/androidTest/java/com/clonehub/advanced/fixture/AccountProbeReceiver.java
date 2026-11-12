package com.clonehub.advanced.fixture;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Answers "what does this app see when it asks Android for a Google account?" from whichever
 * process it runs in — normally, or inside the virtual container where a cloned app runs.
 *
 * The same code answers both, so a difference in the answer is a difference in the container,
 * not in the test. It is deliberately dependency-free Java: it has to load inside the bundled
 * runtime where the host's Kotlin runtime is not the app's entry point.
 *
 * Usage (the host suite drives this):
 *   am broadcast -a com.clonehub.advanced.GOOGLE_ACCOUNT_PROBE --es mode clone  -n <pkg>/...AccountProbeReceiver
 *   am broadcast -a com.clonehub.advanced.GOOGLE_TOKEN_PROBE   --es mode clone  -n <pkg>/...AccountProbeReceiver
 *
 * Answers are written to filesDir/account_probe-<mode>.txt and filesDir/token_probe-<mode>.txt
 * as key=value lines, and mirrored to logcat under the GOOGLE_SIGNIN tag.
 */
public final class AccountProbeReceiver extends BroadcastReceiver {

    private static final String TAG = "GOOGLE_SIGNIN";
    private static final String ACTION_ACCOUNT_PROBE = "com.clonehub.advanced.GOOGLE_ACCOUNT_PROBE";
    private static final String ACTION_TOKEN_PROBE = "com.clonehub.advanced.GOOGLE_TOKEN_PROBE";
    private static final String GOOGLE_ACCOUNT_TYPE = "com.google";
    private static final String TOKEN_TYPE = "oauth2:https://www.googleapis.com/auth/userinfo.email";
    private static final long TOKEN_TIMEOUT_SECONDS = 20L;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        String mode = intent == null ? "" : String.valueOf(intent.getStringExtra("mode"));
        if (ACTION_ACCOUNT_PROBE.equals(action)) {
            report(context, "account_probe-" + mode + ".txt", describeAccounts(context, mode));
        } else if (ACTION_TOKEN_PROBE.equals(action)) {
            report(context, "token_probe-" + mode + ".txt", requestToken(context, mode));
        }
    }

    private String describeAccounts(Context context, String mode) {
        StringBuilder out = new StringBuilder();
        out.append("mode=").append(mode).append('\n');
        try {
            AccountManager manager = AccountManager.get(context);
            Account[] google = manager.getAccountsByType(GOOGLE_ACCOUNT_TYPE);
            Account[] all = manager.getAccounts();
            out.append("ok=true\n");
            out.append("google_accounts=").append(google.length).append('\n');
            out.append("all_accounts=").append(all.length).append('\n');
            out.append("names=").append(names(google)).append('\n');
            out.append("authenticator_present=")
                    .append(manager.getAccountsByTypeAndFeatures(
                            GOOGLE_ACCOUNT_TYPE, null, null, null) != null)
                    .append('\n');
        } catch (Throwable error) {
            // This is the failure the user sees as "login does not work": the call itself throws
            // inside the clone. The reason is recorded instead of an empty list.
            out.append("ok=false\n");
            out.append("error=").append(error.getClass().getSimpleName())
                    .append(": ").append(error.getMessage()).append('\n');
            Log.e(TAG, "Account probe failed in mode " + mode, error);
        }
        return out.toString();
    }

    private String requestToken(Context context, String mode) {
        StringBuilder out = new StringBuilder();
        out.append("mode=").append(mode).append('\n');
        AccountManager manager = AccountManager.get(context);
        Account[] google;
        try {
            google = manager.getAccountsByType(GOOGLE_ACCOUNT_TYPE);
        } catch (Throwable error) {
            out.append("ok=false\nresult=EXCEPTION\nerror=")
                    .append(error.getClass().getSimpleName()).append(": ")
                    .append(error.getMessage()).append('\n');
            return out.toString();
        }
        if (google.length == 0) {
            // No account is signed into the image: the platform's own answer is "nothing to issue".
            // Recorded verbatim — this is not a token and is never presented as one.
            out.append("ok=true\nresult=NO_ACCOUNT\ntoken_length=0\n");
            return out.toString();
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] result = new String[]{"PENDING", "0", null};
        try {
            manager.getAuthToken(google[0], TOKEN_TYPE, null, false, new AccountManagerCallback<Bundle>() {
                @Override
                public void run(AccountManagerFuture<Bundle> future) {
                    try {
                        Bundle bundle = future.getResult();
                        String token = bundle == null ? null : bundle.getString(AccountManager.KEY_AUTHTOKEN);
                        Intent reauth = bundle == null ? null
                                : (Intent) bundle.getParcelable(AccountManager.KEY_INTENT);
                        if (token != null) {
                            result[0] = "TOKEN";
                            result[1] = String.valueOf(token.length());
                        } else if (reauth != null) {
                            // A consent/picker screen was requested. That screen belongs to the
                            // real Google app; this answer records that the request reached it.
                            result[0] = "CONSENT_REQUIRED";
                            result[2] = String.valueOf(reauth.getComponent());
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

    private static String names(Account[] accounts) {
        if (accounts.length == 0) {
            return "";
        }
        String[] values = new String[accounts.length];
        for (int i = 0; i < accounts.length; i++) {
            values[i] = accounts[i].name;
        }
        java.util.Arrays.sort(values);
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                joined.append(',');
            }
            joined.append(values[i]);
        }
        return joined.toString();
    }

    private void report(Context context, String fileName, String content) {
        Log.i(TAG, "GOOGLE_SIGNIN_PROBE_REPORT file=" + fileName + " " + content.replace('\n', ' '));
        try {
            File target = new File(context.getFilesDir(), fileName);
            FileOutputStream out = new FileOutputStream(target, false);
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
