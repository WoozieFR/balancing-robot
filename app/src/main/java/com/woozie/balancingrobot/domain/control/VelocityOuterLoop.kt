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
    val leftRawStepsPerSec: Int? = null,
    val rightRawStepsPerSec: Int? = null,
    val leftCmPerSec: Double? = null,
    val rightCmPerSec: Double? = null,
    val meanCmPerSec: Double? = null,
    val filteredCmPerSec: Double? = null,
    /** Command input retained in cm/s units for compatibility with the gamepad/Web API. */
    val targetCmPerSec: Double,
    val appliedTargetCmPerSec: Double = targetCmPerSec,
    val speedCommandSlewLimited: Boolean = false,
    /** No speed-regulation error is used; this is the measured drift, -filteredSpeed. */
    val errorCmPerSec: Double? = null,
    /** Sum of auto-trim, joystick lean and brake correction. */
    val correctionDeg: Double = 0.0,
    /** Compatibility alias for the learned auto-trim term. */
    val integralCorrectionDeg: Double = 0.0,
    val trimDeg: Double,
    val effectiveTargetDeg: Double,
    val autoTrimState: String = "REST",
    /** Compatibility alias for the learned auto-trim term. */
    val restTrimDeg: Double = 0.0,
    val settledDurationSec: Double = 0.0,
    val quiet: Boolean = false,
    val autoTrimDeg: Double = 0.0,
    val requestedLeanDeg: Double = 0.0,
    val appliedLeanDeg: Double = 0.0,
    val brakeCorrectionDeg: Double = 0.0,
    val userCommandActive: Boolean = false,
    val autoTrimSaturated: Boolean = false,
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

/**
 * Stateful, I/O-free motion-target controller.
 *
 * Wheel feedback is used only for rest auto-trim and optional braking. It
 * never closes a commanded-speed PI loop. The pitch target is:
 *
 *   targetDeg + autoTrimDeg + appliedLeanDeg + brakeCorrectionDeg
 */
class VelocityOuterLoop(initialConfig: RobotConfig) {
    private enum class MotionState { DRIVING, WAIT_REST, REST }

