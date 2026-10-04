package com.woozie.balancingrobot.domain.logging

import com.woozie.balancingrobot.domain.model.RobotConfig
import java.util.ArrayDeque
import java.util.Locale

/** One complete control-loop sample captured while the operator records a session. */
data class ControlLogRecord(
    val timestampNs: Long,
    val receivedTimestampNs: Long,
    val accelX: Double?,
    val accelY: Double?,
    val accelZ: Double?,
    val gyroXDegPerSec: Double?,
    val gyroYDegPerSec: Double?,
    val gyroZDegPerSec: Double?,
    val accelAngleDeg: Double?,
    val estimatedAngleDeg: Double?,
    val gyroRateDegPerSec: Double?,
    val dtSec: Double?,
    val config: RobotConfig,
    val errorDeg: Double?,
    val rawCommand: Double?,
    val boundedCommand: Int?,
    val saturated: Boolean?,
    val motorCommand0: Int?,
    val motorCommand1: Int?,
    val yawTargetDegPerSec: Double? = null,
    val yawRateDegPerSec: Double? = null,
    val yawErrorDegPerSec: Double? = null,
    val turnCommand: Int? = null,
    val controlLatencyMs: Double?,
    val armState: String,
    val sampleStatus: String?,
    val effectiveTargetDeg: Double? = null,
    val speedLoopEnabled: Boolean? = null,
    val speedFeedbackSequence: Long? = null,
    val speedFeedbackAgeMs: Double? = null,
    val speedLeftRawStepsPerSec: Int? = null,
    val speedRightRawStepsPerSec: Int? = null,
    val speedLeftCmPerSec: Double? = null,
    val speedRightCmPerSec: Double? = null,
    val speedMeanCmPerSec: Double? = null,
    val speedFilteredCmPerSec: Double? = null,
    val speedTargetCmPerSec: Double? = null,
    val speedErrorCmPerSec: Double? = null,
    val speedCorrectionDeg: Double? = null,
    val speedIntegralCorrectionDeg: Double? = null,
    val speedTargetSlewLimited: Boolean? = null,
    val speedStale: Boolean? = null,
    val speedTargetSaturated: Boolean? = null,
    val speedLoopActualRateHz: Double? = null,
    val speedFeedbackActualRateHz: Double? = null,
    val gamepadSource: String? = null,
    val gamepadNeutralReason: String? = null,
    val gamepadCommandAgeMs: Double? = null,
    val gamepadSequence: Long? = null,
    val gamepadDeadmanHeld: Boolean? = null,
    val gamepadSpeedTargetCmPerSec: Double? = null,
    val gamepadYawTargetDegPerSec: Double? = null,
)

/** Explicit start/stop ring buffer for a RAM-resident control session. */
class ControlSessionLog(private val capacity: Int = 100_000) {
    private val records = ArrayDeque<ControlLogRecord>()

    @Volatile
    private var recording = false

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    @Synchronized
    fun start() {
        records.clear()
        recording = true
    }

    @Synchronized
    fun stop() {
        recording = false
    }

    fun isRecording(): Boolean = recording

    @Synchronized
    fun append(record: ControlLogRecord) {
        if (!recording) return
        records.addLast(record)
        while (records.size > capacity) records.removeFirst()
    }

    @Synchronized
    fun size(): Int = records.size

    @Synchronized
    fun snapshot(): List<ControlLogRecord> = records.toList()

