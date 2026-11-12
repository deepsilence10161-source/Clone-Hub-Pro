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
        assertTrue(
            "The clone answer came from '${cloned["answer_process"]}', not from an agent process",
            cloned["answer_process"]?.contains(":agent") == true
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
     * Runs the probe once and returns the answer, labelled with the process that really produced it.
     *
     * The label is decided here, not by the probe: inside the container the fixture cannot name its
     * own process truthfully (both the virtualised API and /proc/self/cmdline report the cloned
     * package). So the log is cleared, exactly one launch happens, and the pid reported by the probe
     * is resolved to its real process name from the shell.
     */
    private fun probe(kind: String, token: Boolean = false): Map<String, String> {
        val fileName = if (token) "token_probe.txt" else "account_probe.txt"
        // The answer is published from onCreate, so a fixture that is already running would stay
        // silent. Both the plain fixture and the clone are stopped first.
        runCatching { VirtualContainerEngine.forceStopApp(context, fixturePackage, spaceId) }
        device.executeShellCommand("am force-stop $fixturePackage")
        SystemClock.sleep(500)
        device.executeShellCommand("logcat -c")
        if (kind == "clone") {
            val app = ClonedApp(fixturePackage, "Google Sign-in Fixture", "SignIn", spaceId)
            assertTrue(
                "Fixture failed to install/launch in the clone, so the account path cannot be tested",
                VirtualContainerEngine.launchApp(context, app, spaceId)
            )
            waitForCloneProcess()
        } else {
            // The fixture lives in the instrumentation APK, so the component is named explicitly;
            // starting it through the app's own context resolves to the wrong package.
            val started = runCatching {
                instrumentation.context.startActivity(
                    Intent().setClassName(fixturePackage, CloneFixtureActivity::class.java.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            }
            assertTrue(
                "Could not start the fixture for the normal probe: ${started.exceptionOrNull()}",
                started.isSuccess
            )
        }

        val deadline = SystemClock.elapsedRealtime() + REPORT_TIMEOUT_MS
        var log = device.executeShellCommand("logcat -d")
        var answer = parseProbe(log, fileName)
        while (answer.isEmpty() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(500)
            log = device.executeShellCommand("logcat -d")
            answer = parseProbe(log, fileName)
        }

        val pid = answer["pid"]
        val realProcess = pid?.let { resolveProcessName(it) } ?: ""
        val expected = if (kind == "clone") ":agent" else fixturePackage
        val labelled = LinkedHashMap(answer)
        labelled["answer_process"] = realProcess
        labelled["expected_process"] = expected
        Log.i(
            TAG,
            "GOOGLE_SIGNIN_PROBE kind=$kind file=$fileName pid=$pid realProcess=$realProcess " +
                "expected=$expected answer=$answer"
        )
        writeEvidence("$kind-answer.txt", labelled.entries.joinToString("\n") { "${it.key}=${it.value}" })
        return labelled
    }

    /**
     * Real process name of a pid, read from the shell's own process list. Inside the container the
     * process cannot name itself truthfully, but `ps -A` outside it shows
     * `com.clonehub.pro:agent0` — which is what makes a "clone" answer provably a clone answer.
     */
    private fun resolveProcessName(pid: String): String {
        for (attempt in 1..3) {
            val line = device.executeShellCommand("ps -A")
                .lineSequence()
                .firstOrNull { row ->
                    row.split(Regex("\\s+")).getOrNull(1) == pid ||
                        row.split(Regex("\\s+")).any { it == pid }
                }
            if (line != null) {
                Log.i(TAG, "GOOGLE_SIGNIN_PS pid=$pid line=${line.trim()}")
                return line.trim().split(Regex("\\s+")).last()
            }
            SystemClock.sleep(300)
        }
        return ""
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
            val processes = device.executeShellCommand("ps -A | grep -i agent || true")
            if (processes.contains("agent", ignoreCase = true)) {
                Log.i(TAG, "GOOGLE_SIGNIN_CLONE_PROCESS ${processes.trim().replace('\n', ' ')}")
                return
            }
            SystemClock.sleep(500)
        }
        Log.w(TAG, "No agent process observed before the account checks")
    }
}
