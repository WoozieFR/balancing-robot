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
    fun joystickCommandMapsToLeanAndLeanSlewIsApplied() {
        val loop = VelocityOuterLoop(config(joystickMaxLeanDeg = 3.0, joystickLeanSlewRateDegPerSec = 30.0))
        val first = loop.step(1_000_000_000L, feedback(1, 0, 0, 995_000_000L), targetCmPerSec = 10.0)
        val second = loop.step(1_020_000_000L, feedback(2, 0, 0, 1_015_000_000L), targetCmPerSec = 10.0)

        assertEquals(3.0, first.requestedLeanDeg, 1e-9)
        assertEquals(0.0, first.appliedLeanDeg, 1e-9)
        assertEquals(0.6, second.appliedLeanDeg, 1e-9)
        assertEquals("DRIVING", second.autoTrimState)
        assertTrue(second.userCommandActive)
    }

    @Test
    fun normalizedGamepadFullStickReachesConfiguredMaximumLean() {
        val loop = VelocityOuterLoop(config(joystickMaxLeanDeg = 3.0, joystickLeanSlewRateDegPerSec = 180.0))
        val output = loop.step(
            1_000_000_000L,
            feedback(1, 0, 0, 995_000_000L),
            targetCmPerSec = 0.0,
            forwardNormalized = 1.0,
        )

        assertEquals(3.0, output.requestedLeanDeg, 1e-9)
        assertEquals(10.0, output.targetCmPerSec, 1e-9)
    }

    @Test
    fun commandDeadbandAndYawBothEnterDriving() {
        val loop = VelocityOuterLoop(config(joystickDeadbandCmPerSec = 0.5))
        val belowDeadband = loop.step(1_000_000_000L, feedback(1, 0, 0, 995_000_000L), targetCmPerSec = 0.2)
        val yaw = loop.step(
            1_020_000_000L,
            feedback(2, 0, 0, 1_015_000_000L),
            targetCmPerSec = 0.0,
            yawTargetDegPerSec = 30.0,
        )

        assertEquals("REST", belowDeadband.autoTrimState)
        assertEquals("DRIVING", yaw.autoTrimState)
        assertTrue(yaw.userCommandActive)
    }

    @Test
    fun releaseTransitionsThroughWaitRestAndRequiresContinuousQuietFeedback() {
        val loop = VelocityOuterLoop(config(speedQuietDurationMs = 100, speedFilterAlpha = 1.0))
        loop.step(1_000_000_000L, feedback(1, 0, 0, 995_000_000L), targetCmPerSec = 5.0)
        val released = loop.step(1_020_000_000L, feedback(2, 0, 0, 1_015_000_000L), targetCmPerSec = 0.0)
        val interrupted = loop.step(1_040_000_000L, feedback(3, 1_000, 1_000, 1_035_000_000L), targetCmPerSec = 0.0)
        val quietAgain = loop.step(1_060_000_000L, feedback(4, 0, 0, 1_055_000_000L), targetCmPerSec = 0.0)
        val settled = loop.step(1_080_000_000L, feedback(5, 0, 0, 1_075_000_000L), targetCmPerSec = 0.0)
        val settledLater = loop.step(1_100_000_000L, feedback(6, 0, 0, 1_095_000_000L), targetCmPerSec = 0.0)
        val settledLaterAgain = loop.step(1_120_000_000L, feedback(7, 0, 0, 1_115_000_000L), targetCmPerSec = 0.0)
        val finallySettled = loop.step(1_140_000_000L, feedback(8, 0, 0, 1_135_000_000L), targetCmPerSec = 0.0)

        assertEquals("WAIT_REST", released.autoTrimState)
        assertEquals(0.0, interrupted.settledDurationSec, 1e-9)
        assertEquals("WAIT_REST", quietAgain.autoTrimState)
        assertEquals("WAIT_REST", settled.autoTrimState)
        assertEquals("WAIT_REST", settledLater.autoTrimState)
        assertEquals("WAIT_REST", settledLaterAgain.autoTrimState)
        assertEquals("REST", finallySettled.autoTrimState)
    }

    @Test
    fun autoTrimLearnsOnlyInRestOnNewFeedback() {
        val loop = VelocityOuterLoop(config(speedIntegralGainDegPerCmPerSecSec = 1.0, speedFilterAlpha = 1.0))
        loop.step(1_000_000_000L, feedback(1, 0, 0, 995_000_000L), targetCmPerSec = 0.0)
        val learned = loop.step(1_020_000_000L, feedback(2, 1_000, 1_000, 1_015_000_000L), targetCmPerSec = 0.0)
        val heldSequence = loop.step(1_040_000_000L, feedback(2, 1_000, 1_000, 1_015_000_000L), targetCmPerSec = 0.0)
        val driving = loop.step(1_060_000_000L, feedback(3, 1_000, 1_000, 1_055_000_000L), targetCmPerSec = 5.0)

        assertTrue(learned.autoTrimDeg < 0.0)
        assertEquals(learned.autoTrimDeg, heldSequence.autoTrimDeg, 1e-9)
        assertEquals(learned.autoTrimDeg, driving.autoTrimDeg, 1e-9)
        assertEquals("DRIVING", driving.autoTrimState)
    }

    @Test
    fun autoTrimIsDisabledBeforeBalanceSessionAndResetsAtSessionStart() {
        val loop = VelocityOuterLoop(config(speedIntegralGainDegPerCmPerSecSec = 1.0, speedFilterAlpha = 1.0))
        loop.setAutoTrimLearningEnabled(false)
        loop.step(1_000_000_000L, feedback(1, 0, 0, 995_000_000L), targetCmPerSec = 0.0)
        val disarmed = loop.step(1_020_000_000L, feedback(2, 1_000, 1_000, 1_015_000_000L), targetCmPerSec = 0.0)
        assertEquals(0.0, disarmed.autoTrimDeg, 1e-9)

        loop.setAutoTrimLearningEnabled(true)
        loop.step(2_000_000_000L, feedback(3, 0, 0, 1_995_000_000L), targetCmPerSec = 0.0)
        val armed = loop.step(2_020_000_000L, feedback(4, 1_000, 1_000, 2_015_000_000L), targetCmPerSec = 0.0)
        assertTrue(armed.autoTrimDeg < 0.0)
    }

    @Test
    fun restKeepsLearningAfterAChargeCausesDrift() {
        val loop = VelocityOuterLoop(config(speedIntegralGainDegPerCmPerSecSec = 1.0, speedFilterAlpha = 1.0))
        loop.step(1_000_000_000L, feedback(1, 0, 0, 995_000_000L), targetCmPerSec = 0.0)
        val beforeDrift = loop.step(1_020_000_000L, feedback(2, 0, 0, 1_015_000_000L), targetCmPerSec = 0.0)
        val afterDrift = loop.step(
            1_040_000_000L,
            feedback(3, 1_000, 1_000, 1_035_000_000L),
            targetCmPerSec = 0.0,
            pitchRateDegPerSec = 10.0,
        )

        assertEquals("REST", afterDrift.autoTrimState)
        assertNotEquals(beforeDrift.autoTrimDeg, afterDrift.autoTrimDeg, 1e-9)
    }

    @Test
    fun staleFeedbackPreservesTrimAndDoesNotIntegrateRecoveryGap() {
        val loop = VelocityOuterLoop(config(speedIntegralGainDegPerCmPerSecSec = 1.0, speedFilterAlpha = 1.0))
        loop.step(1_000_000_000L, feedback(1, 0, 0, 995_000_000L), targetCmPerSec = 0.0)
        val learned = loop.step(1_020_000_000L, feedback(2, 1_000, 1_000, 1_015_000_000L), targetCmPerSec = 0.0)
        val stale = loop.step(1_140_000_000L, feedback(2, 1_000, 1_000, 1_015_000_000L), targetCmPerSec = 0.0)
        val recovered = loop.step(1_160_000_000L, feedback(3, 1_000, 1_000, 1_155_000_000L), targetCmPerSec = 0.0)

        assertTrue(stale.stale)
        assertEquals(learned.autoTrimDeg, stale.autoTrimDeg, 1e-9)
        assertEquals(learned.autoTrimDeg, recovered.autoTrimDeg, 1e-9)
        assertEquals(recovered.meanCmPerSec!!, recovered.filteredCmPerSec!!, 1e-9)
    }

    @Test
    fun autoTrimIsAntiWindupBoundedByAbsoluteTargetEnvelope() {
        val loop = VelocityOuterLoop(config(
            targetDeg = 8.0,
            speedAbsoluteAngleLimitDeg = 15.0,
            speedIntegralGainDegPerCmPerSecSec = 2.0,
            speedFilterAlpha = 1.0,
        ))
        loop.step(1_000_000_000L, feedback(1, 0, 0, 995_000_000L), targetCmPerSec = 0.0)
        var output = loop.step(1_500_000_000L, feedback(2, -1_000, -1_000, 1_495_000_000L), targetCmPerSec = 0.0)
        output = loop.step(2_000_000_000L, feedback(3, -1_000, -1_000, 1_995_000_000L), targetCmPerSec = 0.0)
        output = loop.step(2_500_000_000L, feedback(4, -1_000, -1_000, 2_495_000_000L), targetCmPerSec = 0.0)
        output = loop.step(3_000_000_000L, feedback(5, -1_000, -1_000, 2_995_000_000L), targetCmPerSec = 0.0)

        assertEquals(7.0, output.autoTrimDeg, 1e-9)
        assertTrue(output.autoTrimSaturated)
        assertTrue(output.effectiveTargetDeg <= 15.0)
    }

    @Test
    fun waitRestCannotQualifyWhileAppliedLeanIsStillMovingToZero() {
        val loop = VelocityOuterLoop(config(
            joystickLeanSlewRateDegPerSec = 1.0,
            speedQuietDurationMs = 100,
            speedFilterAlpha = 1.0,
        ))
        var nowNs = 1_000_000_000L
        var sequence = 1L
        loop.step(nowNs, feedback(sequence, 0, 0, nowNs - 5_000_000L), targetCmPerSec = 10.0)
        repeat(20) {
            nowNs += 20_000_000L
            sequence += 1
            loop.step(nowNs, feedback(sequence, 0, 0, nowNs - 5_000_000L), targetCmPerSec = 10.0)
        }
        var output = loop.step(nowNs + 20_000_000L, feedback(sequence + 1, 0, 0, nowNs + 15_000_000L), targetCmPerSec = 0.0)
        assertEquals("WAIT_REST", output.autoTrimState)
        repeat(5) {
            nowNs += 20_000_000L
            sequence += 2
            output = loop.step(nowNs, feedback(sequence, 0, 0, nowNs - 5_000_000L), targetCmPerSec = 0.0)
        }
        assertEquals("WAIT_REST", output.autoTrimState)
        repeat(30) {
            nowNs += 20_000_000L
            sequence += 1
            output = loop.step(nowNs, feedback(sequence, 0, 0, nowNs - 5_000_000L), targetCmPerSec = 0.0)
        }
        assertEquals("REST", output.autoTrimState)
    }

    @Test
    fun brakingIsProportionalAndDoesNotCreateAnIntegral() {
        val loop = VelocityOuterLoop(config(brakeKpDegPerCmPerSec = 0.5, brakeLimitDeg = 1.0))
        val output = loop.step(1_000_000_000L, feedback(1, 1_000, 1_000, 995_000_000L), targetCmPerSec = 0.0)

        assertEquals(-1.0, output.brakeCorrectionDeg, 1e-9)
        assertEquals(0.0, output.autoTrimDeg, 1e-9)
        assertFalse(output.userCommandActive)
    }

    @Test
    fun staleFeedbackDecaysTheLastBrakeInsteadOfDroppingItInstantly() {
        val loop = VelocityOuterLoop(config(
            brakeKpDegPerCmPerSec = 0.5,
            brakeLimitDeg = 2.0,
            speedFilterAlpha = 1.0,
            speedFeedbackTimeoutMs = 40,
        ))
        val fresh = loop.step(1_000_000_000L, feedback(1, 1_000, 1_000, 995_000_000L), targetCmPerSec = 0.0)
        val stale = loop.step(1_060_000_000L, feedback(1, 1_000, 1_000, 995_000_000L), targetCmPerSec = 0.0)

        val expectedBrake = -0.5 * stepsPerSecondToCmPerSecond(1_000, 40.0, 1.0)
        assertEquals(expectedBrake, fresh.brakeCorrectionDeg, 1e-9)
        assertTrue(stale.stale)
        assertTrue(stale.brakeCorrectionDeg < 0.0)
        assertTrue(stale.brakeCorrectionDeg > fresh.brakeCorrectionDeg)
    }

    @Test
    fun finalTargetSafetyEnvelopeAndSlewRemainActive() {
        val loop = VelocityOuterLoop(config(
            targetDeg = 4.0,
            joystickMaxLeanDeg = 5.0,
            joystickLeanSlewRateDegPerSec = 180.0,
            speedAbsoluteAngleLimitDeg = 5.0,
            speedTargetSlewRateDegPerSec = 10.0,
        ))
        loop.step(1_000_000_000L, feedback(1, 0, 0, 995_000_000L), targetCmPerSec = 0.0)
        val output = loop.step(1_020_000_000L, feedback(2, 0, 0, 1_015_000_000L), targetCmPerSec = 10.0)

        assertEquals(3.6, output.correctionDeg, 1e-9)
        assertEquals(4.2, output.effectiveTargetDeg, 1e-9)
        assertTrue(output.saturated)
        assertTrue(output.slewLimited)
    }

    @Test
    fun meanSpeedUsesBothWheels() {
        val loop = VelocityOuterLoop(config(speedFilterAlpha = 1.0))
        val output = loop.step(1_000_000_000L, feedback(1, 1_000, -500, 995_000_000L), targetCmPerSec = 0.0)

        assertEquals((output.leftCmPerSec!! + output.rightCmPerSec!!) / 2.0, output.meanCmPerSec!!, 1e-9)
        assertEquals(output.meanCmPerSec!!, output.filteredCmPerSec!!, 1e-9)
    }

    private fun config(
        targetDeg: Double = 0.0,
        speedLoopEnabled: Boolean = true,
        speedTargetCmPerSec: Double = 0.0,
        speedTargetLimitCmPerSec: Double = 10.0,
        joystickMaxLeanDeg: Double = 3.0,
        joystickLeanSlewRateDegPerSec: Double = 30.0,
        joystickDeadbandCmPerSec: Double = 0.1,
        brakeKpDegPerCmPerSec: Double = 0.0,
        brakeLimitDeg: Double = 2.0,
        speedLoopRateHz: Int = 50,
        speedFilterAlpha: Double = 0.5,
        speedTargetAngleLimitDeg: Double = 10.0,
        speedIntegralGainDegPerCmPerSecSec: Double = 0.0,
        speedAbsoluteAngleLimitDeg: Double = 15.0,
        speedTargetSlewRateDegPerSec: Double = 30.0,
        speedFeedbackTimeoutMs: Long = 100,
        speedQuietThresholdCmPerSec: Double = 0.5,
        speedQuietDurationMs: Long = 700,
    ): RobotConfig = RobotConfig(
        targetDeg = targetDeg,
        speedLoopEnabled = speedLoopEnabled,
        speedTargetCmPerSec = speedTargetCmPerSec,
        speedTargetLimitCmPerSec = speedTargetLimitCmPerSec,
        joystickMaxLeanDeg = joystickMaxLeanDeg,
        joystickLeanSlewRateDegPerSec = joystickLeanSlewRateDegPerSec,
        joystickDeadbandCmPerSec = joystickDeadbandCmPerSec,
        brakeKpDegPerCmPerSec = brakeKpDegPerCmPerSec,
        brakeLimitDeg = brakeLimitDeg,
        speedLoopRateHz = speedLoopRateHz,
        speedFilterAlpha = speedFilterAlpha,
        speedTargetAngleLimitDeg = speedTargetAngleLimitDeg,
        speedIntegralGainDegPerCmPerSecSec = speedIntegralGainDegPerCmPerSecSec,
        speedAbsoluteAngleLimitDeg = speedAbsoluteAngleLimitDeg,
        speedTargetSlewRateDegPerSec = speedTargetSlewRateDegPerSec,
        speedFeedbackTimeoutMs = speedFeedbackTimeoutMs,
        speedQuietThresholdCmPerSec = speedQuietThresholdCmPerSec,
        speedQuietDurationMs = speedQuietDurationMs,
    )

    private fun feedback(sequence: Long, left: Int, right: Int, timestampNs: Long) =
        WheelVelocityFeedback(
            sequence = sequence,
            leftStepsPerSec = left,
            rightStepsPerSec = right,
            leftTimestampNs = timestampNs,
            rightTimestampNs = timestampNs,
        )
}
