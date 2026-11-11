package com.clonehub.advanced.core.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Failure reporting must never look like success. */
class EngineOutcomeTest {

    @Test
    fun onlySuccess_isSuccess() {
        assertTrue(EngineOutcome.Success.isSuccess)
        assertFalse(EngineOutcome.RuntimeUnavailable("no binder").isSuccess)
        assertFalse(EngineOutcome.Rejected("install", -1).isSuccess)
        assertFalse(EngineOutcome.NotVerified("install", "package missing").isSuccess)
    }

    @Test
    fun describe_carriesTheFailingStepAndCode() {
        val rejected = EngineOutcome.Rejected("launch", 102, "activity missing")
        assertEquals("Engine rejected launch (code 102): activity missing", rejected.describe())
        assertEquals("OK", EngineOutcome.Success.describe())
        assertTrue(
            EngineOutcome.NotVerified("install", "resultCode=0 but no PackageInfo")
                .describe().startsWith("Could not verify install")
        )
    }

    @Test
    fun unavailableRuntime_neverClaimsSuccess() {
        val outcome = EngineOutcome.RuntimeUnavailable("address resolution failed")
        assertFalse(outcome.isSuccess)
        assertTrue(outcome.describe().contains("address resolution failed"))
    }
}
