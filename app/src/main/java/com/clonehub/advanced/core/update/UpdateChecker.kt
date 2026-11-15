package com.clonehub.advanced.core.update

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * What the update server is allowed to say, and what the app believes.
 *
 * Nothing here is trusted: every field of an [UpdateInfo] is either checked against the running app
 * or reported verbatim next to the value it was compared with, so the panel can show both.
 */
data class UpdateInfo(
    /** The version the user would get, as the server words it. */
    val version: String,
    /** Android's integer version, when the server supplies one — that is the only real comparison. */
    val versionCode: Int?,
    /** Direct APK URL, or null when the server refused to offer one. */
    val apkUrl: String?,
    /** SHA-256 of the bytes to expect, lowercase hex, whenever the server publishes one. */
    val sha256: String?,
    /** Size in bytes when the server publishes one; used to show progress and to sanity-check. */
    val sizeBytes: Long?,
    /** Release notes as plain text, truncated by the caller's UI, not by the parser. */
    val notes: String?,
    /** Where this answer came from: `github` or `manifest`. Shown, never hidden. */
    val source: String,
    /** The running app's version, reported beside the server's so the two can be compared by eye. */
    val installedVersionName: String,
    val installedVersionCode: Int
) {
    /** True only when the server gave a versionCode and it is strictly greater. Never guessed. */
    val isNewer: Boolean get() = versionCode != null && versionCode > installedVersionCode

    /**
     * Why this update cannot be installed from inside the app, or null when it can.
     *
     * A version string alone is not enough to compare two builds, and comparing only `versionName`
     * strings ("2.0.10" < "2.0.9" as text) would offer a downgrade or hide an update. So a build
     * without a versionCode is reported as "cannot tell", not as "up to date".
     */
    val blockedReason: String?
        get() = when {
            versionCode == null -> "The server published no versionCode, so a newer build cannot be identified."
            !isNewer -> "This build (${installedVersionCode}) is not older than the offered one (${versionCode})."
            apkUrl == null -> "The release has no APK asset for this app."
            else -> null
        }
}

/**
 * Does the **installed** app accept an update from an intent-URL path (`content://…`) at all?
 *
 * This is measured once on a real Android 14 device and is the difference between an updater that
 * looks like it works and one that does. Android 14 refuses an intent that carries a URL to an APK
 * rather than a file path — the permission to install packages is not enough — with
 * `INSTALL_FAILED_INVALID_APK: … requested install of an APK with an intent-URL path`. The refusal is
 * detected by *who* refuses: the platform prints it, the app's own code does not.
 */
enum class UpdatePath {
    /** No measurement yet: the panel says so and the first attempt is recorded as the experiment. */
    UNKNOWN,

    /** The platform accepted the installer intent: the normal, silent-ish path. */
    INTENT_ACCEPTED,

    /** The platform refused a content:// intent: the app must point the user at a file instead. */
    INTENT_REFUSED_FOR_URLS
}

