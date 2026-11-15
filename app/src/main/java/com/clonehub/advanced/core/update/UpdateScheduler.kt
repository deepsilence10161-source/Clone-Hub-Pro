package com.clonehub.advanced.core.update

import android.content.Context
import android.util.Log

/**
 * The update check the app runs by itself, and the rules for when it may speak.
 *
 * YMusic-style behaviour, stated as rules rather than intentions: check in the background, but only
 * when the answer is worth a Notification or a dialog, and never more often than the user's patience.
 *
 *  - at most one **network** check per [MIN_INTERVAL_MS] (6 h), so an app opened ten times a day does
 *    not hit GitHub ten times;
 *  - the result is announced **once per versionCode**: a user who says "later" is not asked again
 *    until a newer build exists;
 *  - a check never installs anything by itself. It can put a verified APK on disk and offer it;
 *  - every decision is logged with its numbers ([LOG_TAG]), because "the updater did nothing" is a
 *    bug report that needs a reason attached.
 */
object UpdateScheduler {

    const val LOG_TAG = "UpdateScheduler"
    const val MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private const val PREFS = "update_state"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_ANNOUNCED_CODE = "announced_version_code"
    private const val KEY_LAST_RESULT = "last_result"

    data class CheckOutcome(
        val info: UpdateInfo?,
        val error: String?,
        /** True when the user has not been told about this versionCode yet. */
        val shouldAnnounce: Boolean,
        val skippedBecause: String?
    )

    fun shouldCheckNow(context: Context, force: Boolean): Boolean {
        if (force) return true
        val last = prefs(context).getLong(KEY_LAST_CHECK, 0L)
        val elapsed = System.currentTimeMillis() - last
        val due = elapsed >= MIN_INTERVAL_MS
        Log.i(LOG_TAG, "UPDATE_SCHEDULE last_check_age_ms=$elapsed due=$due interval_ms=$MIN_INTERVAL_MS")
        return due
    }

    /**
     * One check. Blocking; call it from a background dispatcher.
     *
     * @param force true when the user pressed the button — then the interval is ignored but the
     *   "announce once" rule still holds, so pressing twice does not produce two dialogs.
     */
    fun check(context: Context, force: Boolean): CheckOutcome {
        if (!shouldCheckNow(context, force)) {
            return CheckOutcome(null, null, false, "checked recently")
        }
        val result = UpdateChecker.fetchLatest(context)
        prefs(context).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

        result.onFailure { error ->
            // A failed check is not a failed app: recorded, logged, and not shown as a scary dialog.
            val message = "${error.javaClass.simpleName}: ${error.message}"
            prefs(context).edit().putString(KEY_LAST_RESULT, "error $message").apply()
            Log.w(LOG_TAG, "UPDATE_CHECK failed=$message")
        }
        val info = result.getOrNull() ?: return CheckOutcome(null, result.exceptionOrNull()?.message, false, null)

        val announced = prefs(context).getInt(KEY_ANNOUNCED_CODE, -1)
        val offerable = info.blockedReason == null
        val shouldAnnounce = offerable && info.versionCode != null && info.versionCode != announced
        prefs(context).edit()
            .putString(
                KEY_LAST_RESULT,
                "installed=${info.installedVersionName}(${info.installedVersionCode}) " +
                    "offered=${info.version}(${info.versionCode}) newer=${info.isNewer} " +
                    "blocked=${info.blockedReason ?: "no"} source=${info.source}"
            )
            .apply()
        Log.i(
            LOG_TAG,
            "UPDATE_CHECK installed=${info.installedVersionName}(${info.installedVersionCode}) " +
                "offered=${info.version}(${info.versionCode}) newer=${info.isNewer} " +
                "blocked=${info.blockedReason ?: "<none>"} apk=${info.apkUrl ?: "<none>"} " +
                "sha256=${info.sha256 ?: "<not published>"} announce=$shouldAnnounce"
        )
        return CheckOutcome(info, null, shouldAnnounce, null)
    }

    fun markAnnounced(context: Context, versionCode: Int) {
        prefs(context).edit().putInt(KEY_ANNOUNCED_CODE, versionCode).apply()
        Log.i(LOG_TAG, "UPDATE_ANNOUNCED versionCode=$versionCode")
    }

    fun lastResult(context: Context): String? = prefs(context).getString(KEY_LAST_RESULT, null)

    fun lastCheckAt(context: Context): Long = prefs(context).getLong(KEY_LAST_CHECK, 0L)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
