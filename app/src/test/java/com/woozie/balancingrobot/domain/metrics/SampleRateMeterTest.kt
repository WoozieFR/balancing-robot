package com.woozie.balancingrobot.domain.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleRateMeterTest {
    @Test
    fun computesRateAndJitterFromMonotonicTimestamps() {
        val meter = SampleRateMeter(windowSize = 3)
        meter.record(1_000_000_000L)
        meter.record(1_010_000_000L)
        val metrics = meter.record(1_020_000_000L)

        assertEquals(100.0, metrics.frequencyHz, 1e-9)
        assertEquals(10.0, metrics.intervalMs, 1e-9)
        assertEquals(0.0, metrics.jitterMs, 1e-9)
    }

    @Test
    fun rejectsNonMonotonicTimestampWithoutProducingRate() {
        val meter = SampleRateMeter()
        meter.record(10L)
        val metrics = meter.record(9L)

        assertEquals(0.0, metrics.frequencyHz, 0.0)
        assertTrue(metrics.intervalMs == 0.0)
    }
}
