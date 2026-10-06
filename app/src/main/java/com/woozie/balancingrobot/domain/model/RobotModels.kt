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
    val yawTargetDegPerSec: Double = 0.0,
    val yawRateDegPerSec: Double = 0.0,
    val yawErrorDegPerSec: Double = 0.0,
    val turnCommand: Int = 0,
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
    /** User command range retained as the joystick input domain (cm/s equivalent). */
    val speedTargetCmPerSec: Double = 0.0,
    val speedTargetLimitCmPerSec: Double = 10.0,
    /** Maximum forward/backward lean commanded by the joystick. */
    val joystickMaxLeanDeg: Double = 3.0,
    /** Slew rate applied to the joystick lean term. */
    val joystickLeanSlewRateDegPerSec: Double = 30.0,
    /** Dead-zone in the command domain before a maneuver is considered active. */
    val joystickDeadbandCmPerSec: Double = 0.1,
    /** Optional proportional braking correction, disabled by default. */
    val brakeKpDegPerCmPerSec: Double = 0.0,
    val brakeLimitDeg: Double = 2.0,
    /** Maximum speed-target movement per second. */
    val speedTargetSlewRateCmPerSec: Double = 20.0,
    val speedKevDegPerCmPerSec: Double = 0.0,
    val speedLoopRateHz: Int = 50,
    val speedFilterAlpha: Double = 0.5,
    val speedTargetAngleLimitDeg: Double = 10.0,
    /** Maximum velocity-loop correction around targetDeg (the trim). */
    val speedIntegralGainDegPerCmPerSecSec: Double = 0.0,
    /** Absolute target-angle safety envelope applied after trim + correction. */
    val speedAbsoluteAngleLimitDeg: Double = 15.0,
    /** Maximum target-angle movement per second. */
    val speedTargetSlewRateDegPerSec: Double = 30.0,
    val speedFeedbackTimeoutMs: Long = 100,
    /** Absolute filtered mean-speed threshold used to qualify a settled robot. */
    val speedQuietThresholdCmPerSec: Double = 0.5,
    /** Required continuous quiet duration before auto-trim learning resumes. */
    val speedQuietDurationMs: Long = 700,
    /** Desired yaw rate. Zero explicitly disables the yaw correction loop. */
    val yawTargetDegPerSec: Double = 0.0,
    /** Proportional yaw gain in motor-command units per deg/s. */
    val yawKpCommandPerDegPerSec: Double = 1.0,
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
