package com.clonehub.advanced.fixture

import android.app.Activity
import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.PixelCopy
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ordinary third-party fixture used to prove the clone flow. On a physical phone it
 * captures its own visible window without ADB, root, or screen-recording consent.
 */
class CloneFixtureActivity : Activity() {
    private lateinit var captureStatus: TextView
    private var captureStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply {
            text = "CLONE_FIXTURE_RUNNING"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val processName = if (Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            "unavailable"
        }
        val details = TextView(this).apply {
            text = buildString {
                append("package=$packageName\n")
                append("process=$processName\n")
                append("pid=${android.os.Process.myPid()} uid=${android.os.Process.myUid()}\n")
                append("Android=${Build.VERSION.RELEASE} SDK=${Build.VERSION.SDK_INT}\n")
                append("time=${timestamp()}")
            }
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(24, 28, 24, 12)
        }
        captureStatus = TextView(this).apply {
            text = "Automatic screenshot: waiting for visible window…"
            textSize = 15f
            setTextColor(Color.rgb(220, 255, 225))
            gravity = Gravity.CENTER
            setPadding(24, 20, 24, 12)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(20, 80, 55))
            addView(status)
            addView(details)
            addView(captureStatus)
        })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !captureStarted) {
            captureStarted = true
            window.decorView.postDelayed({ captureOwnWindow() }, 1000L)
        }
    }

    private fun captureOwnWindow() {
        val view = window.decorView
        if (view.width <= 0 || view.height <= 0) {
            view.postDelayed({ captureOwnWindow() }, 500L)
            return
        }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        if (Build.VERSION.SDK_INT >= 26) {
            PixelCopy.request(
                window,
                bitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        saveEvidence(bitmap)
                    } else {
                        saveEvidence(drawFallback(view))
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } else {
            saveEvidence(drawFallback(view))
        }
    }

    private fun drawFallback(view: android.view.View): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun saveEvidence(bitmap: Bitmap) {
        captureStatus.text = "Automatic screenshot: saving to Pictures/CloneHubEvidence…"
        Thread {
            val result = runCatching { saveToGallery(bitmap) }.recoverCatching {
                saveToAppPictures(bitmap)
            }
            runOnUiThread {
                captureStatus.text = result.fold(
                    onSuccess = { "SCREENSHOT SAVED\n$it" },
                    onFailure = { "SCREENSHOT SAVE FAILED\n${it.javaClass.simpleName}: ${it.message}" }
                )
                Toast.makeText(this, captureStatus.text, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun saveToGallery(bitmap: Bitmap): String {
        if (Build.VERSION.SDK_INT < 29) throw IllegalStateException("MediaStore relative path needs API 29")
        val name = "clone-fixture-${fileTimestamp()}.png"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/CloneHubEvidence")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = contentResolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert returned null")
        try {
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "PNG compression failed"
                }
            } ?: throw IllegalStateException("Cannot open screenshot output")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return "Gallery → Pictures/CloneHubEvidence/$name"
        } catch (error: Throwable) {
            contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private fun saveToAppPictures(bitmap: Bitmap): String {
        val directory = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "CloneHubEvidence")
            .apply { mkdirs() }
        val file = File(directory, "clone-fixture-${fileTimestamp()}.png")
        file.outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "PNG compression failed"
            }
        }
        return file.absolutePath
    }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
        .format(Date())

    private fun fileTimestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
        .format(Date())
}
