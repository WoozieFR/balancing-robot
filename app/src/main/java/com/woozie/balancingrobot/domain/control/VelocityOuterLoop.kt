package com.woozie.balancingrobot.domain.control

import com.woozie.balancingrobot.domain.model.RobotConfig
import com.woozie.balancingrobot.domain.model.RobotConfigValidator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

data class WheelVelocityFeedback(
    val sequence: Long,
    val leftStepsPerSec: Int,
    val rightStepsPerSec: Int,
    val leftTimestampNs: Long,
    val rightTimestampNs: Long,
)

data class VelocityLoopOutput(
    val enabled: Boolean,
    val updated: Boolean,
    val stale: Boolean,
    val feedbackSequence: Long? = null,
    val feedbackAgeMs: Double? = null,
    val feedbackDtMs: Double? = null,
    val leftRawStepsPerSec: Int? = null,
    val rightRawStepsPerSec: Int? = null,
    val leftCmPerSec: Double? = null,
    val rightCmPerSec: Double? = null,
    val meanCmPerSec: Double? = null,
    val filteredCmPerSec: Double? = null,
    val targetCmPerSec: Double,
    val appliedTargetCmPerSec: Double = targetCmPerSec,
    val speedCommandSlewLimited: Boolean = false,
    val errorCmPerSec: Double? = null,
    val proportionalCorrectionDeg: Double = 0.0,
    val correctionDeg: Double = 0.0,
    val integralCorrectionDeg: Double = 0.0,
    val trimDeg: Double,
    val effectiveTargetDeg: Double,
    val autoTrimState: String = "REST",
    val restTrimDeg: Double = 0.0,
    val restCheckpointDeg: Double = restTrimDeg,
    val settledDurationSec: Double = 0.0,
    val physicalStopCandidate: Boolean = false,
    val quiet: Boolean = false,
    val saturated: Boolean = false,
    val slewLimited: Boolean = false,
)

fun normalizeWheelVelocity(rawStepsPerSecond: Int, motorSign: Int): Int {
    require(motorSign == -1 || motorSign == 1) { "motor sign must be -1 or 1" }
    return rawStepsPerSecond * motorSign
}

fun stepsPerSecondToCmPerSecond(
    stepsPerSecond: Int,
    wheelDiameterMm: Double,
    driveRatio: Double,
): Double {
    require(wheelDiameterMm.isFinite() && wheelDiameterMm > 0.0)
    require(driveRatio.isFinite() && driveRatio > 0.0)
    val circumferenceCm = PI * wheelDiameterMm / 10.0
    return stepsPerSecond.toDouble() * circumferenceCm / (4096.0 * driveRatio)
}

/** Stateful, I/O-free outer velocity loop. Called from the IMU control thread. */
class VelocityOuterLoop(initialConfig: RobotConfig) {
    private enum class AutoTrimState { MANEUVER, BRAKE_TO_ZERO, SETTLING, REST }

    private var config = validated(initialConfig)
    private var lastTickNs = 0L
    private var lastFeedbackSequence: Long? = null
    private var lastFeedbackTimestampNs: Long? = null
    private var filteredCmPerSec: Double? = null
    private var wasStale = true
    private var integralCorrectionDeg = 0.0
    private var restTrimDeg = 0.0
    private var wasManeuverActive = false
    private var autoTrimState = AutoTrimState.REST
    private var settledDurationSec = 0.0
    private var staleDurationSec = 0.0
    private var currentTargetDeg: Double? = null
    private var currentSpeedTargetCmPerSec: Double? = null
    private var lastOutput: VelocityLoopOutput? = null

