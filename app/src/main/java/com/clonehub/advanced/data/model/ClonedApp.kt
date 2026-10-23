package com.clonehub.advanced.data.model

import android.graphics.drawable.Drawable

/**
 * Represents an application cloned into a specific isolated space.
 */
data class ClonedApp(
    val packageName: String,
    var appName: String,
    val originalName: String,
    val spaceId: Int,
    var customIconPath: String? = null,
    val installTime: Long = System.currentTimeMillis(),
    var launchCount: Int = 0,
    @Transient var iconDrawable: Drawable? = null
)
