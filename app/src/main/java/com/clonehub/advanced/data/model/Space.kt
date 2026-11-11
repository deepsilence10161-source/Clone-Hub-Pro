package com.clonehub.advanced.data.model

/**
 * Represents an isolated virtual space (Space 1, Space 2, Space 3...).
 */
data class Space(
    val spaceId: Int,
    val spaceName: String = "Space $spaceId",
    val apps: MutableList<ClonedApp> = mutableListOf()
)

/** A snapshot of the whole model, used by backups and by diagnostics screens. */
data class CloneModel(
    val version: Int = MODEL_VERSION,
    val exportedAt: Long = System.currentTimeMillis(),
    val spaces: List<Space> = emptyList()
) {
    companion object {
        /**
         * Version of the on-disk model. Increment only for a breaking change and keep
         * [com.clonehub.advanced.core.container.SpaceManager] able to read older payloads.
         */
        const val MODEL_VERSION = 1
    }
}
