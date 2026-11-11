package com.clonehub.advanced.ui.dialogs

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
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
import com.clonehub.advanced.ui.home.IconLoader
import com.clonehub.advanced.ui.home.ShortcutLauncherActivity
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
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
        val status: TextView = view.findViewById(R.id.tvHeaderStatus)
        tvSpace.text = "Space $spaceId"
        status.text = when (app.lastLaunchSucceeded) {
            null -> getString(R.string.clone_status_never_launched)
            true -> getString(R.string.clone_status_ok)
            false -> getString(R.string.clone_status_failed, app.lastFailureReason.orEmpty())
        }
        status.setTextColor(
            if (app.lastLaunchSucceeded == false) 0xFFFF5252.toInt() else 0xFFB0B0B0.toInt()
        )
        ivHeader.setImageDrawable(
            IconLoader.loadBlocking(requireContext(), app.packageName, app.customIconPath)
        )

        // Action 1: Open / Launch
        view.findViewById<View>(R.id.actionLaunch).setOnClickListener {
            lifecycleScope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    VirtualContainerEngine.launchAppOutcome(requireContext(), app, spaceId)
                }
                SpaceManager.getInstance(requireContext())
                    .recordLaunch(spaceId, app.packageName, outcome.isSuccess, outcome.describe())
                onAppUpdated()
                if (outcome.isSuccess) {
                    dismiss()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.launch_failed_reason, app.appName, outcome.describe()),
                        Toast.LENGTH_LONG
                    ).show()
                    dismiss()
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
            // Use real space ordering: spaceId + 1 skipped spaces and could write into the wrong one.
            val targetSpace = manager.getNextSpaceAfter(spaceId) ?: manager.createNextSpace()
            if (targetSpace == null) {
                action.isEnabled = true
                Toast.makeText(requireContext(), R.string.slot_limit_reached, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val targetSpaceId = targetSpace.spaceId

            lifecycleScope.launch {
                val installed = withContext(Dispatchers.IO) {
                    VirtualContainerEngine.installAppOutcome(requireContext(), app.packageName, targetSpaceId)
                }
                val added = installed.isSuccess &&
                    manager.addAppToSpace(targetSpaceId, app.packageName, app.appName) != null
                if (added) {
                    Toast.makeText(context, getString(R.string.cloned_to_space, targetSpace.spaceName), Toast.LENGTH_SHORT).show()
                    onAppUpdated()
                    dismiss()
                } else {
                    action.isEnabled = true
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.clone_failed_reason, app.appName, installed.describe()),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        // Action 5: Stop the running clone (this is the only data-affecting action the runtime exposes)
        view.findViewById<View>(R.id.actionForceStop).setOnClickListener {
            lifecycleScope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    VirtualContainerEngine.forceStopApp(requireContext(), app.packageName, spaceId)
                }
                Toast.makeText(
                    requireContext(),
                    if (outcome.isSuccess) getString(R.string.stopped_ok, app.appName)
                    else getString(R.string.stop_failed_reason, outcome.describe()),
                    Toast.LENGTH_LONG
                ).show()
                dismiss()
            }
        }

        // Action 6: Clear Storage — the runtime has no per-clone data deletion call, so say so.
        view.findViewById<View>(R.id.actionClearStorage).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.clear_storage_unavailable_title)
                .setMessage(R.string.clear_storage_unavailable_body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        // Action 7: Remove from this space. Keeps the runtime package, so it is not an uninstall.
        view.findViewById<View>(R.id.actionUninstall).setOnClickListener {
            val removed = SpaceManager.getInstance(requireContext())
                .removeAppFromSpace(spaceId, app.packageName)
            Toast.makeText(
                requireContext(),
                if (removed) getString(R.string.removed_from_space, app.appName, spaceId)
                else getString(R.string.remove_failed, app.appName),
                Toast.LENGTH_LONG
            ).show()
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
