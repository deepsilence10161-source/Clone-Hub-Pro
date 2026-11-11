package com.clonehub.advanced.ui.home

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.clonehub.advanced.core.container.SpaceManager
import com.clonehub.advanced.core.container.VirtualContainerEngine
import com.clonehub.advanced.data.model.ClonedApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/** Native-runtime stress, data-isolation, and cold-restart verification. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DeepCloneFlowInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test(timeout = 120_000)
    fun a_preparePersistentCloneAndFirstLaunch() {
        SpaceManager.getInstance(context).addAppToSpace(
            COLD_SPACE, FIXTURE_PACKAGE, FIXTURE_LABEL
        )
        launchAndAssert(COLD_SPACE, 1)
        expectedColdLaunches().edit().putInt(KEY_EXPECTED_LAUNCHES, 1).commit()
        returnHome()
    }

    @Test(timeout = 240_000)
    fun b_repeatedForceStopAndRelaunch_staysStable() {
        repeat(5) { index ->
            launchAndAssert(REPEAT_SPACE, index + 1)
            returnHome()
        }
    }

    @Test(timeout = 240_000)
    fun c_twoSpaces_haveIndependentPersistentData() {
        launchAndAssert(SPACE_A, 1)
        returnHome()
        launchAndAssert(SPACE_B, 1)
        returnHome()
        launchAndAssert(SPACE_A, 2)
        returnHome()
        launchAndAssert(SPACE_B, 2)
        returnHome()
    }

    @Test(timeout = 120_000)
    fun d_runningClone_hasStableProcessUnderContinuousUse() {
        val before = launchAndAssert(STABILITY_SPACE, 1)
        assertNotNull("Fixture must expose its cloned process PID", before)
        SystemClock.sleep(10_000)
        android.util.Log.i("DeepCloneNative", nativeProcessSnapshot(before))
        SystemClock.sleep(10_000)
        assertTrue(
            "Fixture disappeared during continuous use",
            device.wait(Until.hasObject(By.text(MARKER)), 5_000)
        )
        val after = fixtureDetails()
        assertEquals("Cloned process restarted without a requested force-stop", before, after)
        returnHome()
    }

    @Test(timeout = 120_000)
    fun z_coldRestart_preservesModelAndFixtureData() {
        val persisted = SpaceManager.getInstance(context).getSpace(COLD_SPACE)?.apps
            ?.any { it.packageName == FIXTURE_PACKAGE } == true
        assertTrue("Cloned fixture was not persisted in SpaceManager", persisted)
        val expected = expectedColdLaunches().getInt(KEY_EXPECTED_LAUNCHES, 0) + 1
        assertTrue("Cold-launch expectation was not prepared", expected >= 2)
        launchAndAssert(COLD_SPACE, expected)
        expectedColdLaunches().edit().putInt(KEY_EXPECTED_LAUNCHES, expected).commit()
        returnHome()
    }

    private fun launchAndAssert(spaceId: Int, expectedLaunchCount: Int): String {
        val app = ClonedApp(
            packageName = FIXTURE_PACKAGE,
            appName = FIXTURE_LABEL,
            originalName = FIXTURE_LABEL,
            spaceId = spaceId
        )
        assertTrue(
            "Runtime launch failed for space=$spaceId",
            VirtualContainerEngine.launchApp(context, app, spaceId)
        )
        assertTrue(
            "Real fixture screen was not visible for space=$spaceId",
            device.wait(Until.hasObject(By.text(MARKER)), LAUNCH_TIMEOUT_MS)
        )
        assertTrue(
            "Wrong fixture launch count for space=$spaceId; expected=$expectedLaunchCount",
            device.wait(
                Until.hasObject(By.textContains("launchCount=$expectedLaunchCount")),
                DATA_TIMEOUT_MS
            )
        )
        return fixtureDetails()
    }

    private fun fixtureDetails(): String {
        val details = device.wait(
            Until.findObject(By.textContains("pid=")), DATA_TIMEOUT_MS
        )
        checkNotNull(details) { "Fixture process details were not visible" }
        return details.text.substringAfter("pid=", "").substringBefore(" ")
            .ifBlank { error("Fixture PID was blank") }
    }

    private fun nativeProcessSnapshot(pid: String): String = runCatching {
        device.executeShellCommand(
            "echo PID=$pid; grep -E 'libwnublp|libclonehub' /proc/$pid/maps; " +
                "for task in /proc/$pid/task/*; do echo TASK=${'$'}task; " +
                "cat ${'$'}task/comm ${'$'}task/wchan; done"
        )
    }.getOrElse { "Native process snapshot failed: $it" }

    private fun returnHome() {
        VirtualContainerEngine.forceStopApp(context, FIXTURE_PACKAGE, CURRENT_SPACE)
        // Force-stop every space used by the deep suite; the runtime transaction is
        // space-scoped, so do not assume stopping one space stops another.
        intArrayOf(REPEAT_SPACE, SPACE_A, SPACE_B, STABILITY_SPACE, COLD_SPACE).forEach {
            VirtualContainerEngine.forceStopApp(context, FIXTURE_PACKAGE, it)
        }
        context.startActivity(Intent(context, HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(
            "Clone Hub Home did not recover after force-stop",
            device.wait(Until.hasObject(By.res(context.packageName, "fabAdd")), UI_TIMEOUT_MS)
        )
    }

    private fun expectedColdLaunches() =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val FIXTURE_PACKAGE = "com.clonehub.advanced.debug.test"
        const val FIXTURE_LABEL = "Clone Hub Fixture"
        const val MARKER = "CLONE_FIXTURE_RUNNING"
        const val PREFS_NAME = "deep_clone_test_state"
        const val KEY_EXPECTED_LAUNCHES = "expected_cold_launches"
        const val CURRENT_SPACE = 1
        const val REPEAT_SPACE = 70
        const val SPACE_A = 71
        const val SPACE_B = 72
        const val STABILITY_SPACE = 73
        const val COLD_SPACE = 74
        const val UI_TIMEOUT_MS = 20_000L
        const val LAUNCH_TIMEOUT_MS = 45_000L
        const val DATA_TIMEOUT_MS = 20_000L
    }
}
