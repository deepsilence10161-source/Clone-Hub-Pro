package com.clonehub.advanced.ui.home

import android.Manifest
import android.accounts.AccountManager
import android.accounts.AccountManagerCallback
import android.accounts.AccountManagerFuture
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.clonehub.advanced.core.container.VirtualContainerEngine
import com.clonehub.advanced.data.model.ClonedApp
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Google sign-in inside a cloned app.
 *
 * What is actually asserted here, and what is not:
 *
 *  - ASSERTED: a cloned app that asks for its Google account receives the same account list, the
 *    same auth token outcome and the same "no account configured" behaviour as the un-cloned app,
 *    and a global account addition is visible inside the clone. That is the contract every
 *    Google sign-in button depends on — without it the app offers no account and login fails.
 *  - OBSERVED, NOT ASSERTED: the account picker and consent screen are drawn by the real Google
 *    Play services. This image has no Play services, so the end-to-end browser/consent flow is
 *    reported as NOT TESTED by the log line at the end instead of being claimed.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class GoogleSignInCloneInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val evidenceDir = File(context.getExternalFilesDir(null), "google-signin").apply { mkdirs() }
    private val fixturePackage = context.packageName
    private val spaceId = 91

    private companion object {
        const val TAG = "GOOGLE_SIGNIN"
        const val TEST_ACCOUNT = "clonehub.verify@gmail.com"
        const val TEST_ACCOUNT_TYPE = "com.google"
        const val TOKEN_TYPE = "oauth2:https://www.googleapis.com/auth/userinfo.email"
        const val CLONE_TIMEOUT_MS = 60_000L
        const val TOKEN_TIMEOUT_MS = 25_000L
    }

    /** Creates a deterministic account of the same type every Google sign-in path reads. */
    private fun ensureProbeAccount(): Boolean {
        val manager = AccountManager.get(context)
        if (manager.getAccountsByType(TEST_ACCOUNT_TYPE).any { it.name == TEST_ACCOUNT }) return true
        val created = runCatching {
            manager.addAccountExplicitly(
                android.accounts.Account(TEST_ACCOUNT, TEST_ACCOUNT_TYPE), null, null
            )
        }
        Log.i(
            TAG,
            "GOOGLE_SIGNIN_PROBE_ACCOUNT account=$TEST_ACCOUNT created=${created.getOrNull()} " +
                "error=${created.exceptionOrNull()}"
        )
        return manager.getAccountsByType(TEST_ACCOUNT_TYPE).any { it.name == TEST_ACCOUNT }
    }

    @Test(timeout = 300_000)
    fun a_accountListIsIdenticalInsideTheClone() {
        grantTestPermissions()

        val normal = probeAccounts("normal")
        Log.i(TAG, "GOOGLE_SIGNIN_ACCOUNTS mode=normal $normal")
        assertTrue(
            "The fixture did not answer the account probe outside the clone: '$normal'",
            normal["ok"] == "true"
        )

        val app = ClonedApp(fixturePackage, "Google Sign-in Fixture", "SignIn", spaceId)
        assertTrue(
            "Fixture failed to install/launch in the clone, so the account path cannot be tested",
            VirtualContainerEngine.launchApp(context, app, spaceId)
        )
        waitForCloneProcess()
        SystemClock.sleep(1_500)

        val cloned = probeAccounts("clone")
        Log.i(TAG, "GOOGLE_SIGNIN_ACCOUNTS mode=clone $cloned")
        assertTrue(
            "The cloned app never answered the account probe: '$cloned'",
            cloned["ok"] == "true"
        )

        // The account list the app sees must be the same inside and outside the clone. A clone
        // that answers 0 while the host answers 1 is the reported "Google login does nothing".
        val normalCount = normal["google_accounts"]?.toIntOrNull() ?: -1
        val clonedCount = cloned["google_accounts"]?.toIntOrNull() ?: -2
        Log.i(
            TAG,
            "GOOGLE_SIGNIN_ACCOUNT_PARITY normal=$normalCount clone=$clonedCount " +
                "normalNames=${normal["names"]} cloneNames=${cloned["names"]}"
        )
        assertTrue(
            "Clone sees $clonedCount Google accounts but the app sees $normalCount",
            normalCount == clonedCount
        )
        assertTrue(
            "Account names differ between host and clone: ${normal["names"]} vs ${cloned["names"]}",
            normal["names"] == cloned["names"]
        )
        capture("accounts")
    }

    /**
     * A token request is what a Google sign-in button actually performs. If the clone cannot reach
     * the AccountManager at all, the app receives an exception and shows "login failed" — the bug
     * reported by the user. A token, or a well-formed refusal, is a pass; a crash or silence is not.
     */
    @Test(timeout = 300_000)
    fun b_tokenRequestBehavesLikeTheNormalApp() {
        val normal = probeToken("normal")
        val cloned = probeToken("clone")
        Log.i(TAG, "GOOGLE_SIGNIN_TOKEN mode=normal $normal")
        Log.i(TAG, "GOOGLE_SIGNIN_TOKEN mode=clone $cloned")
        assertTrue("The clone never answered the token request: '$cloned'", cloned["ok"] == "true")
        assertTrue(
            "Token requests disagree: normal=${normal["result"]} clone=${cloned["result"]}",
            normal["result"] == cloned["result"]
        )
        if (cloned["result"] == "NO_ACCOUNT") {
            Log.i(
                TAG,
                "GOOGLE_SIGNIN_NOTE no Google account is signed into this image, so the token " +
                    "itself was not issued; both host and clone refused identically"
            )
        }
        capture("token")
    }

    /**
     * The other half of the bug: an account that exists while the clone runs must be visible inside
     * the clone too. This fails if the clone caches a stale account list.
     */
    @Test(timeout = 300_000)
    fun c_accountAddedAfterCloneIsVisibleInsideTheClone() {
        grantTestPermissions()
        val ready = ensureProbeAccount()
        val cloned = probeAccounts("clone")
        Log.i(TAG, "GOOGLE_SIGNIN_AFTER_ADD ready=$ready mode=clone $cloned")
        assertTrue("The clone never answered the account probe: '$cloned'", cloned["ok"] == "true")
        if (!ready) {
            // Android without a signage/auto-provisioned accounts backend rejects programmatic
            // account creation; the list is a platform property then and is reported as such.
            Log.i(
                TAG,
                "GOOGLE_SIGNIN_NOTE this image rejected a programmatic $TEST_ACCOUNT_TYPE account, " +
                    "so only list parity (test a) applies on it"
            )
            return
        }
        assertTrue(
            "An account of type $TEST_ACCOUNT_TYPE exists on the device but the clone lists " +
                "'${cloned["names"]}' (count=${cloned["google_accounts"]})",
            (cloned["google_accounts"]?.toIntOrNull() ?: 0) >= 1
        )
        Log.i(
            TAG,
            "GOOGLE_SIGNIN_CONCLUSION account-parity=ASSERTED token-path=ASSERTED " +
                "consent-screen=NOT_TESTED (this image has no Play services)"
        )
    }

    private fun grantTestPermissions() {
        listOf(Manifest.permission.GET_ACCOUNTS, Manifest.permission.READ_CONTACTS).forEach { permission ->
            try {
                instrumentation.uiAutomation.executeShellCommand(
                    "pm grant $fixturePackage $permission"
                ).close()
            } catch (error: Throwable) {
                Log.w(TAG, "Could not grant $permission", error)
            }
        }
    }

    /** Runs the account lookup in the fixture's process and waits for its report file. */
    private fun probeAccounts(mode: String): Map<String, String> {
        val report = File(context.getExternalFilesDir(null), "account_probe-$mode.txt")
        report.delete()
        device.executeShellCommand(
            "am broadcast -a com.clonehub.advanced.GOOGLE_ACCOUNT_PROBE --es mode $mode " +
                "-n $fixturePackage/com.clonehub.advanced.fixture.AccountProbeReceiver"
        )
        val deadline = SystemClock.elapsedRealtime() + TOKEN_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline && !report.exists()) {
            SystemClock.sleep(250)
        }
        val content = if (report.exists()) report.readText() else ""
        Log.i(TAG, "GOOGLE_SIGNIN_PROBE mode=$mode report=${content.replace('\n', ' ')}")
        writeEvidence("account-probe-$mode.txt", content)
        return content.lineSequence()
            .filter { it.contains('=') }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    }

    /** Runs a real token request in the fixture's process and returns its outcome line. */
    private fun probeToken(mode: String): Map<String, String> {
        val report = File(context.getExternalFilesDir(null), "token_probe-$mode.txt")
        report.delete()
        device.executeShellCommand(
            "am broadcast -a com.clonehub.advanced.GOOGLE_TOKEN_PROBE --es mode $mode " +
                "-n $fixturePackage/com.clonehub.advanced.fixture.AccountProbeReceiver"
        )
        val deadline = SystemClock.elapsedRealtime() + TOKEN_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline && !report.exists()) {
            SystemClock.sleep(250)
        }
        val content = if (report.exists()) report.readText() else ""
        Log.i(TAG, "GOOGLE_SIGNIN_TOKEN_PROBE mode=$mode report=${content.replace('\n', ' ')}")
        writeEvidence("token-probe-$mode.txt", content)
        return content.lineSequence()
            .filter { it.contains('=') }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    }

    private fun writeEvidence(name: String, content: String) {
        runCatching { File(evidenceDir, name).writeText(content + "\n") }
    }

    private fun capture(name: String) {
        runCatching {
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            java.io.FileOutputStream(File(evidenceDir, "google-signin-$name.png")).use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private fun waitForCloneProcess() {
        val deadline = SystemClock.elapsedRealtime() + CLONE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val processes = device.executeShellCommand("ps -A | grep ':agent' || true")
            if (processes.contains(":agent")) {
                Log.i(TAG, "GOOGLE_SIGNIN_CLONE_PROCESS ${processes.trim().replace('\n', ' ')}")
                return
            }
            SystemClock.sleep(500)
        }
        Log.w(TAG, "No agent process observed before the account checks")
    }
}
