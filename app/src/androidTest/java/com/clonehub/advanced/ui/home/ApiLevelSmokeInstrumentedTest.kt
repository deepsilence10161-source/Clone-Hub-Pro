package com.clonehub.advanced.ui.home

import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.clonehub.advanced.core.container.SpaceManager
import com.clonehub.advanced.core.container.VirtualContainerEngine
import com.clonehub.advanced.data.model.ClonedApp
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Per-Android-level smoke gate.
 *
 * The strict and deep gates all run the same Android 14 image, so minSdk 24 support was claimed but
 * never proven. This suite runs on every image of the API matrix and asserts only what must hold on
 * every supported level: the app installs and renders, a clone can be added, the virtual runtime
 * accepts it, and the clone's own process becomes visible.
 *
 * Anything platform-specific that cannot be asserted yet (older binder/ashmem behaviour, scoped
 * storage, background-start limits) is logged as a measurement line so a regression is visible
 * instead of being silently assumed.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ApiLevelSmokeInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val api = Build.VERSION.SDK_INT

    @Test(timeout = 180_000)
    fun a_homeScreenRendersOnThisAndroidLevel() {
        // In the mixed suite this runs after 20+ tests on a loaded emulator, where a single cold
        // start occasionally exceeds the wait. Retry the start and keep evidence instead of
        // reporting a phantom "did not render" (runs 43151607784 / 43152454751 both failed here).
        var rendered = false
        repeat(3) { attempt ->
            device.wakeUp()
            context.startActivity(Intent(context, HomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            rendered = device.wait(
                Until.hasObject(By.res(context.packageName, "fabAdd")), UI_TIMEOUT_MS
            )
            if (rendered) return@repeat
            Log.w(TAG, "API_SMOKE level=$api home attempt ${attempt + 1} not rendered yet")
            device.waitForIdle()
        }
        capture("api-$api-home.png")
        if (!rendered) {
            runCatching {
                device.dumpWindowHierarchy(File(evidenceDir(), "api-$api-home-failure.xml"))
            }
            Log.e(TAG, "API_SMOKE level=$api homeFailed top=${device.currentActivityName}")
        }
        assertTrue(
            "Home screen did not render on API $api (evidence: api-$api-home-failure.xml)",
            rendered
        )
        Log.i(TAG, "API_SMOKE level=$api homeRendered=true release=${Build.VERSION.RELEASE}")
    }

    @Test(timeout = 120_000)
    fun b_cloneCanBeAddedThroughTheVisibleUi() {
        val appId = context.packageName
        // Instrumentation finishes each test's activities, so this test must bring up the home
        // screen itself instead of relying on the previous test having left it on screen.
        device.wakeUp()
        context.startActivity(Intent(context, HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        // A single injected tap can be swallowed while the home screen is still settling (measured
        // on the API 35 gate: FAB found, tap sent, no picker). Retry with a fresh tap on the node's
        // own bounds and keep a hierarchy dump when it still refuses, so the next run explains
        // itself instead of only failing.
        var pickerOpen = false
        repeat(3) { attempt ->
            val addButton = device.wait(Until.findObject(By.res(appId, "fabAdd")), UI_TIMEOUT_MS)
            checkNotNull(addButton) { "Home Add action never appeared on API $api" }
            // A control hidden under the navigation bar cannot be tapped by a user. Assert the
            // geometry before injecting the tap: this is what caught the Android 15 edge-to-edge
            // regression, where the FAB sat at y=2211..2358 under a bar starting at 2274.
            val bar = device.findObject(
                By.res("com.android.systemui", "navigation_bar_frame")
            ) ?: device.findObject(By.res("com.android.systemui", "navigationBarBackground"))
            val fabBottom = addButton.visibleBounds.bottom
            val barTop = bar?.visibleBounds?.top
            Log.i(
                TAG,
                "API_SMOKE level=$api fab=${addButton.visibleBounds} navBarTop=$barTop attempt=${attempt + 1}"
            )
            if (barTop != null) {
                assertTrue(
                    "Add-app button is covered by the system navigation bar on API $api " +
                        "(button bottom=$fabBottom, nav bar top=$barTop)",
                    fabBottom <= barTop
                )
            }
            addButton.click()
            pickerOpen = device.wait(Until.hasObject(By.res(appId, "searchView")), PICKER_TIMEOUT_MS)
            if (!pickerOpen && attempt < 2) {
                Log.w(TAG, "API_SMOKE level=$api picker attempt ${attempt + 1} did not open; retrying")
                device.waitForIdle()
            }
            if (pickerOpen) return@repeat
        }
        capture("api-$api-picker.png")
        if (!pickerOpen) {
            runCatching {
                device.dumpWindowHierarchy(File(evidenceDir(), "api-$api-picker-failure.xml"))
            }
            Log.e(
                TAG,
                "API_SMOKE level=$api pickerFailed top=${device.currentActivityName}"
            )
        }
        assertTrue("App picker did not open on API $api (evidence: api-$api-picker-failure.xml)", pickerOpen)
        val search = device.wait(Until.findObject(By.res(appId, "search_src_text")), UI_TIMEOUT_MS)
        assertNotNull("Picker search field missing on API $api", search)
        search!!.text = FIXTURE_PACKAGE
        instrumentation.waitForIdleSync()
        val listed = device.wait(Until.hasObject(By.text(FIXTURE_PACKAGE)), UI_TIMEOUT_MS)
        val installButtons = device.findObjects(By.res(appId, "btnClone"))
        capture("api-$api-picker-filtered.png")
        assertTrue("Fixture app was not listed by the picker on API $api", listed)
        assertTrue(
            "Filtered picker must expose exactly one Install action on API $api (found ${installButtons.size})",
            installButtons.size == 1
        )
        installButtons.single().click()
        instrumentation.waitForIdleSync()
        Log.i(TAG, "API_SMOKE level=$api cloneAddedThroughUi=true")
    }

    @Test(timeout = 180_000)
    fun c_virtualRuntimeLaunchesTheCloneOnThisAndroidLevel() {
        val spaceManager = SpaceManager.getInstance(context)
        val app = ClonedApp(
            packageName = FIXTURE_PACKAGE,
            appName = FIXTURE_LABEL,
            originalName = FIXTURE_LABEL,
            spaceId = SPACE_ID
        )
        spaceManager.addAppToSpace(SPACE_ID, FIXTURE_PACKAGE, FIXTURE_LABEL)
        val outcome = VirtualContainerEngine.launchAppOutcome(context, app, SPACE_ID)
        val visible = device.wait(Until.hasObject(By.text(MARKER)), LAUNCH_TIMEOUT_MS)
        capture("api-$api-cloned.png")
        Log.i(
            TAG,
            "API_SMOKE level=$api space=$SPACE_ID outcome=${outcome.describe()} fixtureVisible=$visible " +
                "processes=${device.executeShellCommand("ps -A | grep -c clonehub.pro").trim()}"
        )
        assertTrue(
            "Runtime did not confirm the clone launch on API $api (${outcome.describe()})",
            outcome.isSuccess
        )
        assertTrue("Clone did not render its own screen on API $api", visible)
        VirtualContainerEngine.forceStopApp(context, FIXTURE_PACKAGE, SPACE_ID)
    }

    private fun evidenceDir(): File =
        File(context.getExternalFilesDir(null), "api-matrix").apply { mkdirs() }

    private fun capture(name: String) {
        runCatching { device.takeScreenshot(File(evidenceDir(), name)) }
    }

    private companion object {
        const val TAG = "ApiLevelSmoke"
        const val FIXTURE_PACKAGE = "com.clonehub.pro.test"
        const val FIXTURE_LABEL = "Clone Hub Fixture"
        const val MARKER = "CLONE_FIXTURE_RUNNING"
        const val SPACE_ID = 121
        const val UI_TIMEOUT_MS = 20_000L
        const val LAUNCH_TIMEOUT_MS = 60_000L
        const val PICKER_TIMEOUT_MS = 8_000L
    }
}
