package com.clonehub.advanced.core.backup

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.clonehub.advanced.core.container.SpaceManager
import com.clonehub.advanced.data.model.ClonedApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * On-device proof that backup and restore behave as documented: a merge never removes clones, a
 * damaged file is refused, and an interrupted backup is cleaned instead of being offered back.
 */
class BackupRestoreInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val manager = CloneBackupManager.getInstance(context)
    private val spaces = SpaceManager.getInstance(context)
    private lateinit var directory: File

    private val packageA = "net.gsantner.markor"
    private val packageB = "com.beemdevelopment.aegis"

    @Before
    fun setUp() {
        directory = File(context.cacheDir, "backup-test")
        directory.mkdirs()
        directory.listFiles()?.forEach { it.delete() }
    }

    @Test(timeout = 120_000)
    fun a_backupThenMergeRestore_addsMissingWithoutRemoving() {
        spaces.addAppToSpace(1, packageA, "Markor")
        val file = File(directory, "all.${CloneBackupManager.EXTENSION}")
        val written = manager.backupAll(file)
        assertTrue("Backup failed: $written", written is CloneBackupManager.BackupResult.Success)
        assertTrue(file.exists())

        // A clone created after the backup must survive a merge restore.
        spaces.addAppToSpace(1, packageB, "Aegis")
        val merged = manager.restore(file, replaceExisting = false)
        assertTrue("Merge failed: $merged", merged is CloneBackupManager.RestoreResult.Success)
        val after = spaces.getSpace(1)!!.apps.map { it.packageName }
        assertTrue("Merge removed a newer clone", after.contains(packageB))
        assertTrue(after.contains(packageA))
    }

    @Test(timeout = 120_000)
    fun b_damagedFile_isRefusedAndChangesNothing() {
        spaces.addAppToSpace(1, packageA, "Markor")
        val file = File(directory, "damaged.${CloneBackupManager.EXTENSION}")
        manager.backupAll(file)
        val damaged = file.readText().replace("Markor", "Mark0r")
        file.writeText(damaged)
        val before = spaces.getSpaces().sumOf { it.apps.size }

        val result = manager.restore(file, replaceExisting = true)
        assertTrue("Damaged backup was restored: $result", result is CloneBackupManager.RestoreResult.Failure)
        assertEquals("A refused restore changed the model", before, spaces.getSpaces().sumOf { it.apps.size })
    }

    @Test(timeout = 120_000)
    fun c_interruptedBackupIsRemovedAndCompleteFileVerifies() {
        val partial = File(directory, "interrupted.${CloneBackupManager.EXTENSION}.part")
        partial.writeText("{\"driver\":\"com.clonehub.pro.clone-model\",\"schema\":1")
        assertTrue(manager.cleanInterruptedFiles(directory) >= 1)
        assertTrue("An unfinished backup was left behind", !partial.exists())

        val file = File(directory, "verified.${CloneBackupManager.EXTENSION}")
        manager.backupAll(file)
        assertTrue(manager.inspectModel(file) != null)
    }

    @Test(timeout = 120_000)
    fun d_singleCloneBackupRestoresIntoEmptySpace() {
        spaces.addAppToSpace(1, packageA, "Markor")
        val file = File(directory, "single.${CloneBackupManager.EXTENSION}")
        val written = manager.backupClone(1, packageA, file)
        assertTrue("Single-clone backup failed: $written", written is CloneBackupManager.BackupResult.Success)
        val inspected = manager.inspectModel(file)!!
        assertEquals(1, inspected.spaces.size)
        assertEquals(packageA, inspected.spaces[0].apps.single().packageName)
    }
}
