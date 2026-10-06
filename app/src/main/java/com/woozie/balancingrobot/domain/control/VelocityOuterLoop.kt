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
    val targetCmPerSec: Double,
    val appliedTargetCmPerSec: Double = targetCmPerSec,
    val speedCommandSlewLimited: Boolean = false,
    val errorCmPerSec: Double? = null,
    val correctionDeg: Double = 0.0,
    val integralCorrectionDeg: Double = 0.0,
    val trimDeg: Double,
    val effectiveTargetDeg: Double,
    val autoTrimState: String = "SETTLED",
    val restTrimDeg: Double = 0.0,
    val settledDurationSec: Double = 0.0,
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
    private enum class AutoTrimState { MOVING, BRAKING, YAW_SETTLING, SETTLED }

    private var config = validated(initialConfig)
    private var lastTickNs = 0L
    private var lastFeedbackSequence: Long? = null
    private var filteredCmPerSec: Double? = null
    private var wasStale = true
    private var integralCorrectionDeg = 0.0
    private var restTrimDeg = 0.0
    private var releaseIntegralDeg = 0.0
    private var releaseSpeedTargetCmPerSec = 0.0
    private var releaseRampComplete = true
    /** True after a commanded movement has created a valid rest checkpoint. */
    private var hasRestCheckpoint = false
    private var wasManeuverActive = false
    /** True if the current maneuver included a non-zero translation command. */
    private var maneuverHadTranslation = false
    private var autoTrimState = AutoTrimState.SETTLED
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
            releaseIntegralDeg = 0.0
            releaseSpeedTargetCmPerSec = 0.0
            releaseRampComplete = true
            hasRestCheckpoint = false
            wasManeuverActive = false
            maneuverHadTranslation = false
            autoTrimState = AutoTrimState.SETTLED
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
            releaseIntegralDeg = 0.0
            releaseSpeedTargetCmPerSec = 0.0
            releaseRampComplete = true
            hasRestCheckpoint = false
            wasManeuverActive = false
            maneuverHadTranslation = false
            autoTrimState = AutoTrimState.SETTLED
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
        filteredCmPerSec = null
        wasStale = true
        integralCorrectionDeg = 0.0
        restTrimDeg = 0.0
        releaseIntegralDeg = 0.0
        releaseSpeedTargetCmPerSec = 0.0
        releaseRampComplete = true
        hasRestCheckpoint = false
        wasManeuverActive = false
        maneuverHadTranslation = false
        autoTrimState = AutoTrimState.SETTLED
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
    ): VelocityLoopOutput {
        require(nowNs > 0L) { "monotonic timestamp must be positive" }
        require(targetCmPerSec.isFinite()) { "speed target must be finite" }
        require(pitchRateDegPerSec.isFinite()) { "pitch gyro rate must be finite" }
        require(yawTargetDegPerSec.isFinite()) { "yaw target must be finite" }
        require(yawRateDegPerSec.isFinite()) { "yaw gyro rate must be finite" }
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
            // Keep one checkpoint for the trim learned while the robot was
            // stationary. The integrator remains free to adapt throughout
            // both translation and yaw maneuvers.
            restTrimDeg = integralCorrectionDeg
            hasRestCheckpoint = true
            autoTrimState = AutoTrimState.MOVING
            settledDurationSec = 0.0
            releaseRampComplete = true
            maneuverHadTranslation = speedCommandActive
        }
        if (maneuverActive && speedCommandActive) {
            maneuverHadTranslation = true
        }
        wasManeuverActive = maneuverActive
        val previousAppliedSpeedTarget = currentSpeedTargetCmPerSec ?: requestedSpeedTarget
        val appliedSpeedTarget = approachSpeedTarget(
            requestedSpeedTarget,
            dtSec,
            initializeAtDesired = previousTickNs == 0L,
        )
        val speedCommandSlewLimited = appliedSpeedTarget != requestedSpeedTarget
        if (releasedManeuver) {
            if (maneuverHadTranslation) {
                // Capture the translation bias and the currently applied
                // (ramped) speed. The integral is transferred to restTrim
                // progressively in sync with this applied target instead of
                // being reset at the exact joystick-release instant.
                releaseIntegralDeg = integralCorrectionDeg
                releaseSpeedTargetCmPerSec = previousAppliedSpeedTarget
                releaseRampComplete = abs(previousAppliedSpeedTarget) <= RELEASE_SPEED_EPSILON
                if (releaseRampComplete) integralCorrectionDeg = restTrimDeg
                autoTrimState = AutoTrimState.BRAKING
            } else {
                // A pure yaw maneuver has no translation integral to unwind.
                // Preserve its compensation while the measured speed and yaw
                // settle, then resume normal rest auto-trim learning.
                releaseRampComplete = true
                autoTrimState = AutoTrimState.YAW_SETTLING
            }
            settledDurationSec = 0.0
        }

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
            if (autoTrimState == AutoTrimState.BRAKING) {
                updateBrakingIntegral(appliedSpeedTarget)
            }
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
            val desired = if (config.speedLoopEnabled &&
                staleDurationSec <= STALE_TARGET_HOLD_SEC && currentTargetDeg != null
            ) {
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
        if (feedback.sequence != lastFeedbackSequence) {
            filteredCmPerSec = if (filteredCmPerSec == null || wasStale) mean else {
                config.speedFilterAlpha * mean +
                    (1.0 - config.speedFilterAlpha) * checkNotNull(filteredCmPerSec)
            }
            lastFeedbackSequence = feedback.sequence
        }
        wasStale = false
        val filtered = checkNotNull(filteredCmPerSec)
        // The quiet-speed test is intentionally based on the absolute value
        // of the filtered mean of both wheels. A brief sign crossing must not
        // qualify as settled unless it remains inside the configured band.
        val quiet = abs(filtered) < config.speedQuietThresholdCmPerSec &&
            abs(pitchRateDegPerSec) < QUIET_PITCH_RATE_THRESHOLD_DEG_PER_SEC &&
            abs(yawTargetDegPerSec) < QUIET_YAW_TARGET_THRESHOLD_DEG_PER_SEC
        val yawSettled = quiet &&
            abs(yawRateDegPerSec) < QUIET_YAW_RATE_THRESHOLD_DEG_PER_SEC
        if (maneuverActive) {
            autoTrimState = AutoTrimState.MOVING
            settledDurationSec = 0.0
        } else {
            when (autoTrimState) {
                AutoTrimState.MOVING -> {
                    if (maneuverHadTranslation) {
                        autoTrimState = AutoTrimState.BRAKING
                        settledDurationSec = 0.0
                        releaseIntegralDeg = integralCorrectionDeg
                        releaseSpeedTargetCmPerSec = appliedSpeedTarget
                        releaseRampComplete = abs(appliedSpeedTarget) <= RELEASE_SPEED_EPSILON
                        updateBrakingIntegral(appliedSpeedTarget)
                    } else {
                        autoTrimState = AutoTrimState.YAW_SETTLING
                        settledDurationSec = 0.0
                    }
                }
                AutoTrimState.BRAKING -> {
                    if (!releaseRampComplete) {
                        updateBrakingIntegral(appliedSpeedTarget)
                        settledDurationSec = 0.0
                    } else if (quiet) {
                        settledDurationSec += dtSec
                        if (settledDurationSec >= config.speedQuietDurationMs / 1_000.0) {
                            autoTrimState = AutoTrimState.SETTLED
                        }
                    } else {
                        settledDurationSec = 0.0
                        integralCorrectionDeg = restTrimDeg
                    }
                }
                AutoTrimState.YAW_SETTLING -> {
                    // Unlike translation braking, yaw release must not reset
                    // the integral: it may contain the compensation needed to
                    // cancel a longitudinal bias induced by the rotation.
                    if (yawSettled) {
                        settledDurationSec += dtSec
                        if (settledDurationSec >= config.speedQuietDurationMs / 1_000.0) {
                            autoTrimState = AutoTrimState.SETTLED
                        }
                    } else {
                        settledDurationSec = 0.0
                    }
                }
                AutoTrimState.SETTLED -> {
                    // Before the first user-commanded movement there is no
                    // checkpoint to protect. Keep the initial state SETTLED
                    // so the integrator can learn the equilibrium from a
                    // non-zero measured drift. Once a checkpoint exists,
                    // leaving the quiet band enters BRAKING and freezes it.
                    if (!quiet && hasRestCheckpoint) {
                        autoTrimState = AutoTrimState.BRAKING
                        settledDurationSec = 0.0
                        integralCorrectionDeg = restTrimDeg
                    }
                }
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
                settledDurationSec = settledDurationSec,
                quiet = quiet,
            ).also { lastOutput = it }
        }

        val proportionalCorrection = config.speedKevDegPerCmPerSec * error
        val integralCandidate = (integralCorrectionDeg +
            config.speedIntegralGainDegPerCmPerSecSec * error * dtSec)
            .coerceIn(-config.speedTargetAngleLimitDeg, config.speedTargetAngleLimitDeg)
        val rawCorrection = proportionalCorrection + integralCandidate
        val correction = rawCorrection.coerceIn(
            -config.speedTargetAngleLimitDeg,
            config.speedTargetAngleLimitDeg,
        )
        val correctionSaturated = rawCorrection != correction
        val integrationWouldWorsenSaturation =
            (rawCorrection > config.speedTargetAngleLimitDeg && error > 0.0) ||
                (rawCorrection < -config.speedTargetAngleLimitDeg && error < 0.0)
        val allowIntegralUpdate = autoTrimState != AutoTrimState.BRAKING
        if (allowIntegralUpdate && (!correctionSaturated || !integrationWouldWorsenSaturation)) {
            integralCorrectionDeg = integralCandidate
        }
        if (autoTrimState == AutoTrimState.SETTLED) {
            restTrimDeg = integralCorrectionDeg
        }
        val rawTarget = config.targetDeg + correction
        val absoluteTarget = rawTarget.coerceIn(
            -config.speedAbsoluteAngleLimitDeg,
            config.speedAbsoluteAngleLimitDeg,
        )
        val effective = approachTarget(
            absoluteTarget,
            dtSec,
            applySlew = true,
            initializeAtDesired = currentTargetDeg == null,
        )
        return VelocityLoopOutput(
            enabled = config.speedLoopEnabled,
            updated = true,
            stale = false,
            feedbackSequence = feedback.sequence,
            feedbackAgeMs = ageNs / 1_000_000.0,
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
            correctionDeg = correction,
            integralCorrectionDeg = integralCorrectionDeg,
            trimDeg = config.targetDeg,
            effectiveTargetDeg = effective,
            autoTrimState = autoTrimState.name,
            restTrimDeg = restTrimDeg,
            settledDurationSec = settledDurationSec,
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

    /** Transfers movement-only integral bias to the rest checkpoint smoothly. */
    private fun updateBrakingIntegral(appliedSpeedTarget: Double) {
        if (releaseRampComplete) {
            integralCorrectionDeg = restTrimDeg
            return
        }
        val releaseMagnitude = abs(releaseSpeedTargetCmPerSec)
        val lambda = if (releaseMagnitude > RELEASE_SPEED_EPSILON) {
            (abs(appliedSpeedTarget) / releaseMagnitude).coerceIn(0.0, 1.0)
        } else 0.0
        integralCorrectionDeg = restTrimDeg +
            lambda * (releaseIntegralDeg - restTrimDeg)
        if (abs(appliedSpeedTarget) <= RELEASE_SPEED_EPSILON) {
            releaseRampComplete = true
            integralCorrectionDeg = restTrimDeg
        }
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
        const val QUIET_YAW_TARGET_THRESHOLD_DEG_PER_SEC = 1.0
        const val QUIET_YAW_RATE_THRESHOLD_DEG_PER_SEC = 3.0
        const val STALE_TARGET_HOLD_SEC = 0.05
        const val COMMAND_EPSILON = 1e-6
        const val RELEASE_SPEED_EPSILON = 1e-9
    }

}