    fun updateConfig(newConfig: RobotConfig) {
        val valid = validated(newConfig)
        val previous = config
        val modeChanged = previous.speedLoopEnabled != valid.speedLoopEnabled
        val resetFilter = modeChanged ||
            previous.wheelDiameterMm != valid.wheelDiameterMm ||
            previous.driveRatio != valid.driveRatio ||
            previous.speedFilterAlpha != valid.speedFilterAlpha
        config = valid
        lastTickNs = 0L
        if (!valid.speedLoopEnabled || modeChanged) {
            integralCorrectionDeg = 0.0
            restTrimDeg = 0.0
            wasManeuverActive = false
            autoTrimState = AutoTrimState.REST
            settledDurationSec = 0.0
            staleDurationSec = 0.0
            currentTargetDeg = null
            currentSpeedTargetCmPerSec = null
        } else {
            integralCorrectionDeg = integralCorrectionDeg.coerceIn(
                -valid.speedTargetAngleLimitDeg,
                valid.speedTargetAngleLimitDeg,
            )
            restTrimDeg = restTrimDeg.coerceIn(
                -valid.speedTargetAngleLimitDeg,
                valid.speedTargetAngleLimitDeg,
            )
        }
        if (resetFilter) {
            filteredCmPerSec = null
            lastFeedbackSequence = null
            wasStale = true
            integralCorrectionDeg = 0.0
            restTrimDeg = 0.0
            wasManeuverActive = false
            autoTrimState = AutoTrimState.REST
            settledDurationSec = 0.0
            staleDurationSec = 0.0
            currentTargetDeg = null
            currentSpeedTargetCmPerSec = null
            lastOutput = null
        }
    }

    fun reset() {
        lastTickNs = 0L
        lastFeedbackSequence = null
        lastFeedbackTimestampNs = null
        filteredCmPerSec = null
        wasStale = true
        integralCorrectionDeg = 0.0
        restTrimDeg = 0.0
        wasManeuverActive = false
        autoTrimState = AutoTrimState.REST
        settledDurationSec = 0.0
        staleDurationSec = 0.0
        currentTargetDeg = null
        currentSpeedTargetCmPerSec = null
        lastOutput = null
    }

