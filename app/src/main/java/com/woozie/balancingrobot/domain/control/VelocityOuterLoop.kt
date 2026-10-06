package com.woozie.balancingrobot.domain.control

import com.woozie.balancingrobot.domain.model.RobotConfig
import com.woozie.balancingrobot.domain.model.RobotConfigValidator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
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
    val targetCmPerSec: Double,
    val appliedTargetCmPerSec: Double = targetCmPerSec,
    val speedCommandSlewLimited: Boolean = false,
    val errorCmPerSec: Double? = null,
    /** Dynamic speed-loop correction: P + temporary speed integral. */
    val correctionDeg: Double = 0.0,
    /** Compatibility alias for the temporary speed integral. */
    val integralCorrectionDeg: Double = 0.0,
    val proportionalCorrectionDeg: Double = 0.0,
    val speedIntegralDeg: Double = 0.0,
    val trimDeg: Double,
    val autoTrimDeg: Double = 0.0,
    val effectiveTargetDeg: Double,
    val autoTrimState: String = "REST",
    val settledDurationSec: Double = 0.0,
    val quiet: Boolean = false,
    val autoTrimSaturated: Boolean = false,
    val userCommandActive: Boolean = false,
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
 * Stateful, I/O-free outer velocity controller.
 *
 * target = base trim + learned rest trim + Kp_v * speed error + I_speed
 *
 * The speed integral is temporary: it is integrated during MANEUVER (including
 * pure yaw), then released to zero after all commands are released. The learned
 * auto-trim is updated only in REST, from fresh wheel feedback.
 */
class VelocityOuterLoop(initialConfig: RobotConfig) {
    private enum class MotionState { MANEUVER, RELEASE, REST }

    private var config = validated(initialConfig)
    private var lastTickNs = 0L
    private var lastFeedbackSequence: Long? = null
    private var lastFeedbackTimestampNs: Long? = null
    private var filteredCmPerSec: Double? = null
    private var wasStale = true
    private var autoTrimDeg = 0.0
    private var speedIntegralDeg = 0.0
    private var motionState = MotionState.REST
    private var settledDurationSec = 0.0
    private var lastYawCommandActive = false
    private var currentTargetDeg: Double? = null
    private var currentSpeedTargetCmPerSec: Double? = null
    private var targetSlewDtSec = 0.0
    private var autoTrimSaturated = false
    private var autoTrimLearningEnabled = true
    private var lastOutput: VelocityLoopOutput? = null

    fun updateConfig(newConfig: RobotConfig) {
        val valid = validated(newConfig)
        val previous = config
        val resetFilter = previous.wheelDiameterMm != valid.wheelDiameterMm ||
            previous.driveRatio != valid.driveRatio ||
            previous.speedFilterAlpha != valid.speedFilterAlpha
        config = valid
        lastTickNs = 0L
        speedIntegralDeg = speedIntegralDeg.coerceIn(
            -valid.speedTargetAngleLimitDeg,
            valid.speedTargetAngleLimitDeg,
        )
        autoTrimDeg = autoTrimDeg.coerceIn(
            -valid.speedTargetAngleLimitDeg,
            valid.speedTargetAngleLimitDeg,
        )
        autoTrimSaturated = abs(autoTrimDeg) >= valid.speedTargetAngleLimitDeg - AUTO_TRIM_EPSILON_DEG
        if (!valid.speedLoopEnabled || previous.speedLoopEnabled != valid.speedLoopEnabled) {
            autoTrimDeg = 0.0
            speedIntegralDeg = 0.0
            motionState = MotionState.REST
            settledDurationSec = 0.0
            currentTargetDeg = null
            currentSpeedTargetCmPerSec = null
            lastYawCommandActive = false
            autoTrimSaturated = false
        }
        if (resetFilter) {
            filteredCmPerSec = null
            lastFeedbackSequence = null
            lastFeedbackTimestampNs = null
            wasStale = true
            settledDurationSec = 0.0
            lastOutput = null
        }
    }

