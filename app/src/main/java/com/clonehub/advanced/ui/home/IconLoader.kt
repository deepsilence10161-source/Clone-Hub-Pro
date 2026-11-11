package com.clonehub.advanced.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.clonehub.advanced.R
import java.io.File
import java.util.concurrent.Executors

/**
 * Clone icon cache and decoder.
 *
 * Icons used to be resolved synchronously inside `onBindViewHolder`: a custom icon was decoded from
 * disk and, for every other clone, `PackageManager.getApplicationIcon` ran on the UI thread. On a
 * grid refresh that blocked the frame for every clone on screen.
 *
 * Now the cache serves the common case synchronously (no flicker for already-loaded icons) and
 * anything else is resolved on a single background worker, then delivered to the main thread.
 * A request that is no longer valid is dropped, so a recycled view never shows another clone's icon.
 */
object IconLoader {

    private val cache = object : LruCache<String, Drawable>(64) {}

    /** One worker keeps decode order predictable and avoids a thread burst on a fast scroll. */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CloneIconLoader").apply { priority = Thread.MIN_PRIORITY }
    }
    private val main = Handler(Looper.getMainLooper())

    private fun key(packageName: String, customIconPath: String?): String =
        "$packageName|${customIconPath.orEmpty()}"

    /**
     * Returns the cached icon when it is already available, otherwise [placeholder] immediately and
     * the real icon later through [onLoaded].
     */
    fun load(
        context: Context,
        packageName: String,
        customIconPath: String?,
        onLoaded: (Drawable) -> Unit
    ): Drawable {
        val cacheKey = key(packageName, customIconPath)
        val placeholder = placeholder(context)
        cache.get(cacheKey)?.let { return it }
        val appContext = context.applicationContext
        worker.execute {
            val resolved = decodeCustom(appContext, customIconPath)
                ?: runCatching { appContext.packageManager.getApplicationIcon(packageName) }.getOrNull()
            if (resolved == null) return@execute
            cache.put(cacheKey, resolved)
            main.post { onLoaded(resolved) }
        }
        return placeholder
    }

    /** Synchronous variant, kept for callers that are already off the main thread. */
    fun loadBlocking(context: Context, packageName: String, customIconPath: String?): Drawable {
        val cacheKey = key(packageName, customIconPath)
        cache.get(cacheKey)?.let { return it }
        val resolved = decodeCustom(context, customIconPath)
            ?: runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
            ?: placeholder(context)
        cache.put(cacheKey, resolved)
        return resolved
    }

    fun evict(packageName: String) {
        cache.snapshot().keys.filter { it.startsWith("$packageName|") }.forEach { cache.remove(it) }
    }

    private fun placeholder(context: Context): Drawable =
        context.getDrawable(R.drawable.ic_action_install)!!

    private fun decodeCustom(context: Context, path: String?): Drawable? {
        if (path.isNullOrEmpty()) return null
        val file = File(path)
        if (!file.exists()) return null
        val bitmap: Bitmap = BitmapFactory.decodeFile(path) ?: return null
        return BitmapDrawable(context.resources, bitmap)
    }
}
