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
            try {
                publish(context, "account_probe.txt", describeAccounts(context));
                publish(context, "token_probe.txt", requestToken(context));
            } catch (Throwable error) {
                Log.e(TAG, "Account probe failed", error);
            }
        }, "AccountProbe").start();
    }

    /**
     * A probe cannot name its own process reliably: measured inside the container, both
     * {@link Application#getProcessName()} *and* `/proc/self/cmdline` report the cloned package —
     * the container virtualises them — while the process really is `com.clonehub.pro:agent0`.
     *
     * So the probe reports only facts it can prove ({@code pid}) and the host side decides the label:
     * the test clears the log, triggers exactly one launch, and resolves the pid's real process name
     * from the shell, where the container cannot hide it.
     */
    private static String ownName() {
        try {
            String name = Build.VERSION.SDK_INT >= 28 ? Application.getProcessName() : "unavailable";
            return name == null ? "unavailable" : name;
        } catch (Throwable error) {
            return "unavailable";
        }
    }

    private static Map<String, String> describeAccounts(Context context) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("pid", String.valueOf(android.os.Process.myPid()));
        out.put("reported_name", ownName());
        try {
            AccountManager manager = AccountManager.get(context);
            Account[] google = manager.getAccountsByType(GOOGLE_ACCOUNT_TYPE);
            Account[] all = manager.getAccounts();
            out.put("ok", "true");
            out.put("google_accounts", String.valueOf(google.length));
            out.put("all_accounts", String.valueOf(all.length));
            out.put("names", join(names(google)));
            // Why the list is empty matters: "no account exists" and "this process may not read
            // accounts" are different user-visible failures, and both used to look like 0.
            out.put("can_query_accounts", String.valueOf(canQueryAccounts(context)));
            out.put("account_type_resolvable", String.valueOf(accountTypeResolvable(context)));
            out.put("picker_component", pickerComponent(context));
        } catch (Throwable error) {
            // This is what a user experiences as "the sign-in button does nothing": the call itself
            // throws inside the clone. The reason is recorded, never an empty list.
            out.put("ok", "false");
            out.put("error", error.getClass().getSimpleName() + ": " + error.getMessage());
            Log.e(TAG, "Account list failed", error);
        }
        return out;
    }

    private static Map<String, String> requestToken(Context context) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("pid", String.valueOf(android.os.Process.myPid()));
        out.put("reported_name", ownName());
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
            // One more legitimate path before giving up: a device with no account can still be asked
            // to add one. On an image without Play services this is what a sign-in button would
            // trigger, so whether it resolves is worth recording.
            Bundle addOptions = new Bundle();
            try {
                AccountManagerFuture<Bundle> future = manager.addAccount(
                        GOOGLE_ACCOUNT_TYPE, TOKEN_TYPE, null, addOptions, null, null, null);
                Bundle added = future == null ? null : future.getResult();
                Intent addIntent = added == null ? null
                        : (Intent) added.getParcelable(AccountManager.KEY_INTENT);
                out.put("ok", "true");
                out.put("result", "NO_ACCOUNT");
                out.put("token_length", "0");
                out.put("add_account", addIntent == null ? "NO_INTENT"
                        : String.valueOf(addIntent.getComponent()));
            } catch (Throwable error) {
                out.put("ok", "true");
                out.put("result", "NO_ACCOUNT");
                out.put("token_length", "0");
                out.put("add_account", "EXCEPTION:" + error.getClass().getSimpleName());
            }
            return out;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] result = {"PENDING", "0", null};
        try {
            // A Bundle of options, not null: an app's sign-in library passes one, and the platform
            // treats a null argument differently ("the caller cannot be asked for consent").
            // Only public keys are used — the caller-identity flags are @hide and do not compile
            // against the public SDK (this is exactly what the first attempt of this change hit).
            Bundle options = new Bundle();
            manager.getAuthToken(google[0], TOKEN_TYPE, options, true,
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
                                // AuthenticationException/OperationCanceledException are real answers;
                                // a SecurityException is the platform refusing the clone outright,
                                // which is exactly the reported "sign-in does nothing".
                                result[0] = error instanceof SecurityException
                                        ? "SECURITY_EXCEPTION" : "EXCEPTION";
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
        out.put("account", google[0].name);
        if (result[2] != null) {
            out.put("detail", result[2].replace('\n', ' '));
        }
        return out;
    }

    /** Whether this process may read the account list at all. */
    private static boolean canQueryAccounts(Context context) {
        try {
            return context.checkCallingOrSelfPermission("android.permission.GET_ACCOUNTS")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable error) {
            return false;
        }
    }

    /**
     * The component that would show the account picker for a com.google account, i.e. the real
     * Google account manager. Reporting it proves the intent an app fires for "choose an account"
     * resolves inside the clone, which is the step before any token is minted.
     */
    private static String pickerComponent(Context context) {
        try {
            Intent intent = AccountManager.newChooseAccountIntent(
                    null, null, new String[]{GOOGLE_ACCOUNT_TYPE}, null, null, null, null);
            android.content.pm.ResolveInfo resolved = context.getPackageManager()
                    .resolveActivity(intent, 0);
            if (resolved == null || resolved.activityInfo == null) {
                return "UNRESOLVED";
            }
            return resolved.activityInfo.packageName + "/" + resolved.activityInfo.name;
        } catch (Throwable error) {
            return "ERROR:" + error.getClass().getSimpleName();
        }
    }

    /** Is the com.google authenticator itself visible to this process? */
    private static boolean accountTypeResolvable(Context context) {
        try {
            AccountManager manager = AccountManager.get(context);
            for (android.accounts.AuthenticatorDescription description
                    : manager.getAuthenticatorTypes()) {
                if (GOOGLE_ACCOUNT_TYPE.equals(description.type)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable error) {
            return false;
        }
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
