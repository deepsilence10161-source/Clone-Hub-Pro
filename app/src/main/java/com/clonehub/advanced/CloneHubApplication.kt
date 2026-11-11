package com.clonehub.advanced

import android.app.Application
import android.content.Context
import android.os.Build
import com.clonehub.advanced.core.container.SpaceManager
import com.multibox.clone.hub.AbstractC2211qP
import com.multibox.clone.hub.RuntimeCallbackHandler
import com.multibox.clone.hub.q9
import com.multibox.clone.hub.tc
import com.multibox.clone.hub.uc

class CloneHubApplication : Application() {

    private val preWarm = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "VirtualCorePreWarm").apply { priority = Thread.MIN_PRIORITY }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        uc.a = 0
        tc.a = 0
        AbstractC2211qP.j = 73
    }

    override fun onTerminate() {
        preWarm.shutdownNow()
        super.onTerminate()
    }

    override fun onCreate() {
        super.onCreate()
        val currentProcessName = if (Build.VERSION.SDK_INT >= 28) getProcessName() else packageName
        if (applicationInfo.processName != null && applicationInfo.processName != currentProcessName) {
            RuntimeCallbackHandler.INSTANCE.callbacks[100] = q9()
        }
        // Initialize Space & Sandbox Manager on app boot
        SpaceManager.getInstance(this)

        // Engine crashes inside cloned agent processes are journaled so the UI can show them
        // instead of silently presenting a dead clone as healthy.
        RuntimeCallbackHandler.setJournalDirectory(filesDir)

        // Pre-warm the virtual engine off the startup path. Extraction is expensive, so it runs
        // on a low-priority worker; a failure here is logged and retried lazily on first clone
        // instead of crashing an unrelated background thread.
        preWarm.execute {
            try {
                AbstractC2211qP.g(this)
            } catch (t: Throwable) {
                android.util.Log.e("CloneHubApplication", "Virtual engine pre-warm failed", t)
            }
        }
    }
}
