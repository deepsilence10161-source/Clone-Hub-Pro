package com.clonehub.advanced.ui.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Cheap, deterministic screenshot check.
 *
 * A clone can pass every accessibility-marker assertion and still paint nothing (the classic
 * "black window" clone bug). Screenshots were only ever archived, never inspected, so this turns
 * them into a real assertion: a rendered screen must not be a uniform block of pixels, and a cloned
 * screen is compared against the original app's own screenshot.
 */
object VisualEvidence {

    data class Stats(val standardDeviation: Double, val meanLuma: Double, val samples: Int) {
        /** A uniform (blank/black) frame has a near-zero deviation. */
        val isBlank: Boolean get() = standardDeviation < BLANK_STDDEV

        fun describe(): String =
            "stddev=%.1f mean=%.1f samples=%d".format(standardDeviation, meanLuma, samples)
    }

    private const val BLANK_STDDEV = 4.0

    fun analyse(file: File): Stats? {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val scaled = Bitmap.createScaledBitmap(bitmap, GRID, GRID, true)
        val values = IntArray(GRID * GRID)
        scaled.getPixels(values, 0, GRID, 0, 0, GRID, GRID)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()

        var sum = 0.0
        for (pixel in values) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            sum += 0.299 * r + 0.587 * g + 0.114 * b
        }
        val mean = sum / values.size
        var variance = 0.0
        for (pixel in values) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val delta = (0.299 * r + 0.587 * g + 0.114 * b) - mean
            variance += delta * delta
        }
        return Stats(kotlin.math.sqrt(variance / values.size), mean, values.size)
    }

    /** Mean absolute difference of the two downscaled frames, or null when either is unreadable. */
    fun frameDifference(first: File, second: File): Double? {
        val a = pixels(first) ?: return null
        val b = pixels(second) ?: return null
        var total = 0.0
        for (i in a.indices) total += kotlin.math.abs(a[i] - b[i])
        return total / a.size
    }

    private fun pixels(file: File): DoubleArray? {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val scaled = Bitmap.createScaledBitmap(bitmap, GRID, GRID, true)
        val values = IntArray(GRID * GRID)
        scaled.getPixels(values, 0, GRID, 0, 0, GRID, GRID)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return DoubleArray(values.size) { i ->
            val pixel = values[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            0.299 * r + 0.587 * g + 0.114 * b
        }
    }

    private const val GRID = 48
}
