package com.clonehub.advanced.ui.picker

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppPickerActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "AppPickerActivity"
    }

    private lateinit var appRepo: AppRepository
    private lateinit var adapter: AppPickerAdapter
    private var targetSpaceId: Int = 1
    private var isCloning: Boolean = false

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
            if (isCloning) return@AppPickerAdapter
            val manager = SpaceManager.getInstance(this@AppPickerActivity)
            if (!manager.canAddClone()) {
                Toast.makeText(
                    this@AppPickerActivity,
                    getString(R.string.slot_limit_reached),
                    Toast.LENGTH_LONG
                ).show()
                return@AppPickerAdapter
            }
            isCloning = true
            findViewById<ProgressBar>(R.id.progressBarPicker).isVisible = true

            lifecycleScope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    com.clonehub.advanced.core.container.VirtualContainerEngine.installAppOutcome(
                        this@AppPickerActivity,
                        selectedApp.packageName,
                        targetSpaceId
                    )
                }

                // Update the visible model only after the runtime confirms the installation.
                val added = outcome.isSuccess && manager.addAppToSpace(
                    spaceId = targetSpaceId,
                    packageName = selectedApp.packageName,
                    appName = selectedApp.appName
                ) != null

                if (added) {
                    setResult(Activity.RESULT_OK)
                    finish()
                } else {
                    findViewById<ProgressBar>(R.id.progressBarPicker).isVisible = false
                    isCloning = false
                    val reason = if (outcome.isSuccess) {
                        getString(R.string.slot_limit_reached)
                    } else {
                        outcome.describe()
                    }
                    Toast.makeText(
                        this@AppPickerActivity,
                        getString(R.string.clone_failed_reason, selectedApp.appName, reason),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        rv.adapter = adapter
    }

    private fun setupChips() {
        val chipUser: Chip = findViewById(R.id.chipUser)
        val chipSystem: Chip = findViewById(R.id.chipSystem)

        // The filter has to show which list is on screen: the chips were clickable but never
        // checked, so both looked equally inactive and the user could not tell what they were
        // looking at (visible in the 360dp phone screenshots from the UI matrix).
        chipUser.isChecked = true
        chipUser.setOnClickListener {
            chipSystem.isChecked = false
            chipUser.isChecked = true
            loadApps(includeSystem = false)
        }
        chipSystem.setOnClickListener {
            chipUser.isChecked = false
            chipSystem.isChecked = true
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

    /**
     * Loads the picker list and shows exactly one of three states: loading, a real empty list, or a
     * permission problem.
     *
     * The OEM-permission card used to be the answer to *any* empty result, so a device with no other
     * user apps was told to change MIUI settings — a wrong instruction with no way to act on it. The
     * card is now reserved for a failure, and an empty list says so in its own words.
     */
    private fun loadApps(includeSystem: Boolean) {
        val progress: ProgressBar = findViewById(R.id.progressBarPicker)
        val guide: View = findViewById(R.id.permissionGuide)
        val empty: View = findViewById(R.id.pickerEmptyState)
        val list: androidx.recyclerview.widget.RecyclerView = findViewById(R.id.recyclerInstalledApps)

        progress.isVisible = true
        guide.isVisible = false
        empty.isVisible = false
        list.isVisible = false

        lifecycleScope.launch {
            val loaded = runCatching { appRepo.getInstalledApps(includeSystem) }
            progress.isVisible = false
            loaded.onSuccess { installed ->
                if (installed.isEmpty()) {
                    empty.isVisible = true
                    Log.i(TAG, "Picker empty includeSystem=$includeSystem")
                } else {
                    list.isVisible = true
                    adapter.submitList(installed)
                    Log.i(TAG, "Picker loaded ${installed.size} apps includeSystem=$includeSystem")
                }
            }.onFailure { error ->
                guide.isVisible = true
                Log.w(TAG, "Picker could not read the app list", error)
            }
        }
    }
}
