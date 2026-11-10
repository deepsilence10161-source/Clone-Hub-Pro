package com.clonehub.advanced.ui.home

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.clonehub.advanced.R
import com.clonehub.advanced.core.container.SpaceManager
import com.clonehub.advanced.core.container.VirtualContainerEngine
import com.clonehub.advanced.core.service.ContainerForegroundService
import com.clonehub.advanced.data.model.ClonedApp
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
        setupViewPager()
        setupSpaceControls()
        refreshSpaces()
    }

    override fun onResume() {
        super.onResume()
        refreshSpaces()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        Log.i("HomeActivity", "Dispatch touch: action=${event.action}, x=${event.x}, y=${event.y}")
        return super.dispatchTouchEvent(event)
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
                // Space options menu
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
            val launched = withContext(Dispatchers.IO) {
                VirtualContainerEngine.launchApp(this@HomeActivity, app, spaceId)
            }
            isLaunching = false

            if (launched) {
                runCatching { ContainerForegroundService.start(this@HomeActivity, app.appName, spaceId) }
            } else {
                Toast.makeText(
                    this@HomeActivity,
                    getString(R.string.launch_failed, app.appName),
                    Toast.LENGTH_LONG
                ).show()
            }
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
        updateSpaceIndicator(viewPager.currentItem)
    }
}
