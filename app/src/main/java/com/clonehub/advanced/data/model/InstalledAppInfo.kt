package com.clonehub.advanced.data.model

import android.graphics.drawable.Drawable

/**
 * Represents an installed host app available for cloning.
 */
data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val isSystemApp: Boolean,
    var icon: Drawable? = null
)
