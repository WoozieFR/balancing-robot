package com.woozie.balancingrobot.domain.control

import com.woozie.balancingrobot.domain.model.RobotConfig
import com.woozie.balancingrobot.domain.model.RobotConfigValidator
import kotlin.math.PI
import kotlin.math.min

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
    val trimDeg: Double,
    val effectiveTargetDeg: Double,
    val saturated: Boolean = false,
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
    private var lastOutput: VelocityLoopOutput? = null

    fun updateConfig(newConfig: RobotConfig) {
        val valid = validated(newConfig)
        val resetFilter = config.speedLoopEnabled != valid.speedLoopEnabled ||
            config.wheelDiameterMm != valid.wheelDiameterMm ||
            config.driveRatio != valid.driveRatio ||
            config.speedFilterAlpha != valid.speedFilterAlpha
        config = valid
        lastTickNs = 0L
        if (resetFilter) {
            filteredCmPerSec = null
            lastFeedbackSequence = null
            wasStale = true
            lastOutput = null
        }
    }

    fun reset() {
        lastTickNs = 0L
        lastFeedbackSequence = null
        filteredCmPerSec = null
        wasStale = true
        lastOutput = null
    }

    fun step(nowNs: Long, feedback: WheelVelocityFeedback?): VelocityLoopOutput {
        require(nowNs > 0L) { "monotonic timestamp must be positive" }
        val periodNs = 1_000_000_000L / config.speedLoopRateHz
        if (lastTickNs > 0L && nowNs >= lastTickNs && nowNs - lastTickNs < periodNs) {
            return checkNotNull(lastOutput).copy(updated = false)
        }
        if (lastTickNs > nowNs) reset()
        lastTickNs = nowNs

        val ageNs = feedback?.let {
            nowNs - min(it.leftTimestampNs, it.rightTimestampNs)
        }
        val fresh = feedback != null && ageNs != null && ageNs >= 0L &&
            ageNs <= config.speedFeedbackTimeoutMs * 1_000_000L
        if (!fresh) {
            wasStale = true
            val frozen = lastOutput?.effectiveTargetDeg
                ?: initialTargetDeg()
            return (lastOutput ?: VelocityLoopOutput(
                enabled = config.speedLoopEnabled,
                updated = true,
                stale = true,
                targetCmPerSec = config.speedTargetCmPerSec,
                trimDeg = config.targetDeg,
                effectiveTargetDeg = frozen,
            )).copy(
                enabled = config.speedLoopEnabled,
                updated = true,
                stale = true,
                feedbackAgeMs = ageNs?.coerceAtLeast(0L)?.div(1_000_000.0),
                targetCmPerSec = config.speedTargetCmPerSec,
                trimDeg = config.targetDeg,
                effectiveTargetDeg = if (config.speedLoopEnabled) frozen else config.targetDeg,
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
        val error = config.speedTargetCmPerSec - filtered
        val correction = if (config.speedLoopEnabled) {
            config.speedKevDegPerCmPerSec * error
        } else 0.0
        val rawTarget = config.targetDeg + correction
        val effective = if (config.speedLoopEnabled) {
            rawTarget.coerceIn(-config.speedTargetAngleLimitDeg, config.speedTargetAngleLimitDeg)
        } else config.targetDeg
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
            targetCmPerSec = config.speedTargetCmPerSec,
            errorCmPerSec = error,
            correctionDeg = correction,
            trimDeg = config.targetDeg,
            effectiveTargetDeg = effective,
            saturated = config.speedLoopEnabled && effective != rawTarget,
        ).also { lastOutput = it }
    }

    private fun initialTargetDeg(): Double = if (config.speedLoopEnabled) {
        config.targetDeg.coerceIn(-config.speedTargetAngleLimitDeg, config.speedTargetAngleLimitDeg)
    } else config.targetDeg

    private fun validated(value: RobotConfig): RobotConfig {
        require(RobotConfigValidator.validate(value).isValid) { "invalid robot config" }
        return value
    }
}