    fun step(
        nowNs: Long,
        feedback: WheelVelocityFeedback?,
        targetCmPerSec: Double = config.speedTargetCmPerSec,
        pitchRateDegPerSec: Double = 0.0,
        yawTargetDegPerSec: Double = config.yawTargetDegPerSec,
        yawRateDegPerSec: Double = 0.0,
        pitchAngleDeg: Double? = null,
    ): VelocityLoopOutput {
        require(nowNs > 0L) { "monotonic timestamp must be positive" }
        require(targetCmPerSec.isFinite()) { "speed target must be finite" }
        require(pitchRateDegPerSec.isFinite()) { "pitch gyro rate must be finite" }
        require(yawTargetDegPerSec.isFinite()) { "yaw target must be finite" }
        require(yawRateDegPerSec.isFinite()) { "yaw gyro rate must be finite" }
        require(pitchAngleDeg?.isFinite() != false) { "pitch angle must be finite" }
        val periodNs = 1_000_000_000L / config.speedLoopRateHz
        if (lastTickNs > 0L && nowNs >= lastTickNs && nowNs - lastTickNs < periodNs) {
            return checkNotNull(lastOutput).copy(updated = false)
        }
        if (lastTickNs > nowNs) reset()
        val previousTickNs = lastTickNs
        lastTickNs = nowNs
        val dtSec = if (previousTickNs > 0L) {
            ((nowNs - previousTickNs).toDouble() / 1_000_000_000.0).coerceIn(0.0, 0.5)
        } else 0.0
        val requestedSpeedTarget = targetCmPerSec.coerceIn(
            -config.speedTargetLimitCmPerSec,
            config.speedTargetLimitCmPerSec,
        )
        val speedCommandActive = abs(requestedSpeedTarget) > COMMAND_EPSILON
        val yawCommandActive = abs(yawTargetDegPerSec) > COMMAND_EPSILON
        val maneuverActive = speedCommandActive || yawCommandActive
        val startedManeuver = !wasManeuverActive && maneuverActive
        val releasedManeuver = wasManeuverActive && !maneuverActive
        if (startedManeuver) {
            // A checkpoint is captured only from a validated REST state. If
            // the user starts a new maneuver while braking or settling, keep
            // the previous rest checkpoint: it has not been validated yet.
            if (autoTrimState == AutoTrimState.REST) {
                restTrimDeg = integralCorrectionDeg
            }
            autoTrimState = AutoTrimState.MANEUVER
            settledDurationSec = 0.0
        } else if (maneuverActive) {
            autoTrimState = AutoTrimState.MANEUVER
            settledDurationSec = 0.0
        } else if (releasedManeuver) {
            autoTrimState = AutoTrimState.BRAKE_TO_ZERO
            settledDurationSec = 0.0
        }
        wasManeuverActive = maneuverActive
        val appliedSpeedTarget = approachSpeedTarget(
            requestedSpeedTarget,
            dtSec,
            initializeAtDesired = previousTickNs == 0L,
        )
        val speedCommandSlewLimited = appliedSpeedTarget != requestedSpeedTarget
        val ageNs = feedback?.let {
            nowNs - min(it.leftTimestampNs, it.rightTimestampNs)
        }
        val fresh = feedback != null && ageNs != null && ageNs >= 0L &&
            ageNs <= config.speedFeedbackTimeoutMs * 1_000_000L
        if (!fresh) {
            wasStale = true
            staleDurationSec = (staleDurationSec + dtSec).coerceAtMost(10.0)
            // The quiet interval must be continuous on fresh feedback. A
            // telemetry gap cannot contribute to the settled duration.
            settledDurationSec = 0.0
            // A single missed telemetry sample must not erase the learned trim
            // or make the target angle jump. Hold the last target briefly,
            // then return progressively toward the saved rest trim.
            val trim = config.targetDeg
            val restTarget = if (config.speedLoopEnabled) {
                (trim + restTrimDeg).coerceIn(
                    -config.speedAbsoluteAngleLimitDeg,
                    config.speedAbsoluteAngleLimitDeg,
                )
            } else trim
            val desired = if (config.speedLoopEnabled && currentTargetDeg != null) {
                currentTargetDeg!!
            } else restTarget
            val effective = approachTarget(desired, dtSec, config.speedLoopEnabled)
            return (lastOutput ?: VelocityLoopOutput(
                enabled = config.speedLoopEnabled,
                updated = true,
                stale = true,
                targetCmPerSec = requestedSpeedTarget,
                appliedTargetCmPerSec = appliedSpeedTarget,
                speedCommandSlewLimited = speedCommandSlewLimited,
                trimDeg = trim,
                effectiveTargetDeg = effective,
                autoTrimState = autoTrimState.name,
                restTrimDeg = restTrimDeg,
                settledDurationSec = settledDurationSec,
            )).copy(
                enabled = config.speedLoopEnabled,
                updated = true,
                stale = true,
                feedbackAgeMs = ageNs?.coerceAtLeast(0L)?.div(1_000_000.0),
                targetCmPerSec = requestedSpeedTarget,
                appliedTargetCmPerSec = appliedSpeedTarget,
                speedCommandSlewLimited = speedCommandSlewLimited,
                correctionDeg = effective - trim,
                integralCorrectionDeg = integralCorrectionDeg,
                trimDeg = trim,
                effectiveTargetDeg = effective,
                autoTrimState = autoTrimState.name,
                restTrimDeg = restTrimDeg,
                settledDurationSec = settledDurationSec,
                saturated = false,
                slewLimited = effective != desired,
            ).also { lastOutput = it }
        }

        feedback!!
        staleDurationSec = 0.0
        val feedbackTimestampNs = min(feedback.leftTimestampNs, feedback.rightTimestampNs)
        val newFeedback = feedback.sequence != lastFeedbackSequence
        val feedbackWasStale = wasStale
        val feedbackDtSec = if (newFeedback && !feedbackWasStale && lastFeedbackTimestampNs != null) {
            ((feedbackTimestampNs - checkNotNull(lastFeedbackTimestampNs)).toDouble() /
                1_000_000_000.0).coerceIn(0.0, 0.5)
        } else 0.0
        val left = stepsPerSecondToCmPerSecond(
            feedback.leftStepsPerSec,
            config.wheelDiameterMm,
            config.driveRatio,
        )
        val right = stepsPerSecondToCmPerSecond(
            feedback.rightStepsPerSec,
            config.wheelDiameterMm,
            config.driveRatio,
        )
        val mean = (left + right) / 2.0
        if (newFeedback) {
            filteredCmPerSec = if (filteredCmPerSec == null || feedbackWasStale) mean else {
                config.speedFilterAlpha * mean +
                    (1.0 - config.speedFilterAlpha) * checkNotNull(filteredCmPerSec)
            }
            lastFeedbackSequence = feedback.sequence
            lastFeedbackTimestampNs = feedbackTimestampNs
        }
        wasStale = false
        val filtered = checkNotNull(filteredCmPerSec)
        // A zero crossing of the wheel speed is not a physical stop. The
        // pendulum must also be close to the equilibrium represented by the
        // checkpoint and have low pitch/yaw rates. The angle is optional for
        // callers that do not have an estimate yet; production supplies the
        // latest complementary-filter estimate.
        val checkpointAngleDeg = config.targetDeg + restTrimDeg
        val angleErrorToCheckpointDeg = pitchAngleDeg?.let {
            abs(it - checkpointAngleDeg)
        }
        val physicalStopCandidate = abs(filtered) < config.speedQuietThresholdCmPerSec &&
            abs(pitchRateDegPerSec) < PHYSICAL_STOP_PITCH_RATE_THRESHOLD_DEG_PER_SEC &&
            abs(yawRateDegPerSec) < QUIET_YAW_RATE_THRESHOLD_DEG_PER_SEC &&
            (angleErrorToCheckpointDeg == null ||
                angleErrorToCheckpointDeg < PHYSICAL_STOP_ANGLE_THRESHOLD_DEG)
        val quiet = physicalStopCandidate &&
            abs(yawTargetDegPerSec) <= COMMAND_EPSILON &&
            abs(appliedSpeedTarget) <= COMMAND_EPSILON
        var checkpointRestoredThisStep = false
        if (newFeedback && feedbackDtSec > 0.0 && !maneuverActive) {
            when (autoTrimState) {
                AutoTrimState.MANEUVER -> {
                    autoTrimState = AutoTrimState.BRAKE_TO_ZERO
                    settledDurationSec = 0.0
                }
                AutoTrimState.BRAKE_TO_ZERO -> {
                    if (physicalStopCandidate) {
                        // Start a continuous qualification window. Do not
                        // restore the checkpoint on the first near-zero
                        // sample: the robot may only be crossing v=0.
                        autoTrimState = AutoTrimState.SETTLING
                        settledDurationSec = 0.0
                    }
                }
                AutoTrimState.SETTLING -> {
                    if (!physicalStopCandidate) {
                        autoTrimState = AutoTrimState.BRAKE_TO_ZERO
                        settledDurationSec = 0.0
                    } else {
                        settledDurationSec += feedbackDtSec
                        if (settledDurationSec >= config.speedQuietDurationMs / 1_000.0) {
                            integralCorrectionDeg = restTrimDeg
                            autoTrimState = AutoTrimState.REST
                            checkpointRestoredThisStep = true
                        }
                    }
                }
                AutoTrimState.REST -> Unit
            }
        }
        val error = appliedSpeedTarget - filtered
        if (!config.speedLoopEnabled) {
            integralCorrectionDeg = 0.0
            currentTargetDeg = config.targetDeg
            return VelocityLoopOutput(
                enabled = false,
                updated = true,
                stale = false,
                feedbackSequence = feedback.sequence,
                feedbackAgeMs = ageNs / 1_000_000.0,
                feedbackDtMs = feedbackDtSec * 1_000.0,
                leftRawStepsPerSec = feedback.leftStepsPerSec,
                rightRawStepsPerSec = feedback.rightStepsPerSec,
                leftCmPerSec = left,
                rightCmPerSec = right,
                meanCmPerSec = mean,
                filteredCmPerSec = filtered,
                targetCmPerSec = requestedSpeedTarget,
                appliedTargetCmPerSec = appliedSpeedTarget,
                speedCommandSlewLimited = speedCommandSlewLimited,
                errorCmPerSec = error,
                trimDeg = config.targetDeg,
                effectiveTargetDeg = config.targetDeg,
                autoTrimState = autoTrimState.name,
                restTrimDeg = restTrimDeg,
                restCheckpointDeg = restTrimDeg,
                settledDurationSec = settledDurationSec,
                physicalStopCandidate = physicalStopCandidate,
                quiet = quiet,
            ).also { lastOutput = it }
        }

        val proportionalCorrection = config.speedKevDegPerCmPerSec * error
        val integralCandidate = (integralCorrectionDeg +
            config.speedIntegralGainDegPerCmPerSecSec * error * feedbackDtSec)
            .coerceIn(-config.speedTargetAngleLimitDeg, config.speedTargetAngleLimitDeg)
        val rawCorrection = proportionalCorrection + integralCandidate
        val correction = rawCorrection.coerceIn(
            -config.speedTargetAngleLimitDeg,
            config.speedTargetAngleLimitDeg,
        )
        val correctionSaturated = rawCorrection != correction
        val rawTargetCandidate = config.targetDeg + rawCorrection
        val absoluteTargetCandidate = rawTargetCandidate.coerceIn(
            -config.speedAbsoluteAngleLimitDeg,
            config.speedAbsoluteAngleLimitDeg,
        )
        val absoluteTargetSaturated = absoluteTargetCandidate != rawTargetCandidate
        val integrationWouldWorsenSaturation =
            (correctionSaturated && ((rawCorrection > correction && error > 0.0) ||
                (rawCorrection < correction && error < 0.0))) ||
                (absoluteTargetSaturated && ((rawTargetCandidate > absoluteTargetCandidate && error > 0.0) ||
                    (rawTargetCandidate < absoluteTargetCandidate && error < 0.0)))
        val allowIntegralUpdate = autoTrimState != AutoTrimState.SETTLING &&
            !checkpointRestoredThisStep &&
            newFeedback && !feedbackWasStale && feedbackDtSec > 0.0
        if (allowIntegralUpdate && !integrationWouldWorsenSaturation) {
            integralCorrectionDeg = integralCandidate
        }
        if (autoTrimState == AutoTrimState.REST) {
            restTrimDeg = integralCorrectionDeg
        }
        val rawTarget = config.targetDeg + correction
        val absoluteTarget = rawTarget.coerceIn(
            -config.speedAbsoluteAngleLimitDeg,
            config.speedAbsoluteAngleLimitDeg,
        )
        val effective = if (checkpointRestoredThisStep) {
            // Drop the old braking target together with the checkpoint
            // restoration. Otherwise the pitch target slew would keep
            // applying the obsolete braking command for several cycles.
            currentTargetDeg = absoluteTarget
            absoluteTarget
        } else {
            approachTarget(
                absoluteTarget,
                dtSec,
                applySlew = true,
                initializeAtDesired = currentTargetDeg == null,
            )
        }
        return VelocityLoopOutput(
            enabled = config.speedLoopEnabled,
            updated = true,
            stale = false,
            feedbackSequence = feedback.sequence,
            feedbackAgeMs = ageNs / 1_000_000.0,
            feedbackDtMs = feedbackDtSec * 1_000.0,
            leftRawStepsPerSec = feedback.leftStepsPerSec,
            rightRawStepsPerSec = feedback.rightStepsPerSec,
            leftCmPerSec = left,
            rightCmPerSec = right,
            meanCmPerSec = mean,
            filteredCmPerSec = filtered,
            targetCmPerSec = requestedSpeedTarget,
            appliedTargetCmPerSec = appliedSpeedTarget,
            speedCommandSlewLimited = speedCommandSlewLimited,
            errorCmPerSec = error,
            proportionalCorrectionDeg = proportionalCorrection,
            correctionDeg = correction,
            integralCorrectionDeg = integralCorrectionDeg,
            trimDeg = config.targetDeg,
            effectiveTargetDeg = effective,
            autoTrimState = autoTrimState.name,
            restTrimDeg = restTrimDeg,
            restCheckpointDeg = restTrimDeg,
            settledDurationSec = settledDurationSec,
            physicalStopCandidate = physicalStopCandidate,
            quiet = quiet,
            saturated = correctionSaturated || absoluteTarget != rawTarget,
            slewLimited = effective != absoluteTarget,
        ).also { lastOutput = it }
    }

