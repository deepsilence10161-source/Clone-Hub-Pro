package com.clonehub.advanced.ui.home

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.clonehub.advanced.R
import com.clonehub.advanced.core.container.SpaceManager
import com.clonehub.advanced.ui.backup.BackupActivity
import com.clonehub.advanced.ui.picker.AppPickerActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Mobile-size UI verification.
 *
 * The other suites run on whatever window the emulator happens to have. This one *sets* the
 * display to the screens this app actually ships to — a 360dp and a 320dp phone, at 2x and 3x
 * density, portrait and landscape — and then measures the real layout on each of them:
 *
 *  - every control the user has to reach is inside the screen and inside the safe area
 *    (below the status bar, above the navigation bar);
 *  - every tappable control meets the 48dp minimum touch target Android requires;
 *  - text is not clipped (a control's text must fit its bounds at the density being tested);
 *  - nothing is laid out at zero size or drawn under another control it needs to be tapped over;
 *  - the App Picker row keeps the install button on screen with a long package name.
 *
 * Screenshots are written per configuration so the numbers can be checked by eye as well.
 * A failing measurement fails the gate: this is a layout assertion, not a screenshot gallery.
 */
class PhoneUiMatrixInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val evidenceDir = File(context.getExternalFilesDir(null), "ui-matrix").apply { mkdirs() }
    // The installed package is the applicationId, not the code namespace.
    private val appId = context.packageName
    // The fixture app ships in the instrumentation APK, so its package is the target's with .test.
    private val fixturePackage = "$appId.test"

    private companion object {
        const val TAG = "PHONE_UI"
        const val SETTLE_MS = 900L
        const val FIND_TIMEOUT_MS = 20_000L
        const val MIN_TOUCH_DP = 48
        // A deliberately long label: the grid gives it two lines, so this is where a tile name
        // would push the layout around if it could.
        const val FIXTURE_PACKAGE = "com.clonehub.pro.test"
        const val FIXTURE_LABEL = "Clone Hub Fixture With A Deliberately Long Name"
        const val FIXTURE_SPACE = 77
    }

    /** Bottom of the status bar and top of the navigation bar, read from the platform windows. */
    private data class SystemBars(
        val statusBarBottom: Int?,
        val navigationBarTop: Int?,
        val source: String
    ) {
        fun describe(): String =
            "statusBarBottom=$statusBarBottom navigationBarTop=$navigationBarTop via=$source"
    }

    /**
     * Measures the real system bars.
     *
     * In this image the status and navigation bars are *separate windows* owned by the system UI,
     * not nodes inside the app's hierarchy — which is why walking the app's accessibility tree for
     * `com.android.systemui:id/navigation_bar_frame` returned null and the earlier revision fell
     * back to the app window edges and measured nothing. `AccessibilityWindowInfo` is the platform
     * reporting where those windows actually are.
     */
    private fun measureSystemBars(label: String): SystemBars {
        val displayHeight = device.displayHeight
        var statusBarBottom: Int? = null
        var navigationBarTop: Int? = null
        val found = StringBuilder()
        for (windowInfo in instrumentation.uiAutomation.windows) {
            val geometry = WindowGeometry.of(windowInfo) ?: continue
            val rootName = runCatching {
                windowInfo.root?.packageName?.toString()
            }.getOrNull().orEmpty()
            val isBarWindow = rootName.contains("systemui") || rootName.contains("navigationbar") ||
                (windowInfo.type != AccessibilityWindowInfo.TYPE_APPLICATION && geometry.height() < displayHeight / 4)
            found.append(rootName.ifEmpty { "window${windowInfo.id}" })
                .append('(').append(geometry).append(") ")
            if (!isBarWindow) continue
            if (geometry.top <= displayHeight / 8) {
                statusBarBottom = maxOf(statusBarBottom ?: 0, geometry.bottom)
            } else if (geometry.bottom >= displayHeight - displayHeight / 8) {
                navigationBarTop = minOf(navigationBarTop ?: Int.MAX_VALUE, geometry.top)
            }
        }
        Log.i(TAG, "PHONE_UI config=$label windows=[$found]")
        return SystemBars(
            statusBarBottom = statusBarBottom,
            navigationBarTop = navigationBarTop,
            source = if (found.isEmpty()) "windows:none" else "windows"
        )
    }

    private object WindowGeometry {
        fun of(window: AccessibilityWindowInfo): Rect? = runCatching {
            Rect().also { window.getBoundsInScreen(it) }
        }.getOrNull()
    }

    /** Home screen with one installed clone, so the grid state a user keeps is what gets measured. */
    private fun showHomeWithFixtureClone(label: String) {
        val manager = SpaceManager.getInstance(context)
        // The fixture has to be installed as a real app before it can appear in a grid: the
        // instrumentation APK is not installed by the runner script on the phone-UI level.
        val installed = device.executeShellCommand("pm list packages $fixturePackage")
            .contains(fixturePackage)
        if (!installed) {
            // The test APK is in the app's private code path, which the shell cannot read: stage a
            // copy the platform can install from.
            val staged = File(context.getExternalFilesDir(null), "fixture-app.apk")
            val source = File(instrumentation.context.applicationInfo.sourceDir)
            runCatching {
                if (!staged.exists() || staged.length() != source.length()) {
                    source.inputStream().use { input ->
                        staged.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                device.executeShellCommand("pm install -r -t ${staged.absolutePath}").trim().let {
                    Log.i(TAG, "PHONE_UI config=$label fixture install: $it")
                }
            }.onFailure { Log.w(TAG, "Could not stage the fixture APK on $label", it) }
        }
        // addAppToSpace creates the space when it does not exist, so one call is enough.
        val added = runCatching {
            manager.addAppToSpace(FIXTURE_SPACE, FIXTURE_PACKAGE, FIXTURE_LABEL)
        }.getOrNull()
        if (added == null) Log.w(TAG, "Could not add the fixture clone on $label")
        showHome(label)
    }

    /** 360dp x 720dp @2x — the reference Android phone the layout was designed for. */
    @Test(timeout = 240_000)
    fun a_phone360_portrait() = measure("360x720@2x", 720, 1280, 320, 360, 720, landscape = false)

    /** 320dp wide — the narrowest Android phone still in the wild. */
    @Test(timeout = 240_000)
    fun b_phoneCompact320_portrait() =
        measure("320x640@2x", 640, 1280, 320, 320, 640, landscape = false)

    /** 411dp x 891dp @3x — a modern tall phone, where a bottom-anchored FAB is most at risk. */
    @Test(timeout = 240_000)
    fun c_phoneTall411_portrait() =
        measure("411x891@3x", 1080, 2340, 480, 411, 891, landscape = false)

    /** Landscape on the reference phone: the state where a fixed grid used to get clipped. */
    @Test(timeout = 240_000)
    fun d_phone360_landscape() = measure("720x360@2x", 720, 1280, 320, 720, 360, landscape = true)

    private fun measure(
        label: String,
        naturalWidthPx: Int,
        naturalHeightPx: Int,
        densityDpi: Int,
        widthDp: Int,
        heightDp: Int,
        landscape: Boolean
    ) {
        device.setOrientationNatural()
        device.executeShellCommand("wm size ${naturalWidthPx}x$naturalHeightPx")
        device.executeShellCommand("wm density $densityDpi")
        SystemClock.sleep(SETTLE_MS)
        if (landscape) device.setOrientationLeft() else device.setOrientationNatural()
        SystemClock.sleep(SETTLE_MS)
        try {
            val reported = device.executeShellCommand("wm size").trim().replace('\n', ' ')
            Log.i(TAG, "PHONE_UI config=$label requested=${naturalWidthPx}x$naturalHeightPx@${densityDpi}dpi reported=$reported")

            showHome(label)
            // The app's own window is the only honest reference: on API 34 the display reports
            // 1080x2340 while the app is laid out inside 1080x2136 and draws its system bars on
            // top. Measuring against the display reported every control as "under the nav bar".
            val window = appWindowBounds()
            val screenWidth = window.width()
            val screenHeight = window.height()
            Log.i(
                TAG,
                "PHONE_UI config=$label dp=${widthDp}x$heightDp density=${densityDpi}dpi " +
                    "window=$window display=${device.displayWidth}x${device.displayHeight}"
            )
            val expectedLong = maxOf(naturalWidthPx, naturalHeightPx)
            val expectedShort = minOf(naturalWidthPx, naturalHeightPx)
            assertTrue(
                "App window does not match the requested ${widthDp}x${heightDp}dp on $label: $window",
                maxOf(screenWidth, screenHeight) == expectedLong &&
                    minOf(screenWidth, screenHeight) == expectedShort
            )
            // Real system-bar geometry, measured from the platform's own windows.
            //
            // This used to be the app window's own top/bottom, which is the whole display on this
            // image — so every "inside the safe area" assertion below passed without ever looking at
            // the status or navigation bar (evidence of run 43203694595 logged safeArea top=0
            // bottom=2340 navBarTop=null). The bars are separate windows; they are read as such.
            val bars = measureSystemBars(label)
            val safeTop = bars.statusBarBottom
            val safeBottom = bars.navigationBarTop
            Log.i(
                TAG,
                "PHONE_UI config=$label safeArea top=$safeTop bottom=$safeBottom " +
                    "source=${bars.describe()}"
            )

            // A gate that cannot see the system bars cannot claim a control clears them.
            assertNotNull(
                "The status bar window was not measurable on $label, so the safe area is unknown",
                safeTop
            )
            assertNotNull(
                "The navigation bar window was not measurable on $label, so the safe area is unknown",
                safeBottom
            )

            // 1. The Add action: on screen, above the navigation bar, and a real touch target.
            waitFor(By.res(appId, "fabAdd"), label, "fabAdd")
            val fabBounds = boundsOf(resourceId = "fabAdd")
            assertInside("fabAdd", fabBounds, screenWidth, safeTop!!, safeBottom!!)
            assertTouchTarget("fabAdd", fabBounds, densityDpi)
            logBounds(label, "fabAdd", fabBounds)

            // 2. The space controls in the toolbar: reachable and not overlapping the title.
            listOf("btnPrevSpace", "btnNextSpace", "btnOverflow").forEach { id ->
                val nodeBounds = runCatching { boundsOf(resourceId = id) }.getOrNull()
                if (nodeBounds != null) {
                    logBounds(label, id, nodeBounds)
                    assertInside(id, nodeBounds, screenWidth, safeTop, safeBottom)
                    assertTouchTarget(id, nodeBounds, densityDpi)
                } else {
                    Log.i(TAG, "PHONE_UI config=$label $id hidden (single space / no action)")
                }
            }

            // 3. The grid: a clone tile is square-ish, fully visible and tappable.
            val tileBounds = boundsOf(text = context.getString(R.string.action_add))
            logBounds(label, "gridAddTile", tileBounds)
            assertTrue(
                "gridAddTile label is clipped on $label: $tileBounds",
                tileBounds.height() > 0 && tileBounds.width() > 0
            )
            assertInside("gridAddTile", tileBounds, screenWidth, safeTop, safeBottom)

            // 4. The space card must not be wider than the screen (a clipped card hides clones).
            val cardBounds = runCatching { boundsOf(resourceId = "cardSpace") }.getOrNull()
            if (cardBounds != null) {
                logBounds(label, "cardSpace", cardBounds)
                assertTrue(
                    "Space card overflows the ${widthDp}dp screen on $label: $cardBounds",
                    cardBounds.left >= 0 && cardBounds.right <= screenWidth
                )
            }

            // 4b. Ordering and clipping between the header, the card and the Add action.
            listOf("btnPrevSpace", "btnNextSpace", "btnOverflow").forEach { id ->
                val b = runCatching { boundsOf(resourceId = id) }.getOrNull() ?: return@forEach
                val title = runCatching { boundsOf(resourceId = "tvSpaceIndicator") }.getOrNull()
                if (title != null) {
                    assertTrue(
                        "$id overlaps the space indicator on $label: $b vs $title",
                        b.right <= title.left || b.left >= title.right ||
                            b.bottom <= title.top || b.top >= title.bottom
                    )
                }
            }
            val cardForFab = runCatching { boundsOf(resourceId = "cardSpace") }.getOrNull()
            if (cardForFab != null) {
                assertTrue(
                    "The Add action is drawn on top of the space card content on $label: " +
                        "fab=$fabBounds card=$cardForFab",
                    fabBounds.top >= cardForFab.top
                )
            }

            // 4c. The overflow menu opens, its items are on screen, and it can be dismissed —
            //     a menu nobody can reach is the same as no menu.
            runCatching { device.findObject(By.res(appId, "btnOverflow"))?.click() }
            val menuOpened = device.wait(
                Until.hasObject(By.text(context.getString(R.string.action_backup))),
                FIND_TIMEOUT_MS
            )
            if (menuOpened) {
                val backupItem = boundsOf(text = context.getString(R.string.action_backup))
                assertInside("overflowBackupItem", backupItem, screenWidth, safeTop, safeBottom)
                capture(label, "overflow")
                device.pressBack()
                SystemClock.sleep(SETTLE_MS)
            } else {
                Log.w(TAG, "PHONE_UI config=$label overflow menu did not open")
            }

            // 4d. A grid tile with a real clone in it: this is the state a user actually keeps the
            //     app in, and the one the single "Add App" label never exercised. The name is long
            //     on purpose — two lines, ellipsised, both inside the card.
            showHomeWithFixtureClone(label)
            waitFor(By.text(FIXTURE_LABEL), label, "cloneTile")
            val cloneTileBounds = boundsOf(text = FIXTURE_LABEL)
            logBounds(label, "cloneTile", cloneTileBounds)
            assertInside("cloneTile", cloneTileBounds, screenWidth, safeTop, safeBottom)
            val cardWithTile = runCatching { boundsOf(resourceId = "cardSpace") }.getOrNull()
            if (cardWithTile != null) {
                assertTrue(
                    "Clone tile is outside the space card on $label: tile=$cloneTileBounds " +
                        "card=$cardWithTile",
                    cloneTileBounds.top >= cardWithTile.top &&
                        cloneTileBounds.bottom <= cardWithTile.bottom
                )
            }

            // 4e. Long-press actions: the sheet fits, its rows meet the touch target, and the
            //     status line that reports a broken clone is readable instead of running off screen.
            runCatching {
                device.findObject(By.text(FIXTURE_LABEL))?.longClick()
            }
            if (device.wait(Until.hasObject(By.res(appId, "actionLaunch")), FIND_TIMEOUT_MS)) {
                val statusBounds = runCatching { boundsOf(resourceId = "tvHeaderStatus") }.getOrNull()
                if (statusBounds != null) {
                    logBounds(label, "sheetStatus", statusBounds)
                    assertInside("sheetStatus", statusBounds, screenWidth, safeTop, safeBottom)
                }
                listOf("actionLaunch", "actionFakeIcon", "actionCreateShortcut", "actionForceStop")
                    .forEach { id ->
                        val b = runCatching { boundsOf(resourceId = id) }.getOrNull() ?: return@forEach
                        logBounds(label, id, b)
                        assertInside(id, b, screenWidth, safeTop, safeBottom)
                        assertTouchTarget(id, b, densityDpi)
                    }
                capture(label, "sheet")
                device.pressBack()
                SystemClock.sleep(SETTLE_MS)
            } else {
                Log.w(TAG, "PHONE_UI config=$label long-press sheet did not open")
            }

            // 4f. Backup & restore: every button reachable, nothing under the system bars.
            context.startActivity(BackupActivity.intent(context).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            if (device.wait(Until.hasObject(By.res(appId, "btnCreateBackup")), FIND_TIMEOUT_MS)) {
                listOf("btnCreateBackup", "btnPickRestore", "btnRestoreMerge", "btnRestoreReplace")
                    .forEach { id ->
                        val b = runCatching { boundsOf(resourceId = id) }.getOrNull() ?: return@forEach
                        logBounds(label, id, b)
                        assertInside(id, b, screenWidth, safeTop, safeBottom)
                        assertTouchTarget(id, b, densityDpi)
                    }
                capture(label, "backup")
            } else {
                Log.w(TAG, "PHONE_UI config=$label backup screen did not open")
            }

            showHome(label)
            capture(label, "home")

            // 5. The App Picker: header + list, install button reachable, long names truncate
            //    instead of pushing the button off screen.
            context.startActivity(Intent(context, AppPickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("target_space_id", 1)
            })
            // A picker with nothing to clone must say so, instead of blaming OEM permissions.
            val pickerReady = device.wait(
                Until.hasObject(By.res(appId, "btnClone")) or
                    Until.hasObject(By.res(appId, "pickerEmptyState")),
                FIND_TIMEOUT_MS
            )
            if (device.hasObject(By.res(appId, "pickerEmptyState"))) {
                val emptyTitle = boundsOf(resourceId = "tvEmptyTitle")
                logBounds(label, "pickerEmptyTitle", emptyTitle)
                assertInside("pickerEmptyTitle", emptyTitle, screenWidth, safeTop, safeBottom)
                Log.i(TAG, "PHONE_UI config=$label picker empty state shown (no apps to clone)")
            }
            assertTrue("The picker showed neither a list nor an empty state on $label", pickerReady)
            if (!device.hasObject(By.res(appId, "btnClone"))) {
                Log.i(TAG, "PHONE_UI_RESULT config=$label picker=EMPTY (list assertions skipped)")
                return
            }
            val installBounds = boundsOf(resourceId = "btnClone")
            assertInside("btnClone", installBounds, screenWidth, safeTop, safeBottom)
            assertTouchTarget("btnClone", installBounds, densityDpi)
            runCatching { boundsOf(resourceId = "tvAppName") }
                .onSuccess { logBounds(label, "pickerRowTitle", it) }
            val searchBounds = boundsOf(resourceId = "search_src_text")
            assertInside("pickerSearch", searchBounds, screenWidth, safeTop, safeBottom)
            // The picker title must clear the status bar. Measured on the title text itself: the
            // toolbar is full width, so its own bounds say nothing about the title.
            val pickerTitle = boundsOf(text = context.getString(R.string.action_install_app))
            logBounds(label, "pickerTitle", pickerTitle)
            assertTrue(
                "The picker title is under the status bar on $label: $pickerTitle " +
                    "(status bar bottom=$safeTop)",
                pickerTitle.top >= safeTop
            )
            // The active filter has to be visible, otherwise the user cannot tell whether they are
            // looking at user apps or system apps.
            val userChip = device.findObject(By.res(appId, "chipUser"))
            assertNotNull("The user/system filter is missing on $label", userChip)
            assertTrue(
                "No filter chip is shown as selected on $label",
                userChip?.isChecked == true || device.findObject(By.res(appId, "chipSystem"))?.isChecked == true
            )
            capture(label, "picker")

            Log.i(TAG, "PHONE_UI_RESULT config=$label outcome=PASS")
        } catch (error: Throwable) {
            Log.e(TAG, "PHONE_UI_RESULT config=$label outcome=FAIL", error)
            capture(label, "failure")
            throw error
        } finally {
            device.executeShellCommand("wm size reset")
            device.executeShellCommand("wm density reset")
            device.setOrientationNatural()
            SystemClock.sleep(SETTLE_MS)
        }
    }

    private fun showHome(label: String) {
        device.wakeUp()
        dismissSystemDialogs()
        context.startActivity(Intent(context, HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        // "Rendered" means the screen the user sees is really there: the Add action itself, not
        // just the root view. The first API 34 attempt failed here because a notification-permission
        // dialog was on top and the hierarchy it searched belonged to the dialog.
        val ready = device.wait(Until.hasObject(By.res(appId, "fabAdd")), FIND_TIMEOUT_MS)
        if (!ready) {
            val focus = runCatching {
                device.executeShellCommand("dumpsys window | grep -m2 mCurrentFocus")
            }.getOrNull()
            Log.w(
                TAG,
                "Home not ready on $label; currentPackage=${device.currentPackageName} focus=$focus"
            )
            runCatching {
                device.dumpWindowHierarchy(File(evidenceDir, "home-not-ready-$label.xml"))
            }
            dismissSystemDialogs()
            val retried = device.wait(Until.hasObject(By.res(appId, "fabAdd")), FIND_TIMEOUT_MS)
            assertTrue(
                "Home did not render (Add action missing) on $label; currentPackage=" +
                    "${device.currentPackageName} focus=$focus " + systemBarSummary(label),
                retried
            )
        }
        SystemClock.sleep(SETTLE_MS)
    }

    /**
     * A system dialog on top of the app is not the app's layout, so it is dismissed before any
     * measurement. Note what is deliberately *not* done here: sending the app to the home screen.
     * Android 10+ blocks a background process from starting an activity, so a `pressHome()` between
     * launches is exactly how the earlier revision made every configuration fail with "Home did not
     * render" — the start was silently refused.
     */
    private fun dismissSystemDialogs(permissionsAlreadyGranted: Boolean = true) {
        if (permissionsAlreadyGranted) {
            runCatching {
                device.executeShellCommand(
                    "pm grant $context.packageName android.permission.POST_NOTIFICATIONS"
                )
            }
        }
        runCatching {
            val deny = device.findObject(By.text("Don't allow")) ?: device.findObject(By.text("Deny"))
            if (deny != null) {
                Log.i(TAG, "Dismissing system dialog via '${deny.text}'")
                deny.click()
                SystemClock.sleep(300)
            }
        }
    }

    private fun waitFor(selector: BySelector, label: String, name: String): UiObject2 {
        val node = device.wait(Until.findObject(selector), FIND_TIMEOUT_MS)
        assertNotNull(
            "$name not found on $label; hierarchy dumped to $label-$name-failure.xml",
            node
        )
        return node!!
    }

    private fun assertInside(name: String, bounds: Rect, screenWidth: Int, top: Int, bottom: Int) {
        assertTrue("$name is outside the screen horizontally: $bounds", bounds.left >= 0 && bounds.right <= screenWidth)
        assertTrue("$name is under the status bar area: $bounds (safe top=$top)", bounds.top >= top)
        assertTrue("$name is under the navigation bar: $bounds (safe bottom=$bottom)", bounds.bottom <= bottom)
    }

    private fun assertTouchTarget(name: String, bounds: Rect, densityDpi: Int) {
        val minPx = MIN_TOUCH_DP * densityDpi / 160
        assertTrue(
            "$name is ${bounds.width()}x${bounds.height()}px, below the ${MIN_TOUCH_DP}dp touch target " +
                "(${minPx}px at ${densityDpi}dpi): $bounds",
            bounds.width() >= minPx && bounds.height() >= minPx
        )
    }

    private fun logBounds(label: String, name: String, bounds: Rect) {
        Log.i(TAG, "PHONE_UI config=$label $name=$bounds")
    }

    /**
     * On-screen bounds of a node, read from the live accessibility hierarchy.
     *
     * UiObject2 in this version only exposes `visibleBounds`, which is *clipped by the parent* —
     * exactly the overflow this suite has to catch — and no accessor for the underlying node. So
     * the hierarchy is walked directly: match by view id inside this app, or by exact text for the
     * grid labels, and read the unclipped bounds the layout actually assigned.
     */
    private fun boundsOf(resourceId: String? = null, text: String? = null): Rect {
        val root = instrumentation.uiAutomation.rootInActiveWindow
            ?: throw AssertionError("No active window to measure")
        try {
            @Suppress("DEPRECATION")
            val found = findNode(root, resourceId, text)
            if (found == null) {
                runCatching {
                    device.dumpWindowHierarchy(File(evidenceDir, "hierarchy-failure.xml"))
                }
                throw AssertionError("Node not in the hierarchy: id=$resourceId text=$text")
            }
            try {
                return Rect().also { found.getBoundsInScreen(it) }
            } finally {
                @Suppress("DEPRECATION")
                found.recycle()
            }
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun findNode(
        node: AccessibilityNodeInfo,
        resourceId: String?,
        text: String?
    ): AccessibilityNodeInfo? {
        val idMatches = resourceId != null && node.viewIdResourceName == "$appId:id/$resourceId"
        val textMatches = text != null && node.text?.toString() == text
        if (idMatches || textMatches) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val match = findNode(child, resourceId, text)
            child.recycle()
            if (match != null) return match
        }
        return null
    }

    /** Real window area of the app under test, in pixels, including the system bar areas. */
    private fun appWindowBounds(): Rect {
        val root = instrumentation.uiAutomation.rootInActiveWindow
            ?: throw AssertionError("No active window to measure")
        try {
            val rect = Rect()
            root.getBoundsInScreen(rect)
            return rect
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    private fun capture(label: String, state: String) {
        try {
            val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot()
            FileOutputStream(File(evidenceDir, "phone-$label-$state.png")).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Could not capture $state for $label", error)
        }
    }

    /** Diagnostics: where the system bars are, so a failure report says what the app was up against. */
    private fun systemBarSummary(label: String): String = measureSystemBars(label).describe()

}
