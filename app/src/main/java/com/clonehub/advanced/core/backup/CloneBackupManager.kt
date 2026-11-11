package com.clonehub.advanced.core.backup

import android.content.Context
import android.os.Build
import android.util.Log
import com.clonehub.advanced.core.container.SpaceManager
import com.clonehub.advanced.data.model.CloneModel
import com.clonehub.advanced.data.model.Space
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup and restore for Clone Hub Pro's own clone model: spaces, the clones in them, their names,
 * custom icons and launch history.
 *
 * ### What is and is not included
 *
 * The bundled virtual runtime keeps each clone's real application data inside its own private
 * directory, readable only by the runtime process (this app is deliberately not `debuggable`).
 * This manager therefore does **not** export the cloned apps' internal data and never claims to.
 * It backs up the part this app owns and can verify. After a restore, the runtime re-establishes a
 * clone's sandbox the next time that clone is launched.
 *
 * Safety rules enforced here:
 * * the file format is checksummed and versioned ([BackupDocument]) — a damaged, edited or foreign
 *   file is refused instead of restoring the wrong clones;
 * * files are written to `*.part` and renamed, so an interrupted backup never looks complete;
 * * replacing the current model requires a successful safety copy first, otherwise nothing changes;
 * * merge mode never removes an existing space or clone.
 */
class CloneBackupManager private constructor(private val context: Context) {

    private val spaceManager = SpaceManager.getInstance(context)

    companion object {
        private const val TAG = "CloneBackup"
        const val EXTENSION = "chb"

        @Volatile
        private var INSTANCE: CloneBackupManager? = null

        fun getInstance(context: Context): CloneBackupManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: CloneBackupManager(context.applicationContext).also { INSTANCE = it }
            }

