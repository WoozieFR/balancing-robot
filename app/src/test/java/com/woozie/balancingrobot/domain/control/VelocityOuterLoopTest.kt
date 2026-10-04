package com.woozie.balancingrobot.domain.control

import com.woozie.balancingrobot.domain.model.RobotConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VelocityOuterLoopTest {
    @Test
    fun convertsEncoderStepsPerSecondToCentimetersPerSecond() {
        val speed = stepsPerSecondToCmPerSecond(1_000, 40.0, 1.0)

        assertEquals(3.0679615758, speed, 1e-9)
    }

    @Test
    fun normalizesWheelDirectionWithMotorSign() {
        assertEquals(1_000, normalizeWheelVelocity(1_000, 1))
        assertEquals(1_000, normalizeWheelVelocity(-1_000, -1))
    }

    @Test
    fun computesFilteredSpeedErrorTrimCorrectionAndClamp() {
        val loop = VelocityOuterLoop(config(
            targetDeg = 0.0,
            speedTargetCmPerSec = 10.0,
            speedKevDegPerCmPerSec = 2.0,
            speedTargetAngleLimitDeg = 10.0,
        ))
        val output = loop.step(1_000_000_000L, feedback(1, 1_000, 1_000, 990_000_000L))

        assertEquals(3.0679615758, output.meanCmPerSec!!, 1e-9)
        assertEquals(output.meanCmPerSec!!, output.filteredCmPerSec!!, 1e-9)
        assertEquals(10.0 - output.filteredCmPerSec!!, output.errorCmPerSec!!, 1e-9)
        assertEquals(10.0, output.correctionDeg, 1e-9)
        assertEquals(10.0, output.effectiveTargetDeg, 1e-9)
        assertTrue(output.saturated)
    }

    @Test
    fun exponentialFilterUpdatesOnlyForANewPair() {
        val loop = VelocityOuterLoop(config(speedFilterAlpha = 0.5, speedLoopRateHz = 100))
        val first = loop.step(1_000_000_000L, feedback(1, 1_000, 1_000, 995_000_000L))
        val reused = loop.step(1_010_000_000L, feedback(1, 1_000, 1_000, 995_000_000L))
        val second = loop.step(1_020_000_000L, feedback(2, 3_000, 3_000, 1_015_000_000L))

        assertEquals(first.filteredCmPerSec!!, reused.filteredCmPerSec!!, 1e-9)
        assertEquals(
            (first.meanCmPerSec!! + second.meanCmPerSec!!) / 2.0,
            second.filteredCmPerSec!!,
            1e-9,
        )
    }

    @Test
    fun respectsConfiguredLoopPeriodAndHoldsTargetBetweenTicks() {
        val loop = VelocityOuterLoop(config(speedLoopRateHz = 50, speedKevDegPerCmPerSec = 1.0))
        val first = loop.step(1_000_000_000L, feedback(1, 0, 0, 995_000_000L))
        val held = loop.step(1_010_000_000L, feedback(2, 1_000, 1_000, 1_005_000_000L))
        val next = loop.step(1_020_000_000L, feedback(2, 1_000, 1_000, 1_005_000_000L))

        assertTrue(first.updated)
        assertFalse(held.updated)
        assertEquals(first.effectiveTargetDeg, held.effectiveTargetDeg, 0.0)
        assertTrue(next.updated)
    }

    @Test
    fun staleFeedbackReturnsTargetTowardTrimAndFreshRecoveryResetsFilter() {
        val loop = VelocityOuterLoop(config(
            targetDeg = 6.7,
            speedKevDegPerCmPerSec = 1.0,
            speedLoopRateHz = 50,
            speedFeedbackTimeoutMs = 100,
            speedTargetSlewRateDegPerSec = 5.0,
        ))
        val valid = loop.step(1_000_000_000L, feedback(1, 1_000, 1_000, 995_000_000L))
        val stale = loop.step(1_120_000_000L, feedback(1, 1_000, 1_000, 995_000_000L))
        val recovered = loop.step(1_140_000_000L, feedback(2, 3_000, 3_000, 1_135_000_000L))

        assertTrue(stale.stale)
        assertTrue(stale.effectiveTargetDeg < valid.effectiveTargetDeg)
        assertTrue(stale.effectiveTargetDeg > 6.7)
        assertFalse(recovered.stale)
        assertEquals(recovered.meanCmPerSec!!, recovered.filteredCmPerSec!!, 1e-9)
    }

    @Test
    fun correctionIsCenteredOnTrimBeforeAbsoluteSafetyClamp() {
        val loop = VelocityOuterLoop(config(
            targetDeg = 6.7,
            speedTargetCmPerSec = 10.0,
            speedKevDegPerCmPerSec = 2.0,
            speedTargetAngleLimitDeg = 10.0,
            speedAbsoluteAngleLimitDeg = 15.0,
        ))

        val output = loop.step(1_000_000_000L, feedback(1, 1_000, 1_000, 995_000_000L))

        assertEquals(10.0, output.correctionDeg, 1e-9)
        assertEquals(15.0, output.effectiveTargetDeg, 1e-9)
        assertTrue(output.saturated)
    }

    @Test
    fun integralAutoTrimPersistsAfterSpeedErrorReturnsToZero() {
        val loop = VelocityOuterLoop(config(
            speedTargetCmPerSec = 5.0,
            speedIntegralGainDegPerCmPerSecSec = 1.0,
        ))
        loop.step(1_000_000_000L, feedback(1, 0, 0, 995_000_000L))
        val accumulated = loop.step(1_020_000_000L, feedback(1, 0, 0, 1_015_000_000L))
        assertTrue(accumulated.integralCorrectionDeg > 0.0)

        loop.updateConfig(config(speedTargetCmPerSec = 0.0, speedIntegralGainDegPerCmPerSecSec = 1.0))
        val retained = loop.step(1_040_000_000L, feedback(2, 0, 0, 1_035_000_000L))
        assertEquals(accumulated.integralCorrectionDeg, retained.integralCorrectionDeg, 1e-9)
    }

    @Test
    fun targetSlewRateLimitsLiveTargetChanges() {
        val loop = VelocityOuterLoop(config(
            speedTargetCmPerSec = 10.0,
            speedKevDegPerCmPerSec = 1.0,
            speedTargetSlewRateDegPerSec = 10.0,
        ))
        loop.step(1_000_000_000L, feedback(1, 0, 0, 995_000_000L))
        loop.updateConfig(config(
            speedTargetCmPerSec = 0.0,
            speedKevDegPerCmPerSec = 1.0,
            speedTargetSlewRateDegPerSec = 10.0,
        ))

        loop.step(1_020_000_000L, feedback(2, 0, 0, 1_015_000_000L))
        val output = loop.step(1_040_000_000L, feedback(3, 0, 0, 1_035_000_000L))

        assertTrue(output.slewLimited)
        assertEquals(9.8, output.effectiveTargetDeg, 1e-9)
    }

    @Test
    fun disabledLoopUsesTrimWithoutClamping() {
        val loop = VelocityOuterLoop(config(speedLoopEnabled = false, targetDeg = 12.0))

        val output = loop.step(1_000_000_000L, null)

        assertEquals(12.0, output.effectiveTargetDeg, 0.0)
        assertFalse(output.enabled)
    }

    private fun config(
        targetDeg: Double = 0.0,
        speedLoopEnabled: Boolean = true,
        speedTargetCmPerSec: Double = 5.0,
        speedKevDegPerCmPerSec: Double = 0.0,
        speedLoopRateHz: Int = 50,
        speedFilterAlpha: Double = 0.5,
        speedTargetAngleLimitDeg: Double = 10.0,
        speedIntegralGainDegPerCmPerSecSec: Double = 0.0,
        speedAbsoluteAngleLimitDeg: Double = 15.0,
        speedTargetSlewRateDegPerSec: Double = 30.0,
        speedFeedbackTimeoutMs: Long = 100,
    ) = RobotConfig(
        targetDeg = targetDeg,
        speedLoopEnabled = speedLoopEnabled,
        speedTargetCmPerSec = speedTargetCmPerSec,
        speedKevDegPerCmPerSec = speedKevDegPerCmPerSec,
        speedLoopRateHz = speedLoopRateHz,
        speedFilterAlpha = speedFilterAlpha,
        speedTargetAngleLimitDeg = speedTargetAngleLimitDeg,
        speedIntegralGainDegPerCmPerSecSec = speedIntegralGainDegPerCmPerSecSec,
        speedAbsoluteAngleLimitDeg = speedAbsoluteAngleLimitDeg,
        speedTargetSlewRateDegPerSec = speedTargetSlewRateDegPerSec,
        speedFeedbackTimeoutMs = speedFeedbackTimeoutMs,
    )

    private fun feedback(
        sequence: Long,
        left: Int,
        right: Int,
        timestampNs: Long,
    ) = WheelVelocityFeedback(sequence, left, right, timestampNs, timestampNs)
}