/**
 * Reads the update channel and decides what the app may honestly tell the user.
 *
 * The channel is this repository's own GitHub release, the same one `Clone Hub Pro — Latest Verified
 * APK` publishes to after both native gates pass:
 *
 *   GET https://api.github.com/repos/<owner>/<repo>/releases/latest
 *
 * A plain-HTTP manifest is also supported for private/fork builds, because the GitHub API is the one
 * thing this feature cannot work without and a self-hoster should have a way around it. The manifest
 * shape is documented in [parseManifest].
 *
 * What this class will not do:
 *  - it never installs anything itself; it downloads bytes to the app's own cache and returns their
 *    path, and the caller hands them to Android's installer;
 *  - it never trusts a version *name*: without a numeric versionCode it says it cannot tell;
 *  - it never ignores a published digest: if the server publishes a sha256, a mismatch deletes the
 *    download and fails loudly.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    /** The channel: the release this repository publishes after its gates pass. */
    const val RELEASE_API =
        "https://api.github.com/repos/deepsilence10161-source/Clone-Hub-Pro/releases/latest"

    /** Optional override for forks/self-hosting; see [parseManifest] for the shape. */
    const val MANIFEST_URL = ""

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** GitHub asks for a User-Agent and a stable API version; both are recorded in the panel. */
    private const val USER_AGENT = "CloneHubPro-Updater"

    // ---------------------------------------------------------------------------------------------
    // Looking

    /** One network call. Blocking: call it from a coroutine on [kotlinx.coroutines.Dispatchers.IO]. */
    fun fetchLatest(context: Context): Result<UpdateInfo> = runCatching {
        val installedName = installedVersionName(context)
        val installedCode = installedVersionCode(context)
        if (MANIFEST_URL.isNotEmpty()) {
            Log.i(TAG, "UPDATE_SOURCE manifest=$MANIFEST_URL")
            return@runCatching parseManifest(
                body = httpGet(MANIFEST_URL), installedName = installedName,
                installedCode = installedCode
            )
        }
        Log.i(TAG, "UPDATE_SOURCE github=$RELEASE_API installed=$installedName ($installedCode)")
        parseGitHubRelease(
            body = httpGet(RELEASE_API),
            installedName = installedName,
            installedCode = installedCode
        )
    }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code from $url: ${body.take(200)}")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    /**
     * GitHub's `/releases/latest` shape.
     *
     * Two fields decide everything and neither is assumed:
     *  - `versionCode` comes from the release **title or tag**: this project's publish workflow writes
     *    `Verified commit: <sha>` in the notes, not a version, so a release without a numeric version
     *    in its tag or name is reported as "cannot tell" rather than guessed from the file size;
     *  - `sha256` comes from the asset's own `digest` field when GitHub publishes it. When it is
     *    absent the panel says so, and the download is still checked against the *installed* app's
     *    signature by Android at install time.
     */
    fun parseGitHubRelease(body: String, installedName: String, installedCode: Int): UpdateInfo {
        val release = JSONObject(body)
        val tag = release.optString("tag_name")
        val name = release.optString("name")
        val notes = release.optString("body").take(4_000).ifBlank { null }
        val versionCode = versionCodeFromText(tag) ?: versionCodeFromText(name)
        val assets = release.optJSONArray("assets") ?: JSONArray()
        val apk = pickApk(assets)
        return UpdateInfo(
            version = versionCodeFromText(name)?.let { name } ?: tag.ifBlank { name },
            versionCode = versionCode,
            apkUrl = apk?.optString("browser_download_url")?.ifBlank { null },
            sha256 = apk?.optString("digest")?.takeIf { it.startsWith("sha256:") }
                ?.removePrefix("sha256:")?.lowercase(),
            sizeBytes = apk?.optLong("size")?.takeIf { it > 0 },
            notes = notes,
            source = "github",
            installedVersionName = installedName,
            installedVersionCode = installedCode
        )
    }

    /**
     * A release can carry several APKs (arm, x86, a universal fallback for emulators). The one this
     * build updates itself with is the one whose package name matches this app, preferring a
     * universal/`app-` asset when the repository publishes both flavours.
     */
    private fun pickApk(assets: JSONArray): JSONObject? {
        val candidates = (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.optString("name").startsWith("CloneHub-Pro", true) }
            ?: candidates.first()
    }

    /**
     * A `versionCode` stated in a tag or title, e.g. `v2.0.2+2002`, `2.0.2 (2002)`, `build-2002`.
     * Returns null when the text does not state one — deliberately, because inventing one (from the
     * version name, say) is how an updater offers a downgrade.
     */
    fun versionCodeFromText(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        Regex("""(?:build[-_ ]?|[+(])\s*(\d{3,})""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        // `2.0.2.2002` — a four-segment version states the code last.
        Regex("""^\D*(\d+)\.(\d+)\.(\d+)\.(\d+)\D*$""").find(text.trim())?.groupValues
            ?.getOrNull(4)?.toIntOrNull()?.let { return it }
        return null
    }

    /**
     * A self-hosted manifest, so a private or forked build is not locked out of updating itself:
     *
     * ```json
     * { "version": "2.0.2", "versionCode": 2002,
     *   "apkUrl": "https://example.com/CloneHub-Pro.apk",
     *   "sha256": "…64 hex…", "sizeBytes": 7905790, "notes": "what changed" }
     * ```
     *
     * `versionCode` and `sha256` are optional in the file but their absence changes what the app says
     * (see [UpdateInfo.blockedReason] and [downloadApk]).
     */
    fun parseManifest(body: String, installedName: String, installedCode: Int): UpdateInfo {
        val manifest = JSONObject(body)
        return UpdateInfo(
            version = manifest.optString("version", "unstated"),
            versionCode = if (manifest.has("versionCode")) manifest.optInt("versionCode") else null,
            apkUrl = manifest.optString("apkUrl").ifBlank { null },
            sha256 = manifest.optString("sha256").ifBlank { null }?.lowercase(),
            sizeBytes = manifest.optLong("sizeBytes").takeIf { it > 0 },
            notes = manifest.optString("notes").ifBlank { null },
            source = "manifest",
            installedVersionName = installedName,
            installedVersionCode = installedCode
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Downloading

    data class Download(
        val file: File,
        val bytes: Long,
        val sha256: String,
        /** True when the server published a digest and the bytes matched it. */
        val digestVerified: Boolean
    )

    /**
     * Downloads [info] to the app's cache and verifies it before anyone can install it.
     *
     * @param progress called with (bytes so far, total or -1). Called on the downloading thread.
     */
    fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<Download> = runCatching {
        val url = info.apkUrl ?: error("The release has no APK asset to download.")
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // One file per versionCode, so a half-finished download of an older attempt is never installed.
        val target = File(dir, "CloneHub-Pro-${info.versionCode ?: "unknown"}.apk")
        if (target.exists() && info.sizeBytes != null && target.length() == info.sizeBytes) {
            val existing = sha256Of(target)
            if (info.sha256 == null || existing == info.sha256) {
                Log.i(TAG, "UPDATE_DOWNLOAD reused=${target.name} bytes=${target.length()} sha256=$existing")
                return@runCatching Download(target, target.length(), existing, info.sha256 != null)
            }
        }

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code downloading $url")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes ?: -1L
            var written = 0L
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                }
            }
            if (written == 0L) error("The server answered 200 with an empty body.")
            if (info.sizeBytes != null && written != info.sizeBytes) {
                target.delete()
                error("Downloaded $written bytes but the release states ${info.sizeBytes}.")
            }
            val digest = sha256Of(target)
            val verified = info.sha256 != null && digest == info.sha256
            if (info.sha256 != null && !verified) {
                target.delete()
                error("SHA-256 mismatch: expected ${info.sha256}, got $digest. The file was deleted.")
            }
            Log.i(
                TAG,
                "UPDATE_DOWNLOAD file=${target.name} bytes=$written sha256=$digest " +
                    "published_digest=${info.sha256 ?: "<none>"} verified=$verified"
            )
            Download(target, written, digest, verified)
        } finally {
            connection.disconnect()
        }
    }

    fun sha256Of(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Old downloads, so a cache directory does not grow by one APK per release. */
    fun clearDownloads(context: Context) {
        File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
    }

    // ---------------------------------------------------------------------------------------------
    // Installing, and telling the truth about whether this device can

    /**
     * The installer intent Android documents, with the two flags the platform requires for a
     * `content://` source. Kept in one place so the receiver and the UI cannot disagree.
     */
    fun installerIntent(apkUri: android.net.Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(apkUri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Is this app allowed to install packages at all?
     *
     * Android 8 (API 26) introduced the per-app "install unknown apps" grant; before that the switch
     * was global (`Settings.SECURITY_SETTINGS`), and the app cannot read it — which is reported as
     * "unknown", not as "allowed".
     */
    fun canRequestInstalls(context: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** The Settings screen that grants it, so the app can send the user somewhere real. */
    fun installPermissionSettings(context: Context): Intent =
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

    /**
     * Reads the running app's own signature certificate, SHA-256, so the panel can show it.
     *
     * Android enforces the key match at install time — an APK signed with a different key is refused
     * with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and no app can bypass that. Showing the fingerprint
     * is what lets a user see *why* a refused update was refused, instead of reading a system dialog
     * and guessing.
     */
    fun signingFingerprint(context: Context): String? = runCatching {
        val pm = context.packageManager
        val flags = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        val signatures = if (android.os.Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, flags).signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
                .signatures
        }
        val first = signatures?.firstOrNull() ?: return@runCatching null
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
        digest.joinToString(":") { "%02X".format(it) }
    }.getOrNull()

    fun installedVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    fun installedVersionCode(context: Context): Int = runCatching {
        val pm = context.packageManager
        val info = if (android.os.Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, 0).longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0).versionCode.toLong()
        }
        info.toInt()
    }.getOrDefault(0)

    /** The app's own icon as a PNG in the cache, so the update panel looks like the app it updates. */
    fun iconFile(context: Context): File? = runCatching {
        val target = File(context.cacheDir, "updates/icon.png")
        if (target.exists() && target.length() > 0) return@runCatching target
        target.parentFile?.mkdirs()
        val drawable: Drawable = context.packageManager.getApplicationIcon(context.packageName)
        val bitmap = if (drawable is BitmapDrawable) drawable.bitmap else {
            Bitmap.createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            ).also { Canvas(it).also { canvas -> drawable.setBounds(0, 0, canvas.width, canvas.height); drawable.draw(canvas) } }
        }
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            target.writeBytes(out.toByteArray())
        }
        target
    }.getOrNull()

    /**
     * Which key this build is signed with, described by the fingerprint's first 32 hex characters.
     *
     * Two real values were measured on the CI artifacts, and they differ from each other:
     * the API 34 strict run of commit `c90418d` was signed with `f4de4c14cbac15a36d1b7bf494e18a26`
     * and the published build of `ce6181a` with `7d45e14ccb62d19f8168006b50d20ae7`. That is why the
     * update panel names the key instead of promising a silent install: Android refuses an update
     * signed with a different key, and a user deserves to see which key they have.
     */
    fun signingKeyKind(context: Context): String {
        val fingerprint = signingFingerprint(context)?.replace(":", "")?.lowercase() ?: return "unsigned"
        val prefix = fingerprint.take(32)
        return when (prefix) {
            // The AOSP/AGP debug key: same key on every machine, so two debug builds can update
            // each other. Everything else is a one-off key and can only be replaced by a full install.
            "f4de4c14cbac15a36d1b7bf494e18a26" -> "debug:$prefix"
            "7d45e14ccb62d19f8168006b50d20ae7" -> "ci:$prefix"
            else -> "other:$prefix"
        }
    }

}
