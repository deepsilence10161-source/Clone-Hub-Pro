package com.clonehub.advanced.ui.home

import android.content.Intent
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

/** Compares each real app's normal visible UI with its cloned visible UI. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ThirdPartyCloneCompatibilityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val evidenceDir = File(context.getExternalFilesDir(null), "compatibility").apply {
        mkdirs()
    }

    private companion object {
        const val TAG = "CompatibilityVisual"
        const val BASELINE_TIMEOUT_MS = 45_000L
        const val CLONE_TIMEOUT_MS = 60_000L
        val IGNORED_MARKERS = setOf(
            "Allow", "Deny", "Don't allow", "Not now", "Cancel", "OK", "Next",
            "Skip", "Continue", "Settings", "Clone Hub Pro"
        )
    }

    @Test(timeout = 210_000)
    fun a_markor_realUiWorksWhenCloned() {
        verifyRealAppClone("net.gsantner.markor", "Markor", 81)
    }

    @Test(timeout = 210_000)
    fun b_simpleCalculator_realUiWorksWhenCloned() {
        verifyRealAppClone("com.simplemobiletools.calculator", "Calculator", 82)
    }

    @Test(timeout = 210_000)
    fun c_aegis_realUiWorksWhenCloned() {
        verifyRealAppClone("com.beemdevelopment.aegis", "Aegis", 83)
    }

    private fun verifyRealAppClone(packageName: String, label: String, spaceId: Int) {
        val slug = packageName.substringAfterLast('.')
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            checkNotNull(launchIntent) { "$label has no launchable activity" }
            context.startActivity(launchIntent.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            val markers = awaitOriginalMarkers(packageName)
            assertTrue(
                "$label did not expose a real baseline UI before cloning",
                markers.isNotEmpty()
            )
            capture("${slug}-01-original.png")

            device.executeShellCommand("am force-stop $packageName")
            showHome()
            val app = ClonedApp(packageName, label, label, spaceId)
            assertTrue(
                "$label failed its virtual install or launch transaction",
                VirtualContainerEngine.launchApp(context, app, spaceId)
            )
            assertTrue(
                "$label did not reproduce its original visible UI after cloning",
                awaitClonedMarkers(markers)
            )
            val clonedShot = capture("${slug}-02-cloned.png")
            val originalShot = File(evidenceDir, "${slug}-01-original.png")
            val originalStats = VisualEvidence.analyse(originalShot)
            val clonedStats = clonedShot?.let { VisualEvidence.analyse(it) }
            Log.i(
                TAG,
                "COMPAT_VISUAL app=$slug original=${originalStats?.describe() ?: "unreadable"} " +
                    "cloned=${clonedStats?.describe() ?: "unreadable"} " +
                    "frameDiff=${VisualEvidence.frameDifference(originalShot, clonedShot).orZero()}"
            )
            // The original screen is the reference: if the real app painted something, the clone
            // must paint something too. A blank clone frame is the classic clone failure and is
            // now a hard assertion instead of an archived screenshot nobody looked at.
            if (originalStats != null && !originalStats.isBlank) {
                assertTrue(
                    "$label cloned window rendered a blank frame (" +
                        "${clonedStats?.describe() ?: "screenshot unreadable"}) while the original " +
                        "app rendered content",
                    clonedStats != null && !clonedStats.isBlank
                )
            } else {
                Log.w(
                    TAG,
                    "COMPAT_VISUAL_SKIPPED app=$slug reference frame is blank on this emulator; " +
                        "blankness cannot be judged from it"
                )
            }

            SystemClock.sleep(30_000)
            dismissPermissionPrompt()
            assertTrue(
                "$label cloned UI disappeared during continuous use",
                visibleMarkers(markers).isNotEmpty() && !homeIsVisible()
            )
            capture("${slug}-03-stable.png")
        } catch (error: Throwable) {
            capture("${slug}-99-failure.png")
            runCatching {
                device.dumpWindowHierarchy(File(evidenceDir, "${slug}-failure.xml"))
            }
            throw error
        } finally {
            capture("${slug}-04-final.png")
            VirtualContainerEngine.forceStopApp(context, packageName, spaceId)
            device.executeShellCommand("am force-stop $packageName")
            showHome()
        }
    }

    private fun awaitOriginalMarkers(packageName: String): Set<String> {
        val deadline = SystemClock.elapsedRealtime() + BASELINE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            dismissPermissionPrompt()
            val markers = device.findObjects(By.pkg(packageName)).flatMap { node ->
                listOfNotNull(node.text, node.contentDescription)
            }.map { it.trim() }.filter { marker ->
                marker.length >= 2 && marker.length <= 80 && marker !in IGNORED_MARKERS
            }.toSet()
            if (markers.isNotEmpty()) return markers
            SystemClock.sleep(500)
        }
        return emptySet()
    }

    private fun awaitClonedMarkers(markers: Set<String>): Boolean {
        val deadline = SystemClock.elapsedRealtime() + CLONE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            dismissPermissionPrompt()
            if (visibleMarkers(markers).isNotEmpty() && !homeIsVisible()) return true
            SystemClock.sleep(500)
        }
        return false
    }

    private fun visibleMarkers(markers: Set<String>): Set<String> = markers.filterTo(
        linkedSetOf()
    ) { marker ->
        device.hasObject(By.text(marker)) || device.hasObject(By.desc(marker))
    }

    private fun dismissPermissionPrompt() {
        listOf("Don't allow", "Deny", "Not now").forEach { label ->
            runCatching {
                if (device.hasObject(By.text(label))) {
                    device.findObject(By.text(label)).click()
                }
            }
        }
    }

    private fun homeIsVisible(): Boolean =
        device.hasObject(By.res(context.packageName, "fabAdd"))

    private fun showHome() {
        context.startActivity(Intent(context, HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        device.wait(Until.hasObject(By.res(context.packageName, "fabAdd")), 10_000)
    }

    private fun capture(name: String): File? = runCatching {
        File(evidenceDir, name).also { device.takeScreenshot(it) }
    }.getOrNull()

    private fun Double?.orZero() = this?.let { "%.1f".format(it) } ?: "unknown"

}
