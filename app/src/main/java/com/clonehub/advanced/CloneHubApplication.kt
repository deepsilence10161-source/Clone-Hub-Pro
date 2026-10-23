package com.clonehub.advanced

import android.app.Application
import android.content.Context
import com.clonehub.advanced.core.container.SpaceManager
import com.multibox.clone.hub.AbstractC2211qP
import kotlin.concurrent.thread

class CloneHubApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        AbstractC2211qP.j = 73
    }

    override fun onCreate() {
        super.onCreate()
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
