package com.clonehub.advanced.ui.home

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.clonehub.advanced.R
import com.clonehub.advanced.core.container.SpaceManager
import com.clonehub.advanced.core.container.VirtualContainerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShortcutLauncherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val packageName = intent.getStringExtra("extra_package")
        val spaceId = intent.getIntExtra("extra_space_id", 1)
        val app = packageName?.let { name ->
            SpaceManager.getInstance(this).getSpace(spaceId)?.apps?.find { it.packageName == name }
        }

        if (app == null) {
            Toast.makeText(this, "App not found in Space $spaceId", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            val launched = withContext(Dispatchers.IO) {
                VirtualContainerEngine.launchApp(this@ShortcutLauncherActivity, app, spaceId)
            }
            if (!launched) {
                Toast.makeText(
                    this@ShortcutLauncherActivity,
                    getString(R.string.launch_failed, app.appName),
                    Toast.LENGTH_LONG
                ).show()
            }
            finish()
        }
    }
}