    fun reset() {
        lastTickNs = 0L
        lastFeedbackSequence = null
        lastFeedbackTimestampNs = null
        filteredCmPerSec = null
        wasStale = true
        autoTrimDeg = 0.0
        speedIntegralDeg = 0.0
        motionState = MotionState.REST
        settledDurationSec = 0.0
        currentTargetDeg = null
        currentSpeedTargetCmPerSec = null
        targetSlewDtSec = 0.0
        lastYawCommandActive = false
        autoTrimSaturated = false
        lastOutput = null
    }

    /** Gates learning to an armed balance session. Enabling starts a fresh session. */
    fun setAutoTrimLearningEnabled(enabled: Boolean) {
        if (enabled && !autoTrimLearningEnabled) reset()
        autoTrimLearningEnabled = enabled
    }

    fun step(
        nowNs: Long,
        feedback: WheelVelocityFeedback?,
        targetCmPerSec: Double = config.speedTargetCmPerSec,
        yawTargetDegPerSec: Double = config.yawTargetDegPerSec,
        pitchRateDegPerSec: Double = 0.0,
        yawRateDegPerSec: Double = 0.0,
    ): VelocityLoopOutput {
        require(nowNs > 0L) { "monotonic timestamp must be positive" }
        require(targetCmPerSec.isFinite()) { "speed target must be finite" }
        require(yawTargetDegPerSec.isFinite()) { "yaw target must be finite" }
        require(pitchRateDegPerSec.isFinite()) { "pitch rate must be finite" }
        require(yawRateDegPerSec.isFinite()) { "yaw rate must be finite" }

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
        val appliedSpeedTarget = approachSpeedTarget(
            requestedSpeedTarget,
            dtSec,
            initializeAtDesired = previousTickNs == 0L,
        )
        val speedCommandSlewLimited = appliedSpeedTarget != requestedSpeedTarget
        val speedCommandActive = abs(requestedSpeedTarget) > COMMAND_DEADBAND_CM_PER_SEC
        val yawCommandActive = abs(yawTargetDegPerSec) > YAW_COMMAND_DEADBAND_DEG_PER_SEC
        val yawReleased = lastYawCommandActive && !yawCommandActive
        val allCommandsReleased = !speedCommandActive && !yawCommandActive
        if (yawReleased || allCommandsReleased) {
            // I_speed belongs to the maneuver regime that just ended. It must
            // not survive a yaw-off transition or a complete command release.
            speedIntegralDeg = 0.0
        }
        lastYawCommandActive = yawCommandActive
        val userCommandActive = speedCommandActive || yawCommandActive
        updateMotionState(userCommandActive)

        val ageNs = feedback?.let { nowNs - min(it.leftTimestampNs, it.rightTimestampNs) }
        val fresh = feedback != null && ageNs != null && ageNs >= 0L &&
            ageNs <= config.speedFeedbackTimeoutMs * 1_000_000L

        if (!fresh) {
            wasStale = true
            if (motionState == MotionState.RELEASE) settledDurationSec = 0.0
            return buildOutput(
                requestedSpeedTarget = requestedSpeedTarget,
                appliedSpeedTarget = appliedSpeedTarget,
                speedCommandSlewLimited = speedCommandSlewLimited,
                feedback = feedback,
                feedbackAgeMs = ageNs?.coerceAtLeast(0L)?.div(1_000_000.0),
                stale = true,
                meanCmPerSec = null,
                filteredCmPerSec = filteredCmPerSec,
                errorCmPerSec = null,
                proportionalCorrectionDeg = 0.0,
                quiet = false,
                pitchRateDegPerSec = pitchRateDegPerSec,
                yawRateDegPerSec = yawRateDegPerSec,
            )
        }

        val validFeedback = checkNotNull(feedback)
        val feedbackTimestampNs = min(validFeedback.leftTimestampNs, validFeedback.rightTimestampNs)
        val newFeedback = validFeedback.sequence != lastFeedbackSequence
        val feedbackWasStale = wasStale
        val feedbackDtSec = if (newFeedback && !feedbackWasStale && lastFeedbackTimestampNs != null) {
            ((feedbackTimestampNs - checkNotNull(lastFeedbackTimestampNs)).toDouble() /
                1_000_000_000.0).coerceIn(0.0, 0.5)
        } else 0.0
        val left = stepsPerSecondToCmPerSecond(
            validFeedback.leftStepsPerSec,
            config.wheelDiameterMm,
            config.driveRatio,
        )
        val right = stepsPerSecondToCmPerSecond(
            validFeedback.rightStepsPerSec,
            config.wheelDiameterMm,
            config.driveRatio,
        )
        val mean = (left + right) / 2.0
        if (newFeedback) {
            filteredCmPerSec = if (filteredCmPerSec == null || wasStale) mean else {
                config.speedFilterAlpha * mean +
                    (1.0 - config.speedFilterAlpha) * checkNotNull(filteredCmPerSec)
            }
            lastFeedbackSequence = validFeedback.sequence
            lastFeedbackTimestampNs = feedbackTimestampNs
        }
        wasStale = false
        val filtered = checkNotNull(filteredCmPerSec)
        val error = appliedSpeedTarget - filtered
        val quiet = abs(filtered) < config.speedQuietThresholdCmPerSec &&
            abs(pitchRateDegPerSec) < QUIET_PITCH_RATE_THRESHOLD_DEG_PER_SEC &&
            abs(yawRateDegPerSec) < QUIET_YAW_RATE_THRESHOLD_DEG_PER_SEC &&
            abs(yawTargetDegPerSec) <= YAW_COMMAND_DEADBAND_DEG_PER_SEC &&
            abs(appliedSpeedTarget) <= COMMAND_DEADBAND_CM_PER_SEC

        if (!config.speedLoopEnabled) {
            speedIntegralDeg = 0.0
            return buildOutput(
                requestedSpeedTarget = requestedSpeedTarget,
                appliedSpeedTarget = appliedSpeedTarget,
                speedCommandSlewLimited = speedCommandSlewLimited,
                feedback = validFeedback,
                feedbackAgeMs = ageNs / 1_000_000.0,
                stale = false,
                meanCmPerSec = mean,
                filteredCmPerSec = filtered,
                errorCmPerSec = error,
                proportionalCorrectionDeg = 0.0,
                quiet = quiet,
                pitchRateDegPerSec = pitchRateDegPerSec,
                yawRateDegPerSec = yawRateDegPerSec,
            )
        }

        updateRestQualification(
            quiet = quiet,
            newFeedback = newFeedback,
            feedbackDtSec = feedbackDtSec,
            appliedSpeedTarget = appliedSpeedTarget,
        )

        val proportionalCorrectionDeg = config.speedKevDegPerCmPerSec * error
        if (motionState == MotionState.MANEUVER && !yawReleased &&
            newFeedback && !feedbackWasStale && feedbackDtSec > 0.0
        ) {
            updateSpeedIntegral(error, feedbackDtSec, proportionalCorrectionDeg)
        }

        if (autoTrimLearningEnabled && motionState == MotionState.REST &&
            newFeedback && !feedbackWasStale && feedbackDtSec > 0.0
        ) {
            updateAutoTrim(
                filteredSpeedCmPerSec = filtered,
                feedbackDtSec = feedbackDtSec,
                proportionalCorrectionDeg = proportionalCorrectionDeg,
            )
        }

        return buildOutput(
            requestedSpeedTarget = requestedSpeedTarget,
            appliedSpeedTarget = appliedSpeedTarget,
            speedCommandSlewLimited = speedCommandSlewLimited,
            feedback = validFeedback,
            feedbackAgeMs = ageNs / 1_000_000.0,
            stale = false,
            meanCmPerSec = mean,
            filteredCmPerSec = filtered,
            errorCmPerSec = error,
            proportionalCorrectionDeg = proportionalCorrectionDeg,
            quiet = quiet,
            pitchRateDegPerSec = pitchRateDegPerSec,
            yawRateDegPerSec = yawRateDegPerSec,
        )
    }

