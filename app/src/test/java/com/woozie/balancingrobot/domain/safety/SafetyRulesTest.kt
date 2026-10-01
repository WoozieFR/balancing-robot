package com.woozie.balancingrobot.domain.safety

import com.woozie.balancingrobot.domain.model.FaultCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyRulesTest {
    @Test
    fun freshnessUsesMonotonicNanosecondsAndTimeout() {
        assertTrue(SafetyRules.isGyroFresh(110_000_000L, 50_000_000L, 60))
        assertFalse(SafetyRules.isGyroFresh(111_000_001L, 50_000_000L, 60))
        assertFalse(SafetyRules.isGyroFresh(1L, null, 100))
        assertEquals(FaultCode.IMU_STALE, SafetyRules.classifyImu(200_000_000L, 1L, 100))
    }

    @Test
    fun fallMustPersistForConfiguredDuration() {
        assertTrue(SafetyRules.isFallAngleExceeded(-36.0, 35.0))
        assertFalse(SafetyRules.isFallAngleExceeded(34.9, 35.0))
        assertFalse(SafetyRules.isFallDurationExceeded(1_000_000L, 50_000_000L, 100))
        assertTrue(SafetyRules.isFallDurationExceeded(1_000_000L, 101_000_000L, 100))
    }
}