    private fun approachTarget(
        desired: Double,
        dtSec: Double,
        applySlew: Boolean,
        initializeAtDesired: Boolean = false,
    ): Double {
        if (currentTargetDeg == null && initializeAtDesired) {
            currentTargetDeg = desired
            return desired
        }
        val current = (currentTargetDeg ?: initialTargetDeg()).let { value ->
            if (config.speedLoopEnabled) {
                value.coerceIn(-config.speedAbsoluteAngleLimitDeg, config.speedAbsoluteAngleLimitDeg)
            } else value
        }
        val next = if (!applySlew || dtSec <= 0.0) {
            if (currentTargetDeg == null) current else current
        } else {
            val maxDelta = config.speedTargetSlewRateDegPerSec * dtSec
            val delta = desired - current
            when {
                kotlin.math.abs(delta) <= maxDelta -> desired
                else -> current + sign(delta) * maxDelta
            }
        }
        currentTargetDeg = next
        return next
    }

    private fun approachSpeedTarget(
        desired: Double,
        dtSec: Double,
        initializeAtDesired: Boolean,
    ): Double {
        if (abs(desired) <= COMMAND_EPSILON) {
            // Releasing the command is an immediate stop request. The slew is
            // retained for acceleration, never for delaying braking.
            currentSpeedTargetCmPerSec = 0.0
            return 0.0
        }
        if (currentSpeedTargetCmPerSec == null && initializeAtDesired) {
            currentSpeedTargetCmPerSec = desired
            return desired
        }
        val current = currentSpeedTargetCmPerSec ?: 0.0
        val maxDelta = config.speedTargetSlewRateCmPerSec * dtSec
        val delta = desired - current
        val next = when {
            dtSec <= 0.0 || maxDelta <= 0.0 -> current
            kotlin.math.abs(delta) <= maxDelta -> desired
            else -> current + sign(delta) * maxDelta
        }
        currentSpeedTargetCmPerSec = next
        return next
    }

    private fun initialTargetDeg(): Double = if (config.speedLoopEnabled) {
        config.targetDeg.coerceIn(-config.speedAbsoluteAngleLimitDeg, config.speedAbsoluteAngleLimitDeg)
    } else config.targetDeg

    private fun validated(value: RobotConfig): RobotConfig {
        require(RobotConfigValidator.validate(value).isValid) { "invalid robot config" }
        return value
    }

    private companion object {
        const val PHYSICAL_STOP_PITCH_RATE_THRESHOLD_DEG_PER_SEC = 2.0
        const val PHYSICAL_STOP_ANGLE_THRESHOLD_DEG = 1.0
        const val QUIET_YAW_RATE_THRESHOLD_DEG_PER_SEC = 3.0
        const val COMMAND_EPSILON = 1e-6
    }

}
