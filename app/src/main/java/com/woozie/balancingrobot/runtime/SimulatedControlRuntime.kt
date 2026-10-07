package com.woozie.balancingrobot.runtime

import com.woozie.balancingrobot.domain.control.applyMotorSigns
import com.woozie.balancingrobot.domain.control.mixDifferential
import com.woozie.balancingrobot.domain.control.pdStep
import com.woozie.balancingrobot.domain.control.yawTurnStep
import com.woozie.balancingrobot.domain.estimation.AttitudeEstimator
import com.woozie.balancingrobot.domain.estimation.AttitudeEstimatorFactory
import com.woozie.balancingrobot.domain.model.AttitudeFilterMode
import com.woozie.balancingrobot.domain.model.Axis
import com.woozie.balancingrobot.domain.model.ControlOutput
import com.woozie.balancingrobot.domain.model.Estimate
import com.woozie.balancingrobot.domain.model.FaultCode
import com.woozie.balancingrobot.domain.model.RobotConfig
import com.woozie.balancingrobot.domain.model.RobotConfigValidator
import com.woozie.balancingrobot.domain.model.Vector3

data class SimulationStep(
    val estimate: Estimate?,
    val control: ControlOutput?,
    val fault: FaultCode?,
)

/** Complete estimate + PD chain with no Android/USB side effects. */
class SimulatedControlRuntime(initialConfig: RobotConfig) {
    @Volatile
    private var config: RobotConfig = initialConfig
    private var estimator: AttitudeEstimator = AttitudeEstimatorFactory.create(
        initialConfig.attitudeFilterMode,
        initialConfig.alpha,
    )

    val estimatorInitialized: Boolean
        get() = estimator.initialized

    val estimatorResetCount: Long
        get() = estimator.resetCount

    init {
        val validation = RobotConfigValidator.validate(initialConfig)
        require(validation.isValid) { "invalid robot config: ${validation.errors.joinToString()}" }
    }

    /** Applies tuning changes without resetting the estimator unless its mode changes. */
    fun updateConfig(newConfig: RobotConfig) {
        val validation = RobotConfigValidator.validate(newConfig)
        require(validation.isValid) { "invalid robot config: ${validation.errors.joinToString()}" }
        if (newConfig.attitudeFilterMode != config.attitudeFilterMode) {
            estimator = AttitudeEstimatorFactory.create(newConfig.attitudeFilterMode, newConfig.alpha)
        } else {
            estimator.updateAlpha(newConfig.alpha)
        }
        config = newConfig
    }

    fun reset() = estimator.reset()

    fun estimate(
        accel: Vector3,
        gyroBodyRadPerSec: Vector3,
        dtSec: Double,
    ): Estimate? {
        val attitude = estimator.step(
            accelBodyMps2 = accel,
            gyroBodyRadPerSec = gyroBodyRadPerSec,
            dtSec = dtSec,
            axis = config.axis,
            imuSign = config.imuSign,
            zeroOffsetDeg = config.zeroOffsetDeg,
        ) ?: return null
        return Estimate(
            accelAngleDeg = attitude.accelAngleDeg,
            angleDeg = attitude.thetaDeg,
            gyroRateDegPerSec = attitude.pitchRateDegPerSec,
            dtSec = attitude.dtSec,
            yawRateDegPerSec = attitude.yawRateDegPerSec,
            filterMode = attitude.filterMode,
        )
    }

    fun control(
        estimate: Estimate,
        targetDeg: Double = config.targetDeg,
        yawTargetDegPerSec: Double = config.yawTargetDegPerSec,
    ): SimulationStep = buildControl(estimate, targetDeg, yawTargetDegPerSec)

    fun step(
        accel: Vector3,
        gyroBodyRadPerSec: Vector3,
        dtSec: Double,
        targetDeg: Double = config.targetDeg,
        yawTargetDegPerSec: Double = config.yawTargetDegPerSec,
    ): SimulationStep {
        val estimate = estimate(accel, gyroBodyRadPerSec, dtSec)
            ?: return SimulationStep(null, null, FaultCode.ESTIMATE_INVALID)
        return control(estimate, targetDeg, yawTargetDegPerSec)
    }

    /** Compatibility overload used by scalar simulation callers and older tests. */
    fun step(
        accel: Vector3,
        gyroRateDegPerSec: Double,
        dtSec: Double,
        targetDeg: Double = config.targetDeg,
        yawRateDegPerSec: Double = 0.0,
        yawTargetDegPerSec: Double = config.yawTargetDegPerSec,
    ): SimulationStep {
        val gyroRadPerSec = Math.toRadians(gyroRateDegPerSec)
        val gyro = when (config.axis) {
            Axis.X -> Vector3(gyroRadPerSec, 0.0, 0.0)
            Axis.Y -> Vector3(0.0, gyroRadPerSec, 0.0)
            Axis.Z -> Vector3(0.0, 0.0, gyroRadPerSec)
        }
        val step = step(accel, gyro, dtSec, targetDeg, yawTargetDegPerSec)
        if (step.control == null || config.attitudeFilterMode != AttitudeFilterMode.LEGACY_COMPLEMENTARY) {
            return step
        }
        val estimate = step.estimate ?: return step
        return buildControl(estimate.copy(yawRateDegPerSec = yawRateDegPerSec), targetDeg, yawTargetDegPerSec)
    }

    private fun buildControl(
        estimate: Estimate,
        targetDeg: Double,
        yawTargetDegPerSec: Double,
    ): SimulationStep {
        val base = try {
            pdStep(targetDeg, estimate.angleDeg, estimate.gyroRateDegPerSec, config.kp, config.kd, config.commandLimit)
        } catch (_: IllegalArgumentException) {
            return SimulationStep(estimate, null, FaultCode.ESTIMATE_INVALID)
        }
        val yaw = try {
            yawTurnStep(
                targetDegPerSec = yawTargetDegPerSec,
                measuredDegPerSec = estimate.yawRateDegPerSec,
                kpCommandPerDegPerSec = config.yawKpCommandPerDegPerSec,
                commandLimit = config.commandLimit,
            )
        } catch (_: IllegalArgumentException) {
            return SimulationStep(estimate, null, FaultCode.ESTIMATE_INVALID)
        }
        val mix = mixDifferential(base.boundedCommand, yaw.boundedCommand, config.commandLimit)
        val output = ControlOutput(
            targetDeg = targetDeg,
            errorDeg = base.errorDeg,
            rawCommand = base.rawCommand,
            boundedCommand = base.boundedCommand,
            motorCommands = applyMotorSigns(listOf(mix.left, mix.right), config.motorSigns),
            saturated = base.saturated || yaw.saturated || mix.saturated,
            yawTargetDegPerSec = yawTargetDegPerSec,
            yawRateDegPerSec = estimate.yawRateDegPerSec,
            yawErrorDegPerSec = yaw.errorDegPerSec,
            turnCommand = yaw.boundedCommand,
        )
        return SimulationStep(estimate, output, null)
    }
}
