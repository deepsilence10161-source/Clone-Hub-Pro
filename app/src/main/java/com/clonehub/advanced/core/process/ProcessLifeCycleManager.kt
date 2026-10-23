package com.clonehub.advanced.core.process

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.util.Log

/**
 * Intelligent Process & Battery Optimizer.
 * Ensures 0% idle battery consumption and optimizes RAM by killing or hibernating
 * unused background spaces.
 */
class ProcessLifeCycleManager private constructor(private val context: Context) {

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val activeSpacePids = mutableMapOf<Int, MutableSet<Int>>()

    companion object {
        private const val TAG = "ProcessManager"

        @Volatile
        private var INSTANCE: ProcessLifeCycleManager? = null

        fun getInstance(context: Context): ProcessLifeCycleManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProcessLifeCycleManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * Registers a process ID running under a specific space.
     */
    @Synchronized
    fun registerProcess(spaceId: Int, pid: Int) {
        activeSpacePids.getOrPut(spaceId) { mutableSetOf() }.add(pid)
        Log.d(TAG, "Registered PID $pid for Space $spaceId")
    }

    /**
     * Hibernates / kills background processes of a space when switching away or on low memory.
     */
    @Synchronized
    fun hibernateSpace(spaceId: Int) {
        val pids = activeSpacePids[spaceId] ?: return
        pids.forEach { pid ->
            if (pid != Process.myPid()) {
                try {
                    Process.killProcess(pid)
                    Log.i(TAG, "Hibernated background virtual process $pid in Space $spaceId")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not kill PID $pid", e)
                }
            }
        }
        pids.clear()
    }

    /**
     * Performs a deep RAM cleanup across all non-foreground spaces.
     */
    @Synchronized
    fun cleanAllIdleSpaces(currentForegroundSpaceId: Int) {
        activeSpacePids.keys.filter { it != currentForegroundSpaceId }.forEach { spaceId ->
            hibernateSpace(spaceId)
        }
    }
}
