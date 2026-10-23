package com.clonehub.advanced.ui.picker

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.clonehub.advanced.R
import com.clonehub.advanced.core.container.SpaceManager
import com.clonehub.advanced.data.repository.AppRepository
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch

class AppPickerActivity : AppCompatActivity() {

    private lateinit var appRepo: AppRepository
    private lateinit var adapter: AppPickerAdapter
    private var targetSpaceId: Int = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)

        targetSpaceId = intent.getIntExtra("target_space_id", 1)
        appRepo = AppRepository(this)

        setupToolbar()
        setupRecyclerView()
        setupChips()
        setupSearch()

        loadApps(includeSystem = false)
    }

    private fun setupToolbar() {
        val toolbar: MaterialToolbar = findViewById(R.id.toolbarPicker)
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        val rv: RecyclerView = findViewById(R.id.recyclerInstalledApps)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = AppPickerAdapter { selectedApp ->
            // Clone into Space
            SpaceManager.getInstance(this).addAppToSpace(
                spaceId = targetSpaceId,
                packageName = selectedApp.packageName,
                appName = selectedApp.appName
            )
            // Register and install into virtual container sandbox
            kotlin.concurrent.thread {
                com.clonehub.advanced.core.container.VirtualContainerEngine.installApp(
                    this,
                    selectedApp.packageName,
                    targetSpaceId
                )
            }
            setResult(Activity.RESULT_OK)
            finish()
        }
        rv.adapter = adapter
    }

    private fun setupChips() {
        val chipUser: Chip = findViewById(R.id.chipUser)
        val chipSystem: Chip = findViewById(R.id.chipSystem)

        chipUser.setOnClickListener {
            loadApps(includeSystem = false)
        }
        chipSystem.setOnClickListener {
            loadApps(includeSystem = true)
        }

        findViewById<View>(R.id.btnOpenSettings)?.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }
    }

    private fun setupSearch() {
        val searchView: SearchView = findViewById(R.id.searchView)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                adapter.filter(newText.orEmpty())
                return true
            }
        })
    }

    private fun loadApps(includeSystem: Boolean) {
        val progress: ProgressBar = findViewById(R.id.progressBarPicker)
        val guide: View = findViewById(R.id.permissionGuide)

        progress.isVisible = true
        guide.isVisible = false

        lifecycleScope.launch {
            try {
                val list = appRepo.getInstalledApps(includeSystem)
                progress.isVisible = false
                if (list.isEmpty()) {
                    guide.isVisible = true
                } else {
                    adapter.submitList(list)
                }
            } catch (e: Exception) {
                progress.isVisible = false
                guide.isVisible = true
            }
        }
    }
}
