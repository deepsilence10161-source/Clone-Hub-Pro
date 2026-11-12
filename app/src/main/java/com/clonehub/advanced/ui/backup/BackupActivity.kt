package com.clonehub.advanced.ui.backup

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.clonehub.advanced.R
import com.clonehub.advanced.core.backup.BackupDocument
import com.clonehub.advanced.core.backup.CloneBackupManager
import com.clonehub.advanced.core.container.SpaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Backup and restore screen.
 *
 * * The user picks the location (system file picker); nothing is uploaded anywhere by this app.
 * * A restore always shows what the file contains and asks before anything changes.
 * * Nothing is reported as done until the manager has verified it.
 */
class BackupActivity : AppCompatActivity() {

    private lateinit var manager: CloneBackupManager
    private lateinit var statusView: TextView
    private lateinit var detailsView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var backupButton: Button
    private lateinit var restoreMergeButton: Button
    private lateinit var restoreReplaceButton: Button
    private var pendingRestore: File? = null

    private val createBackupFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri == null) {
                setStatus(getString(R.string.backup_cancelled))
                return@registerForActivityResult
            }
            runBackup(uri)
        }

    private val pickRestoreFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                setStatus(getString(R.string.restore_cancelled))
                return@registerForActivityResult
            }
            inspectRestoreFile(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbarBackup)
            .setNavigationOnClickListener { finish() }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        manager = CloneBackupManager.getInstance(this)

        statusView = findViewById(R.id.tvBackupStatus)
        detailsView = findViewById(R.id.tvBackupDetails)
        progress = findViewById(R.id.backupProgress)
        backupButton = findViewById(R.id.btnCreateBackup)
        restoreMergeButton = findViewById(R.id.btnRestoreMerge)
        restoreReplaceButton = findViewById(R.id.btnRestoreReplace)

        backupButton.setOnClickListener { startBackup() }
        // Picking a file only inspects it; nothing changes until one of the two buttons below.
        findViewById<Button>(R.id.btnPickRestore).setOnClickListener { startRestore() }
        restoreMergeButton.setOnClickListener { applyRestore(replaceExisting = false) }
        restoreReplaceButton.setOnClickListener { confirmReplaceAndRestore() }

        // Earlier runs may have left a half-written file behind; it must never be restorable.
        lifecycleScope.launch(Dispatchers.IO) {
            val removed = manager.cleanInterruptedFiles(CloneBackupManager.defaultDirectory())
            if (removed > 0) {
                withContext(Dispatchers.Main) {
                    detailsView.text = getString(R.string.backup_cleaned_partial, removed)
                }
            }
        }
        renderSummary()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun renderSummary() {
        val spaces = SpaceManager.getInstance(this).getSpaces()
        val clones = spaces.sumOf { it.apps.size }
        statusView.text = getString(R.string.backup_summary, spaces.size, clones)
    }

    private fun startBackup() {
        setStatus(getString(R.string.backup_choose_location))
        createBackupFile.launch(CloneBackupManager.suggestedFileName())
    }

    private fun runBackup(uri: Uri) {
        setBusy(true, getString(R.string.backup_writing))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val temporary = File(cacheDir, "backup-${System.currentTimeMillis()}.${CloneBackupManager.EXTENSION}")
                when (val written = manager.backupAll(temporary)) {
                    is CloneBackupManager.BackupResult.Failure -> written
                    is CloneBackupManager.BackupResult.Success -> {
                        // Only copy to the user's location after the file verified locally.
                        val copied = runCatching {
                            contentResolver.openOutputStream(uri, "wt")?.use { out ->
                                temporary.inputStream().use { input -> input.copyTo(out) }
                            } ?: error(getString(R.string.backup_cannot_write))
                        }
                        temporary.delete()
                        if (copied.isSuccess) written else CloneBackupManager.BackupResult.Failure(
                            copied.exceptionOrNull()?.message ?: getString(R.string.backup_cannot_write)
                        )
                    }
                }
            }
            setBusy(false, null)
            when (result) {
                is CloneBackupManager.BackupResult.Success -> setStatus(
                    getString(R.string.backup_done, result.cloneCount, result.bytes)
                )
                is CloneBackupManager.BackupResult.Failure -> setStatus(
                    getString(R.string.backup_failed, result.reason)
                )
            }
            renderSummary()
        }
    }

    private fun startRestore() {
        setStatus(getString(R.string.restore_choose_file))
        pickRestoreFile.launch(arrayOf("*/*"))
    }

    private fun inspectRestoreFile(uri: Uri) {
        setBusy(true, getString(R.string.restore_reading))
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val local = File(cacheDir, "restore-${System.currentTimeMillis()}.${CloneBackupManager.EXTENSION}")
                    contentResolver.openInputStream(uri)?.use { input ->
                        local.outputStream().use { output -> input.copyTo(output) }
                    } ?: error(getString(R.string.restore_cannot_read))
                    pendingRestore = local
                    manager.inspect(local)
                }.getOrElse { error ->
                    BackupDocument.ParseResult.Rejected(
                        error.message ?: getString(R.string.restore_cannot_read)
                    )
                }
            }
            setBusy(false, null)
            when (outcome) {
                is BackupDocument.ParseResult.Rejected -> {
                    pendingRestore = null
                    setStatus(getString(R.string.restore_rejected, outcome.reason))
                }
                is BackupDocument.ParseResult.Valid -> {
                    val created = if (outcome.createdAt > 0) {
                        DateFormat.getDateTimeInstance().format(Date(outcome.createdAt))
                    } else {
                        getString(R.string.restore_unknown_date)
                    }
                    val spaces = outcome.model.spaces.size
                    val clones = outcome.model.spaces.sumOf { it.apps.size }
                    setStatus(getString(R.string.restore_found, spaces, clones, created))
                    detailsView.text = getString(
                        R.string.restore_explainer,
                        getString(R.string.backup_scope_note)
                    )
                }
            }
        }
    }

    private fun confirmReplaceAndRestore() {
        if (pendingRestore == null) {
            setStatus(getString(R.string.restore_pick_first))
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.restore_replace_title)
            .setMessage(R.string.restore_replace_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.restore_replace_confirm) { _, _ ->
                applyRestore(replaceExisting = true)
            }
            .show()
    }

    private fun applyRestore(replaceExisting: Boolean) {
        val file = pendingRestore
        if (file == null) {
            setStatus(getString(R.string.restore_pick_first))
            return
        }
        setBusy(true, getString(R.string.restore_applying))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { manager.restore(file, replaceExisting) }
            setBusy(false, null)
            when (result) {
                is CloneBackupManager.RestoreResult.Success -> {
                    val message = if (result.replacedExisting) {
                        getString(R.string.restore_done_replaced, result.addedSpaces, result.addedClones)
                    } else {
                        getString(
                            R.string.restore_done_merged,
                            result.addedSpaces,
                            result.addedClones,
                            result.skippedDuplicates
                        )
                    }
                    val safety = result.safetyCopy?.let {
                        getString(R.string.restore_safety_copy, it.name)
                    } ?: ""
                    setStatus(message + safety)
                    pendingRestore = null
                    file.delete()
                }
                is CloneBackupManager.RestoreResult.Failure ->
                    setStatus(getString(R.string.restore_failed, result.reason))
            }
            renderSummary()
        }
    }

    private fun setBusy(busy: Boolean, message: String?) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        backupButton.isEnabled = !busy
        findViewById<Button>(R.id.btnPickRestore).isEnabled = !busy
        restoreMergeButton.isEnabled = !busy
        restoreReplaceButton.isEnabled = !busy
        if (message != null) setStatus(message)
    }

    private fun setStatus(text: String) {
        statusView.text = text
        findViewById<ScrollView>(R.id.backupScroll).post {
            findViewById<ScrollView>(R.id.backupScroll).fullScroll(View.FOCUS_DOWN)
        }
    }

    companion object {
        fun intent(activity: Activity): Intent = Intent(activity, BackupActivity::class.java)
    }
}
