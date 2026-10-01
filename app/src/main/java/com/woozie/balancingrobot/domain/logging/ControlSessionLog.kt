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
    val controlLatencyMs: Double?,
    val armState: String,
    val sampleStatus: String?,
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
                "gyro_rate_dps,dt_s,schema_version,axis,imu_sign,zero_offset_deg,alpha,target_deg,kp,kd," +
                "vmax,motor_id_0,motor_id_1,motor_sign_0,motor_sign_1,baud_rate,torque_limit," +
                "imu_timeout_ms,fall_angle_deg,fall_duration_ms,manual_timeout_ms,log_capacity," +
                "web_port,error_deg,raw_command,bounded_command,saturated,motor_command_0," +
                "motor_command_1,control_latency_ms,arm_state,sample_status",
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
                .append(config.kp).append(',')
                .append(config.kd).append(',')
                .append(config.vmax).append(',')
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
                .append(config.logCapacity).append(',')
                .append(config.webPort).append(',')
                .appendNullable(record.errorDeg).append(',')
                .appendNullable(record.rawCommand).append(',')
                .append(record.boundedCommand ?: "").append(',')
                .append(record.saturated ?: "").append(',')
                .append(record.motorCommand0 ?: "").append(',')
                .append(record.motorCommand1 ?: "").append(',')
                .appendNullable(record.controlLatencyMs).append(',')
                .append(record.armState).append(',')
                .append(record.sampleStatus?.replace(',', ';') ?: "")
                .appendLine()
        }
    }

    private fun StringBuilder.appendNullable(value: Double?): StringBuilder {
        if (value != null) append(String.format(Locale.US, "%.9f", value))
        return this
    }
}
