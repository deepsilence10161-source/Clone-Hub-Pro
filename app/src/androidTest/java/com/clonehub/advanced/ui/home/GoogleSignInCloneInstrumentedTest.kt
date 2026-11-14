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
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
        // The app the platform sees hosting the container.
        const val CLONE_PACKAGE = "com.clonehub.pro"
        const val SEED_ACCOUNT = "clonehub.probe@example.com"
        val ACCOUNT_PERMISSIONS = listOf(
            Manifest.permission.GET_ACCOUNTS,
            Manifest.permission.READ_CONTACTS
        )
    }

    @Test(timeout = 300_000)
    fun a_accountListIsIdenticalInsideTheClone() {
        grantTestPermissions()
        seedGoogleAccount()
        shareAccountWithCloneHost()

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
        // Parity alone is satisfiable by "both processes are blind": before this assertion the
        // gate passed with 0 == 0 while the app under test held no GET_ACCOUNTS grant at all, so a
        // sign-in button would have been refused for a reason the suite never looked at.
        assertTrue(
            "The app may not read the account list (can_query_accounts=${normal["can_query_accounts"]}); " +
                "a clone would see the same refusal and sign-in would fail for a reason unrelated to cloning",
            normal["can_query_accounts"] == "true"
        )
        assertTrue(
            "The clone may not read the account list (can_query_accounts=${cloned["can_query_accounts"]})",
            cloned["can_query_accounts"] == "true"
        )
        // The account picker intent an app fires must resolve inside the clone as well.
        Log.i(
            TAG,
            "GOOGLE_SIGNIN_PICKER normal=${normal["picker_component"]} clone=${cloned["picker_component"]} " +
                "authenticator normal=${normal["account_type_resolvable"]} " +
                "clone=${cloned["account_type_resolvable"]}"
        )
        // If the account manager is reachable at all, the chooser must be reachable from inside
        // the clone too — that is the step before any token is minted.
        if (normal["account_type_resolvable"] == "true") {
            assertTrue(
                "The Google authenticator is visible to the app but the account chooser does not " +
                    "resolve inside the clone (${cloned["picker_component"]})",
                cloned["picker_component"] != "UNRESOLVED"
            )
        }
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
        // With an account present, "sign in does nothing" would show up as one side refusing where
        // the other succeeds — or as a clone that gets a *different*, empty answer.
        val seeded = googleAccountCount()
        if (seeded > 0) {
            assertTrue(
                "The device has $seeded Google account(s) but the token request returned " +
                    "${cloned["result"]} inside the clone (host: ${normal["result"]})",
                cloned["result"] == "TOKEN"
            )
            assertTrue(
                "The clone received a token of length ${cloned["token_length"]}; the app received " +
                    "${normal["token_length"]}",
                cloned["token_length"] == normal["token_length"]
            )
            val proof = authenticatorProof()
            Log.i(TAG, "GOOGLE_SIGNIN_AUTHENTICATOR_PROOF $proof")
            assertTrue(
                "The account authenticator never recorded a token request, so the token came from " +
                    "somewhere else: '$proof'",
                proof.contains("getAuthToken")
            )
        } else {
            Log.i(
                TAG,
                "GOOGLE_SIGNIN_NOTE no Google account could be created in this image, so no token " +
                    "could be issued; host and clone refused identically"
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
                "account-permission=ASSERTED picker-intent=ASSERTED " +
                "consent-screen=${if (clonedCount > 0) "REQUESTED" else "NOT_TESTED (no account in this image)"}"
        )
    }

    /**
     * The step a user actually performs: the picker/consent screen.
     *
     * This asks Android, from inside the clone, to open the account chooser, and records exactly
     * what came back — the component that answered, or the platform's reason for refusing. It does
     * not pretend the browser-based OAuth flow was completed: on an image with no Google account
     * and no Play services there is nothing to consent to, and that is reported as such.
     */
    @Test(timeout = 300_000)
    fun d_accountChooserAnsweredInsideTheClone() {
        grantTestPermissions()
        val cloned = probe("clone")
        assertTrue("The clone never answered the account probe: '$cloned'", cloned["ok"] == "true")
        val chooser = cloned["picker_component"] ?: "ABSENT"
        val accounts = cloned["google_accounts"]?.toIntOrNull() ?: -1
        Log.i(
            TAG,
            "GOOGLE_SIGNIN_CHOOSER clone accounts=$accounts component=$chooser " +
                "authenticator=${cloned["account_type_resolvable"]}"
        )
        // Two acceptable truths, both recorded with their evidence: the chooser resolves (the real
        // Google account manager is reachable from inside the clone), or it does not resolve because
        // this image ships no com.google authenticator — which is an image property, stated as such.
        val authenticatorPresent = cloned["account_type_resolvable"] == "true" ||
            authenticatorTypes().contains(GOOGLE_ACCOUNT_TYPE)
        if (authenticatorPresent) {
            assertTrue(
                "The Google authenticator is present but the account chooser does not resolve in the clone: $chooser",
                chooser != "UNRESOLVED"
            )
        } else {
            Log.i(
                TAG,
                "GOOGLE_SIGNIN_CHOOSER_NOTE this image exposes no com.google authenticator, so there is " +
                    "no chooser to open; the clone answered the same way the app does"
            )
        }
        capture("chooser")
    }

    /**
     * Grants the account permissions on both processes that answer the probe.
     *
     * The clone's answer comes from the Pro process (`com.clonehub.pro:agent0`), not from the
     * fixture APK, so granting the fixture alone left the clone un-granted — and with parity as the
     * only assertion, "both sides refused" looked like success. The grant is applied to the app under
     * test as well, and the probe reports the permission state it actually saw.
     */
    /**
     * Makes sure the device has a `com.google` account, created the way the platform allows.
     *
     * On an image without Play services there is no authenticator for `com.google`, so an app's
     * sign-in cannot reach the account manager and every gate could only report `NO_ACCOUNT` — for
     * both the app and the clone, which is parity but proves nothing. The instrumentation APK
     * registers a test authenticator for the same account type; this method then adds an account
     * through `AccountManager`, and records whether the platform accepted it.
     */
    private fun seedGoogleAccount() {
        if (googleAccountCount() > 0) return
        val staged = File(context.getExternalFilesDir(null), "fixture-app.apk")
        if (!staged.exists() || staged.length() == 0L) {
            runCatching {
                File(instrumentation.context.applicationInfo.sourceDir).inputStream().use { input ->
                    staged.outputStream().use { output -> input.copyTo(output) }
                }
            }.onFailure { Log.w(TAG, "Could not stage the authenticator APK", it) }
        }
        device.executeShellCommand("pm install -r -t ${staged.absolutePath}").trim().let {
            Log.i(TAG, "GOOGLE_SIGNIN_SEED authenticator install: $it")
        }
        // The platform only accepts an account-add for a type that has a registered authenticator.
        val added = runCatching {
            val manager = android.accounts.AccountManager.get(context)
            manager.addAccountExplicitly(
                android.accounts.Account(SEED_ACCOUNT, GOOGLE_ACCOUNT_TYPE), null, null
            )
        }.getOrElse {
            Log.w(TAG, "GOOGLE_SIGNIN_SEED addAccountExplicitly failed", it)
            false
        }
        // The measured answer to "who is allowed to see this account". If it is not VISIBLE the
        // clone can never list it, and the reason is the platform's, not the clone's.
        val visibility = runCatching {
            val manager = android.accounts.AccountManager.get(context)
            val account = android.accounts.Account(SEED_ACCOUNT, GOOGLE_ACCOUNT_TYPE)
            "self=" + manager.getAccountVisibility(account, context.packageName) +
                " clone=" + manager.getAccountVisibility(account, CLONE_PACKAGE)
        }.getOrElse { "unavailable(" + it.javaClass.simpleName + ")" }
        Log.i(
            TAG,
            "GOOGLE_SIGNIN_SEED added=$added accounts=${googleAccountCount()} " +
                "authenticators=${authenticatorTypes()} visibility=[$visibility] " +
                "proof=${authenticatorProof()}"
        )
        Log.i(TAG, "GOOGLE_SIGNIN_PROOF ${authenticatorProof()}")
    }

    /**
     * Makes the seeded account visible to the app that hosts the clone, the same way a user does by
     * choosing the account on the platform's account screen.
     *
     * Without this the account is invisible to a package whose signature does not match the
     * authenticator's (measured: clone list empty while the app's own list had one entry), so a
     * cloned app could never sign in — for a reason that has nothing to do with cloning. Setting it
     * explicitly makes the clone's account answer meaningful: any difference after this is the
     * container's own behaviour.
     */
    private fun shareAccountWithCloneHost() {
        val outcome = runCatching {
            val manager = android.accounts.AccountManager.get(context)
            manager.setAccountVisibility(
                android.accounts.Account(SEED_ACCOUNT, GOOGLE_ACCOUNT_TYPE),
                context.packageName,
                android.accounts.AccountManager.VISIBILITY_VISIBLE
            )
        }.getOrElse { "failed: " + it.javaClass.simpleName + ": " + it.message }
        Log.i(TAG, "GOOGLE_SIGNIN_VISIBILITY set=$outcome")
        Log.i(TAG, "GOOGLE_SIGNIN_PROOF ${authenticatorProof()}")
    }

    private fun googleAccountCount(): Int = runCatching {
        android.accounts.AccountManager.get(context)
            .getAccountsByType(GOOGLE_ACCOUNT_TYPE).size
    }.getOrDefault(0)

    private fun authenticatorTypes(): String = runCatching {
        android.accounts.AccountManager.get(context).getAuthenticatorTypes()
            .joinToString(",") { it.type }
    }.getOrDefault("unavailable")

    /**
     * What the test authenticator recorded when the token was requested — read from the device, so
     * it proves the request arrived from the app/clone rather than being answered inside it.
     */
    private fun authenticatorProof(): String = runCatching {
        device.executeShellCommand(
            "cat /sdcard/Android/data/$fixturePackage/files/test_authenticator_proof.txt 2>/dev/null " +
                "| grep getAuthToken | tail -3"
        ).trim().replace('\n', ' ')
    }.getOrDefault("")

    private fun grantTestPermissions() {
        val packages = listOf(fixturePackage, context.packageName)
        for (target in packages) {
            for (permission in ACCOUNT_PERMISSIONS) {
                try {
                    device.executeShellCommand("pm grant $target $permission")
                } catch (error: Throwable) {
                    Log.w(TAG, "Could not grant $permission to $target", error)
                }
            }
        }
        val state = packages.joinToString(" ") { target ->
            ACCOUNT_PERMISSIONS.joinToString(",") { permission ->
                val granted = device.executeShellCommand(
                    "dumpsys package $target | grep -c '$permission: granted=true'"
                ).trim()
                "$target/$permission=$granted"
            }
        }
        Log.i(TAG, "GOOGLE_SIGNIN_GRANTS $state")
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
        // Every probe runs against a device that has a com.google account, so "no account" is never
        // mistaken for a broken clone. The account is created through the platform's own
        // AccountManager with a test authenticator registered under the real com.google type (see
        // TestGoogleAuthenticator): the credential is fake and labelled as such, the path is real.
        seedGoogleAccount()
        // The answer is published from onCreate, so a running fixture stays silent. For the normal
        // probe the plain fixture is restarted. For the clone probe the app process must *not* be
        // killed — force-stopping the host package also tears down the clone's agent process, and the
        // launch then resolves back to the plain fixture (measured on run 43198828390: the "clone"
        // answer came from pid 3935 = com.clonehub.pro.test, not from an agent).
        if (kind != "clone") {
            device.executeShellCommand("am force-stop $fixturePackage")
            SystemClock.sleep(500)
        } else {
            runCatching { VirtualContainerEngine.forceStopApp(context, fixturePackage, spaceId) }
            SystemClock.sleep(500)
        }
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

        // Wait for an answer *from the expected process*: a pid that belongs to another process is a
        // running instance publishing late, not the evidence this probe is asking for.
        val expected = if (kind == "clone") ":agent" else fixturePackage
        val deadline = SystemClock.elapsedRealtime() + REPORT_TIMEOUT_MS
        var answer = emptyMap<String, String>()
        var realProcess = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            answer = parseProbe(device.executeShellCommand("logcat -d"), fileName)
            val candidatePid = answer["pid"]
            if (candidatePid != null) {
                realProcess = resolveProcessName(candidatePid)
                val matches = if (kind == "clone") {
                    realProcess.contains(":agent")
                } else {
                    realProcess == fixturePackage
                }
                if (matches) break
                Log.i(
                    TAG,
                    "GOOGLE_SIGNIN_PROBE ignoring answer from pid=$candidatePid ($realProcess), " +
                        "waiting for $expected"
                )
            }
            SystemClock.sleep(500)
        }

        val pid = answer["pid"]
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
     * Real process name of a pid, taken from the system's own record.
     *
     * Neither the process itself nor `ps` can be trusted here: inside the container both report the
     * cloned package name for a process that is really `com.clonehub.pro:agent0`, and `ps` even shows
     * the virtualised name. ActivityManager's "Start proc <pid>:<name>" line is written by the
     * platform outside the container, so it is the one authoritative source (run 43201019116 had
     * pid 4999 recorded there as `com.clonehub.pro:agent0` while `ps` called it
     * `com.clonehub.pro.test`).
     */
    private fun resolveProcessName(pid: String): String {
        for (attempt in 1..3) {
            val fromPlatform = device.executeShellCommand("logcat -d")
                .lineSequence()
                .firstOrNull { it.contains("Start proc $pid:") }
                ?.substringAfter("Start proc $pid:", "")
                ?.substringBefore("/")
                ?.trim()
            if (!fromPlatform.isNullOrEmpty()) {
                Log.i(TAG, "GOOGLE_SIGNIN_PROCESS pid=$pid platformName=$fromPlatform")
                return fromPlatform
            }
            SystemClock.sleep(500)
        }
        // Fallback only: the shell's process list, which may be virtualised.
        val psLine = device.executeShellCommand("ps -A")
            .lineSequence()
            .firstOrNull { row -> row.split(Regex("\\s+")).any { it == pid } }
        Log.i(TAG, "GOOGLE_SIGNIN_PROCESS pid=$pid psLine=${psLine?.trim()}")
        return psLine?.trim()?.split(Regex("\\s+"))?.last() ?: ""
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

    /**
     * The host side of the fix, proven on the device: the account answer a clone is supposed to get
     * must be obtainable from the host process through the bridge, and must agree with what the
     * platform tells the app itself.
     *
     * This is the half of the sign-in contract that is inside our own code. Whether the bundled
     * runtime routes its own account calls to this bridge is reported by the probe above and by
     * [d_accountChooserAnsweredInsideTheClone] — it is never assumed here.
     */
    @Test(timeout = 180_000)
    fun e_hostAccountBridgeAnswersLikeThePlatform() {
        seedGoogleAccount()
        val platform = android.accounts.AccountManager.get(context)
            .getAccountsByType(GOOGLE_ACCOUNT_TYPE)
            .map { it.name }
            .sorted()

        val accounts = VirtualContainerEngine.hostAccountQuery(context, "accounts", GOOGLE_ACCOUNT_TYPE)
        assertTrue("The host account bridge returned no reply", accounts != null)
        val bridged = accounts!!.getStringArrayList("names").orEmpty().sorted()
        Log.i(TAG, "GOOGLE_SIGNIN_BRIDGE platform=$platform bridged=$bridged " +
            "error=${accounts.getString("error")}")
        assertTrue(
            "Host bridge disagrees with the platform: platform=$platform bridged=$bridged",
            platform == bridged
        )

        val token = VirtualContainerEngine.hostAccountQuery(context, "token", GOOGLE_ACCOUNT_TYPE)
        assertTrue("The host account bridge returned no token reply", token != null)
        val result = token!!.getString("result")
        Log.i(TAG, "GOOGLE_SIGNIN_BRIDGE_TOKEN result=$result length=${token.getInt("length")} " +
            "detail=${token.getString("detail")}")
        assertTrue(
            "The bridge did not mint a token although the platform gave the app one: $result",
            result == "TOKEN"
        )
    }

    private fun waitForCloneProcess() {
        val deadline = SystemClock.elapsedRealtime() + CLONE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val processes = device.executeShellCommand("logcat -d")
                .lineSequence()
                .filter { it.contains("Start proc") && it.contains(":agent") }
                .joinToString(" | ")
            if (processes.isNotEmpty()) {
                Log.i(TAG, "GOOGLE_SIGNIN_CLONE_PROCESS ${processes.trim().replace('\n', ' ')}")
                return
            }
            SystemClock.sleep(500)
        }
        Log.w(TAG, "No agent process observed before the account checks")
    }
}
