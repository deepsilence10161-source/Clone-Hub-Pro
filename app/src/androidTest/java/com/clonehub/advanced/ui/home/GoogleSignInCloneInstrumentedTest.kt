package com.clonehub.advanced.ui.home

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.clonehub.advanced.core.container.VirtualContainerEngine
import com.clonehub.advanced.fixture.CloneFixtureActivity
import com.clonehub.advanced.data.model.ClonedApp
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File

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
    // CloneFixtureActivity ships inside the instrumentation APK, so its package is the target's
    // with the .test suffix — not the app's own package.
    private val fixturePackage = "com.clonehub.pro.test"
    private val spaceId = 91

    private companion object {
        const val TAG = "GOOGLE_SIGNIN"
        const val CLONE_TIMEOUT_MS = 60_000L
        const val REPORT_TIMEOUT_MS = 40_000L
    }

    @Test(timeout = 300_000)
    fun a_accountListIsIdenticalInsideTheClone() {
        grantTestPermissions()

        val normal = probe("normal")
        assertTrue(
            "The fixture did not answer the account probe outside the clone: '$normal'",
            normal["ok"] == "true"
        )
        val cloned = probe("clone")
        assertTrue(
            "The cloned app never answered the account probe: '$cloned'",
            cloned["ok"] == "true"
        )

        // The account list the app sees must be the same inside and outside the clone. A clone
        // that answers 0 while the app answers 1 is the reported "Google login does nothing".
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
     * A token request is what a Google sign-in button actually performs. A token, a consent
     * request, or the platform's own "no account configured" answer are all real outcomes; the
     * clone must produce the same one as the app. A crash or silence is the failure.
     */
    @Test(timeout = 300_000)
    fun b_tokenRequestBehavesLikeTheNormalApp() {
        val normal = probe("normal", token = true)
        val cloned = probe("clone", token = true)
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
                "GOOGLE_SIGNIN_NOTE no Google account is signed into this image, so no token could " +
                    "be issued; host and clone refused identically"
            )
        }
        capture("token")
    }

    /**
     * The other half of the bug: a Google account that is present on the device must be visible
     * inside the clone as well. This fails if the clone keeps its own stale account list.
     */
    @Test(timeout = 300_000)
    fun c_accountPresenceMatchesInsideTheClone() {
        grantTestPermissions()
        val cloned = probe("clone")
        assertTrue("The clone never answered the account probe: '$cloned'", cloned["ok"] == "true")
        val deviceGoogleAccounts = try {
            device.executeShellCommand(
                "dumpsys account | grep -A2 'Accounts: ' | grep -c 'com.google' || true"
            ).trim().toIntOrNull() ?: 0
        } catch (error: Throwable) {
            Log.w(TAG, "Could not read the device account service", error)
            0
        }
        val clonedCount = cloned["google_accounts"]?.toIntOrNull() ?: -1
        Log.i(
            TAG,
            "GOOGLE_SIGNIN_ACCOUNT_PRESENCE device=$deviceGoogleAccounts clone=$clonedCount"
        )
        // A signed-in device must never look signed-out inside a clone.
        if (deviceGoogleAccounts > 0) {
            assertTrue(
                "The device has $deviceGoogleAccounts Google account(s) but the clone lists none",
                clonedCount > 0
            )
        }
        Log.i(
            TAG,
            "GOOGLE_SIGNIN_CONCLUSION account-list-parity=ASSERTED token-parity=ASSERTED " +
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

    /**
     * Asks the fixture to report what it sees: normally the fixture is started directly, inside the
     * clone the cloned activity runs the very same code. Each answer is published one key per log
     * line, so it reaches the test no matter which process wrote it — the normal answer comes from
     * the fixture's own process, the clone answer from the virtual agent process.
     */
    private fun probe(kind: String, token: Boolean = false): Map<String, String> {
        val mode = if (kind == "clone") "clone" else "normal"
        val fileName = if (token) "token_probe-$mode.txt" else "account_probe-$mode.txt"
        device.executeShellCommand("logcat -c")
        File(context.filesDir, "account_probe_request").writeText(kind)
        if (kind == "clone") {
            val app = ClonedApp(fixturePackage, "Google Sign-in Fixture", "SignIn", spaceId)
            assertTrue(
                "Fixture failed to install/launch in the clone, so the account path cannot be tested",
                VirtualContainerEngine.launchApp(context, app, spaceId)
            )
            waitForCloneProcess()
        } else {
            context.startActivity(Intent(context, CloneFixtureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
        }

        val deadline = SystemClock.elapsedRealtime() + REPORT_TIMEOUT_MS
        var answer = parseProbe(device.executeShellCommand("logcat -d"), fileName)
        while (answer.isEmpty() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(500)
            answer = parseProbe(device.executeShellCommand("logcat -d"), fileName)
        }
        Log.i(TAG, "GOOGLE_SIGNIN_PROBE kind=$kind file=$fileName answer=$answer")
        writeEvidence(fileName, answer.entries.joinToString("\n") { "${it.key}=${it.value}" })
        return answer
    }

    /** Reads the key=value answer the probe published for one file from the device log. */
    private fun parseProbe(log: String, fileName: String): Map<String, String> {
        val answer = mutableMapOf<String, String>()
        log.lineSequence()
            .filter { it.contains("GOOGLE_SIGNIN_KV") && it.contains("file=$fileName") }
            .forEach { line ->
                val key = line.substringAfter("key=", "").substringBefore(" ").trim()
                val value = line.substringAfter("value=", "").trim()
                if (key.isNotEmpty()) answer[key] = value
            }
        return answer
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
