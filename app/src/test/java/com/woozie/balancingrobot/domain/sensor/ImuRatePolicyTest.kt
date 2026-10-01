package com.woozie.balancingrobot.domain.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImuRatePolicyTest {
    @Test
    fun acceptsConfiguredRangeAndConvertsToPeriod() {
        assertTrue(ImuRatePolicy.isValid(200))
        assertTrue(ImuRatePolicy.isValid(50))
        assertEquals(5_000, ImuRatePolicy.periodUs(200))
        assertEquals(20_000, ImuRatePolicy.periodUs(50))
    }

    @Test
    fun rejectsUnsafeOrUnsupportedRates() {
        assertFalse(ImuRatePolicy.isValid(0))
        assertFalse(ImuRatePolicy.isValid(201))
    }
}
