package com.clonehub.advanced.data.model

import android.graphics.drawable.Drawable

/**
 * Represents an application cloned into a specific isolated space.
 *
 * New fields must carry defaults so previously persisted models keep loading.
 */
data class ClonedApp(
    val packageName: String,
    var appName: String,
    val originalName: String,
    val spaceId: Int,
    var customIconPath: String? = null,
    val installTime: Long = System.currentTimeMillis(),
    var launchCount: Int = 0,
    /** Wall-clock time of the most recent launch attempt, or 0 when never launched. */
    var lastLaunchAt: Long = 0L,
    /** Result of the most recent launch attempt; null until the first launch. */
    var lastLaunchSucceeded: Boolean? = null,
    /** Human-readable reason for the most recent failure, if any. */
    var lastFailureReason: String? = null,
    @Transient var iconDrawable: Drawable? = null
)
