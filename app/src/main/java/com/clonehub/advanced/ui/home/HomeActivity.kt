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

    private companion object {
        const val TAG = "HomeActivity"
    }

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
        // One overflow menu instead of a decorative gear and a loose save icon: every secondary
        // action (backup, new space, about) lives here and is reachable on a 320dp phone.
        findViewById<View>(R.id.btnOverflow).setOnClickListener { showOverflowMenu() }
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
        applySystemBarInsets()
    }

    /**
     * Android 15 (targetSdk 35) forces edge-to-edge, so the activity draws under the navigation
     * bar and the FAB ended up behind the gesture bar: taps landed on the system bar and the app
     * picker never opened (measured on the API 35 gate, run 43153564675).
     *
     * The theme deliberately starts outside the system bars, so the insets are applied as padding
     * on the root view instead of being consumed blindly — this keeps working on API 24-34, where
     * the insets are already zero and the padding simply adds nothing.
     */
    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.coordinator) ?: return
        val basePadding = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        val baseFabMargin = findViewById<View>(R.id.fabAdd)?.let { fab ->
            (fab.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                intArrayOf(it.leftMargin, it.topMargin, it.rightMargin, it.bottomMargin)
            }
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            root.setPadding(
                basePadding[0] + bars.left,
                basePadding[1] + bars.top,
                basePadding[2] + bars.right,
                basePadding[3] + bars.bottom
            )
            // The FAB is anchored to the bottom, so it needs the navigation-bar inset as margin.
            findViewById<View>(R.id.fabAdd)?.let { fab ->
                (fab.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { params ->
                    if (baseFabMargin != null) {
                        params.leftMargin = baseFabMargin[0] + bars.left
                        params.topMargin = baseFabMargin[1]
                        params.rightMargin = baseFabMargin[2] + bars.right
                        params.bottomMargin = baseFabMargin[3] + 0
                    }
                    fab.layoutParams = params
                }
            }
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(root)
        Log.i(TAG, "System bar insets applied to the home layout (API ${android.os.Build.VERSION.SDK_INT})")
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
                createSpaceAndShow()
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
        findViewById<View>(R.id.tvCapacityNote)?.isVisible = spaces.any { it.apps.isNotEmpty() }
    }

    private fun showOverflowMenu() {
        val items = arrayOf(getString(R.string.action_backup), getString(R.string.action_new_space))
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startActivity(BackupActivity.intent(this))
                    else -> createSpaceAndShow()
                }
            }
            .show()
    }

    private fun createSpaceAndShow() {
        val newSpace = spaceManager.createNextSpace()
        if (newSpace == null) {
            Toast.makeText(this, R.string.slot_limit_reached, Toast.LENGTH_LONG).show()
            return
        }
        refreshSpaces()
        viewPager.post {
            val position = spaceManager.getSpaces().indexOfFirst { it.spaceId == newSpace.spaceId }
            if (position >= 0) selectSpace(position)
        }
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