    private fun updateMotionState(userCommandActive: Boolean) {
        if (userCommandActive) {
            motionState = MotionState.MANEUVER
            settledDurationSec = 0.0
        } else if (motionState == MotionState.MANEUVER) {
            motionState = MotionState.RELEASE
            settledDurationSec = 0.0
        }
    }

    private fun updateRestQualification(
        quiet: Boolean,
        newFeedback: Boolean,
        feedbackDtSec: Double,
        appliedSpeedTarget: Double,
    ) {
        if (motionState != MotionState.RELEASE) return
        val integralReleased = abs(speedIntegralDeg) <= SPEED_INTEGRAL_EPSILON_DEG
        if (newFeedback && quiet && integralReleased &&
            abs(appliedSpeedTarget) <= COMMAND_DEADBAND_CM_PER_SEC
        ) {
            settledDurationSec += feedbackDtSec
            if (settledDurationSec >= config.speedQuietDurationMs / 1_000.0) {
                speedIntegralDeg = 0.0
                motionState = MotionState.REST
            }
        } else if (!quiet || !integralReleased ||
            abs(appliedSpeedTarget) > COMMAND_DEADBAND_CM_PER_SEC
        ) {
            settledDurationSec = 0.0
        }
    }

    private fun updateSpeedIntegral(
        errorCmPerSec: Double,
        feedbackDtSec: Double,
        proportionalCorrectionDeg: Double,
    ) {
        val candidate = (speedIntegralDeg +
            config.speedIntegralGainDegPerCmPerSecSec * errorCmPerSec * feedbackDtSec)
            .coerceIn(-config.speedTargetAngleLimitDeg, config.speedTargetAngleLimitDeg)
        val rawCorrection = proportionalCorrectionDeg + candidate
        val correction = rawCorrection.coerceIn(
            -config.speedTargetAngleLimitDeg,
            config.speedTargetAngleLimitDeg,
        )
        val integrationWouldWorsenSaturation =
            (rawCorrection > config.speedTargetAngleLimitDeg && errorCmPerSec > 0.0) ||
                (rawCorrection < -config.speedTargetAngleLimitDeg && errorCmPerSec < 0.0)
        if (rawCorrection == correction || !integrationWouldWorsenSaturation) {
            speedIntegralDeg = candidate
        }
    }

