package com.clonehub.advanced.ui.home

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.clonehub.advanced.R
import com.clonehub.advanced.core.container.SpaceManager
import com.clonehub.advanced.core.container.VirtualContainerEngine
import com.clonehub.advanced.data.model.ClonedApp
import com.clonehub.advanced.ui.dialogs.AppActionsBottomSheet
import com.clonehub.advanced.ui.picker.AppPickerActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton

class HomeActivity : AppCompatActivity() {

    private lateinit var spaceManager: SpaceManager
    private lateinit var viewPager: ViewPager2
    private lateinit var spacePagerAdapter: SpacePagerAdapter
    private lateinit var tvSpaceIndicator: TextView
    private lateinit var btnPrevSpace: ImageButton
    private lateinit var btnNextSpace: ImageButton
    private lateinit var fabAdd: FloatingActionButton

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
                VirtualContainerEngine.launchApp(this, app, spaceId)
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
                viewPager.setCurrentItem(current - 1, true)
            }
        }

        btnNextSpace.setOnClickListener {
            val current = viewPager.currentItem
            val count = spacePagerAdapter.itemCount
            if (current < count - 1) {
                viewPager.setCurrentItem(current + 1, true)
            } else {
                // Create Next Space
                val newSpace = spaceManager.createNextSpace()
                refreshSpaces()
                viewPager.setCurrentItem(newSpace.spaceId - 1, true)
            }
        }
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
