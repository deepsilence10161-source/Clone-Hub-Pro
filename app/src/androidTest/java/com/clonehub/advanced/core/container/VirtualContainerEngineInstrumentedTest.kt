package com.clonehub.advanced.core.container

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.clonehub.advanced.data.model.ClonedApp
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VirtualContainerEngineInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test(timeout = 120_000)
    fun runtimeBridge_installsAndVerifiesExistingSystemPackage() {
        Log.i(TAG, "Supported emulator ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")

        assertTrue(
            "Runtime must install and then return PackageInfo for the existing Settings package",
            VirtualContainerEngine.installApp(context, TEST_PACKAGE, TEST_SPACE)
        )
    }

    @Test(timeout = 120_000)
    fun runtimeBridge_launchAcceptsOnlySuccessfulRuntimeResult() {
        val app = ClonedApp(
            packageName = TEST_PACKAGE,
            appName = "Settings",
            originalName = "Settings",
            spaceId = TEST_SPACE
        )

        assertTrue(
            "Settings must launch successfully inside the test virtual space",
            VirtualContainerEngine.launchApp(context, app, TEST_SPACE)
        )
    }

    private companion object {
        const val TAG = "RuntimeBridgeE2E"
        const val TEST_PACKAGE = "com.android.settings"
        const val TEST_SPACE = 90
    }
}
