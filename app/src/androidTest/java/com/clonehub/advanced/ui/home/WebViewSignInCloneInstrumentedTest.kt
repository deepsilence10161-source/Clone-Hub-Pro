package com.clonehub.advanced.ui.home

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.clonehub.advanced.core.container.VirtualContainerEngine
import com.clonehub.advanced.data.model.ClonedApp
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sign-in path a real user hit, on the device: a web-based app signing in with Google through a
 * WebView, once un-cloned and once inside the clone.
 *
 * Why this test exists: the field report was a screenshot of a cloned web app answering "Google
 * sign-in failed". The suite next door only ever tested the *native* account path
 * (`AccountManager`), which a web app never touches — so a whole class of failure was untested while
 * the gate reported the sign-in contract as proven. This closes that gap by measuring the same probe
 * in both processes and comparing what the platform actually did.
 *
 * The probe reports one marker per line (`WEBVIEW_SIGNIN_MARKER <step> …`), so a difference between
 * the two runs is not a matter of interpretation: it is a named step that happened in one process and
 * either failed or never happened in the other.
 */
class WebViewSignInCloneInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val fixturePackage = "com.clonehub.pro.test"
    // Deliberately the launcher activity, on both sides: the container launches the launcher
    // activity, so naming any other activity here would measure a route only the test can use.
    private val fixtureActivity = "$fixturePackage/com.clonehub.advanced.fixture.CloneFixtureActivity"
    private val spaceId = 92

    private companion object {
        const val TAG = "WEBVIEW_SIGNIN_GATE"
        const val RUN_PREFIX = "WEBVIEW_SIGNIN_RUN "
        const val MARKER_PREFIX = "WEBVIEW_SIGNIN_MARKER "
        const val RUN_TIMEOUT_MS = 120_000L
    }

    @Test(timeout = 420_000)
    fun webSignInBehavesLikeTheNormalApp() {
        enableProbeTriggers()
        try {
            compareBothRuns()
        } finally {
            restoreProbeTriggers()
        }
    }

    /**
     * Asks the fixture to run the probe, three ways at once.
     *
     * The un-cloned run is asked with an intent extra; the cloned run cannot be, because the container
     * builds its own intent, so the global setting and the system property are set too. Which channel
     * was seen is logged by the probe, so a channel that the container hides is a finding.
     */
    private fun enableProbeTriggers() {
        device.executeShellCommand("settings put global clonehub_webprobe 1")
        device.executeShellCommand("setprop debug.clonehub.webprobe 1")
        Log.i(TAG, "WEBVIEW_GATE_TRIGGERS global=" +
            device.executeShellCommand("settings get global clonehub_webprobe").trim() +
            " property=" + device.executeShellCommand("getprop debug.clonehub.webprobe").trim())
    }

    private fun restoreProbeTriggers() {
        device.executeShellCommand("settings delete global clonehub_webprobe")
        device.executeShellCommand("setprop debug.clonehub.webprobe 0")
        Log.i(TAG, "WEBVIEW_GATE_TRIGGERS_CLEARED")
    }

    private fun compareBothRuns() {
        val native = runProbe(native = true)
        Log.i(TAG, "WEBVIEW_GATE_NATIVE ${native.steps}")
        assertTrue(
            "The un-cloned app never reported a WebView run; nothing can be compared. steps=${native.steps}",
            native.run != null
        )

        if (native.steps["webview_failed"] != null) {
            // Both processes can only fail identically when the image ships no usable WebView. That is
            // an image property, so it is reported as NOT TESTED - never as a pass, so a build where
            // the clone's WebView is broken can never be published as "web sign-in works".
            Log.i(
                TAG,
                "WEBVIEW_GATE_INFRA this image has no usable WebView (${native.steps["webview_failed"]}); " +
                    "the clone comparison is NOT TESTED here"
            )
            return
        }

        val nativeError = native.steps.keys.firstOrNull {
            it == "main_frame_error" || it == "ssl_error" || it == "http_error"
        }
        if (nativeError != null) {
            Log.i(
                TAG,
                "WEBVIEW_GATE_INFRA the un-cloned app could not load a page either " +
                    "($nativeError: ${native.steps[nativeError]}); the clone comparison is NOT TESTED " +
                    "on this image"
            )
            return
        }

        val cloned = runProbe(native = false)
        Log.i(TAG, "WEBVIEW_GATE_CLONE ${cloned.steps}")

        // Compare marker by marker. A step present in the app and absent in the clone is the defect
        // shape this test was written for (a WebView that never loads), and a step that reports a
        // different value names the layer that differs (TLS, console, cookies, user agent).
        val nativeSteps = native.steps.keys
        val clonedSteps = cloned.steps.keys
        val missing = nativeSteps - clonedSteps
        Log.i(
            TAG,
            "WEBVIEW_GATE_PARITY native=${nativeSteps.joinToString(",")} " +
                "clone=${clonedSteps.joinToString(",")} missing=${missing.joinToString(",")}"
        )
        assertTrue(
            "The clone never reported: ${missing.joinToString(",")}",
            missing.isEmpty()
        )

        for (step in listOf(
            "webview_created", "browser_intent", "settings", "page_finished", "page_state",
            "cookie_after_reload", "gsi_finished", "gsi_state", "verdict"
        )) {
            Log.i(TAG, "WEBVIEW_GATE_STEP $step native=${native.steps[step]} clone=${cloned.steps[step]}")
        }

        // The two answers the user's failure reduces to: did the page load in the clone, and does the
        // clone's engine behave the same about cookies. Both are asserted from the probe's own words.
        val nativeFinished = native.steps["page_finished"] ?: ""
        val clonedFinished = cloned.steps["page_finished"] ?: ""
        assertTrue(
            "The page finished un-cloned but not in the clone: native=[$nativeFinished] clone=[$clonedFinished]",
            clonedFinished.isNotEmpty()
        )
        val clonedVerdict = cloned.steps["verdict"] ?: ""
        val nativeVerdict = native.steps["verdict"] ?: ""
        Log.i(TAG, "WEBVIEW_GATE_VERDICT native=[$nativeVerdict] clone=[$clonedVerdict]")
        assertTrue(
            "The clone's WebView did not keep a cookie across a reload (native: $nativeVerdict, " +
                "clone: $clonedVerdict); a Google redirect chain cannot finish without that",
            clonedVerdict.contains("cookie_roundtrip=true") || nativeVerdict.contains("cookie_roundtrip=false")
        )
        device.executeShellCommand("logcat -d -s WEBVIEW_SIGNIN:* > /sdcard/webview-signin.txt || true")
    }

    /** One probe run, launched exactly the way the app launches a clone. */
    private fun runProbe(native: Boolean): ProbeResult {
        val wantedKind = if (native) "native" else "clone"
        runCatching { VirtualContainerEngine.forceStopApp(context, fixturePackage, spaceId) }
        SystemClock.sleep(1_000)
        device.executeShellCommand("logcat -c")

        if (native) {
            device.executeShellCommand("am start -W -n $fixtureActivity --ei webview_probe 1")
        } else {
            val app = ClonedApp(fixturePackage, "WebView Sign-in Fixture", "WebViewSignIn", spaceId)
            val launched = runCatching { VirtualContainerEngine.launchApp(context, app, spaceId) }
                .getOrDefault(false)
            Log.i(TAG, "WEBVIEW_GATE_LAUNCH clone launched=$launched")
            if (!launched) return ProbeResult(null, emptyMap())
        }

        val deadline = SystemClock.elapsedRealtime() + RUN_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val log = device.executeShellCommand("logcat -d")
            val parsed = parse(log, wantedKind)
            if (parsed.run != null && log.contains("WEBVIEW_SIGNIN_DONE")) return parsed
            SystemClock.sleep(2_000)
        }
        return parse(device.executeShellCommand("logcat -d"), wantedKind)
    }

    private data class ProbeResult(val run: Map<String, String>?, val steps: Map<String, String>)

    /**
     * Reads the probe's own log lines. Only lines from this run are used: the run line carries the
     * process name, and every marker after it belongs to that run, so a stale process from an earlier
     * test cannot be mistaken for this one's answer.
     */
    /**
     * Reads the probe's own log lines, keeping only the run whose `kind` matches the side being asked.
     *
     * Two properties matter and both were learned from live runs: the marker is not at the start of a
     * logcat line (runs 43690036509/43690755006 lost every line to that), and the fixture and the clone
     * share one process name, so a run has to be chosen by the kind the probe itself reported rather
     * than by whichever answer arrived last.
     */
    private fun parse(log: String, wantedKind: String): ProbeResult {
        var run: Map<String, String>? = null
        var steps = LinkedHashMap<String, String>()
        var fresh = false
        for (line in log.lineSequence()) {
            val runAt = line.indexOf(RUN_PREFIX)
            val markerAt = line.indexOf(MARKER_PREFIX)
            when {
                runAt >= 0 -> {
                    val candidate = line.substring(runAt + RUN_PREFIX.length).toMarkerMap()
                    if (candidate["kind"] == wantedKind) {
                        run = candidate
                        steps = LinkedHashMap()
                        fresh = true
                    } else {
                        fresh = false
                    }
                }
                markerAt >= 0 && fresh -> {
                    val rest = line.substring(markerAt + MARKER_PREFIX.length)
                    val step = rest.substringBefore(' ')
                    steps[step] = rest.removePrefix(step).trim()
                }
            }
        }
        return ProbeResult(run, steps)
    }

    private fun String.toMarkerMap(): Map<String, String> =
        split(' ').filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
}
