package com.clonehub.advanced.fixture;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.accounts.NetworkErrorException;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

/**
 * A stand-in for the Google account authenticator, for the run where the image has no Play services.
 *
 * Why this exists: on this image `com.google` has no authenticator at all, so an app's
 * "sign in with Google" cannot reach the real account manager no matter how correct the clone is.
 * That is an image property, and it is why end-to-end sign-in has been reported NOT TESTED.
 *
 * This authenticator is registered by the instrumentation APK *on purpose*, under the real
 * `com.google` account type, so the platform routes a token request through its own
 * AccountManagerService to an authenticator the same way it would route it to Google's. What it
 * returns is a clearly marked fake token — this proves the plumbing a cloned app depends on
 * (account visibility, token request, channel back to the caller), never the identity behind it.
 */
public final class TestGoogleAuthenticator extends AbstractAccountAuthenticator {

    public static final String TAG = "SIGNIN_FIXTURE";
    public static final String TOKEN_PREFIX = "fake-google-oauth-token-";
    /** Path the automatic token add/remove timestamps are written to, for the evidence archive. */
    private static final String PROOF_NAME = "test_authenticator_proof.txt";

    private final Context context;

    public TestGoogleAuthenticator(Context context) {
        super(context);
        this.context = context;
    }

    /** Minted here and handed back to whoever asked; the length is enough to prove delivery. */
    private static String tokenFor(Account account) {
        return TOKEN_PREFIX + account.name + "-len-" + TOKEN_PREFIX.length() + "-issued";
    }

    private static void appendProof(String line) {
        try {
            java.io.File dir = new java.io.File("/sdcard/Android/data/com.clonehub.pro.test/files");
            dir.mkdirs();
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                    new java.io.File(dir, PROOF_NAME), true)) {
                out.write((System.currentTimeMillis() + " " + line + "\n").getBytes("UTF-8"));
            }
        } catch (Throwable error) {
            Log.w(TAG, "Could not append authenticator proof", error);
        }
    }

    @Override
    public Bundle editProperties(AccountAuthenticatorResponse response, String accountType) {
        return new Bundle();
    }

    @Override
    public Bundle addAccount(AccountAuthenticatorResponse response, String accountType,
            String authTokenType, String[] requiredFeatures, Bundle options)
            throws NetworkErrorException {
        appendProof("addAccount type=" + accountType + " authTokenType=" + authTokenType);
        Bundle result = new Bundle();
        result.putString(AccountManager.KEY_ACCOUNT_NAME, "probe.account@example.com");
        result.putString(AccountManager.KEY_ACCOUNT_TYPE, accountType);
        return result;
    }

    @Override
    public Bundle confirmCredentials(AccountAuthenticatorResponse response, Account account,
            Bundle options) {
        return new Bundle();
    }

    /**
     * The call a "Sign in with Google" button ends in. The proof line records the account and the
     * token type that arrived here, i.e. that the request came from inside the clone with the
     * account the platform knows.
     */
    @Override
    public Bundle getAuthToken(AccountAuthenticatorResponse response, Account account,
            String authTokenType, Bundle options) throws NetworkErrorException {
        appendProof("getAuthToken account=" + account.name + " type=" + account.type
                + " authTokenType=" + authTokenType);
        Bundle result = new Bundle();
        result.putString(AccountManager.KEY_ACCOUNT_NAME, account.name);
        result.putString(AccountManager.KEY_ACCOUNT_TYPE, account.type);
        result.putString(AccountManager.KEY_AUTHTOKEN, tokenFor(account));
        return result;
    }

    @Override
    public String getAuthTokenLabel(String authTokenType) {
        return authTokenType;
    }

    @Override
    public Bundle updateCredentials(AccountAuthenticatorResponse response, Account account,
            String authTokenType, Bundle options) {
        return new Bundle();
    }

    @Override
    public Bundle hasFeatures(AccountAuthenticatorResponse response, Account account,
            String[] features) {
        Bundle result = new Bundle();
        result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, true);
        return result;
    }

    /** The bound service the platform talks to. */
    public static final class AuthenticatorService extends Service {
        private TestGoogleAuthenticator authenticator;

        @Override
        public void onCreate() {
            super.onCreate();
            authenticator = new TestGoogleAuthenticator(this);
            Log.i(TAG, "Test Google authenticator service created");
        }

        @Override
        public IBinder onBind(Intent intent) {
            return authenticator.getIBinder();
        }
    }
}
