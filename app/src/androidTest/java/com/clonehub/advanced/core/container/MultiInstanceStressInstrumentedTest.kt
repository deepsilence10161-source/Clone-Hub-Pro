package com.clonehub.advanced.core.container

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.clonehub.advanced.data.model.ClonedApp
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-instance measurement: launches 1, 2, 5 and 10 clones in separate spaces and records what
 * actually happens — how many reach a verified agent process, how many stay alive for 15 s, and the
 * memory each one costs.
 *
 * This test deliberately does **not** assert a marketing number. It asserts only that a single
 * clone works; everything beyond that is logged as measurement and published, so the product states
 * the limit the device really has instead of promising "unlimited".
 */
class MultiInstanceStressInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext<Context>()
    private val spaceManager = SpaceManager.getInstance(context)

    @Test(timeout = 420_000)
    fun measureConcurrentClones() {
        val steps = listOf(1, 2, 5, 10)
        steps.forEach { count ->
            val outcome = runInstanceStep(count)
            android.util.Log.i(TAG, outcome)
        }
        android.util.Log.i(
            TAG,
            "MULTI_INSTANCE_SUMMARY maxAlive=$maxAliveObserved counts=$observedAlive"
        )
        // Measured behaviour, encoded so it cannot drift silently:
        // * every requested launch is accepted and verified at that moment;
        // * the runtime keeps only a small number of clone processes alive afterwards (observed
        //   1 at 1/2/5 launches and 2 at a 10-launch burst) — it reaps the rest.
        // A device that starts keeping many more alive means the documented capacity wording has
        // to change, so the test fails instead of letting the docs go stale.
        assertTrue("A single clone could not be verified", singleCloneVerified())
        assertTrue(
            "Runtime kept more than $MAX_DOCUMENTED_ALIVE clone processes alive " +
                "(observed=$observedAlive); update the documented capacity before shipping",
            maxAliveObserved <= MAX_DOCUMENTED_ALIVE
        )
        assertTrue(
            "Not every requested clone could be launched",
            launchesAccepted == requestedLaunches
        )
    }

    private fun runInstanceStep(count: Int): String {
        stopAll()
        val spaces = (0 until count).map { BASE_SPACE + it }
        var launched = 0
        spaces.forEach { spaceId ->
            spaceManager.addAppToSpace(spaceId, FIXTURE_PACKAGE, "Fixture $spaceId")
            val app = ClonedApp(
                packageName = FIXTURE_PACKAGE,
                appName = "Fixture $spaceId",
                originalName = FIXTURE_PACKAGE,
                spaceId = spaceId
            )
            requestedLaunches++
            val result = VirtualContainerEngine.launchAppOutcome(context, app, spaceId)
            if (result.isSuccess) {
                launched++
                launchesAccepted++
            } else {
                android.util.Log.w(TAG, "space=$spaceId ${result.describe()}")
            }
        }
        // Let every agent process settle, then measure again: a clone that dies immediately would
        // otherwise be counted as a success.
        SystemClock.sleep(SETTLE_MS)
        val alive = agentProcesses().size
        val memoryKb = totalPssKb()
        val averageKb = if (alive == 0) 0 else memoryKb / alive
        stopAll()
        lastSingleVerified = if (count == 1) alive >= 1 else lastSingleVerified
        maxAliveObserved = maxOf(maxAliveObserved, alive)
        observedAlive = observedAlive + count + "->" + alive
        return "MULTI_INSTANCE requested=$count launched=$launched aliveAfter15s=$alive " +
            "totalPssKb=$memoryKb averagePssKbPerClone=$averageKb"
    }

    private fun singleCloneVerified(): Boolean = lastSingleVerified

    private fun stopAll() {
        (0 until 10).forEach { index ->
            val spaceId = BASE_SPACE + index
            VirtualContainerEngine.forceStopApp(context, FIXTURE_PACKAGE, spaceId)
        }
        SystemClock.sleep(500)
    }

    private fun agentProcesses(): List<ActivityManager.RunningAppProcessInfo> {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val prefix = context.packageName + ":agent"
        return manager.runningAppProcesses.orEmpty().filter { it.processName.startsWith(prefix) }
    }

    private fun totalPssKb(): Int {
        val pids = agentProcesses().map { it.pid }.toIntArray()
        if (pids.isEmpty()) return 0
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val infos: Array<Debug.MemoryInfo> = manager.getProcessMemoryInfo(pids)
        return infos.sumOf { it.totalPss }
    }

    private companion object {
        const val TAG = "MultiInstance"
        /** Documented upper bound; measured 1 (1/2/5 launches) and 2 (10 launches). */
        const val MAX_DOCUMENTED_ALIVE = 3
        const val FIXTURE_PACKAGE = "com.clonehub.pro.test"
        const val BASE_SPACE = 200
        const val SETTLE_MS = 15_000L
        var lastSingleVerified = false
        var maxAliveObserved = 0
        var observedAlive = ""
        var requestedLaunches = 0
        var launchesAccepted = 0
    }
}