    private fun updateAutoTrim(
        filteredSpeedCmPerSec: Double,
        feedbackDtSec: Double,
        proportionalCorrectionDeg: Double,
    ) {
        val candidate = (autoTrimDeg +
            config.speedAutoTrimGainDegPerCmPerSecSec * (-filteredSpeedCmPerSec) * feedbackDtSec)
            .coerceIn(-config.speedTargetAngleLimitDeg, config.speedTargetAngleLimitDeg)
        val nonTrimTarget = config.targetDeg + proportionalCorrectionDeg + speedIntegralDeg
        val minTrim = max(
            -config.speedTargetAngleLimitDeg,
            -config.speedAbsoluteAngleLimitDeg - nonTrimTarget,
        )
        val maxTrim = min(
            config.speedTargetAngleLimitDeg,
            config.speedAbsoluteAngleLimitDeg - nonTrimTarget,
        )
        val next = if (minTrim <= maxTrim) candidate.coerceIn(minTrim, maxTrim) else autoTrimDeg
        autoTrimDeg = next
        autoTrimSaturated = next != candidate ||
            abs(next) >= config.speedTargetAngleLimitDeg - AUTO_TRIM_EPSILON_DEG
    }

    private fun buildOutput(
        requestedSpeedTarget: Double,
        appliedSpeedTarget: Double,
        speedCommandSlewLimited: Boolean,
        feedback: WheelVelocityFeedback?,
        feedbackAgeMs: Double?,
        stale: Boolean,
        meanCmPerSec: Double?,
        filteredCmPerSec: Double?,
        errorCmPerSec: Double?,
        proportionalCorrectionDeg: Double,
        quiet: Boolean,
        pitchRateDegPerSec: Double,
        yawRateDegPerSec: Double,
    ): VelocityLoopOutput {
        val enabled = config.speedLoopEnabled
        val correctionDeg = if (enabled) {
            (proportionalCorrectionDeg + speedIntegralDeg).coerceIn(
                -config.speedTargetAngleLimitDeg,
                config.speedTargetAngleLimitDeg,
            )
        } else 0.0
        val rawTarget = config.targetDeg + autoTrimDeg + correctionDeg
        val absoluteTarget = if (enabled) {
            rawTarget.coerceIn(-config.speedAbsoluteAngleLimitDeg, config.speedAbsoluteAngleLimitDeg)
        } else config.targetDeg
        val effectiveTarget = if (enabled) {
            approachTarget(absoluteTarget)
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
            leftCmPerSec = feedback?.let {
                stepsPerSecondToCmPerSecond(it.leftStepsPerSec, config.wheelDiameterMm, config.driveRatio)
            },
            rightCmPerSec = feedback?.let {
                stepsPerSecondToCmPerSecond(it.rightStepsPerSec, config.wheelDiameterMm, config.driveRatio)
            },
            meanCmPerSec = meanCmPerSec,
            filteredCmPerSec = filteredCmPerSec,
            targetCmPerSec = requestedSpeedTarget,
            appliedTargetCmPerSec = appliedSpeedTarget,
            speedCommandSlewLimited = speedCommandSlewLimited,
            errorCmPerSec = errorCmPerSec,
            correctionDeg = correctionDeg,
            integralCorrectionDeg = speedIntegralDeg,
            proportionalCorrectionDeg = proportionalCorrectionDeg,
            speedIntegralDeg = speedIntegralDeg,
            trimDeg = config.targetDeg + autoTrimDeg,
            autoTrimDeg = autoTrimDeg,
            effectiveTargetDeg = effectiveTarget,
            autoTrimState = motionState.name,
            settledDurationSec = settledDurationSec,
            quiet = quiet && abs(pitchRateDegPerSec) < QUIET_PITCH_RATE_THRESHOLD_DEG_PER_SEC &&
                abs(yawRateDegPerSec) < QUIET_YAW_RATE_THRESHOLD_DEG_PER_SEC,
            autoTrimSaturated = autoTrimSaturated,
            userCommandActive = motionState == MotionState.MANEUVER,
            saturated = correctionDeg != proportionalCorrectionDeg + speedIntegralDeg ||
                absoluteTarget != rawTarget,
            slewLimited = effectiveTarget != absoluteTarget,
        ).also { lastOutput = it }
    }

