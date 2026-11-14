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
 * The device's real account list and token path, asked from the host process.
 *
 * What was measured, and what replaced the earlier guess (deep runs 43586239139, 43588718279,
 * 43589662481 and 43590481245, all on the ARM64 API 34 image with one `com.google` account):
 *
 *  - The container does *not* answer account calls with an empty list of its own. Inside a clone,
 *    `AccountManager.get()` is `android.accounts.AccountManager` and the query reaches the platform:
 *    AccountManagerService logs it, and the account is listed once the process is past start-up.
 *  - Immediately after an agent process starts, the same query inside the same process returns
 *    nothing: sampled at 100 ms resolution, pid 3177 answered 0 at +787 ms and 1 at +887 ms, while
 *    twelve other launches answered 1 at their first sample (+682..+716 ms).
 *  - The container claims the query is its own: `caller_identity=uid=10087
 *    packages=com.clonehub.pro.test` — the real uid is 10088 (`u0a88`), so the platform is told the
 *    host package, and the host package's account visibility decides the answer.
 *  - `content://com.clonehub.pro.accounts` is not reachable from inside the clone at all:
 *    `IllegalArgumentException: Unknown authority`, in every probe, answered or not.
 *
 * So this class exists for two reasons, both measured rather than assumed: it is the only answer
 * that is independent of what the container reports about the caller, and it is the only one
 * available during the window a real sign-in button is pressed in (a cold start), where the
 * container's own path can still be empty. It never fabricates an account or a token: if the device
 * has no account the answer is an empty list and `NO_ACCOUNT`, exactly as the app itself would get.
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

    /**
     * How long a first answer is treated as possibly premature.
     *
     * Measured on the deep gate (run 43590481245): inside a clone whose process had just started, the
     * account list was empty at +787 ms and complete at +887 ms; twelve other launches were already
     * complete at their first sample, between +682 ms and +716 ms. 2 s leaves a 2x margin over the
     * only late answer measured. The wait is skipped entirely when the first answer is already
     * non-empty, so the common case pays nothing.
     */
    private const val SETTLE_WINDOW_MS = 2_000L
    private const val SETTLE_POLL_MS = 200L

    /**
     * Account names of [type], or the reason the platform refused — never an invented list.
     *
     * A caller that is told "no accounts" is told the truth only if the answer has settled, and the
     * measurement above says a fresh process can answer "no accounts" for the first second. So an
     * empty first answer is re-asked for up to [SETTLE_WINDOW_MS], and how long it took is returned
     * with it; a device with no account still ends up with an empty list, after the window.
     */
    fun accountNames(type: String): Pair<List<String>, String?> =
        accountNamesSettled(type).let { it.names to it.error }

    data class SettledAccounts(val names: List<String>, val error: String?, val waitedMs: Long)

    fun accountNamesSettled(type: String): SettledAccounts {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var lastError: String? = null
        while (true) {
            val (names, error) = readNamesOnce(type)
            lastError = error
            val waited = android.os.SystemClock.elapsedRealtime() - startedAt
            if (names.isNotEmpty() || error != null || waited >= SETTLE_WINDOW_MS) {
                if (waited > SETTLE_POLL_MS) {
                    Log.i(TAG, "accountNames type=$type settled after ${waited}ms names=${names.size} error=$error")
                }
                return SettledAccounts(names, lastError, waited)
            }
            android.os.SystemClock.sleep(SETTLE_POLL_MS)
        }
    }

    private fun readNamesOnce(type: String): Pair<List<String>, String?> {
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
        // Same settle rule as the list: a token request made during a clone's first second must not
        // be answered with NO_ACCOUNT when the device does have the account.
        val names = accountNamesSettled(type).names
        val accounts = try {
            manager.getAccountsByType(type)
        } catch (error: Throwable) {
            return failure(error)
        }
        if (names.isEmpty() && accounts.isEmpty()) return TokenOutcome("NO_ACCOUNT", 0, null)

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
