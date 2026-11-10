package com.clonehub.advanced.ui.home

import android.content.Intent
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.clonehub.advanced.core.container.SpaceManager
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drives the installed app exactly through its visible UI. Success requires the
 * separate fixture app's own screen, not merely an install or launch result code.
 */
class RealUserCloneFlowInstrumentedTest {

    @Test(timeout = 180_000)
    fun addCloneAndLaunch_thirdPartyAppShowsItsRealScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val appId = context.packageName
        val device = UiDevice.getInstance(instrumentation)
        val screenshotDir = File(context.getExternalFilesDir(null), "real-user-flow").apply {
            mkdirs()
        }

        device.wakeUp()
        context.startActivity(Intent(context, HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue("Home Add action was not visible", device.wait(
            Until.hasObject(By.res(appId, "fabAdd")), UI_TIMEOUT_MS
        ))
        capture(device, screenshotDir, "01-home.png")
        device.findObject(By.res(appId, "fabAdd")).click()

        assertTrue("App picker did not open", device.wait(
            Until.hasObject(By.res(appId, "searchView")), UI_TIMEOUT_MS
        ))
        capture(device, screenshotDir, "02-picker.png")
        val searchInput = device.wait(
            Until.findObject(By.res(appId, "search_src_text")), UI_TIMEOUT_MS
        )
        check(searchInput != null) { "Picker search input was not available" }
        searchInput.text = FIXTURE_PACKAGE
        instrumentation.waitForIdleSync()

        assertTrue("Separate user fixture was not listed by the picker", device.wait(
            Until.hasObject(By.text(FIXTURE_PACKAGE)), UI_TIMEOUT_MS
        ))
        capture(device, screenshotDir, "02-picker-filtered.png")
        val cloneButtons = device.findObjects(By.res(appId, "btnClone"))
        assertTrue("Filtered picker must expose exactly one Install action", cloneButtons.size == 1)
        cloneButtons.single().click()

        assertTrue("Clone did not return a visible fixture entry to Home", device.wait(
            Until.hasObject(By.res(appId, "tvAppTitle").text(FIXTURE_LABEL)), INSTALL_TIMEOUT_MS
        ))
        val fixturePersisted = SpaceManager.getInstance(context).getSpaces().any { space ->
            space.apps.any { it.packageName == FIXTURE_PACKAGE }
        }
        assertTrue("Cloned fixture was not persisted in a virtual space", fixturePersisted)
        capture(device, screenshotDir, "03-clone-added.png")

        device.findObject(By.res(appId, "tvAppTitle").text(FIXTURE_LABEL)).click()
        val realFixtureScreen = device.wait(
            Until.hasObject(By.text("CLONE_FIXTURE_RUNNING")), LAUNCH_TIMEOUT_MS
        )
        capture(device, screenshotDir, "04-after-launch.png")
        Log.i(TAG, "After clone tap, foreground package=${device.currentPackageName}")
        assertTrue(
            "The separate cloned app did not display its real screen; foreground=" +
                device.currentPackageName,
            realFixtureScreen
        )
    }

    private fun capture(device: UiDevice, directory: File, name: String) {
        runCatching { device.takeScreenshot(File(directory, name)) }
            .onFailure { Log.w(TAG, "Could not capture $name", it) }
    }

    private companion object {
        const val TAG = "RealUserCloneFlowE2E"
        const val FIXTURE_PACKAGE = "com.clonehub.advanced.debug.test"
        const val FIXTURE_LABEL = "Clone Hub Fixture"
        const val UI_TIMEOUT_MS = 20_000L
        const val INSTALL_TIMEOUT_MS = 90_000L
        const val LAUNCH_TIMEOUT_MS = 60_000L
    }
}