        /** Default, user-visible folder: Documents/CloneHubPro on shared storage. */
        @Suppress("DEPRECATION")
        fun defaultDirectory(): File = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOCUMENTS
            ),
            "CloneHubPro"
        )

        fun suggestedFileName(prefix: String = "clonehub-backup"): String {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            return "$prefix-$stamp.$EXTENSION"
        }
    }

    sealed class BackupResult {
        data class Success(val file: File, val cloneCount: Int, val bytes: Long) : BackupResult()
        data class Failure(val reason: String) : BackupResult()
    }

    sealed class RestoreResult {
        data class Success(
            val addedSpaces: Int,
            val addedClones: Int,
            val skippedDuplicates: Int,
            val replacedExisting: Boolean,
            val safetyCopy: File?
        ) : RestoreResult()

        data class Failure(val reason: String) : RestoreResult()
    }

    /** Backs up every space and clone. */
    fun backupAll(target: File): BackupResult = write(target, spaceManager.exportModel())

    /** Backs up one space. */
    fun backupSpace(spaceId: Int, target: File): BackupResult {
        val space = spaceManager.getSpace(spaceId)
            ?: return BackupResult.Failure("Space $spaceId does not exist")
        return write(target, spaceManager.exportModel().copy(spaces = listOf(space)))
    }

    /** Backs up one clone. */
    fun backupClone(spaceId: Int, packageName: String, target: File): BackupResult {
        val space = spaceManager.getSpace(spaceId)
            ?: return BackupResult.Failure("Space $spaceId does not exist")
        val app = space.apps.find { it.packageName == packageName }
            ?: return BackupResult.Failure("$packageName is not cloned into Space $spaceId")
        val single = Space(spaceId = spaceId, spaceName = space.spaceName, apps = mutableListOf(app))
        return write(target, spaceManager.exportModel().copy(spaces = listOf(single)))
    }

    /**
     * Restores a backup. [replaceExisting] = false merges (nothing is removed); `true` replaces the
     * model and first writes a safety copy of the current state next to [source].
     */
    fun restore(source: File, replaceExisting: Boolean): RestoreResult {
        if (!source.isFile) return RestoreResult.Failure("${source.name} was not found")
        val text = try {
            source.readText(Charsets.UTF_8)
        } catch (error: Exception) {
            return RestoreResult.Failure("The backup file could not be read: ${error.message}")
        }
        val parsed = when (val result = BackupDocument.decode(text)) {
            is BackupDocument.ParseResult.Rejected -> return RestoreResult.Failure(result.reason)
            is BackupDocument.ParseResult.Valid -> result
        }
        val clones = parsed.model.spaces.sumOf { it.apps.size }
        if (clones > SpaceManager.MAX_CLONE_SLOTS) {
            return RestoreResult.Failure(
                "This backup holds $clones clones; the runtime supports ${SpaceManager.MAX_CLONE_SLOTS}"
            )
        }

        var safetyCopy: File? = null
        if (replaceExisting) {
            val directory = source.parentFile ?: defaultDirectory()
            val safety = File(directory, suggestedFileName("pre-restore-safety"))
            when (val written = write(safety, spaceManager.exportModel())) {
                is BackupResult.Success -> safetyCopy = written.file
                is BackupResult.Failure -> return RestoreResult.Failure(
                    "Could not write a safety copy before replacing, so nothing was changed " +
                        "(${written.reason})"
                )
            }
        }

        val outcome = spaceManager.importModel(parsed.model, replaceExisting)
        return when (outcome) {
            is SpaceManager.ImportOutcome.Failure -> RestoreResult.Failure(outcome.reason)
            is SpaceManager.ImportOutcome.Success -> RestoreResult.Success(
                addedSpaces = outcome.addedSpaces,
                addedClones = outcome.addedClones,
                skippedDuplicates = outcome.skippedDuplicates,
                replacedExisting = replaceExisting,
                safetyCopy = safetyCopy
            )
        }
    }

    /** Inspects a backup without touching the current model (used by the restore preview). */
    fun inspect(source: File): BackupDocument.ParseResult = try {
        BackupDocument.decode(source.readText(Charsets.UTF_8))
    } catch (error: Exception) {
        BackupDocument.ParseResult.Rejected("The backup file could not be read: ${error.message}")
    }

    fun inspectModel(source: File): CloneModel? =
        (inspect(source) as? BackupDocument.ParseResult.Valid)?.model

    /** Deletes leftovers of interrupted backups so they can never be restored by accident. */
    @Suppress("unused")
    fun cleanInterruptedFiles(directory: File): Int {
        val partials = directory.listFiles { file -> file.isFile && file.name.endsWith(".part") } ?: return 0
        var removed = 0
        partials.forEach { if (it.delete()) removed++ }
        if (removed > 0) Log.i(TAG, "Removed $removed interrupted backup file(s)")
        return removed
    }

    private fun write(target: File, model: CloneModel): BackupResult {
        val text = BackupDocument.encode(
            model = model,
            versionName = versionName(),
            versionCode = versionCode(),
            abi = Build.SUPPORTED_ABIS.joinToString(",")
        )
        val bytes = text.toByteArray(Charsets.UTF_8)
        val parent = target.parentFile ?: return BackupResult.Failure("Invalid backup location")
        if (!parent.exists() && !parent.mkdirs()) {
            return BackupResult.Failure("Cannot create ${parent.path}")
        }
        val free = parent.usableSpace
        if (free in 1 until bytes.size * 2L) {
            return BackupResult.Failure("Not enough free space for a ${bytes.size}-byte backup")
        }

        val part = File(parent, target.name + ".part")
        return try {
            FileOutputStream(part).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            // Prove the file we just wrote decodes before publishing it as a backup.
            val selfCheck = BackupDocument.decode(part.readText(Charsets.UTF_8))
            if (selfCheck is BackupDocument.ParseResult.Rejected) {
                part.delete()
                return BackupResult.Failure("Self-check of the written backup failed: ${selfCheck.reason}")
            }
            if (target.exists() && !target.delete()) {
                part.delete()
                return BackupResult.Failure("Cannot overwrite ${target.name}")
            }
            if (!part.renameTo(target)) {
                part.delete()
                return BackupResult.Failure("Cannot move the backup into place")
            }
            val clones = model.spaces.sumOf { it.apps.size }
            Log.i(TAG, "Backup written: ${target.name}, clones=$clones, bytes=${bytes.size}")
            BackupResult.Success(target, clones, bytes.size.toLong())
        } catch (error: Exception) {
            part.delete()
            Log.e(TAG, "Backup failed", error)
            BackupResult.Failure(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun versionName(): String? = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (error: Exception) {
        null
    }

    private fun versionCode(): Long = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    } catch (error: Exception) {
        0L
    }
}