    private fun approachTarget(desired: Double): Double {
        if (currentTargetDeg == null) {
            currentTargetDeg = desired
            return desired
        }
        val current = currentTargetDeg ?: initialTargetDeg()
        val maxDelta = config.speedTargetSlewRateDegPerSec * targetSlewDtSec
        val delta = desired - current
        currentTargetDeg = when {
            targetSlewDtSec <= 0.0 || maxDelta <= 0.0 -> current
            abs(delta) <= maxDelta -> desired
            else -> current + sign(delta) * maxDelta
        }
        return currentTargetDeg!!
    }

    private fun approachSpeedTarget(
        desired: Double,
        dtSec: Double,
        initializeAtDesired: Boolean,
    ): Double {
        targetSlewDtSec = dtSec
        if (abs(desired) <= COMMAND_DEADBAND_CM_PER_SEC) {
            // A released command is a stop request, not a slow new maneuver.
            // Remove the command ramp so P_v immediately sees error = -v.
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
            abs(delta) <= maxDelta -> desired
            else -> current + sign(delta) * maxDelta
        }
        currentSpeedTargetCmPerSec = next
        return next
    }

    private fun initialTargetDeg(): Double = if (config.speedLoopEnabled) {
        (config.targetDeg + autoTrimDeg).coerceIn(
            -config.speedAbsoluteAngleLimitDeg,
            config.speedAbsoluteAngleLimitDeg,
        )
    } else config.targetDeg

    private fun validated(value: RobotConfig): RobotConfig {
        require(RobotConfigValidator.validate(value).isValid) { "invalid robot config" }
        return value
    }

    private companion object {
        const val COMMAND_DEADBAND_CM_PER_SEC = 0.1
        const val YAW_COMMAND_DEADBAND_DEG_PER_SEC = 1.0
        const val QUIET_PITCH_RATE_THRESHOLD_DEG_PER_SEC = 3.0
        const val QUIET_YAW_RATE_THRESHOLD_DEG_PER_SEC = 3.0
        const val SPEED_INTEGRAL_EPSILON_DEG = 1e-9
        const val AUTO_TRIM_EPSILON_DEG = 1e-9
    }
}
