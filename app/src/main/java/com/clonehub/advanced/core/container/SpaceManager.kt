package com.clonehub.advanced.core.container

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.clonehub.advanced.data.model.CloneModel
import com.clonehub.advanced.data.model.ClonedApp
import com.clonehub.advanced.data.model.Space
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Isolated Space & Sandbox manager for the clone model.
 *
 * This class owns the *host-side* record of what the user cloned. The virtual runtime owns the
 * clone's real data; this class never claims to have deleted runtime data it cannot delete.
 */
class SpaceManager private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("clone_spaces_db", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val spaces = mutableListOf<Space>()

    init {
        loadSpaces()
    }

    companion object {
        private const val TAG = "SpaceManager"

        /** The bundled runtime exposes one agent slot per process; the manifest declares 300. */
        const val MAX_CLONE_SLOTS = 300

        @Volatile
        private var INSTANCE: SpaceManager? = null

        fun getInstance(context: Context): SpaceManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SpaceManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    @Synchronized
    fun getSpaces(): List<Space> = spaces.map { it.copy(apps = it.apps.toMutableList()) }

    @Synchronized
    fun getSpace(spaceId: Int): Space? = spaces.find { it.spaceId == spaceId }

    @Synchronized
    fun totalClonedApps(): Int = spaces.sumOf { it.apps.size }

    @Synchronized
    fun canAddClone(): Boolean = totalClonedApps() < MAX_CLONE_SLOTS

    @Synchronized
    fun createNextSpace(): Space? {
        if (spaces.size >= MAX_CLONE_SLOTS) {
            Log.w(TAG, "Refusing to create a space beyond $MAX_CLONE_SLOTS")
            return null
        }
        val nextId = if (spaces.isEmpty()) 1 else spaces.maxOf { it.spaceId } + 1
        val newSpace = Space(spaceId = nextId, spaceName = "Space $nextId")
        spaces.add(newSpace)
        saveSpaces()
        return newSpace
    }

    /**
     * The space that follows [spaceId] in display order, or null when it is the last one.
     * Previous behaviour used `spaceId + 1`, which skipped spaces after a deletion and could
     * clone into the wrong space.
     */
    @Synchronized
    fun getNextSpaceAfter(spaceId: Int): Space? {
        val ordered = spaces.sortedBy { it.spaceId }
        val index = ordered.indexOfFirst { it.spaceId == spaceId }
        if (index < 0) return ordered.firstOrNull()
        return ordered.getOrNull(index + 1)
    }

    /** Renames a space. Space itself is immutable so the stored model is replaced atomically. */
    @Synchronized
    fun renameSpace(spaceId: Int, name: String): Boolean {
        val index = spaces.indexOfFirst { it.spaceId == spaceId }
        if (index < 0) return false
        val current = spaces[index]
        spaces[index] = current.copy(spaceName = name.ifBlank { "Space $spaceId" })
        saveSpaces()
        return true
    }

    /** Removes an empty space. A space that still owns clones is never deleted. */
    @Synchronized
    fun removeSpace(spaceId: Int): Boolean {
        val space = getSpace(spaceId) ?: return false
        if (space.apps.isNotEmpty() || spaces.size <= 1) return false
        spaces.remove(space)
        saveSpaces()
        return true
    }

    @Synchronized
    fun addAppToSpace(spaceId: Int, packageName: String, appName: String): ClonedApp? {
        var space = getSpace(spaceId)
        if (space == null) {
            if (!canAddClone()) return null
            space = Space(spaceId, "Space $spaceId")
            spaces.add(space)
        }

        val existing = space.apps.find { it.packageName == packageName }
        if (existing != null) return existing
        if (!canAddClone()) {
            Log.w(TAG, "Clone slot limit $MAX_CLONE_SLOTS reached")
            return null
        }

        val clonedApp = ClonedApp(
            packageName = packageName,
            appName = appName,
            originalName = appName,
            spaceId = spaceId
        )
        space.apps.add(clonedApp)
        getSpaceDataDirectory(spaceId, packageName).mkdirs()
        saveSpaces()
        return clonedApp
    }

    /**
     * Removes the clone from the model. The virtual runtime keeps its own copy of the app data,
     * so this is a "remove from space" action, not an uninstall. Callers must present it that way.
     */
    @Synchronized
    fun removeAppFromSpace(spaceId: Int, packageName: String): Boolean {
        val space = getSpace(spaceId) ?: return false
        val removed = space.apps.removeAll { it.packageName == packageName }
        if (removed) {
            getSpaceDataDirectory(spaceId, packageName).deleteRecursively()
            saveSpaces()
        }
        return removed
    }

    /** Records the outcome of the last launch so the UI can show real clone health. */
    @Synchronized
    fun recordLaunch(spaceId: Int, packageName: String, succeeded: Boolean, reason: String?) {
        val app = getSpace(spaceId)?.apps?.find { it.packageName == packageName } ?: return
        app.lastLaunchAt = System.currentTimeMillis()
        app.lastLaunchSucceeded = succeeded
        app.lastFailureReason = if (succeeded) null else reason
        saveSpaces()
    }

    @Synchronized
    fun updateAppCustomization(
        spaceId: Int,
        packageName: String,
        newName: String,
        customIconPath: String?
    ) {
        val space = getSpace(spaceId) ?: return
        val app = space.apps.find { it.packageName == packageName } ?: return
        app.appName = newName
        app.customIconPath = customIconPath
        saveSpaces()
    }

    /** Full model snapshot for backup and diagnostics. */
    @Synchronized
    fun exportModel(): CloneModel = CloneModel(spaces = spaces.map { it.copy(apps = it.apps.toMutableList()) })

    /** Result of applying a backup. */
    sealed class ImportOutcome {
        data class Success(
            val addedSpaces: Int,
            val addedClones: Int,
            val skippedDuplicates: Int
        ) : ImportOutcome()

        data class Failure(val reason: String) : ImportOutcome() {
            fun toRestoreFailure(): String = reason
        }
    }

    /**
     * Applies a validated backup.
     *
     * Merge mode never removes an existing space or clone. Replace mode swaps the whole model and is
     * only reached after the caller has written a safety copy of the current model. The clone-slot
     * limit is enforced here too, so a backup cannot create more clones than the runtime can host.
     */
    @Synchronized
    fun importModel(model: CloneModel, replaceExisting: Boolean): ImportOutcome {
        if (model.spaces.isEmpty()) {
            return ImportOutcome.Failure("Backup contains no spaces")
        }
        val incomingClones = model.spaces.sumOf { it.apps.size }
        if (incomingClones > MAX_CLONE_SLOTS) {
            return ImportOutcome.Failure(
                "Backup holds $incomingClones clones; the runtime supports $MAX_CLONE_SLOTS"
            )
        }

        if (replaceExisting) {
            spaces.clear()
            model.spaces.sortedBy { it.spaceId }.forEach { spaces.add(it.copy(apps = it.apps.toMutableList())) }
            saveSpaces()
            Log.i(TAG, "Model replaced from backup: spaces=${spaces.size}, clones=$incomingClones")
            return ImportOutcome.Success(spaces.size, incomingClones, 0)
        }

        var addedSpaces = 0
        var addedClones = 0
        var skipped = 0
        model.spaces.sortedBy { it.spaceId }.forEach { incoming ->
            val existing = spaces.find { it.spaceId == incoming.spaceId }
            if (existing == null) {
                spaces.add(incoming.copy(apps = incoming.apps.toMutableList()))
                addedSpaces++
                addedClones += incoming.apps.size
                return@forEach
            }
            incoming.apps.forEach { app ->
                if (existing.apps.any { it.packageName == app.packageName } || !canAddClone()) {
                    skipped++
                } else {
                    existing.apps.add(app.copy(spaceId = existing.spaceId))
                    addedClones++
                }
            }
        }
        spaces.sortBy { it.spaceId }
        saveSpaces()
        Log.i(TAG, "Model merged from backup: +spaces=$addedSpaces +clones=$addedClones skipped=$skipped")
        return ImportOutcome.Success(addedSpaces, addedClones, skipped)
    }

    fun getSpaceDataDirectory(spaceId: Int, packageName: String): File {
        return File(context.filesDir, "spaces/$spaceId/data/$packageName")
    }

    @Synchronized
    private fun saveSpaces() {
        val json = gson.toJson(spaces)
        // Clone launches can move immediately into another process. Apply-only writes
        // can lose the model if that process is then force-stopped; make the durable
        // commit part of the mutation itself so cold restarts see the same model.
        val persisted = prefs.edit().putString("spaces_json", json).commit()
        if (!persisted) {
            Log.e(TAG, "Could not persist clone spaces")
        }
    }

    @Synchronized
    private fun loadSpaces() {
        spaces.clear()
        val json = prefs.getString("spaces_json", null)
        if (!json.isNullOrEmpty()) {
            val type = object : TypeToken<List<Space>>() {}.type
            val loaded: List<Space>? = runCatching { gson.fromJson<List<Space>>(json, type) }.getOrNull()
            if (!loaded.isNullOrEmpty()) {
                spaces.addAll(loaded)
            }
        }
        if (spaces.isEmpty()) {
            spaces.add(Space(spaceId = 1, spaceName = "Space 1"))
            saveSpaces()
        }
    }
}
