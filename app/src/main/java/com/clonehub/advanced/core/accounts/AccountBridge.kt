package com.clonehub.advanced.core.accounts

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AccountManagerCallback
import android.accounts.AccountManagerFuture
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The device's real account list and token path, answered from the host process.
 *
 * Why this class exists: the bundled virtual runtime answers a cloned app's `AccountManager` calls
 * with its own implementation, which reports **zero accounts** regardless of what the device has.
 * Measured on the deep gate (run 43564391516, commit 3641e9a) with one `com.google` account on the
 * device: the un-cloned app got a token, the same code inside a clone reported `NO_ACCOUNT`. That is
 * the reported "Google login does not work in a cloned app".
 *
 * Copying the account list into the container cannot fix it: a token is minted by the authenticator,
 * and only the host process is registered with the platform for that. So the answer has to come from
 * here.
 *
 * What this does NOT do: it never fabricates an account or a token. If the device has no account the
 * answer is an empty list and `NO_ACCOUNT`, exactly as the app itself would get. Any security
 * exception is reported as `SECURITY_EXCEPTION` rather than being hidden.
 */
object AccountBridge {

    private const val TAG = "AccountBridge"
    const val GOOGLE_ACCOUNT_TYPE = "com.google"
    const val TOKEN_TYPE = "oauth2:https://www.googleapis.com/auth/userinfo.email"
    private const val TOKEN_TIMEOUT_SECONDS = 20L

    private val accountManager: AccountManager?
        get() = runCatching {
            AccountManager.get(CloneHubContextHolder.applicationContext)
        }.getOrNull()

    /** Account names of [type], or the reason the platform refused — never an invented list. */
    fun accountNames(type: String): Pair<List<String>, String?> {
        val manager = accountManager ?: return emptyList<String>() to "NO_ACCOUNT_MANAGER"
        return try {
            val accounts: Array<Account> = manager.getAccountsByType(type)
            accounts.map { it.name }.sorted() to null
        } catch (error: Throwable) {
            Log.w(TAG, "Could not read accounts of type $type", error)
            emptyList<String>() to "${error.javaClass.simpleName}: ${error.message}"
        }
    }

    /**
     * The outcome of a token request, worded the same way the sign-in probe words it so the two can
     * be compared directly: TOKEN / NO_ACCOUNT / CONSENT_REQUIRED / SECURITY_EXCEPTION / EXCEPTION.
     */
    data class TokenOutcome(val result: String, val length: Int, val detail: String?)

    fun authToken(type: String, tokenType: String = TOKEN_TYPE): TokenOutcome {
        val manager = accountManager
            ?: return TokenOutcome("EXCEPTION", 0, "NO_ACCOUNT_MANAGER")
        val accounts = try {
            manager.getAccountsByType(type)
        } catch (error: Throwable) {
            return failure(error)
        }
        if (accounts.isEmpty()) return TokenOutcome("NO_ACCOUNT", 0, null)

        val latch = CountDownLatch(1)
        var outcome = TokenOutcome("PENDING", 0, null)
        return try {
            manager.getAuthToken(
                accounts[0],
                tokenType,
                Bundle(),
                true,
                AccountManagerCallback<Bundle> { future: AccountManagerFuture<Bundle> ->
                    outcome = try {
                        val bundle: Bundle? = future.result
                        val token = bundle?.getString(AccountManager.KEY_AUTHTOKEN)
                        @Suppress("DEPRECATION")
                        val consent = bundle?.getParcelable<Intent>(AccountManager.KEY_INTENT)
                        when {
                            token != null -> TokenOutcome("TOKEN", token.length, null)
                            consent != null -> TokenOutcome(
                                "CONSENT_REQUIRED",
                                0,
                                consent.component?.toString()
                            )
                            else -> TokenOutcome("EMPTY_TOKEN", 0, null)
                        }
                    } catch (error: Throwable) {
                        failure(error)
                    } finally {
                        latch.countDown()
                    }
                },
                null
            )
            latch.await(TOKEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            Log.i(TAG, "authToken type=$type outcome=${outcome.result} length=${outcome.length}")
            outcome
        } catch (error: Throwable) {
            failure(error)
        }
    }

    private fun failure(error: Throwable): TokenOutcome = TokenOutcome(
        if (error is SecurityException) "SECURITY_EXCEPTION" else "EXCEPTION",
        0,
        "${error.javaClass.simpleName}: ${error.message}"
    )
}

/** The host Context for code that runs outside an Activity (the account provider). */
object CloneHubContextHolder {
    @Volatile
    var applicationContext: Context? = null

    fun install(context: Context) {
        applicationContext = context.applicationContext
    }
}
