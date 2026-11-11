package com.clonehub.advanced.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import com.clonehub.advanced.R
import java.io.File

/**
 * Small LRU cache for clone icons.
 *
 * Previously every bind decoded the custom icon bitmap and asked PackageManager for the app icon
 * on the UI thread; a 4-column grid of clones did that on every adapter refresh.
 */
object IconLoader {

    private val cache = object : LruCache<String, Drawable>(64) {}

    fun load(context: Context, packageName: String, customIconPath: String?): Drawable {
        val key = "$packageName|${customIconPath.orEmpty()}"
        cache.get(key)?.let { return it }
        val drawable = decodeCustom(context, customIconPath)
            ?: runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
            ?: context.getDrawable(R.drawable.ic_action_install)!!
        cache.put(key, drawable)
        return drawable
    }

    fun evict(packageName: String) {
        cache.snapshot().keys.filter { it.startsWith("$packageName|") }.forEach { cache.remove(it) }
    }

    private fun decodeCustom(context: Context, path: String?): Drawable? {
        if (path.isNullOrEmpty()) return null
        val file = File(path)
        if (!file.exists()) return null
        val bitmap: Bitmap = BitmapFactory.decodeFile(path) ?: return null
        return BitmapDrawable(context.resources, bitmap)
    }
}
