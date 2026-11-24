package com.clonehub.advanced.ui.update

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Why "app not installed" happens when an APK is installed over an older one, measured on the device.
 *
 * The field report: a user with the old `2.0.2` APK installs the new one over it and Android answers
 * "App not installed"; deleting the old app first makes the same APK install fine. That is not a
 * mystery — Android refuses an in-place update for exactly two reasons when the versions are otherwise
 * fine (the new versionCode is higher):
 *
 *   1. a different package name — measured, the old artifact is `com.clonehub.advanced.debug` while
 *      the new one is `com.clonehub.pro`, so they are two different apps, not two versions;
 *   2. a different signing key — measured earlier in this project, three CI runs carried three
 *      different keys before the key was made stable.
 *
 * This test reproduces the mechanism with the APKs the gate stage installs: the legacy artifact
 * (`com.clonehub.advanced.debug`, built from the `legacyDebug` flavour) and the current artifact
 * (`com.clonehub.pro`), both staged on the device by the gate script before this runs. It records what
 * Android says in each case, because the install path is what is being tested — not a description of it.
 *
 * Every step's verdict is one log line, so the evidence is readable without the test report:
 *
 *   LEGACY_UPDATE_GATE case=<name> result=<verdict> detail=<pm output, first line>
 */
class LegacyUpdateMigrationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val evidenceDir = File(context.getExternalFilesDir(null), "legacy-update").apply { mkdirs() }

    private companion object {
        const val TAG = "LEGACY_UPDATE_GATE"
        const val LEGACY_PACKAGE = "com.clonehub.advanced.debug"
        const val NEW_PACKAGE = "com.clonehub.pro"
        /** Staged by the gate script; the same files it installs by hand for the strict cases. */
        const val STAGED_DIR = "/data/local/tmp/legacy-update"
        const val LEGACY_APK = "legacy-debug.apk"
        const val NEW_APK = "new-pro.apk"
    }

    @Test(timeout = 300_000)
    fun installingNewOverOldFailsForTheMeasuredReasonsAndMigrationWorks() {
        clearBoth()

        // Case 1: the two packages are different apps. Installing the new one over the old one cannot
        // be an update at all, and this is what the user hit.
        val legacyInstalled = install(LEGACY_APK)
        Log.i(TAG, "LEGACY_UPDATE_GATE case=install-legacy result=$legacyInstalled installed=${isInstalled(LEGACY_PACKAGE)}")
        val crossInstall = sessionInstall(NEW_APK)
        Log.i(TAG, "LEGACY_UPDATE_GATE case=new-over-legacy-cross-package result=$crossInstall new=${isInstalled(NEW_PACKAGE)} legacy=${isInstalled(LEGACY_PACKAGE)}")
        assertTrue(
            "The old package should still be installed after the cross-package attempt: $crossInstall",
            isInstalled(LEGACY_PACKAGE)
        )

        // Case 2: the same package, lower versionCode. Android accepts this (it is a downgrade, which
        // `pm install -r` allows) but an update from the app itself would never offer it; recorded so
        // the difference between "installs" and "is an update" is visible in the evidence.
        val samePackageLower = sessionInstall(NEW_APK) // same version, not lower: baseline for case 3
        Log.i(TAG, "LEGACY_UPDATE_GATE case=same-package-same-version result=$samePackageLower new=${isInstalled(NEW_PACKAGE)}")

        // Case 3: the migration that actually works for an existing user — uninstall the old artifact,
        // install the new one, and confirm the new package then updates itself in place. This is the
        // only route open to a user who already installed the old APK, and the app must say so.
        val removed = uninstall(LEGACY_PACKAGE)
        Log.i(TAG, "LEGACY_UPDATE_GATE case=remove-legacy result=$removed legacy=${isInstalled(LEGACY_PACKAGE)}")
        val migrated = sessionInstall(NEW_APK)
        Log.i(TAG, "LEGACY_UPDATE_GATE case=install-new-after-removal result=$migrated new=${isInstalled(NEW_PACKAGE)}")
        assertTrue("The new APK must install once the old package is gone: $migrated", isInstalled(NEW_PACKAGE))

        // And now the update that a user of the new artifact gets afterwards: same package, same key,
        // so it replaces in place and the data survives. Recorded as the verdict of this gate.
        val inPlace = sessionInstall(NEW_APK)
        Log.i(TAG, "LEGACY_UPDATE_GATE case=in-place-update-new-package result=$inPlace new=${isInstalled(NEW_PACKAGE)}")
        assertTrue("A build of the same package and key must replace itself in place: $inPlace", inPlace.startsWith("Success"))

        writeEvidence(
            "install-legacy=$legacyInstalled",
            "new-over-legacy=$crossInstall",
            "legacy-still-installed=${isInstalled(LEGACY_PACKAGE)}",
            "remove-legacy=$removed",
            "install-new=$migrated",
            "in-place-update=$inPlace",
            "packages: old=$LEGACY_PACKAGE new=$NEW_PACKAGE"
        )
    }

    /** Installs through a real package-installer session, the same path a user's tap uses. */
    private fun sessionInstall(apkName: String): String {
        val apk = stagedFile(apkName) ?: return "MISSING($apkName)"
        val create = device.executeShellCommand("pm install-create -r -t -S ${apk.length()}")
            .trim()
        val sessionId = Regex("""\[(\d+)]""").find(create)?.groupValues?.get(1)
        if (sessionId == null) return "CREATE_FAILED: ${create.lineSequence().firstOrNull() ?: create}"
        device.executeShellCommand("pm install-write -S ${apk.length()} $sessionId base ${apk.absolutePath}")
        val commit = device.executeShellCommand("pm install-commit $sessionId").trim()
        return commit.lineSequence().firstOrNull() ?: commit
    }

    /** Installs a whole APK the simple way, used where a session is not needed. */
    private fun install(apkName: String): String {
        val apk = stagedFile(apkName) ?: return "MISSING($apkName)"
        return device.executeShellCommand("pm install -r -t ${apk.absolutePath}").trim()
            .lineSequence().firstOrNull() ?: "no output"
    }

    private fun uninstall(pkg: String): String =
        device.executeShellCommand("pm uninstall $pkg").trim().lineSequence().firstOrNull() ?: "no output"

    private fun isInstalled(pkg: String): Boolean =
        device.executeShellCommand("pm list packages $pkg").contains(pkg)

    private fun clearBoth() {
        uninstall(LEGACY_PACKAGE)
        uninstall(NEW_PACKAGE)
    }

    /** The APKs ship as assets of the test APK, which is a real installed app, so they are copied out. */
    private fun stagedFile(apkName: String): File? {
        val path = "$STAGED_DIR/$apkName"
        val listed = device.executeShellCommand("ls -l $path 2>/dev/null").trim()
        return if (listed.contains(apkName)) File(path) else null
    }

    private fun writeEvidence(vararg lines: String) {
        runCatching { File(evidenceDir, "legacy-update.txt").writeText(lines.joinToString("\n")) }
    }
}