    @Synchronized
    fun toCsv(): String = buildString {
        appendLine(
            "timestamp_ns,received_timestamp_ns,accel_x_mps2,accel_y_mps2,accel_z_mps2," +
                "gyro_x_dps,gyro_y_dps,gyro_z_dps,accel_angle_deg,estimated_angle_deg," +
                "gyro_rate_dps,dt_s,schema_version,axis,imu_sign,zero_offset_deg,alpha,angle_trim_deg,target_deg,kp,kd," +
                "speed_loop_enabled,speed_target_cmps,speed_target_limit_cmps,speed_kev_deg_per_cmps," +
                "speed_loop_rate_hz,speed_filter_alpha,speed_target_angle_limit_deg," +
                "speed_integral_gain_deg_per_cmps_s,speed_absolute_angle_limit_deg,speed_target_slew_rate_deg_per_sec," +
                "speed_feedback_timeout_ms," +
                "yaw_target_dps,yaw_kp_command_per_dps," +
                "wheel_diameter_mm,drive_ratio," +
                "vmax,motor_control_mode,pwm_max,command_limit,motor_id_0,motor_id_1,motor_sign_0,motor_sign_1,baud_rate,torque_limit," +
                "imu_timeout_ms,fall_angle_deg,fall_duration_ms,manual_timeout_ms,inhibit_safety_auto_disarm,log_capacity," +
                "web_port,error_deg,raw_command,bounded_command,saturated,motor_command_0," +
                "motor_command_1,yaw_target_dps,yaw_rate_dps,yaw_error_dps,turn_command,control_latency_ms," +
                "speed_feedback_sequence,speed_feedback_age_ms," +
                "speed_left_raw_steps_per_s,speed_right_raw_steps_per_s,speed_left_cmps,speed_right_cmps," +
                "speed_mean_cmps,speed_filtered_cmps,speed_error_cmps,speed_correction_deg," +
                "speed_integral_correction_deg,speed_target_slew_limited,speed_stale," +
                "speed_target_saturated,speed_loop_actual_hz,speed_feedback_actual_hz,arm_state,sample_status," +
                "gamepad_source,gamepad_neutral_reason,gamepad_command_age_ms,gamepad_sequence," +
                "gamepad_deadman_held,gamepad_speed_target_cmps,gamepad_yaw_target_dps",
        )
        records.forEach { record ->
            val config = record.config
            append(record.timestampNs).append(',')
                .append(record.receivedTimestampNs).append(',')
                .appendNullable(record.accelX).append(',')
                .appendNullable(record.accelY).append(',')
                .appendNullable(record.accelZ).append(',')
                .appendNullable(record.gyroXDegPerSec).append(',')
                .appendNullable(record.gyroYDegPerSec).append(',')
                .appendNullable(record.gyroZDegPerSec).append(',')
                .appendNullable(record.accelAngleDeg).append(',')
                .appendNullable(record.estimatedAngleDeg).append(',')
                .appendNullable(record.gyroRateDegPerSec).append(',')
                .appendNullable(record.dtSec).append(',')
                .append(config.schemaVersion).append(',')
                .append(config.axis.name).append(',')
                .append(config.imuSign).append(',')
                .append(config.zeroOffsetDeg).append(',')
                .append(config.alpha).append(',')
                .append(config.targetDeg).append(',')
                .appendNullable(record.effectiveTargetDeg).append(',')
                .append(config.kp).append(',')
                .append(config.kd).append(',')
                .append(record.speedLoopEnabled ?: config.speedLoopEnabled).append(',')
                .append(record.speedTargetCmPerSec ?: config.speedTargetCmPerSec).append(',')
                .append(config.speedTargetLimitCmPerSec).append(',')
                .append(config.speedKevDegPerCmPerSec).append(',')
                .append(config.speedLoopRateHz).append(',')
                .append(config.speedFilterAlpha).append(',')
                .append(config.speedTargetAngleLimitDeg).append(',')
                .append(config.speedIntegralGainDegPerCmPerSecSec).append(',')
                .append(config.speedAbsoluteAngleLimitDeg).append(',')
                .append(config.speedTargetSlewRateDegPerSec).append(',')
                .append(config.speedFeedbackTimeoutMs).append(',')
                .append(config.yawTargetDegPerSec).append(',')
                .append(config.yawKpCommandPerDegPerSec).append(',')
                .append(config.wheelDiameterMm).append(',')
                .append(config.driveRatio).append(',')
                .append(config.vmax).append(',')
                .append(config.motorControlMode.name).append(',')
                .append(config.pwmMax).append(',')
                .append(config.commandLimit).append(',')
                .append(config.motorIds.getOrNull(0) ?: "").append(',')
                .append(config.motorIds.getOrNull(1) ?: "").append(',')
                .append(config.motorSigns.getOrNull(0) ?: "").append(',')
                .append(config.motorSigns.getOrNull(1) ?: "").append(',')
                .append(config.baudRate).append(',')
                .append(config.torqueLimit).append(',')
                .append(config.imuTimeoutMs).append(',')
                .append(config.fallAngleDeg).append(',')
                .append(config.fallDurationMs).append(',')
                .append(config.manualTimeoutMs).append(',')
                .append(config.inhibitSafetyAutoDisarm).append(',')
                .append(config.logCapacity).append(',')
                .append(config.webPort).append(',')
                .appendNullable(record.errorDeg).append(',')
                .appendNullable(record.rawCommand).append(',')
                .append(record.boundedCommand ?: "").append(',')
                .append(record.saturated ?: "").append(',')
                .append(record.motorCommand0 ?: "").append(',')
                .append(record.motorCommand1 ?: "").append(',')
                .appendNullable(record.yawTargetDegPerSec).append(',')
                .appendNullable(record.yawRateDegPerSec).append(',')
                .appendNullable(record.yawErrorDegPerSec).append(',')
                .append(record.turnCommand ?: "").append(',')
                .appendNullable(record.controlLatencyMs).append(',')
                .append(record.speedFeedbackSequence ?: "").append(',')
                .appendNullable(record.speedFeedbackAgeMs).append(',')
                .append(record.speedLeftRawStepsPerSec ?: "").append(',')
                .append(record.speedRightRawStepsPerSec ?: "").append(',')
                .appendNullable(record.speedLeftCmPerSec).append(',')
                .appendNullable(record.speedRightCmPerSec).append(',')
                .appendNullable(record.speedMeanCmPerSec).append(',')
                .appendNullable(record.speedFilteredCmPerSec).append(',')
                .appendNullable(record.speedErrorCmPerSec).append(',')
                .appendNullable(record.speedCorrectionDeg).append(',')
                .appendNullable(record.speedIntegralCorrectionDeg).append(',')
                .append(record.speedTargetSlewLimited ?: "").append(',')
                .append(record.speedStale ?: "").append(',')
                .append(record.speedTargetSaturated ?: "").append(',')
                .appendNullable(record.speedLoopActualRateHz).append(',')
                .appendNullable(record.speedFeedbackActualRateHz).append(',')
                .append(record.armState).append(',')
                .append(record.sampleStatus?.replace(',', ';') ?: "").append(',')
                .append(record.gamepadSource ?: "").append(',')
                .append(record.gamepadNeutralReason ?: "").append(',')
                .appendNullable(record.gamepadCommandAgeMs).append(',')
                .append(record.gamepadSequence ?: "").append(',')
                .append(record.gamepadDeadmanHeld ?: "").append(',')
                .appendNullable(record.gamepadSpeedTargetCmPerSec).append(',')
                .appendNullable(record.gamepadYawTargetDegPerSec)
                .appendLine()
        }
    }

    private fun StringBuilder.appendNullable(value: Double?): StringBuilder {
        if (value != null) append(String.format(Locale.US, "%.9f", value))
        return this
    }
}
