package com.woozie.balancingrobot.runtime

import com.woozie.balancingrobot.domain.control.applyMotorSigns
import com.woozie.balancingrobot.domain.control.pdStep
import com.woozie.balancingrobot.domain.estimation.ComplementaryEstimator
import com.woozie.balancingrobot.domain.estimation.accelAngleDeg
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

/** Lot 1 runtime: the complete estimate + PD chain, with no Android/USB side effects. */
class SimulatedControlRuntime(initialConfig: RobotConfig) {
    @Volatile
    private var config: RobotConfig = initialConfig
    private val estimator = ComplementaryEstimator(initialConfig.alpha)

    init {
        val validation = RobotConfigValidator.validate(initialConfig)
        require(validation.isValid) { "invalid robot config: ${validation.errors.joinToString()}" }
    }

    /** Applies tuning changes to the next IMU sample without resetting the filter. */
    fun updateConfig(newConfig: RobotConfig) {
        val validation = RobotConfigValidator.validate(newConfig)
        require(validation.isValid) { "invalid robot config: ${validation.errors.joinToString()}" }
        estimator.updateAlpha(newConfig.alpha)
        config = newConfig
    }

    fun reset() = estimator.reset()

    fun step(accel: Vector3, gyroRateDegPerSec: Double, dtSec: Double): SimulationStep {
        val reference = accelAngleDeg(accel, config.axis, config.imuSign, config.zeroOffsetDeg)
            ?: return SimulationStep(null, null, FaultCode.ESTIMATE_INVALID)
        return step(reference, gyroRateDegPerSec, dtSec)
    }

    fun step(accelAngleDeg: Double, gyroRateDegPerSec: Double, dtSec: Double): SimulationStep {
        val angle = estimator.step(gyroRateDegPerSec, dtSec, accelAngleDeg)
            ?: return SimulationStep(null, null, FaultCode.ESTIMATE_INVALID)
        val estimate = Estimate(accelAngleDeg, angle, gyroRateDegPerSec, dtSec)
        val base = try {
            pdStep(config.targetDeg, angle, gyroRateDegPerSec, config.kp, config.kd, config.commandLimit)
        } catch (_: IllegalArgumentException) {
            return SimulationStep(estimate, null, FaultCode.ESTIMATE_INVALID)
        }
        val output = ControlOutput(
            targetDeg = config.targetDeg,
            errorDeg = base.errorDeg,
            rawCommand = base.rawCommand,
            boundedCommand = base.boundedCommand,
            motorCommands = applyMotorSigns(base.boundedCommand, config.motorSigns),
            saturated = base.saturated,
        )
        return SimulationStep(estimate, output, null)
    }
}
