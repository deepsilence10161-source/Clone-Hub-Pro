package com.clonehub.advanced.ui.home

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.clonehub.advanced.core.container.SpaceManager
import com.clonehub.advanced.core.container.VirtualContainerEngine

class ShortcutLauncherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val packageName = intent.getStringExtra("extra_package")
        val spaceId = intent.getIntExtra("extra_space_id", 1)

        if (!packageName.isNullOrEmpty()) {
            val space = SpaceManager.getInstance(this).getSpace(spaceId)
            val app = space?.apps?.find { it.packageName == packageName }

            if (app != null) {
                VirtualContainerEngine.launchApp(this, app, spaceId)
            } else {
                Toast.makeText(this, "App not found in Space $spaceId", Toast.LENGTH_SHORT).show()
            }
        }

        finish()
    }
}
