package com.clonehub.advanced.ui.dialogs

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import com.clonehub.advanced.R
import com.clonehub.advanced.core.container.SpaceManager
import com.clonehub.advanced.core.container.VirtualContainerEngine
import com.clonehub.advanced.data.model.ClonedApp
import com.clonehub.advanced.ui.home.ShortcutLauncherActivity
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppActionsBottomSheet(
    private val app: ClonedApp,
    private val spaceId: Int,
    private val onAppUpdated: () -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_app_actions, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ivHeader: ImageView = view.findViewById(R.id.ivHeaderIcon)
        val tvTitle: TextView = view.findViewById(R.id.tvHeaderTitle)
        val tvSpace: TextView = view.findViewById(R.id.tvHeaderSpace)

        tvTitle.text = app.appName
        tvSpace.text = "Space $spaceId"

        // Load Icon
        if (!app.customIconPath.isNullOrEmpty() && File(app.customIconPath!!).exists()) {
            val bitmap = BitmapFactory.decodeFile(app.customIconPath)
            ivHeader.setImageDrawable(BitmapDrawable(resources, bitmap))
        } else {
            val icon = runCatching { requireContext().packageManager.getApplicationIcon(app.packageName) }.getOrNull()
            ivHeader.setImageDrawable(icon ?: requireContext().getDrawable(R.drawable.ic_action_install))
        }

        // Action 1: Open / Launch
        view.findViewById<View>(R.id.actionLaunch).setOnClickListener {
            lifecycleScope.launch {
                val launched = withContext(Dispatchers.IO) {
                    VirtualContainerEngine.launchApp(requireContext(), app, spaceId)
                }
                if (launched) {
                    dismiss()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.launch_failed, app.appName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        // Action 2: Change Icon / Name
        view.findViewById<View>(R.id.actionFakeIcon).setOnClickListener {
            dismiss()
            AppCustomizeDialog(requireContext(), app, spaceId) {
                onAppUpdated()
            }.show()
        }

        // Action 3: Create Shortcut
        view.findViewById<View>(R.id.actionCreateShortcut).setOnClickListener {
            pinShortcut(requireContext(), app, spaceId)
            dismiss()
        }

        // Action 4: Clone to Next Space
        view.findViewById<View>(R.id.actionCloneNext).setOnClickListener { action ->
            action.isEnabled = false
            val manager = SpaceManager.getInstance(requireContext())
            val targetSpace = manager.getSpace(spaceId + 1) ?: manager.createNextSpace()
            val targetSpaceId = targetSpace.spaceId

            lifecycleScope.launch {
                val installed = withContext(Dispatchers.IO) {
                    VirtualContainerEngine.installApp(requireContext(), app.packageName, targetSpaceId)
                }
                if (installed) {
                    manager.addAppToSpace(targetSpaceId, app.packageName, app.appName)
                    Toast.makeText(context, "Cloned to Space $targetSpaceId", Toast.LENGTH_SHORT).show()
                    onAppUpdated()
                    dismiss()
                } else {
                    action.isEnabled = true
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.clone_failed, app.appName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        // Action 5: Clear Storage
        view.findViewById<View>(R.id.actionClearStorage).setOnClickListener {
            SpaceManager.getInstance(requireContext()).clearAppStorage(spaceId, app.packageName)
            Toast.makeText(context, "Storage cleared for ${app.appName}", Toast.LENGTH_SHORT).show()
            dismiss()
        }

        // Action 6: Uninstall
        view.findViewById<View>(R.id.actionUninstall).setOnClickListener {
            SpaceManager.getInstance(requireContext()).removeAppFromSpace(spaceId, app.packageName)
            Toast.makeText(context, "Removed ${app.appName} from Space $spaceId", Toast.LENGTH_SHORT).show()
            onAppUpdated()
            dismiss()
        }
    }

    private fun pinShortcut(context: Context, app: ClonedApp, spaceId: Int) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            Toast.makeText(context, "Pinned shortcuts not supported on your launcher", Toast.LENGTH_SHORT).show()
            return
        }

        val launchIntent = Intent(context, ShortcutLauncherActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("extra_package", app.packageName)
            putExtra("extra_space_id", spaceId)
        }

        val icon = runCatching { context.packageManager.getApplicationIcon(app.packageName) }.getOrNull()
        val bitmap = icon?.toBitmap() ?: BitmapFactory.decodeResource(context.resources, R.drawable.ic_action_install)

        val pinShortcutInfo = ShortcutInfoCompat.Builder(context, "${app.packageName}_space_$spaceId")
            .setShortLabel(app.appName)
            .setLongLabel("${app.appName} (Space $spaceId)")
            .setIcon(IconCompat.createWithBitmap(bitmap))
            .setIntent(launchIntent)
            .build()

        ShortcutManagerCompat.requestPinShortcut(context, pinShortcutInfo, null)
        Toast.makeText(context, "Shortcut added to home screen", Toast.LENGTH_SHORT).show()
    }
}
