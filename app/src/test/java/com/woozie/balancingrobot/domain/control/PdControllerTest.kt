package com.woozie.balancingrobot.domain.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdControllerTest {
    @Test
    fun computesDampedCommand() {
        val output = pdStep(targetDeg = 0.0, angleDeg = -2.0, gyroRateDegPerSec = 3.0, kp = 10.0, kd = 0.5, vmax = 6000)

        assertEquals(2.0, output.errorDeg, 1e-9)
        assertEquals(18.5, output.rawCommand, 1e-9)
        // Kotlin's round follows the reference Python implementation and uses
        // ties-to-even: 18.5 becomes 18.
        assertEquals(18, output.boundedCommand)
        assertFalse(output.saturated)
    }

    @Test
    fun saturatesBeforeRoundingAndAppliesSigns() {
        val output = pdStep(0.0, -10.0, 0.0, 100.0, 0.0, 600)

        assertEquals(600, output.boundedCommand)
        assertTrue(output.saturated)
        assertEquals(listOf(600, -600), applyMotorSigns(output.boundedCommand, listOf(1, -1)))
    }
}
