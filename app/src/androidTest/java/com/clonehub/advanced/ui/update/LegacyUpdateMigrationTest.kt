package com.clonehub.advanced.ui.update

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Why installing a newer APK answers "App not installed", measured on a device.
 *
 * Two builds of the SAME package, same versionCode, differing only in signing key, are installed for
 * real: `com.clonehub.pro.migrate` signed with Gradle's per-machine debug key plays the build the user
 * already has, and the same package signed with the committed key plays the build being shipped. That is
 * the reported situation — the old and new APKs are both "Clone Hub Pro", so the reason cannot be a
 * package-name mismatch, and this measures the remaining candidate instead of assuming it.
 *
 * The app under test (`com.clonehub.pro`) is never installed over, uninstalled, or touched: doing that in
 * run 45439011167 killed the instrumentation process and failed every later gate in the same run.
 *
 * One log line per case, so the evidence reads without the test report:
 *   LEGACY_UPDATE_GATE case=<name> result=<verdict> detail=<pm, first line>
 */
class LegacyUpdateMigrationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val evidenceDir = File(context.getExternalFilesDir(null), "legacy-update").apply { mkdirs() }

    private companion object {
        const val TAG = "LEGACY_UPDATE_GATE"
        /** The probe package: the app under test's own name must not appear anywhere in this gate. */
        const val PROBE_PACKAGE = "com.clonehub.pro.migrate"
        const val STAGED_DIR = "/data/local/tmp/legacy-update"
        /** Signed with the per-machine debug key — "the build the user already has". */
        const val OLDER_APK = "migrate-older.apk"
        /** The same package and versionCode, signed with the committed key — "the new APK". */
        const val NEWER_APK = "migrate-newer.apk"
    }

    @Test(timeout = 300_000)
    fun samePackageDifferentKeyIsRefusedAndRemovalIsTheWayThrough() {
        assertTrue(
            "The gate must never operate on the app under test; probe package is $PROBE_PACKAGE",
            PROBE_PACKAGE != context.packageName
        )
        clearProbe()

        // Case 1 — the reported failure. Same package, same versionCode, different signing key.
        val oldInstalled = install(OLDER_APK)
        val oldKey = signerOf(PROBE_PACKAGE)
        Log.i(TAG, "LEGACY_UPDATE_GATE case=install-same-package-old-key result=$oldInstalled key=$oldKey")

        val newOverOld = install(NEWER_APK)
        val keptOld = signerOf(PROBE_PACKAGE)
        Log.i(TAG, "LEGACY_UPDATE_GATE case=new-over-old-same-package-different-key result=$newOverOld key_after=$keptOld")

        // Case 2 — what actually installs: the same key. Recorded so the evidence shows the difference
        // between "refused" and "accepted" is the key alone, with package and versionCode held equal.
        uninstallProbe()
        val newFresh = install(NEWER_APK)
        val newKey = signerOf(PROBE_PACKAGE)
        Log.i(TAG, "LEGACY_UPDATE_GATE case=install-new-key-on-empty result=$newFresh key=$newKey")

        // Case 3 — and once the key matches, a build of the same package replaces itself in place, which
        // is what every update after the one-time switch does.
        val inPlace = install(NEWER_APK)
        Log.i(TAG, "LEGACY_UPDATE_GATE case=in-place-update-same-key result=$inPlace key=${signerOf(PROBE_PACKAGE)}")

        uninstallProbe()
        assertTrue(
            "A build signed with another key must be refused over the installed one; the device said: " +
                "$newOverOld",
            newOverOld.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") ||
                newOverOld.contains("Failure") || newOverOld.contains("Error")
        )
        assertTrue("The same key must install on an empty device: $newFresh", newFresh.startsWith("Success"))
        assertTrue("The same key must update in place: $inPlace", inPlace.startsWith("Success"))

        writeEvidence(
            "old-key install=$oldInstalled key=$oldKey",
            "new-over-old (different key)=$newOverOld key_after=$keptOld",
            "new on empty=$newFresh key=$newKey",
            "in-place same key=$inPlace",
            "probe package=$PROBE_PACKAGE (app under test untouched: ${context.packageName})"
        )
    }

    /** Installs a staged APK, answering with the installer's own first line. */
    private fun install(apkName: String): String {
        val path = "$STAGED_DIR/$apkName"
        val exists = device.executeShellCommand("ls -l $path 2>/dev/null").contains(apkName)
        if (!exists) return "MISSING($path)"
        val output = device.executeShellCommand(
            "pm install -r -t --user 0 $path 2>&1; echo EXIT=$?"
        ).trim()
        return output.lineSequence().filter { it.isNotBlank() }.joinToString(" | ").take(300)
    }

    /** The certificate the package manager reports for the installed package, as a short digest. */
    private fun signerOf(pkg: String): String {
        val dump = device.executeShellCommand("dumpsys package $pkg 2>/dev/null")
        val line = dump.lineSequence().firstOrNull { it.contains("signatures=") || it.contains("Signature") }
            ?: return "not-installed"
        return line.trim().take(80)
    }

    private fun uninstallProbe(): String =
        device.executeShellCommand("pm uninstall $PROBE_PACKAGE 2>&1").trim().lineSequence().firstOrNull() ?: ""

    private fun clearProbe() {
        uninstallProbe()
    }

    private fun writeEvidence(vararg lines: String) {
        runCatching { File(evidenceDir, "legacy-update.txt").writeText(lines.joinToString("\n")) }
    }
}
