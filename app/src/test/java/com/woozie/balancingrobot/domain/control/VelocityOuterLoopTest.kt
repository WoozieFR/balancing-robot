package com.woozie.balancingrobot.domain.control

import com.woozie.balancingrobot.domain.model.RobotConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VelocityOuterLoopTest {
    @Test
    fun convertsEncoderStepsPerSecondToCentimetersPerSecond() {
        assertEquals(3.0679615758, stepsPerSecondToCmPerSecond(1_000, 40.0, 1.0), 1e-9)
    }

    @Test
    fun normalizesWheelDirectionWithMotorSign() {
        assertEquals(1_000, normalizeWheelVelocity(1_000, 1))
        assertEquals(1_000, normalizeWheelVelocity(-1_000, -1))
    }

    @Test
    fun computesSpeedProportionalCorrectionAndClamp() {
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
    fun speedIntegralIsActiveDuringTranslationAndYawManeuvers() {
        val loop = VelocityOuterLoop(config(
            speedIntegralGainDegPerCmPerSecSec = 1.0,
            speedAutoTrimGainDegPerCmPerSecSec = 0.0,
        ))
        loop.step(1_000_000_000L, feedback(1, 0, 0, 995_000_000L), targetCmPerSec = 5.0)
        val translation = loop.step(
            1_020_000_000L,
            feedback(2, 0, 0, 1_015_000_000L),
            targetCmPerSec = 5.0,
        )
        assertTrue(translation.speedIntegralDeg > 0.0)
        assertEquals("MANEUVER", translation.autoTrimState)

        val yawLoop = VelocityOuterLoop(config(
            speedTargetCmPerSec = 0.0,
            speedIntegralGainDegPerCmPerSecSec = 1.0,
            speedAutoTrimGainDegPerCmPerSecSec = 0.0,
        ))
        yawLoop.step(
            1_000_000_000L,
            feedback(1, 0, 0, 995_000_000L),
            targetCmPerSec = 0.0,
            yawTargetDegPerSec = 60.0,
        )
        val yaw = yawLoop.step(
            1_040_000_000L,
            feedback(3, 1_000, 1_000, 1_035_000_000L),
            targetCmPerSec = 0.0,
            yawTargetDegPerSec = 60.0,
        )
        assertTrue(yaw.speedIntegralDeg < 0.0)
        assertEquals("MANEUVER", yaw.autoTrimState)
    }

    @Test
    fun speedIntegralReturnsToZeroAfterRelease() {
        val loop = VelocityOuterLoop(config(
            speedIntegralGainDegPerCmPerSecSec = 1.0,
            speedAutoTrimGainDegPerCmPerSecSec = 0.0,
        ))
        loop.step(1_000_000_000L, feedback(1, 0, 0, 995_000_000L), targetCmPerSec = 5.0)
        val accumulated = loop.step(
            1_020_000_000L,
            feedback(2, 0, 0, 1_015_000_000L),
            targetCmPerSec = 5.0,
        )
        val released = loop.step(
            1_040_000_000L,
            feedback(3, 0, 0, 1_035_000_000L),
            targetCmPerSec = 0.0,
        )

        assertTrue(accumulated.speedIntegralDeg > 0.0)
        assertTrue(released.speedIntegralDeg < accumulated.speedIntegralDeg)
        assertEquals("RELEASE", released.autoTrimState)
    }

    @Test
    fun releasedSpeedTargetBypassesTheAccelerationRamp() {
        val loop = VelocityOuterLoop(config(
            speedTargetSlewRateCmPerSec = 1.0,
            speedAutoTrimGainDegPerCmPerSecSec = 0.0,
        ))
        val moving = loop.step(
            1_000_000_000L,
            feedback(1, 0, 0, 995_000_000L),
            targetCmPerSec = 5.0,
        )
        val released = loop.step(
            1_020_000_000L,
            feedback(2, 1_000, 1_000, 1_015_000_000L),
            targetCmPerSec = 0.0,
        )

        assertEquals(5.0, moving.appliedTargetCmPerSec, 1e-9)
        assertEquals(0.0, released.appliedTargetCmPerSec, 1e-9)
        assertFalse(released.speedCommandSlewLimited)
    }

    @Test
    fun yawReleaseClearsSpeedIntegralWhileTranslationContinues() {
        val loop = VelocityOuterLoop(config(
            speedIntegralGainDegPerCmPerSecSec = 1.0,
            speedAutoTrimGainDegPerCmPerSecSec = 0.0,
        ))
        loop.step(
            1_000_000_000L,
            feedback(1, 0, 0, 995_000_000L),
            targetCmPerSec = 5.0,
            yawTargetDegPerSec = 60.0,
        )
        val duringYaw = loop.step(
            1_020_000_000L,
            feedback(2, 0, 0, 1_015_000_000L),
            targetCmPerSec = 5.0,
            yawTargetDegPerSec = 60.0,
        )
        val yawReleased = loop.step(
            1_040_000_000L,
            feedback(3, 0, 0, 1_035_000_000L),
            targetCmPerSec = 5.0,
            yawTargetDegPerSec = 0.0,
        )

        assertTrue(duringYaw.speedIntegralDeg > 0.0)
        assertEquals(0.0, yawReleased.speedIntegralDeg, 1e-9)
        assertEquals("MANEUVER", yawReleased.autoTrimState)
    }

    @Test
    fun restAutoTrimIsSeparateAndFrozenDuringManeuver() {
        val loop = VelocityOuterLoop(config(
            speedTargetCmPerSec = 0.0,
            speedIntegralGainDegPerCmPerSecSec = 0.0,
            speedAutoTrimGainDegPerCmPerSecSec = 0.5,
            speedQuietDurationMs = 100,
            speedFilterAlpha = 1.0,
        ))
        loop.step(1_000_000_000L, feedback(1, 0, 0, 995_000_000L))
        val beforeRest = loop.step(1_020_000_000L, feedback(2, 0, 0, 1_015_000_000L))
        val trimAtStart = beforeRest.autoTrimDeg
        assertEquals(0.0, trimAtStart, 1e-9)

        loop.step(1_040_000_000L, feedback(3, 0, 0, 1_035_000_000L))
        loop.step(1_060_000_000L, feedback(4, 0, 0, 1_055_000_000L))
        loop.step(1_080_000_000L, feedback(5, 0, 0, 1_075_000_000L))
        loop.step(1_100_000_000L, feedback(6, 0, 0, 1_095_000_000L))
        val rest = loop.step(1_120_000_000L, feedback(7, 0, 0, 1_115_000_000L))
        assertEquals("REST", rest.autoTrimState)

        val learned = loop.step(1_140_000_000L, feedback(8, 1_000, 1_000, 1_135_000_000L))
        val learnedTrim = learned.autoTrimDeg
        assertTrue(learnedTrim < 0.0)

        val moving = loop.step(
            1_160_000_000L,
            feedback(9, 1_000, 1_000, 1_155_000_000L),
            targetCmPerSec = 5.0,
        )
        assertEquals(learnedTrim, moving.autoTrimDeg, 1e-9)
        assertEquals("MANEUVER", moving.autoTrimState)
    }

    @Test
    fun firstBalanceSessionMustSettleBeforeAutoTrimLearning() {
        val loop = VelocityOuterLoop(config(
            speedTargetCmPerSec = 0.0,
            speedAutoTrimGainDegPerCmPerSecSec = 1.0,
            speedQuietDurationMs = 100,
            speedFilterAlpha = 1.0,
        ))

        val initial = loop.step(
            1_000_000_000L,
            feedback(1, 2_000, 2_000, 995_000_000L),
        )
        assertEquals("ACQUIRE_REST", initial.autoTrimState)
        assertEquals(0.0, initial.autoTrimDeg, 1e-9)

        val oscillating = loop.step(
            1_020_000_000L,
            feedback(2, 2_000, 2_000, 1_015_000_000L),
            pitchRateDegPerSec = 10.0,
        )
        assertEquals("ACQUIRE_REST", oscillating.autoTrimState)
        assertEquals(0.0, oscillating.autoTrimDeg, 1e-9)

        val quiet = loop.step(
            1_040_000_000L,
            feedback(3, 0, 0, 1_035_000_000L),
        )
        assertEquals("ACQUIRE_REST", quiet.autoTrimState)
        assertEquals(0.0, quiet.autoTrimDeg, 1e-9)

        loop.step(1_060_000_000L, feedback(4, 0, 0, 1_055_000_000L))
        loop.step(1_080_000_000L, feedback(5, 0, 0, 1_075_000_000L))
        loop.step(1_100_000_000L, feedback(6, 0, 0, 1_095_000_000L))
        val rest = loop.step(
            1_120_000_000L,
            feedback(7, 0, 0, 1_115_000_000L),
        )
        assertEquals("REST", rest.autoTrimState)

        val loaded = loop.step(
            1_140_000_000L,
            feedback(8, 1_000, 1_000, 1_135_000_000L),
        )
        assertEquals("REST", loaded.autoTrimState)
        assertTrue(loaded.autoTrimDeg < 0.0)
    }

    @Test
    fun staleFeedbackDoesNotEraseTemporaryIntegralOrLearnTrim() {
        val loop = VelocityOuterLoop(config(
            speedIntegralGainDegPerCmPerSecSec = 1.0,
            speedAutoTrimGainDegPerCmPerSecSec = 0.5,
        ))
        loop.step(1_000_000_000L, feedback(1, 0, 0, 995_000_000L), targetCmPerSec = 5.0)
        val accumulated = loop.step(
            1_020_000_000L,
            feedback(2, 0, 0, 1_015_000_000L),
            targetCmPerSec = 5.0,
        )
        val stale = loop.step(
            1_140_000_000L,
            feedback(2, 0, 0, 1_015_000_000L),
            targetCmPerSec = 5.0,
        )

        assertTrue(stale.stale)
        assertEquals(accumulated.speedIntegralDeg, stale.speedIntegralDeg, 1e-9)
        assertEquals(0.0, stale.autoTrimDeg, 1e-9)
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
    fun disabledLoopUsesBaseTrimWithoutClamping() {
        val loop = VelocityOuterLoop(config(speedLoopEnabled = false, targetDeg = 12.0))

        val output = loop.step(1_000_000_000L, null)

        assertEquals(12.0, output.effectiveTargetDeg, 0.0)
        assertFalse(output.enabled)
    }

    private fun config(
        targetDeg: Double = 0.0,
        speedLoopEnabled: Boolean = true,
        speedTargetCmPerSec: Double = 5.0,
        speedTargetSlewRateCmPerSec: Double = 20.0,
        speedKevDegPerCmPerSec: Double = 0.0,
        speedLoopRateHz: Int = 50,
        speedFilterAlpha: Double = 0.5,
        speedTargetAngleLimitDeg: Double = 10.0,
        speedIntegralGainDegPerCmPerSecSec: Double = 0.0,
        speedAutoTrimGainDegPerCmPerSecSec: Double = 0.0,
        speedAbsoluteAngleLimitDeg: Double = 15.0,
        speedTargetSlewRateDegPerSec: Double = 30.0,
        speedFeedbackTimeoutMs: Long = 100,
        speedQuietDurationMs: Long = 700,
    ) = RobotConfig(
        targetDeg = targetDeg,
        speedLoopEnabled = speedLoopEnabled,
        speedTargetCmPerSec = speedTargetCmPerSec,
        speedTargetSlewRateCmPerSec = speedTargetSlewRateCmPerSec,
        speedKevDegPerCmPerSec = speedKevDegPerCmPerSec,
        speedLoopRateHz = speedLoopRateHz,
        speedFilterAlpha = speedFilterAlpha,
        speedTargetAngleLimitDeg = speedTargetAngleLimitDeg,
        speedIntegralGainDegPerCmPerSecSec = speedIntegralGainDegPerCmPerSecSec,
        speedAutoTrimGainDegPerCmPerSecSec = speedAutoTrimGainDegPerCmPerSecSec,
        speedAbsoluteAngleLimitDeg = speedAbsoluteAngleLimitDeg,
        speedTargetSlewRateDegPerSec = speedTargetSlewRateDegPerSec,
        speedFeedbackTimeoutMs = speedFeedbackTimeoutMs,
        speedQuietDurationMs = speedQuietDurationMs,
    )

    private fun feedback(
        sequence: Long,
        left: Int,
        right: Int,
        timestampNs: Long,
    ) = WheelVelocityFeedback(sequence, left, right, timestampNs, timestampNs)
}
