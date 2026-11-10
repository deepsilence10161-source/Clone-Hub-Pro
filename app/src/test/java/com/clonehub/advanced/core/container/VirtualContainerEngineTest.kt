package com.clonehub.advanced.core.container

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualContainerEngineTest {

    @Test
    fun launchResult_onlyAndroidStartSuccessIsAccepted() {
        assertTrue(VirtualContainerEngine.isLaunchResultSuccessful(0))

        // Runtime-specific failure and missing-activity results must not be reported as launches.
        assertFalse(VirtualContainerEngine.isLaunchResultSuccessful(-96))
        assertFalse(VirtualContainerEngine.isLaunchResultSuccessful(102))
        assertFalse(VirtualContainerEngine.isLaunchResultSuccessful(-1))
        assertFalse(VirtualContainerEngine.isLaunchResultSuccessful(1))
    }
}
