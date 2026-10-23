package com.clonehub.advanced.core.fs

import android.content.Context
import java.io.File

/**
 * Advanced Virtual File System Redirector.
 * Safely redirects standard data paths to isolated virtual space directories,
 * preventing any data collision between Space 1, Space 2, Space 3 and Host OS.
 */
class FileSystemRedirector(private val context: Context) {

    /**
     * Resolves the isolated private storage directory for a cloned package in a specific space.
     * Path: /data/data/com.clonehub.advanced/files/spaces/{spaceId}/data/{packageName}
     */
    fun resolveDataDir(spaceId: Int, packageName: String): File {
        val dir = File(context.filesDir, "spaces/$spaceId/data/$packageName").apply {
            if (!exists()) mkdirs()
        }
        // Subdirectories: shared_prefs, databases, files, cache, code_cache
        File(dir, "shared_prefs").mkdirs()
        File(dir, "databases").mkdirs()
        File(dir, "files").mkdirs()
        File(dir, "cache").mkdirs()
        return dir
    }

    /**
     * Resolves the isolated external storage directory (Android/data/{pkg}).
     */
    fun resolveExternalDataDir(spaceId: Int, packageName: String): File {
        val extBase = context.getExternalFilesDir(null) ?: context.filesDir
        return File(extBase, "virtual_sdcard/spaces/$spaceId/Android/data/$packageName").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Completely wipes an app's isolated space directory (Clean Storage action).
     */
    fun wipeAppData(spaceId: Int, packageName: String): Boolean {
        val internalDir = File(context.filesDir, "spaces/$spaceId/data/$packageName")
        val externalDir = resolveExternalDataDir(spaceId, packageName)
        val internalDeleted = if (internalDir.exists()) internalDir.deleteRecursively() else true
        val externalDeleted = if (externalDir.exists()) externalDir.deleteRecursively() else true
        return internalDeleted && externalDeleted
    }

    /**
     * Calculates storage size consumed by a cloned app.
     */
    fun calculateAppStorageSize(spaceId: Int, packageName: String): Long {
        val dir = File(context.filesDir, "spaces/$spaceId/data/$packageName")
        return calculateDirSize(dir)
    }

    private fun calculateDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { file ->
            if (file.isFile) total += file.length()
        }
        return total
    }
}