    private var config = validated(initialConfig)
    private var lastTickNs = 0L
    private var lastFeedbackSequence: Long? = null
    private var lastTrimFeedbackTimestampNs: Long? = null
    private var filteredCmPerSec: Double? = null
    private var wasStale = true
    private var autoTrimDeg = 0.0
    private var motionState = MotionState.REST
    private var restDurationSec = 0.0
    private var currentTargetDeg: Double? = null
    private var currentLeanDeg = 0.0
    private var currentDtSec = 0.0
    private var lastBrakeCorrectionDeg = 0.0
    private var autoTrimSaturated = false
    /** Tests may drive this pure controller directly; the service gates it by arm state. */
    private var autoTrimLearningEnabled = true
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
        currentLeanDeg = currentLeanDeg.coerceIn(-valid.joystickMaxLeanDeg, valid.joystickMaxLeanDeg)
        autoTrimDeg = autoTrimDeg.coerceIn(-valid.speedTargetAngleLimitDeg, valid.speedTargetAngleLimitDeg)
        autoTrimSaturated = abs(autoTrimDeg) >= valid.speedTargetAngleLimitDeg - AUTO_TRIM_EPSILON_DEG
        if (modeChanged || !valid.speedLoopEnabled) {
            autoTrimDeg = 0.0
            motionState = MotionState.REST
            restDurationSec = 0.0
            currentTargetDeg = null
            currentLeanDeg = 0.0
            lastBrakeCorrectionDeg = 0.0
            autoTrimSaturated = false
        }
        if (resetFilter) {
            filteredCmPerSec = null
            lastFeedbackSequence = null
            lastTrimFeedbackTimestampNs = null
            wasStale = true
            restDurationSec = 0.0
            lastBrakeCorrectionDeg = 0.0
            lastOutput = null
        }
    }

    fun reset() {
        lastTickNs = 0L
        lastFeedbackSequence = null
        lastTrimFeedbackTimestampNs = null
        filteredCmPerSec = null
        wasStale = true
        autoTrimDeg = 0.0
        motionState = MotionState.REST
        restDurationSec = 0.0
        currentTargetDeg = null
        currentLeanDeg = 0.0
        currentDtSec = 0.0
        lastBrakeCorrectionDeg = 0.0
        autoTrimSaturated = false
        lastOutput = null
    }

    /** Starts a fresh balance session and permits auto-trim learning. */
    fun setAutoTrimLearningEnabled(enabled: Boolean) {
        if (enabled && !autoTrimLearningEnabled) {
            lastTickNs = 0L
            lastFeedbackSequence = null
            lastTrimFeedbackTimestampNs = null
            filteredCmPerSec = null
            wasStale = true
            autoTrimDeg = 0.0
            autoTrimSaturated = false
            motionState = MotionState.REST
            restDurationSec = 0.0
            currentTargetDeg = null
            currentLeanDeg = 0.0
            currentDtSec = 0.0
            lastBrakeCorrectionDeg = 0.0
            lastOutput = null
        }
        autoTrimLearningEnabled = enabled
    }

    fun step(
        nowNs: Long,
        feedback: WheelVelocityFeedback?,
        targetCmPerSec: Double = config.speedTargetCmPerSec,
        pitchRateDegPerSec: Double = 0.0,
        yawTargetDegPerSec: Double = config.yawTargetDegPerSec,
        yawRateDegPerSec: Double = 0.0,
        forwardNormalized: Double? = null,
    ): VelocityLoopOutput {
        require(nowNs > 0L) { "monotonic timestamp must be positive" }
        require(targetCmPerSec.isFinite()) { "motion command must be finite" }
        require(pitchRateDegPerSec.isFinite()) { "pitch gyro rate must be finite" }
        require(yawTargetDegPerSec.isFinite()) { "yaw target must be finite" }
        require(yawRateDegPerSec.isFinite()) { "yaw gyro rate must be finite" }
        require(forwardNormalized == null || forwardNormalized.isFinite()) {
            "normalized forward command must be finite"
        }
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
        currentDtSec = dtSec

        val normalizedCommand = forwardNormalized?.coerceIn(-1.0, 1.0)
        val requestedCommand = (normalizedCommand?.let {
            it * config.speedTargetLimitCmPerSec
        } ?: targetCmPerSec).coerceIn(
            -config.speedTargetLimitCmPerSec,
            config.speedTargetLimitCmPerSec,
        )
        val speedCommandActive = abs(requestedCommand) > config.joystickDeadbandCmPerSec
        val yawCommandActive = abs(yawTargetDegPerSec) > YAW_COMMAND_DEADBAND_DEG_PER_SEC
        val userCommandActive = speedCommandActive || yawCommandActive
        if (userCommandActive) {
            motionState = MotionState.DRIVING
            restDurationSec = 0.0
        } else if (motionState == MotionState.DRIVING) {
            motionState = MotionState.WAIT_REST
            restDurationSec = 0.0
        }

        val requestedLeanDeg = if (speedCommandActive) {
            (normalizedCommand ?: (requestedCommand / config.speedTargetLimitCmPerSec)) * config.joystickMaxLeanDeg
        } else 0.0
        val appliedLeanDeg = approachLean(requestedLeanDeg, dtSec)

        val ageNs = feedback?.let { nowNs - min(it.leftTimestampNs, it.rightTimestampNs) }
        val fresh = feedback != null && ageNs != null && ageNs >= 0L &&
            ageNs <= config.speedFeedbackTimeoutMs * 1_000_000L
        if (!fresh) {
            wasStale = true
            if (motionState == MotionState.WAIT_REST) restDurationSec = 0.0
            lastBrakeCorrectionDeg = approachBrakeToZero(lastBrakeCorrectionDeg, dtSec)
            return buildOutput(
                requestedCommand = requestedCommand,
                appliedLeanDeg = appliedLeanDeg,
                requestedLeanDeg = requestedLeanDeg,
                brakeCorrectionDeg = lastBrakeCorrectionDeg,
                feedback = feedback,
                feedbackAgeMs = ageNs?.coerceAtLeast(0L)?.div(1_000_000.0),
                stale = true,
                leftCmPerSec = null,
                rightCmPerSec = null,
                meanCmPerSec = null,
                filteredCmPerSec = filteredCmPerSec,
                errorCmPerSec = filteredCmPerSec?.let { -it },
                quiet = false,
            )
        }

        feedback!!
        val feedbackTimestampNs = min(feedback.leftTimestampNs, feedback.rightTimestampNs)
        val newFeedback = feedback.sequence != lastFeedbackSequence
        val feedbackWasStale = wasStale
        val feedbackDtSec = if (newFeedback && !feedbackWasStale && lastTrimFeedbackTimestampNs != null) {
            ((feedbackTimestampNs - checkNotNull(lastTrimFeedbackTimestampNs)).toDouble() /
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
            filteredCmPerSec = if (filteredCmPerSec == null || wasStale) mean else {
                config.speedFilterAlpha * mean +
                    (1.0 - config.speedFilterAlpha) * checkNotNull(filteredCmPerSec)
            }
            lastFeedbackSequence = feedback.sequence
            lastTrimFeedbackTimestampNs = feedbackTimestampNs
        }
        wasStale = false
        val filtered = checkNotNull(filteredCmPerSec)
        val quiet = abs(filtered) < config.speedQuietThresholdCmPerSec &&
            abs(pitchRateDegPerSec) < QUIET_PITCH_RATE_THRESHOLD_DEG_PER_SEC &&
            abs(yawRateDegPerSec) < QUIET_YAW_RATE_THRESHOLD_DEG_PER_SEC &&
            abs(yawTargetDegPerSec) < YAW_COMMAND_DEADBAND_DEG_PER_SEC &&
            abs(currentLeanDeg) < LEAN_REST_EPSILON_DEG
        if (motionState == MotionState.WAIT_REST) {
            if (newFeedback && quiet) {
                restDurationSec += feedbackDtSec
                if (restDurationSec >= config.speedQuietDurationMs / 1_000.0) {
                    motionState = MotionState.REST
                }
            } else if (!quiet) {
                restDurationSec = 0.0
            }
        }
        val brakeCorrectionDeg = if (!userCommandActive) {
            (-config.brakeKpDegPerCmPerSec * filtered).coerceIn(-config.brakeLimitDeg, config.brakeLimitDeg)
        } else 0.0
        lastBrakeCorrectionDeg = brakeCorrectionDeg
        // The only integrator in this architecture is the rest auto-trim. It
        // runs on new, fresh wheel feedback and is never reset by stale data.
        if (autoTrimLearningEnabled && motionState == MotionState.REST &&
            newFeedback && !feedbackWasStale && feedbackDtSec > 0.0
        ) {
            updateAutoTrim(filtered, feedbackDtSec, appliedLeanDeg, brakeCorrectionDeg)
        }
        return buildOutput(
            requestedCommand = requestedCommand,
            appliedLeanDeg = appliedLeanDeg,
            requestedLeanDeg = requestedLeanDeg,
            brakeCorrectionDeg = brakeCorrectionDeg,
            feedback = feedback,
            feedbackAgeMs = ageNs / 1_000_000.0,
            stale = false,
            leftCmPerSec = left,
            rightCmPerSec = right,
            meanCmPerSec = mean,
            filteredCmPerSec = filtered,
            errorCmPerSec = -filtered,
            quiet = quiet,
        )
    }

    private fun buildOutput(
        requestedCommand: Double,
        appliedLeanDeg: Double,
        requestedLeanDeg: Double,
        brakeCorrectionDeg: Double,
        feedback: WheelVelocityFeedback?,
        feedbackAgeMs: Double?,
        stale: Boolean,
        leftCmPerSec: Double?,
        rightCmPerSec: Double?,
        meanCmPerSec: Double?,
        filteredCmPerSec: Double?,
        errorCmPerSec: Double?,
        quiet: Boolean,
    ): VelocityLoopOutput {
        val enabled = config.speedLoopEnabled
        val totalCorrectionDeg = if (enabled) autoTrimDeg + appliedLeanDeg + brakeCorrectionDeg else 0.0
        val rawTarget = config.targetDeg + totalCorrectionDeg
        val absoluteTarget = if (enabled) {
            rawTarget.coerceIn(-config.speedAbsoluteAngleLimitDeg, config.speedAbsoluteAngleLimitDeg)
        } else config.targetDeg
        val effectiveTarget = if (enabled) {
            approachTarget(absoluteTarget, currentDtSec, currentTargetDeg == null)
        } else {
            currentTargetDeg = config.targetDeg
            config.targetDeg
        }
        return VelocityLoopOutput(
            enabled = enabled,
            updated = true,
            stale = stale,
            feedbackSequence = feedback?.sequence,
            feedbackAgeMs = feedbackAgeMs,
            leftRawStepsPerSec = feedback?.leftStepsPerSec,
            rightRawStepsPerSec = feedback?.rightStepsPerSec,
            leftCmPerSec = leftCmPerSec,
            rightCmPerSec = rightCmPerSec,
            meanCmPerSec = meanCmPerSec,
            filteredCmPerSec = filteredCmPerSec,
            targetCmPerSec = requestedCommand,
            appliedTargetCmPerSec = requestedCommand,
            speedCommandSlewLimited = false,
            errorCmPerSec = errorCmPerSec,
            correctionDeg = totalCorrectionDeg,
            integralCorrectionDeg = autoTrimDeg,
            trimDeg = config.targetDeg,
            effectiveTargetDeg = effectiveTarget,
            autoTrimState = motionState.name,
            restTrimDeg = autoTrimDeg,
            settledDurationSec = restDurationSec,
            quiet = quiet,
            autoTrimDeg = autoTrimDeg,
            requestedLeanDeg = requestedLeanDeg,
            appliedLeanDeg = appliedLeanDeg,
            brakeCorrectionDeg = brakeCorrectionDeg,
            userCommandActive = motionState == MotionState.DRIVING,
            autoTrimSaturated = autoTrimSaturated,
            saturated = absoluteTarget != rawTarget,
            slewLimited = effectiveTarget != absoluteTarget,
        ).also { lastOutput = it }
    }

    private fun approachLean(desired: Double, dtSec: Double): Double {
        val maxDelta = config.joystickLeanSlewRateDegPerSec * dtSec
        val delta = desired - currentLeanDeg
        currentLeanDeg = when {
            dtSec <= 0.0 || maxDelta <= 0.0 -> currentLeanDeg
            abs(delta) <= maxDelta -> desired
            else -> currentLeanDeg + sign(delta) * maxDelta
        }
        return currentLeanDeg
    }

    private fun approachBrakeToZero(current: Double, dtSec: Double): Double {
        val maxDelta = BRAKE_RELEASE_RATE_DEG_PER_SEC * dtSec
        return when {
            dtSec <= 0.0 || maxDelta <= 0.0 -> current
            abs(current) <= maxDelta -> 0.0
            else -> current - sign(current) * maxDelta
        }
    }

    private fun updateAutoTrim(
        filteredSpeedCmPerSec: Double,
        feedbackDtSec: Double,
        appliedLeanDeg: Double,
        brakeCorrectionDeg: Double,
    ) {
        val requested = autoTrimDeg +
            config.speedIntegralGainDegPerCmPerSecSec * (-filteredSpeedCmPerSec) * feedbackDtSec
        val amplitudeLimited = requested.coerceIn(
            -config.speedTargetAngleLimitDeg,
            config.speedTargetAngleLimitDeg,
        )
        var limited = amplitudeLimited != requested
        val nonTrimTarget = config.targetDeg + appliedLeanDeg + brakeCorrectionDeg
        val minByAbsoluteTarget = -config.speedAbsoluteAngleLimitDeg - nonTrimTarget
        val maxByAbsoluteTarget = config.speedAbsoluteAngleLimitDeg - nonTrimTarget
        val next = if (minByAbsoluteTarget <= maxByAbsoluteTarget) {
            val constrained = amplitudeLimited.coerceIn(minByAbsoluteTarget, maxByAbsoluteTarget)
            limited = limited || constrained != amplitudeLimited
            constrained
        } else {
            // If the non-trim terms already exceed the envelope, only accept a
            // candidate that moves the raw target closer to that envelope.
            val currentRaw = nonTrimTarget + autoTrimDeg
            val candidateRaw = nonTrimTarget + amplitudeLimited
            val currentDistance = abs(currentRaw - currentRaw.coerceIn(
                -config.speedAbsoluteAngleLimitDeg,
                config.speedAbsoluteAngleLimitDeg,
            ))
            val candidateDistance = abs(candidateRaw - candidateRaw.coerceIn(
                -config.speedAbsoluteAngleLimitDeg,
                config.speedAbsoluteAngleLimitDeg,
            ))
            if (candidateDistance < currentDistance) amplitudeLimited else autoTrimDeg
        }
        autoTrimDeg = next
        autoTrimSaturated = limited || abs(autoTrimDeg) >= config.speedTargetAngleLimitDeg - AUTO_TRIM_EPSILON_DEG
    }

    private fun approachTarget(desired: Double, dtSec: Double, initializeAtDesired: Boolean): Double {
        if (currentTargetDeg == null && initializeAtDesired) {
            currentTargetDeg = desired
            return desired
        }
        val current = currentTargetDeg ?: initialTargetDeg()
        val maxDelta = config.speedTargetSlewRateDegPerSec * dtSec
        val delta = desired - current
        currentTargetDeg = when {
            dtSec <= 0.0 || maxDelta <= 0.0 -> current
            abs(delta) <= maxDelta -> desired
            else -> current + sign(delta) * maxDelta
        }
        return currentTargetDeg!!
    }

    private fun initialTargetDeg(): Double = if (config.speedLoopEnabled) {
        config.targetDeg.coerceIn(-config.speedAbsoluteAngleLimitDeg, config.speedAbsoluteAngleLimitDeg)
    } else config.targetDeg

    private fun validated(value: RobotConfig): RobotConfig {
        require(RobotConfigValidator.validate(value).isValid) { "invalid robot config" }
        return value
    }

    private companion object {
        const val QUIET_PITCH_RATE_THRESHOLD_DEG_PER_SEC = 3.0
        const val QUIET_YAW_RATE_THRESHOLD_DEG_PER_SEC = 3.0
        const val YAW_COMMAND_DEADBAND_DEG_PER_SEC = 1.0
        const val LEAN_REST_EPSILON_DEG = 0.1
        const val BRAKE_RELEASE_RATE_DEG_PER_SEC = 20.0
        const val AUTO_TRIM_EPSILON_DEG = 1e-9
    }
}
