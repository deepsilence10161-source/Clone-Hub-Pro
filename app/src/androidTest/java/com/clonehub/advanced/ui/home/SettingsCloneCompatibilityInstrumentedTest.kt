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
import org.junit.Test
import java.io.File

/** Reproduces a real system-app clone, including the former 20-second failure boundary. */
class SettingsCloneCompatibilityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test(timeout = 150_000)
    fun settingsClone_showsRealUiAndSurvivesContinuousUse() {
        val app = ClonedApp(
            packageName = SETTINGS_PACKAGE,
            appName = "Settings",
            originalName = "Settings",
            spaceId = SPACE_ID
        )
        val screenshot = File(context.getExternalFilesDir(null), "settings-clone.png")
        try {
            assertTrue(
                "Settings could not be installed and launched in its virtual space",
                VirtualContainerEngine.launchApp(context, app, SPACE_ID)
            )
            assertTrue(
                "The real Settings UI was not visible after cloning",
                device.wait(Until.hasObject(By.text("Settings")), LAUNCH_TIMEOUT_MS)
            )
            Log.i(TAG, "Settings clone visible; foreground=${device.currentPackageName}")

            // The native runtime previously terminated or broke Binder hooks here.
            SystemClock.sleep(30_000)
            assertTrue(
                "Settings clone did not survive continuous use",
                device.wait(Until.hasObject(By.text("Settings")), 5_000)
            )
            runCatching { device.takeScreenshot(screenshot) }
        } finally {
            runCatching { device.takeScreenshot(screenshot) }
            VirtualContainerEngine.forceStopApp(context, SETTINGS_PACKAGE, SPACE_ID)
            context.startActivity(Intent(context, HomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
        }
    }

    private companion object {
        const val TAG = "SettingsCloneE2E"
        const val SETTINGS_PACKAGE = "com.android.settings"
        const val SPACE_ID = 80
        const val LAUNCH_TIMEOUT_MS = 45_000L
    }
}
