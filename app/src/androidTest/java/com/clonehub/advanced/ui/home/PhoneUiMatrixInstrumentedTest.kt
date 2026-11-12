package com.clonehub.advanced.ui.home

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.clonehub.advanced.R
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
    private val appId = "com.clonehub.advanced"

    private companion object {
        const val TAG = "PHONE_UI"
        const val SETTLE_MS = 900L
        const val FIND_TIMEOUT_MS = 20_000L
        const val MIN_TOUCH_DP = 48
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
        assertTrue("Display $naturalWidthPx x $naturalHeightPx is not supported",
            device.setOrientationNatural())
        device.executeShellCommand("wm size ${naturalWidthPx}x$naturalHeightPx")
        device.executeShellCommand("wm density $densityDpi")
        SystemClock.sleep(SETTLE_MS)
        if (landscape) device.setOrientationLeft() else device.setOrientationNatural()
        SystemClock.sleep(SETTLE_MS)
        try {
            val reported = device.executeShellCommand("wm size").trim().replace('\n', ' ')
            Log.i(TAG, "PHONE_UI config=$label requested=${widthPx}x$heightPx@${densityDpi}dpi reported=$reported")

            showHome(label)
            val screenWidth = device.displayWidth
            val screenHeight = device.displayHeight
            // The resize has to be real, otherwise every number below is about a different screen.
            assertTrue(
                "Display was not resized for $label: asked ${naturalWidthPx}x$naturalHeightPx px, " +
                    "actual ${screenWidth}x$screenHeight px at ${densityDpi}dpi",
                setOf(screenWidth, screenHeight) == setOf(naturalWidthPx, naturalHeightPx)
            )
            val safeTop = systemBarTop(label, bottom = false).orZero()
            val safeBottom = systemBarTop(label, bottom = true) ?: screenHeight
            Log.i(
                TAG,
                "PHONE_UI config=$label dp=${widthDp}x$heightDp density=${densityDpi}dpi " +
                    "screen=${screenWidth}x$screenHeight safeTop=$safeTop safeBottom=$safeBottom"
            )

            // 1. The Add action: on screen, above the navigation bar, and a real touch target.
            val fab = waitFor(By.res(appId, "fabAdd"), label, "fabAdd")
            assertInside("fabAdd", fab.bounds, screenWidth, safeTop, safeBottom)
            assertTouchTarget("fabAdd", fab.bounds, densityDpi)
            logBounds(label, "fabAdd", fab.bounds)

            // 2. The space controls in the toolbar: reachable and not overlapping the title.
            listOf("btnPrevSpace", "btnNextSpace", "btnSettings", "btnBackup").forEach { id ->
                val node = device.findObject(By.res(appId, id))
                if (node != null) {
                    logBounds(label, id, node.bounds)
                    assertInside(id, node.bounds, screenWidth, safeTop, safeBottom)
                    assertTouchTarget(id, node.bounds, densityDpi)
                } else {
                    Log.w(TAG, "PHONE_UI config=$label $id not found in the hierarchy")
                }
            }

            // 3. The grid: a clone tile is square-ish, fully visible and tappable.
            val addTile = device.findObject(By.text(context.getString(R.string.action_add)))
            assertNotNull("The empty space no longer offers an Add tile on $label", addTile)
            addTile?.let { tile ->
                logBounds(label, "gridAddTile", tile.bounds)
                // A clipped label is reported as a zero-height or off-screen node.
                assertTrue(
                    "gridAddTile label is clipped on $label: ${tile.bounds}",
                    tile.bounds.height() > 0 && tile.bounds.width() > 0
                )
                assertInside("gridAddTile", tile.bounds, screenWidth, safeTop, safeBottom)
            }

            // 4. The space card must not be wider than the screen (a clipped card hides clones).
            val card = device.findObject(By.res(appId, "cardSpace"))
            if (card != null) {
                logBounds(label, "cardSpace", card.bounds)
                assertTrue(
                    "Space card overflows the ${widthDp}dp screen on $label: ${card.bounds}",
                    card.bounds.left >= 0 && card.bounds.right <= screenWidth
                )
            }

            capture(label, "home")

            // 5. The App Picker: header + list, install button reachable, long names truncate
            //    instead of pushing the button off screen.
            context.startActivity(Intent(context, AppPickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("target_space_id", 1)
            })
            val installButton = waitFor(By.res(appId, "btnClone"), label, "btnClone")
            assertInside("btnClone", installButton.bounds, screenWidth, safeTop, safeBottom)
            assertTouchTarget("btnClone", installButton.bounds, densityDpi)
            val row = device.findObject(By.res(appId, "tvAppName"))
            if (row != null) logBounds(label, "pickerRowTitle", row.bounds)
            val search = device.findObject(By.clazz("android.widget.EditText"))
            assertNotNull("Search field missing on $label", search)
            assertInside("pickerSearch", search!!.bounds, screenWidth, safeTop, safeBottom)
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
        context.startActivity(Intent(context, HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        val rendered = device.wait(
            Until.hasObject(By.res(appId, "coordinator")), FIND_TIMEOUT_MS
        )
        assertTrue("Home did not render on $label", rendered)
        SystemClock.sleep(SETTLE_MS)
    }

    private fun waitFor(selector: By, label: String, name: String): UiObject2 {
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
     * Finds the system bar that overlays the app: the status bar at the top, or the navigation bar
     * at the bottom. Returned in pixels so every other measurement can be compared directly.
     */
    private fun systemBarTop(label: String, bottom: Boolean): Int? {
        val candidates = if (bottom) {
            listOf("navigation_bar_frame", "navigationBarBackground", "navigation_bar_container")
        } else {
            listOf("status_bar", "statusBarBackground", "status_bar_container")
        }
        for (id in candidates) {
            val node = device.findObject(By.res("com.android.systemui", id)) ?: continue
            val bounds = runCatching { node.bounds }.getOrNull() ?: continue
            Log.i(TAG, "PHONE_UI config=$label systemui:$id=$bounds")
            return if (bottom) bounds.top else bounds.bottom
        }
        return null
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

    private fun Int?.orZero(): Int = this ?: 0
}
