package com.woozie.balancingrobot.domain.model

/** Domain-only identifiers. No Android or transport type belongs in this package. */
enum class ServiceState { STOPPED, STARTING, RUNNING, STOPPING, FAILED }

enum class ArmState { DISARMED, READY, MANUAL_ARMED, BALANCE_ARMED, FAULT_LATCHED }

enum class ComponentId { IMU, ESTIMATOR, PD, USB, MOTOR_OUTPUT, TELEMETRY, LOG, WEB }

enum class ComponentStatus { OFF, STARTING, ACTIVE, BLOCKED, FAULT }

enum class Preset { SENSORS, FILTER, MANUAL_MOTORS, PD_SIMULATION, BALANCE }

enum class Axis { X, Y, Z }

/** STS3215 motor command mode. The mode is written to Operating Mode (33). */
enum class MotorControlMode(val protocolValue: Int, val label: String) {
    VELOCITY(1, "vitesse"),
    PWM(2, "PWM"),
}

enum class SensorKind { ACCEL, GYRO }

enum class FaultCode {
    IMU_STALE,
    IMU_TIMESTAMP_INVALID,
    ESTIMATE_INVALID,
    FALL_ANGLE,
    USB_DISCONNECTED,
    MOTOR_MISSING,
    BUS_ERROR,
    CONTROL_OVERRUN,
    INTERNAL_ERROR,
}

data class Vector3(val x: Double, val y: Double, val z: Double) {
    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
}

data class SensorSample(
    val kind: SensorKind,
    val values: Vector3,
    val sensorTimestampNs: Long,
    val receivedTimestampNs: Long,
)

data class Estimate(
    val accelAngleDeg: Double,
    val angleDeg: Double,
    val gyroRateDegPerSec: Double,
    val dtSec: Double,
)

data class ControlOutput(
    val targetDeg: Double,
    val errorDeg: Double,
    val rawCommand: Double,
    val boundedCommand: Int,
    val motorCommands: List<Int>,
    val saturated: Boolean,
)

data class RobotFault(
    val code: FaultCode,
    val message: String,
    val firstTimestampNs: Long,
    val lastTimestampNs: Long,
    val occurrences: Long,
    val details: Map<String, String> = emptyMap(),
)

data class RobotConfig(
    val schemaVersion: Int = 2,
    val axis: Axis = Axis.X,
    val imuSign: Int = 1,
    val zeroOffsetDeg: Double = 0.0,
    val alpha: Double = 0.98,
    val targetDeg: Double = 0.0,
    val kp: Double = 0.0,
    val kd: Double = 0.0,
    val speedLoopEnabled: Boolean = true,
    val speedTargetCmPerSec: Double = 0.0,
    val speedTargetLimitCmPerSec: Double = 10.0,
    val speedKevDegPerCmPerSec: Double = 0.0,
    val speedLoopRateHz: Int = 50,
    val speedFilterAlpha: Double = 0.5,
    val speedTargetAngleLimitDeg: Double = 10.0,
    val speedFeedbackTimeoutMs: Long = 100,
    val wheelDiameterMm: Double = 40.0,
    /** Motor revolutions per wheel revolution. */
    val driveRatio: Double = 1.0,
    val vmax: Int = 6000,
    val motorControlMode: MotorControlMode = MotorControlMode.VELOCITY,
    val pwmMax: Int = 1000,
    val motorIds: List<Int> = listOf(6, 7),
    val motorSigns: List<Int> = listOf(1, 1),
    val baudRate: Int = 1_000_000,
    val torqueLimit: Int = 1023,
    val imuTimeoutMs: Long = 100,
    val fallAngleDeg: Double = 35.0,
    val fallDurationMs: Long = 100,
    val manualTimeoutMs: Long = 300,
    /** Keep the arm state during IMU/control safety faults, while stopping fresh motor commands. */
    val inhibitSafetyAutoDisarm: Boolean = false,
    val logCapacity: Int = 200_000,
    val webPort: Int = 8766,
) {
    /** Output unit and limit used by both the PD loop and the motor scheduler. */
    val commandLimit: Int
        get() = if (motorControlMode == MotorControlMode.PWM) pwmMax else vmax
}
