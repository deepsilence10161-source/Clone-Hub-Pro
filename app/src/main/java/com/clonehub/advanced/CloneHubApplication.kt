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
import kotlin.concurrent.thread

class CloneHubApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        uc.a = 0
        tc.a = 0
        AbstractC2211qP.j = 73
    }

    override fun onCreate() {
        super.onCreate()
        val currentProcessName = if (Build.VERSION.SDK_INT >= 28) getProcessName() else packageName
        if (applicationInfo.processName != null && applicationInfo.processName != currentProcessName) {
            RuntimeCallbackHandler.INSTANCE.callbacks[100] = q9()
        }
        // Initialize Space & Sandbox Manager on app boot
        SpaceManager.getInstance(this)

        // Pre-warm Virtual Engine core in background
        thread(name = "VirtualCorePreWarm") {
            try {
                AbstractC2211qP.g(this)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
