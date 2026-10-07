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
        assertEquals(valid.effectiveTargetDeg, stale.effectiveTargetDeg, 1e-9)
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
    fun integralAutoTrimKeepsMovementBiasAtReleaseBeforeRampDecays() {
        val loop = VelocityOuterLoop(config(
            speedTargetCmPerSec = 5.0,
            speedIntegralGainDegPerCmPerSecSec = 1.0,
        ))
        loop.step(1_000_000_000L, feedback(1, 0, 0, 995_000_000L))
        val accumulated = loop.step(1_020_000_000L, feedback(2, 0, 0, 1_015_000_000L))
        assertTrue(accumulated.integralCorrectionDeg > 0.0)

        loop.updateConfig(config(speedTargetCmPerSec = 0.0, speedIntegralGainDegPerCmPerSecSec = 1.0))
        val retained = loop.step(1_040_000_000L, feedback(3, 1_000, 1_000, 1_035_000_000L))
        assertEquals(0.0, retained.appliedTargetCmPerSec, 1e-9)
        assertEquals("BRAKE_TO_ZERO", retained.autoTrimState)
        assertTrue(retained.integralCorrectionDeg < accumulated.integralCorrectionDeg)
    }

    @Test
    fun releaseTransfersMovementIntegralAlongAppliedSpeedRamp() {
        val loop = VelocityOuterLoop(config(
            speedTargetCmPerSec = 0.0,
            speedTargetSlewRateCmPerSec = 10.0,
            speedFilterAlpha = 1.0,
            speedIntegralGainDegPerCmPerSecSec = 1.0,
        ))
        loop.step(1_000_000_000L, feedback(1, 0, 0, 995_000_000L), 0.0)
        val moving = loop.step(1_020_000_000L, feedback(2, 0, 0, 1_015_000_000L), 5.0)
        val movingAgain = loop.step(1_040_000_000L, feedback(3, 0, 0, 1_035_000_000L), 5.0)
        assertTrue(movingAgain.integralCorrectionDeg > moving.integralCorrectionDeg)

        val halfway = loop.step(1_060_000_000L, feedback(4, 0, 0, 1_055_000_000L), 0.0)
        assertEquals(0.0, halfway.appliedTargetCmPerSec, 1e-9)
        assertEquals("SETTLING", halfway.autoTrimState)
        assertEquals(movingAgain.integralCorrectionDeg, halfway.integralCorrectionDeg, 1e-9)

        val complete = loop.step(1_080_000_000L, feedback(5, 0, 0, 1_075_000_000L), 0.0)
        assertEquals(0.0, complete.appliedTargetCmPerSec, 1e-9)
        assertEquals(halfway.integralCorrectionDeg, complete.integralCorrectionDeg, 1e-9)
    }

    @Test
    fun pureYawKeepsIntegralActiveAndDoesNotRestoreRestCheckpointOnRelease() {
        val loop = VelocityOuterLoop(config(
            speedTargetCmPerSec = 0.0,
            speedFilterAlpha = 1.0,
            speedIntegralGainDegPerCmPerSecSec = 1.0,
            speedQuietDurationMs = 200,
        ))

        loop.step(
            1_000_000_000L,
            feedback(1, 0, 0, 995_000_000L),
            targetCmPerSec = 0.0,
            yawTargetDegPerSec = 60.0,
            yawRateDegPerSec = 20.0,
        )
        val duringYaw = loop.step(
            1_020_000_000L,
            feedback(2, 100, 100, 1_015_000_000L),
            targetCmPerSec = 0.0,
            yawTargetDegPerSec = 60.0,
            yawRateDegPerSec = 20.0,
        )
        assertEquals("MANEUVER", duringYaw.autoTrimState)
        assertTrue(duringYaw.integralCorrectionDeg < 0.0)

        val releasedYaw = loop.step(
            1_040_000_000L,
            feedback(3, 1_000, 1_000, 1_035_000_000L),
            targetCmPerSec = 0.0,
            yawTargetDegPerSec = 0.0,
            yawRateDegPerSec = 0.0,
        )
        assertEquals("BRAKE_TO_ZERO", releasedYaw.autoTrimState)
        assertTrue(releasedYaw.integralCorrectionDeg < 0.0)

        var nowNs = 1_040_000_000L
        var sequence = 3L
        var settled = releasedYaw
        repeat(12) {
            nowNs += 20_000_000L
            sequence += 1L
            settled = loop.step(
                nowNs,
                feedback(sequence, 0, 0, nowNs - 5_000_000L),
                targetCmPerSec = 0.0,
                yawTargetDegPerSec = 0.0,
                yawRateDegPerSec = 0.0,
            )
        }
        assertEquals("REST", settled.autoTrimState)
        assertEquals(0.0, settled.integralCorrectionDeg, 1e-9)
    }

    @Test
    fun autoTrimCheckpointRequiresContinuousQuietRest() {
        val loop = VelocityOuterLoop(config(
            speedTargetCmPerSec = 0.0,
            speedTargetSlewRateCmPerSec = 100.0,
            speedFilterAlpha = 1.0,
            speedIntegralGainDegPerCmPerSecSec = 1.0,
        ))
        loop.step(1_000_000_000L, feedback(1, 0, 0, 995_000_000L), 0.0)
        val rest = loop.step(1_020_000_000L, feedback(2, -100, -100, 1_015_000_000L), 0.0)
        val moving = loop.step(1_040_000_000L, feedback(3, 0, 0, 1_035_000_000L), 5.0)
        assertTrue(moving.integralCorrectionDeg > rest.integralCorrectionDeg)

        val released = loop.step(1_060_000_000L, feedback(4, 1_000, 1_000, 1_055_000_000L), 0.0)
        val rolling = loop.step(1_080_000_000L, feedback(5, 1_000, 1_000, 1_075_000_000L), 0.0)
        assertEquals("BRAKE_TO_ZERO", released.autoTrimState)
        assertTrue(released.integralCorrectionDeg != rest.integralCorrectionDeg)
        assertEquals("BRAKE_TO_ZERO", rolling.autoTrimState)

        val crossing = loop.step(1_100_000_000L, feedback(6, 0, 0, 1_095_000_000L), 0.0)
        val afterCrossing = loop.step(1_120_000_000L, feedback(7, 1_000, 1_000, 1_115_000_000L), 0.0)
        assertEquals("SETTLING", crossing.autoTrimState)
        assertTrue(crossing.integralCorrectionDeg != rest.integralCorrectionDeg)
        assertEquals("BRAKE_TO_ZERO", afterCrossing.autoTrimState)
        assertTrue(afterCrossing.integralCorrectionDeg != crossing.integralCorrectionDeg)

        var nowNs = 1_120_000_000L
        var sequence = 7L
        var pitchMoving = afterCrossing
        repeat(40) {
            nowNs += 20_000_000L
            sequence += 1
            pitchMoving = loop.step(
                nowNs,
                feedback(sequence, 100, 100, nowNs - 5_000_000L),
                0.0,
                pitchRateDegPerSec = 5.0,
            )
        }
        assertTrue(pitchMoving.integralCorrectionDeg != afterCrossing.integralCorrectionDeg)

        var relearned = pitchMoving
        repeat(40) {
            nowNs += 20_000_000L
            sequence += 1
            relearned = loop.step(
                nowNs,
                feedback(sequence, 100, 100, nowNs - 5_000_000L),
                0.0,
                pitchRateDegPerSec = 0.0,
            )
        }
        assertEquals("REST", relearned.autoTrimState)
    }

    @Test
    fun initialRestWithoutCheckpointCanLearnEvenBeforeQuietBand() {
        val loop = VelocityOuterLoop(config(
            speedTargetCmPerSec = 0.0,
            speedFilterAlpha = 1.0,
            speedIntegralGainDegPerCmPerSecSec = 1.0,
        ))

        loop.step(
            1_000_000_000L,
            feedback(1, 1_000, 1_000, 995_000_000L),
            targetCmPerSec = 0.0,
            pitchRateDegPerSec = 8.0,
        )
        val learned = loop.step(
            1_020_000_000L,
            feedback(2, 1_000, 1_000, 1_015_000_000L),
            targetCmPerSec = 0.0,
            pitchRateDegPerSec = 8.0,
        )

        assertTrue(learned.integralCorrectionDeg < 0.0)
        assertEquals("REST", learned.autoTrimState)
    }

    @Test
    fun quietUsesAbsoluteFilteredMeanAndConfiguredThresholdAndDuration() {
        val loop = VelocityOuterLoop(config(
            speedTargetCmPerSec = 0.0,
            speedFilterAlpha = 1.0,
            speedQuietThresholdCmPerSec = 0.5,
            speedQuietDurationMs = 2_000,
        ))

        val oppositeWheels = loop.step(
            1_000_000_000L,
            feedback(1, 500, -500, 995_000_000L),
            targetCmPerSec = 0.0,
        )
        assertEquals(0.0, oppositeWheels.meanCmPerSec!!, 1e-9)
        assertTrue(oppositeWheels.quiet)

        val settlingLoop = VelocityOuterLoop(config(
            speedTargetCmPerSec = 0.0,
            speedFilterAlpha = 1.0,
            speedQuietThresholdCmPerSec = 0.5,
            speedQuietDurationMs = 2_000,
        ))
        settlingLoop.step(1_000_000_000L, feedback(1, 0, 0, 995_000_000L), 0.0)
        settlingLoop.step(1_020_000_000L, feedback(2, 0, 0, 1_015_000_000L), 5.0)
        var nowNs = 1_040_000_000L
        var sequence = 3L
        var output = settlingLoop.step(
            nowNs,
            feedback(sequence, 0, 0, nowNs - 5_000_000L),
            targetCmPerSec = 0.0,
        )
        repeat(40) {
            nowNs += 20_000_000L
            sequence += 1L
            output = settlingLoop.step(
                nowNs,
                feedback(sequence, 100, 100, nowNs - 5_000_000L),
                targetCmPerSec = 0.0,
            )
        }
        assertTrue(kotlin.math.abs(output.meanCmPerSec!!) < 0.5)
        assertTrue(output.quiet)
        assertEquals("SETTLING", output.autoTrimState)
        assertTrue(output.settledDurationSec < 2.0)
    }

    @Test
    fun staleFeedbackPreservesIntegralCorrection() {
        val loop = VelocityOuterLoop(config(
            speedTargetCmPerSec = 0.0,
            speedFilterAlpha = 1.0,
            speedIntegralGainDegPerCmPerSecSec = 1.0,
        ))
        loop.step(1_000_000_000L, feedback(1, 0, 0, 995_000_000L), 0.0)
        val moving = loop.step(1_020_000_000L, feedback(2, 0, 0, 1_015_000_000L), 5.0)
        val stale = loop.step(1_140_000_000L, feedback(2, 0, 0, 1_015_000_000L), 5.0)

        assertTrue(moving.integralCorrectionDeg > 0.0)
        assertTrue(stale.stale)
        assertEquals(moving.integralCorrectionDeg, stale.integralCorrectionDeg, 1e-9)
    }

    @Test
    fun staleFeedbackDoesNotCountTowardContinuousQuietDuration() {
        val loop = VelocityOuterLoop(config(
            speedTargetCmPerSec = 0.0,
            speedFilterAlpha = 1.0,
            speedQuietThresholdCmPerSec = 0.5,
            speedQuietDurationMs = 1_000,
        ))
        loop.step(1_000_000_000L, feedback(1, 0, 0, 995_000_000L), 0.0)
        loop.step(1_020_000_000L, feedback(2, 0, 0, 1_015_000_000L), 5.0)
        loop.step(1_040_000_000L, feedback(3, 0, 0, 1_035_000_000L), 0.0)
        repeat(10) { index ->
            val nowNs = 1_060_000_000L + index * 20_000_000L
            loop.step(nowNs, feedback(4L + index, 100, 100, nowNs - 5_000_000L), 0.0)
        }

        val stale = loop.step(1_300_000_000L, feedback(13, 100, 100, 1_060_000_000L), 0.0)
        assertTrue(stale.stale)
        val recovered = loop.step(
            1_320_000_000L,
            feedback(14, 100, 100, 1_315_000_000L),
            0.0,
        )
        assertEquals("SETTLING", recovered.autoTrimState)
        assertEquals(0.0, recovered.settledDurationSec, 1e-9)
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
    fun speedTargetSlewRateLimitsLiveSpeedCommandChanges() {
        val loop = VelocityOuterLoop(config(
            speedTargetCmPerSec = 0.0,
            speedTargetSlewRateCmPerSec = 10.0,
            speedLoopRateHz = 50,
        ))
        val initial = loop.step(
            1_000_000_000L,
            feedback(1, 0, 0, 995_000_000L),
            targetCmPerSec = 0.0,
        )
        val changed = loop.step(
            1_020_000_000L,
            feedback(2, 0, 0, 1_015_000_000L),
            targetCmPerSec = 5.0,
        )

        assertEquals(0.0, initial.appliedTargetCmPerSec, 1e-9)
        assertEquals(5.0, changed.targetCmPerSec, 1e-9)
        assertEquals(0.2, changed.appliedTargetCmPerSec, 1e-9)
        assertTrue(changed.speedCommandSlewLimited)
    }

    @Test
    fun releasingYawWhileTranslationContinuesDoesNotResetTheManeuver() {
        val loop = VelocityOuterLoop(config(
            speedTargetCmPerSec = 5.0,
            speedFilterAlpha = 1.0,
            speedIntegralGainDegPerCmPerSecSec = 1.0,
        ))
        loop.step(
            1_000_000_000L,
            feedback(1, 0, 0, 995_000_000L),
            targetCmPerSec = 5.0,
            yawTargetDegPerSec = 60.0,
        )
        val duringCombined = loop.step(
            1_020_000_000L,
            feedback(2, 0, 0, 1_015_000_000L),
            targetCmPerSec = 5.0,
            yawTargetDegPerSec = 60.0,
        )
        val yawReleased = loop.step(
            1_040_000_000L,
            feedback(3, 1_000, 1_000, 1_035_000_000L),
            targetCmPerSec = 5.0,
            yawTargetDegPerSec = 0.0,
        )

        assertEquals("MANEUVER", yawReleased.autoTrimState)
        assertTrue(yawReleased.integralCorrectionDeg != duringCombined.integralCorrectionDeg)
    }

    @Test
    fun releasingTranslationWhileYawContinuesSetsZeroSpeedButKeepsIntegralActive() {
        val loop = VelocityOuterLoop(config(
            speedTargetCmPerSec = 5.0,
            speedFilterAlpha = 1.0,
            speedIntegralGainDegPerCmPerSecSec = 1.0,
        ))
        loop.step(
            1_000_000_000L,
            feedback(1, 0, 0, 995_000_000L),
            targetCmPerSec = 5.0,
            yawTargetDegPerSec = 60.0,
        )
        val beforeRelease = loop.step(
            1_020_000_000L,
            feedback(2, 0, 0, 1_015_000_000L),
            targetCmPerSec = 5.0,
            yawTargetDegPerSec = 60.0,
        )
        val translationReleased = loop.step(
            1_040_000_000L,
            feedback(3, 1_000, 1_000, 1_035_000_000L),
            targetCmPerSec = 0.0,
            yawTargetDegPerSec = 60.0,
        )

        assertEquals(0.0, translationReleased.appliedTargetCmPerSec, 1e-9)
        assertEquals("MANEUVER", translationReleased.autoTrimState)
        assertTrue(translationReleased.integralCorrectionDeg != beforeRelease.integralCorrectionDeg)
    }

    @Test
    fun settlingExitReactivatesIntegralInsteadOfFreezingAtCheckpoint() {
        val loop = VelocityOuterLoop(config(
            speedTargetCmPerSec = 5.0,
            speedFilterAlpha = 1.0,
            speedIntegralGainDegPerCmPerSecSec = 1.0,
        ))
        loop.step(1_000_000_000L, feedback(1, 0, 0, 995_000_000L), 5.0)
        val moving = loop.step(1_020_000_000L, feedback(2, 0, 0, 1_015_000_000L), 5.0)
        val release = loop.step(1_040_000_000L, feedback(3, 1_000, 1_000, 1_035_000_000L), 0.0)
        assertEquals("BRAKE_TO_ZERO", release.autoTrimState)
        val settled = loop.step(1_060_000_000L, feedback(4, 0, 0, 1_055_000_000L), 0.0)
        assertEquals("SETTLING", settled.autoTrimState)

        val departed = loop.step(1_080_000_000L, feedback(5, 1_000, 1_000, 1_075_000_000L), 0.0)
        assertEquals("BRAKE_TO_ZERO", departed.autoTrimState)
        assertTrue(departed.integralCorrectionDeg != settled.integralCorrectionDeg)
        assertTrue(moving.integralCorrectionDeg != 0.0)
    }

    @Test
    fun physicalStopNeedsEquilibriumAngleAndContinuousQualification() {
        val loop = VelocityOuterLoop(config(
            targetDeg = 8.0,
            speedTargetCmPerSec = 0.0,
            speedFilterAlpha = 1.0,
            speedIntegralGainDegPerCmPerSecSec = 1.0,
            speedQuietThresholdCmPerSec = 0.5,
            speedQuietDurationMs = 100,
            speedTargetSlewRateDegPerSec = 180.0,
        ))

        loop.step(
            1_000_000_000L,
            feedback(1, 0, 0, 995_000_000L),
            targetCmPerSec = 0.0,
            pitchAngleDeg = 8.0,
        )
        loop.step(
            1_020_000_000L,
            feedback(2, 0, 0, 1_015_000_000L),
            targetCmPerSec = 5.0,
            pitchAngleDeg = 8.0,
        )
        val moving = loop.step(
            1_040_000_000L,
            feedback(3, 0, 0, 1_035_000_000L),
            targetCmPerSec = 5.0,
            pitchAngleDeg = 8.0,
        )
        val wrongAngle = loop.step(
            1_060_000_000L,
            feedback(4, 0, 0, 1_055_000_000L),
            targetCmPerSec = 0.0,
            pitchAngleDeg = 12.0,
        )
        assertEquals("BRAKE_TO_ZERO", wrongAngle.autoTrimState)
        assertFalse(wrongAngle.physicalStopCandidate)

        val firstCandidate = loop.step(
            1_080_000_000L,
            feedback(5, 0, 0, 1_075_000_000L),
            targetCmPerSec = 0.0,
            pitchAngleDeg = 8.0,
        )
        assertEquals("SETTLING", firstCandidate.autoTrimState)
        assertEquals(moving.integralCorrectionDeg, firstCandidate.integralCorrectionDeg, 1e-9)

        var output = firstCandidate
        var nowNs = 1_080_000_000L
        var sequence = 5L
        repeat(4) {
            nowNs += 20_000_000L
            sequence += 1L
            output = loop.step(
                nowNs,
                feedback(sequence, 0, 0, nowNs - 5_000_000L),
                targetCmPerSec = 0.0,
                pitchAngleDeg = 8.0,
            )
            assertEquals("SETTLING", output.autoTrimState)
            assertEquals(moving.integralCorrectionDeg, output.integralCorrectionDeg, 1e-9)
        }
        nowNs += 20_000_000L
        sequence += 1L
        output = loop.step(
            nowNs,
            feedback(sequence, 0, 0, nowNs - 5_000_000L),
            targetCmPerSec = 0.0,
            pitchAngleDeg = 8.0,
        )

        assertEquals("REST", output.autoTrimState)
        assertEquals(0.0, output.integralCorrectionDeg, 1e-9)
        assertEquals(8.0, output.effectiveTargetDeg, 1e-9)
        assertFalse(output.slewLimited)
    }

    @Test
    fun newManeuverDuringBrakingKeepsUnvalidatedRestCheckpoint() {
        val loop = VelocityOuterLoop(config(
            speedTargetCmPerSec = 0.0,
            speedFilterAlpha = 1.0,
            speedIntegralGainDegPerCmPerSecSec = 1.0,
        ))

        loop.step(1_000_000_000L, feedback(1, -100, -100, 995_000_000L), 0.0)
        val rest = loop.step(1_020_000_000L, feedback(2, -100, -100, 1_015_000_000L), 0.0)
        val moving = loop.step(1_040_000_000L, feedback(3, 0, 0, 1_035_000_000L), 5.0)
        val released = loop.step(1_060_000_000L, feedback(4, 1_000, 1_000, 1_055_000_000L), 0.0)
        assertEquals("BRAKE_TO_ZERO", released.autoTrimState)

        val restarted = loop.step(1_080_000_000L, feedback(5, 1_000, 1_000, 1_075_000_000L), 5.0)

        assertEquals("MANEUVER", restarted.autoTrimState)
        assertEquals(rest.restCheckpointDeg, restarted.restCheckpointDeg, 1e-9)
        assertNotEquals(rest.restCheckpointDeg, moving.integralCorrectionDeg, 1e-9)
    }

    @Test
    fun integralDtComesFromNewWheelFeedbackTimestamps() {
        val loop = VelocityOuterLoop(config(
            speedTargetCmPerSec = 5.0,
            speedFilterAlpha = 1.0,
            speedIntegralGainDegPerCmPerSecSec = 1.0,
        ))
        loop.step(1_000_000_000L, feedback(1, 0, 0, 995_000_000L), 5.0)
        val output = loop.step(1_040_000_000L, feedback(2, 0, 0, 1_015_000_000L), 5.0)

        assertEquals(20.0, output.feedbackDtMs!!, 1e-9)
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
        speedTargetSlewRateCmPerSec: Double = 20.0,
        speedKevDegPerCmPerSec: Double = 0.0,
        speedLoopRateHz: Int = 50,
        speedFilterAlpha: Double = 0.5,
        speedTargetAngleLimitDeg: Double = 10.0,
        speedIntegralGainDegPerCmPerSecSec: Double = 0.0,
        speedAbsoluteAngleLimitDeg: Double = 15.0,
        speedTargetSlewRateDegPerSec: Double = 30.0,
        speedFeedbackTimeoutMs: Long = 100,
        speedQuietThresholdCmPerSec: Double = 0.5,
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
        speedAbsoluteAngleLimitDeg = speedAbsoluteAngleLimitDeg,
        speedTargetSlewRateDegPerSec = speedTargetSlewRateDegPerSec,
        speedFeedbackTimeoutMs = speedFeedbackTimeoutMs,
        speedQuietThresholdCmPerSec = speedQuietThresholdCmPerSec,
        speedQuietDurationMs = speedQuietDurationMs,
    )

    private fun feedback(
        sequence: Long,
        left: Int,
        right: Int,
        timestampNs: Long,
    ) = WheelVelocityFeedback(sequence, left, right, timestampNs, timestampNs)
}
