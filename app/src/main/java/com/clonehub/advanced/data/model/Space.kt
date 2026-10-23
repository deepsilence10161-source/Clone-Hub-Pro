package com.clonehub.advanced.data.model

/**
 * Represents an isolated virtual space (Space 1, Space 2, Space 3...).
 */
data class Space(
    val spaceId: Int,
    val spaceName: String = "Space $spaceId",
    val apps: MutableList<ClonedApp> = mutableListOf()
)
