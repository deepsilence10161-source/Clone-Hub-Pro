package com.clonehub.advanced.core.update

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a verified APK to Android's installer, and records what the platform did with it.
 *
 * The app never installs anything itself: [UpdateChecker] downloads and checks the bytes, and this
 * class only builds the intent Android documents. The user still confirms in the system dialog, and
 * Android still refuses the install if the APK is signed with another key.
 *
 * ## Why there are two paths and a recorded verdict
 *
 * Android 8–13 accept the documented intent with a `content://` URI. Android 14 added a rule that
 * refuses an APK delivered by an intent **URL** rather than a file path:
 * `INSTALL_FAILED_INVALID_APK: … requested install of an APK with an intent-URL path`. Which rule
 * applies to *this* device is not something this code can assume — the gates run on API 27…35 — so
 * the first attempt is treated as the experiment and its verdict is stored:
 *
 *  - the platform's own installer reports success or a named failure in logcat;
 *  - [InstallOutcomeWatcher] reads that log after the attempt and stores [UpdatePath.INTENT_ACCEPTED]
 *    or [UpdatePath.INTENT_REFUSED_FOR_URLS];
 *  - on the refusal path the panel stops offering the intent and instead shows the verified file's
 *    location with a button to open it, which is the one path every level accepts.
 *
 * The stored verdict is written to the log with the numbers it was derived from, so a future reader
 * can check it instead of trusting it.
 */
object UpdateInstaller {

    private const val TAG = "UpdateInstaller"
    private const val PREFS = "update_state"
    private const val KEY_PATH = "install_path"
    private const val KEY_PATH_EVIDENCE = "install_path_evidence"
    private const val KEY_LAST_ATTEMPT = "last_attempt_ms"

    /** `FileProvider` authority, declared in the manifest as `${applicationId}.updates`. */
    fun authority(context: Context): String = "${context.packageName}.updates"

    fun apkUri(context: Context, file: File) =
        FileProvider.getUriForFile(context, authority(context), file)

    /**
     * Starts Android's installer for [file] **when the measured path allows it**.
     *
     * Returns false when the stored verdict says the platform refuses intent URLs, so the caller does
     * not fire an intent that is going to fail: the UI shows the file instead.
     */
    fun tryInstall(context: Context, file: File): Boolean {
        if (recordedPath(context) == UpdatePath.INTENT_REFUSED_FOR_URLS) {
            Log.i(
                TAG,
                "UPDATE_INSTALL skipped=refused_path evidence=${evidence(context)} " +
                    "file=${file.absolutePath}"
            )
            return false
        }
        if (Build.VERSION.SDK_INT >= 26 && !UpdateChecker.canRequestInstalls(context)) {
            Log.i(TAG, "UPDATE_INSTALL skipped=no_permission package=${context.packageName}")
            return false
        }
        val uri = apkUri(context, file)
        context.startActivity(UpdateChecker.installerIntent(uri))
        markAttempt(context)
        Log.i(
            TAG,
            "UPDATE_INSTALL started sdk=${Build.VERSION.SDK_INT} file=${file.name} " +
                "bytes=${file.length()} uri=$uri path=${recordedPath(context)}"
        )
        return true
    }

    fun recordedPath(context: Context): UpdatePath = runCatching {
        UpdatePath.valueOf(
            prefs(context).getString(KEY_PATH, UpdatePath.UNKNOWN.name) ?: UpdatePath.UNKNOWN.name
        )
    }.getOrDefault(UpdatePath.UNKNOWN)

    fun evidence(context: Context): String? = prefs(context).getString(KEY_PATH_EVIDENCE, null)

    private fun markAttempt(context: Context) {
        prefs(context).edit()
            .putLong(KEY_LAST_ATTEMPT, System.currentTimeMillis())
            .apply()
    }

    fun lastAttemptAt(context: Context): Long = prefs(context).getLong(KEY_LAST_ATTEMPT, 0L)

    /**
     * Records the platform's verdict, once, from logcat.
     *
     * Called after an attempt. Two platform behaviours are looked for, in the platform's own words:
     *  - `INSTALL_FAILED_INVALID_APK` together with `intent-URL path` → [UpdatePath.INTENT_REFUSED_FOR_URLS];
     *  - any `PackageInstaller`/`PackageManager` success line for this package → [UpdatePath.INTENT_ACCEPTED].
     *
     * Anything else leaves the verdict untouched and says so: an unread log is not evidence.
     */
    fun recordVerdictFromLog(context: Context, logcat: String): UpdatePath {
        val refused = logcat.lineSequence().any { line ->
            line.contains("INSTALL_FAILED_INVALID_APK") && line.contains("intent-URL", ignoreCase = true)
        }
        val accepted = logcat.lineSequence().any { line ->
            (line.contains("PackageInstaller") || line.contains("PackageManager")) &&
                line.contains(context.packageName) &&
                (line.contains("INSTALL_SUCCEEDED") || line.contains("installPackageLI") &&
                    line.contains("Success"))
        }
        val verdict = when {
            refused -> UpdatePath.INTENT_REFUSED_FOR_URLS
            accepted -> UpdatePath.INTENT_ACCEPTED
            else -> UpdatePath.UNKNOWN
        }
        if (verdict != UpdatePath.UNKNOWN) {
            val evidence = if (refused) {
                "INSTALL_FAILED_INVALID_APK with an intent-URL path (Android 14 rule)"
            } else {
                "the installer reported success for ${context.packageName}"
            }
            prefs(context).edit()
                .putString(KEY_PATH, verdict.name)
                .putString(KEY_PATH_EVIDENCE, evidence)
                .apply()
            Log.i(TAG, "UPDATE_INSTALL verdict=$verdict evidence=$evidence")
        } else {
            Log.i(TAG, "UPDATE_INSTALL verdict=UNKNOWN (the log named neither outcome)")
        }
        return verdict
    }

    /** The panel's one-line answer to "will pressing Install work on this phone?". */
    fun describePath(context: Context): String = when (recordedPath(context)) {
        UpdatePath.INTENT_ACCEPTED -> "Installer intent accepted on this device (measured)."
        UpdatePath.INTENT_REFUSED_FOR_URLS ->
            "This Android version refuses APK installs from inside an app (measured: " +
                "INSTALL_FAILED_INVALID_APK, intent-URL path). The APK is verified and saved; open it " +
                "from the location below and Android's package installer takes it."
        UpdatePath.UNKNOWN ->
            "Not measured yet on this device. The first Install attempt is the experiment and its " +
                "result is recorded here."
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
