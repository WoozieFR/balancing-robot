package com.woozie.balancingrobot.domain.control

import com.woozie.balancingrobot.domain.model.RobotConfig
import com.woozie.balancingrobot.domain.model.RobotConfigValidator
import kotlin.math.PI
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
    val errorCmPerSec: Double? = null,
    val correctionDeg: Double = 0.0,
    val integralCorrectionDeg: Double = 0.0,
    val trimDeg: Double,
    val effectiveTargetDeg: Double,
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
    private var config = validated(initialConfig)
    private var lastTickNs = 0L
    private var lastFeedbackSequence: Long? = null
    private var filteredCmPerSec: Double? = null
    private var wasStale = true
    private var integralCorrectionDeg = 0.0
    private var currentTargetDeg: Double? = null
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
            currentTargetDeg = null
        } else {
            integralCorrectionDeg = integralCorrectionDeg.coerceIn(
                -valid.speedTargetAngleLimitDeg,
                valid.speedTargetAngleLimitDeg,
            )
        }
        if (resetFilter) {
            filteredCmPerSec = null
            lastFeedbackSequence = null
            wasStale = true
            integralCorrectionDeg = 0.0
            currentTargetDeg = null
            lastOutput = null
        }
    }

    fun reset() {
        lastTickNs = 0L
        lastFeedbackSequence = null
        filteredCmPerSec = null
        wasStale = true
        integralCorrectionDeg = 0.0
        currentTargetDeg = null
        lastOutput = null
    }

    fun step(
        nowNs: Long,
        feedback: WheelVelocityFeedback?,
        targetCmPerSec: Double = config.speedTargetCmPerSec,
    ): VelocityLoopOutput {
        require(nowNs > 0L) { "monotonic timestamp must be positive" }
        require(targetCmPerSec.isFinite()) { "speed target must be finite" }
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

        val ageNs = feedback?.let {
            nowNs - min(it.leftTimestampNs, it.rightTimestampNs)
        }
        val fresh = feedback != null && ageNs != null && ageNs >= 0L &&
            ageNs <= config.speedFeedbackTimeoutMs * 1_000_000L
        if (!fresh) {
            wasStale = true
            // Do not freeze an old acceleration command. Clear the learned trim
            // correction and bring the applied target back toward the manual
            // trim through the same slew-rate limiter used during recovery.
            integralCorrectionDeg = 0.0
            val trim = config.targetDeg
            val desired = if (config.speedLoopEnabled) {
                trim.coerceIn(-config.speedAbsoluteAngleLimitDeg, config.speedAbsoluteAngleLimitDeg)
            } else trim
            val effective = approachTarget(desired, dtSec, config.speedLoopEnabled)
            return (lastOutput ?: VelocityLoopOutput(
                enabled = config.speedLoopEnabled,
                updated = true,
                stale = true,
                targetCmPerSec = targetCmPerSec,
                trimDeg = trim,
                effectiveTargetDeg = effective,
            )).copy(
                enabled = config.speedLoopEnabled,
                updated = true,
                stale = true,
                feedbackAgeMs = ageNs?.coerceAtLeast(0L)?.div(1_000_000.0),
                targetCmPerSec = targetCmPerSec,
                correctionDeg = effective - trim,
                integralCorrectionDeg = 0.0,
                trimDeg = trim,
                effectiveTargetDeg = effective,
                saturated = false,
                slewLimited = effective != desired,
            ).also { lastOutput = it }
        }

        feedback!!
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
        val error = targetCmPerSec - filtered
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
                targetCmPerSec = targetCmPerSec,
                errorCmPerSec = error,
                trimDeg = config.targetDeg,
                effectiveTargetDeg = config.targetDeg,
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
        if (!correctionSaturated || !integrationWouldWorsenSaturation) {
            integralCorrectionDeg = integralCandidate
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
            targetCmPerSec = targetCmPerSec,
            errorCmPerSec = error,
            correctionDeg = correction,
            integralCorrectionDeg = integralCorrectionDeg,
            trimDeg = config.targetDeg,
            effectiveTargetDeg = effective,
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

    private fun initialTargetDeg(): Double = if (config.speedLoopEnabled) {
        config.targetDeg.coerceIn(-config.speedAbsoluteAngleLimitDeg, config.speedAbsoluteAngleLimitDeg)
    } else config.targetDeg

    private fun validated(value: RobotConfig): RobotConfig {
        require(RobotConfigValidator.validate(value).isValid) { "invalid robot config" }
        return value
    }
}
