package com.clonehub.advanced.ui.home

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.view.isVisible
import androidx.viewpager2.widget.ViewPager2
import com.clonehub.advanced.R
import com.clonehub.advanced.core.container.SpaceManager
import com.clonehub.advanced.core.container.VirtualContainerEngine
import com.clonehub.advanced.core.service.ContainerForegroundService
import com.clonehub.advanced.data.model.ClonedApp
import com.clonehub.advanced.ui.backup.BackupActivity
import com.clonehub.advanced.ui.dialogs.AppActionsBottomSheet
import com.clonehub.advanced.ui.picker.AppPickerActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeActivity : AppCompatActivity() {

    private lateinit var spaceManager: SpaceManager
    private lateinit var viewPager: ViewPager2
    private lateinit var spacePagerAdapter: SpacePagerAdapter
    private lateinit var tvSpaceIndicator: TextView
    private lateinit var btnPrevSpace: ImageButton
    private lateinit var btnNextSpace: ImageButton
    private lateinit var fabAdd: FloatingActionButton
    private var isLaunching: Boolean = false

    private val pickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            refreshSpaces()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        spaceManager = SpaceManager.getInstance(this)

        initViews()
        findViewById<View>(R.id.btnBackup).setOnClickListener {
            startActivity(BackupActivity.intent(this))
        }
        setupViewPager()
        setupSpaceControls()
        refreshSpaces()
    }

    override fun onResume() {
        super.onResume()
        refreshSpaces()
    }

    private fun initViews() {
        viewPager = findViewById(R.id.viewPagerSpaces)
        tvSpaceIndicator = findViewById(R.id.tvSpaceIndicator)
        btnPrevSpace = findViewById(R.id.btnPrevSpace)
        btnNextSpace = findViewById(R.id.btnNextSpace)
        fabAdd = findViewById(R.id.fabAdd)

        fabAdd.setOnClickListener {
            val currentSpaceId = getCurrentSpaceId()
            openAppPicker(currentSpaceId)
        }
    }

    private fun setupViewPager() {
        spacePagerAdapter = SpacePagerAdapter(
            context = this,
            onAppClick = { app, spaceId ->
                launchClonedApp(app, spaceId)
            },
            onAppLongClick = { app, spaceId ->
                val sheet = AppActionsBottomSheet(app, spaceId) {
                    refreshSpaces()
                }
                sheet.show(supportFragmentManager, "app_actions")
            },
            onAddClick = { spaceId ->
                openAppPicker(spaceId)
            },
            onSpaceMenuClick = { space ->
                showSpaceActions(space)
            }
        )

        viewPager.adapter = spacePagerAdapter
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateSpaceIndicator(position)
            }
        })
    }

    private fun setupSpaceControls() {
        btnPrevSpace.setOnClickListener {
            val current = viewPager.currentItem
            if (current > 0) {
                selectSpace(current - 1)
            }
        }

        btnNextSpace.setOnClickListener {
            val current = viewPager.currentItem
            val count = spacePagerAdapter.itemCount
            Log.i("HomeActivity", "Next space action: current=$current, count=$count")
            if (current < count - 1) {
                selectSpace(current + 1)
            } else {
                val newSpace = spaceManager.createNextSpace()
                if (newSpace == null) {
                    Toast.makeText(this, R.string.slot_limit_reached, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                Log.i("HomeActivity", "Created Space ${newSpace.spaceId}; total=${spaceManager.getSpaces().size}")
                refreshSpaces()
                // Let ViewPager consume the adapter update before selecting the new page;
                // otherwise its restoration callback can move back to Space 1.
                viewPager.post {
                    val newPosition = spaceManager.getSpaces().indexOfFirst { it.spaceId == newSpace.spaceId }
                    if (newPosition >= 0) {
                        selectSpace(newPosition)
                    }
                }
            }
        }
    }

    private fun selectSpace(position: Int) {
        // Immediate selection keeps the indicator and Add action in sync with the visible space.
        viewPager.setCurrentItem(position, false)
        updateSpaceIndicator(position)
    }

    private fun updateSpaceIndicator(position: Int) {
        val spaces = spaceManager.getSpaces()
        if (position in spaces.indices) {
            tvSpaceIndicator.text = "${spaces[position].spaceId}"
        }
    }

    private fun getCurrentSpaceId(): Int {
        val spaces = spaceManager.getSpaces()
        val pos = viewPager.currentItem
        return if (pos in spaces.indices) spaces[pos].spaceId else 1
    }

    private fun launchClonedApp(app: ClonedApp, spaceId: Int) {
        if (isLaunching) return
        isLaunching = true

        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                VirtualContainerEngine.launchAppOutcome(this@HomeActivity, app, spaceId)
            }
            isLaunching = false

            // Record the real result so the grid can show clone health instead of a silent failure.
            spaceManager.recordLaunch(spaceId, app.packageName, outcome.isSuccess, outcome.describe())
            if (outcome.isSuccess) {
                runCatching { ContainerForegroundService.start(this@HomeActivity, app.appName, spaceId) }
            } else {
                val reason = outcome.describe()
                Log.w("HomeActivity", "Launch failed for ${app.packageName}: $reason")
                Toast.makeText(
                    this@HomeActivity,
                    getString(R.string.launch_failed_reason, app.appName, reason),
                    Toast.LENGTH_LONG
                ).show()
            }
            refreshSpaces()
        }
    }

    private fun openAppPicker(spaceId: Int) {
        val intent = Intent(this, AppPickerActivity::class.java).apply {
            putExtra("target_space_id", spaceId)
        }
        pickerLauncher.launch(intent)
    }

    private fun refreshSpaces() {
        val spaces = spaceManager.getSpaces()
        spacePagerAdapter.submitSpaces(spaces)
        if (viewPager.currentItem > spaces.lastIndex) {
            viewPager.setCurrentItem(spaces.lastIndex.coerceAtLeast(0), false)
        }
        updateSpaceIndicator(viewPager.currentItem)
        findViewById<View>(R.id.layoutEmpty).isVisible = spaces.all { it.apps.isEmpty() }
    }

    /** Space management: rename, and delete only when the space holds no clones. */
    private fun showSpaceActions(space: com.clonehub.advanced.data.model.Space) {
        val options = arrayOf(getString(R.string.space_rename), getString(R.string.space_delete))
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(space.spaceName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> promptRenameSpace(space)
                    else -> {
                        val removed = spaceManager.removeSpace(space.spaceId)
                        Toast.makeText(
                            this,
                            if (removed) getString(R.string.space_deleted, space.spaceName)
                            else getString(R.string.space_delete_blocked),
                            Toast.LENGTH_LONG
                        ).show()
                        refreshSpaces()
                    }
                }
            }
            .show()
    }

    private fun promptRenameSpace(space: com.clonehub.advanced.data.model.Space) {
        val input = android.widget.EditText(this).apply {
            setText(space.spaceName)
            setHint(R.string.space_name_hint)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.space_rename)
            .setView(input)
            .setPositiveButton(R.string.positive) { _, _ ->
                val renamed = spaceManager.renameSpace(space.spaceId, input.text.toString().trim())
                if (!renamed) {
                    Toast.makeText(this, R.string.space_rename_failed, Toast.LENGTH_LONG).show()
                }
                refreshSpaces()
            }
            .setNegativeButton(R.string.negative, null)
            .show()
    }
}
