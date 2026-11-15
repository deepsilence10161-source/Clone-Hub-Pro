package com.clonehub.advanced.ui.update

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import com.clonehub.advanced.core.update.UpdateChecker
import com.clonehub.advanced.core.update.UpdateInstaller
import com.clonehub.advanced.core.update.UpdatePath
import java.io.File

/**
 * The in-app updater, measured on the device instead of assumed.
 *
 * What this asserts is what a phone can prove without a second build and without a network:
 *
 *  - the installed build's own versionCode and versionName are readable (the comparison's left side);
 *  - the app carries a signing key, and which one — because Android refuses an update signed with a
 *    different key, and two CI runs of this repository really did produce different keys;
 *  - `REQUEST_INSTALL_PACKAGES` is declared and the per-app install permission can be read;
 *  - the `FileProvider` that the installer path depends on actually resolves for a file in the update
 *    cache — a mis-declared provider is a `FileUriExposedException` at the worst possible moment;
 *  - the release channel answers, when the device has a network, and the payload parses into a
 *    versionCode that can be compared. With no network the test is reported as NOT MEASURED with the
 *    reason, never as a pass.
 *
 * What it deliberately does not do: install an update over itself. That would replace the binary
 * under the running test process, and the *result* of a real update is what the next gate run checks
 * by reading `dumpsys package` for the new versionCode.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class UpdateFlowInstrumentedTest {

    private companion object {
        const val TAG = "UPDATE_GATE"
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun a_installedBuildIsReadableAndSigned() {
        val name = UpdateChecker.installedVersionName(context)
        val code = UpdateChecker.installedVersionCode(context)
        val key = UpdateChecker.signingKeyKind(context)
        val fingerprint = UpdateChecker.signingFingerprint(context)
        Log.i(TAG, "UPDATE_GATE installed name=$name code=$code key=$key fingerprint=$fingerprint")
        // The stable key committed at .github/signing/clonehub-signing.p12. If this line stops
        // matching, every installed copy needs a manual reinstall before it can update again — so
        // the gate says it out loud instead of letting a silent key change break updates.
        val stableKeyPrefix = "e2f0843c8f5cc9e8dc67a7af860815d6"
        val actualPrefix = fingerprint?.replace(":", "")?.lowercase()?.take(32)
        Log.i(
            TAG,
            "UPDATE_GATE signing_key stable=$stableKeyPrefix actual=$actualPrefix " +
                "matches=${actualPrefix == stableKeyPrefix}"
        )
        assertTrue("The installed versionCode is unreadable: $code", code > 0)
        assertTrue("The installed versionName is unreadable: $name", name.isNotBlank() && name != "unknown")
        assertFalse("This build reports no signing key at all", key == "unsigned")
        assertNotNull("No signing fingerprint could be read", fingerprint)
    }

    @Test
    fun b_installPermissionIsDeclaredAndReadable() {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions?.toList() ?: emptyList()
        val declares = requested.contains("android.permission.REQUEST_INSTALL_PACKAGES")
        val granted = UpdateChecker.canRequestInstalls(context)
        Log.i(
            TAG,
            "UPDATE_GATE install_permission declared=$declares canRequestInstalls=$granted " +
                "sdk=${android.os.Build.VERSION.SDK_INT} path=${UpdateInstaller.recordedPath(context)}"
        )
        assertTrue("The manifest does not declare REQUEST_INSTALL_PACKAGES", declares)
    }

    @Test
    fun c_fileProviderResolvesForTheUpdateCache() {
        // Exactly the file the installer path shares: a digest-verified APK in cache/updates.
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "provider-probe.apk").apply { writeText("not a real apk, only a path probe") }
        val uri = UpdateInstaller.apkUri(context, apk)
        Log.i(TAG, "UPDATE_GATE provider authority=${UpdateInstaller.authority(context)} uri=$uri")
        assertEquals("content", uri.scheme)
        assertEquals(UpdateInstaller.authority(context), uri.authority)
        // The provider must answer for that URI; a wrong path in file_paths.xml throws here.
        context.contentResolver.openInputStream(uri).use { stream ->
            assertNotNull("The update FileProvider did not serve the cache file", stream)
        }
        val icon = UpdateChecker.iconFile(context)
        Log.i(TAG, "UPDATE_GATE icon=${icon?.absolutePath} bytes=${icon?.length()}")
        assertNotNull("The update panel cannot render the app's own icon", icon)
        apk.delete()
    }

    @Test
    fun d_releaseChannelParsesOnTheDevice() {
        val result = UpdateChecker.fetchLatest(context)
        result.onFailure { error ->
            // No network is not a failure of the app: say so, with the platform's own words.
            Log.w(TAG, "UPDATE_GATE channel NOT_MEASURED error=${error.javaClass.simpleName}: ${error.message}")
        }
        assumeTrue(
            "NOT MEASURED: the release channel was unreachable from this device: " +
                (result.exceptionOrNull()?.message ?: ""),
            result.isSuccess
        )
        val info = result.getOrThrow()
        Log.i(
            TAG,
            "UPDATE_GATE channel source=${info.source} version=${info.version} " +
                "versionCode=${info.versionCode} installed=${info.installedVersionCode} " +
                "newer=${info.isNewer} blocked=${info.blockedReason ?: "<none>"} " +
                "apk=${info.apkUrl ?: "<none>"} sha256=${info.sha256 ?: "<not published>"} " +
                "size=${info.sizeBytes ?: -1}"
        )
        // The channel must at least be parseable and name an APK; whether it is newer than this
        // build is a fact about the release, not about the code, so it is logged, not asserted.
        assertNotNull("The release published no APK asset", info.apkUrl)
        assertEquals(info.installedVersionCode, UpdateChecker.installedVersionCode(context))
    }

    @Test
    fun e_installerIntentIsTheDocumentedOne() {
        val uri = Uri.parse("content://${UpdateInstaller.authority(context)}/updates/probe.apk")
        val intent = UpdateChecker.installerIntent(uri)
        Log.i(TAG, "UPDATE_GATE installer_intent action=${intent.action} type=${intent.type} flags=${intent.flags}")
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertTrue(
            "The installer intent must grant read access to the URI",
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        )
        // The panel's wording is derived from a stored verdict; on a fresh install it must say
        // "not measured" rather than promise an install that Android might refuse.
        val described = UpdateInstaller.describePath(context)
        Log.i(TAG, "UPDATE_GATE path_text=$described path=${UpdateInstaller.recordedPath(context)}")
        if (UpdateInstaller.recordedPath(context) == UpdatePath.UNKNOWN) {
            assertTrue("An unmeasured path must say so", described.contains("Not measured"))
        }
    }
}
