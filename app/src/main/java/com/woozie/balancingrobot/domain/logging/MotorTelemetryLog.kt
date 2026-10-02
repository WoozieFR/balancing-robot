package com.woozie.balancingrobot.domain.logging

import com.woozie.balancingrobot.motor.FeetechTelemetry
import com.woozie.balancingrobot.motor.MotorWriteTrace
import java.util.ArrayDeque
import java.util.Locale

data class MotorLogRecord(
    val timestampNs: Long,
    val command: Int?,
    val telemetry: FeetechTelemetry,
    val controlSequence: Long? = null,
    val controlSubmittedNs: Long? = null,
    val motorWriteStartNs: Long? = null,
    val motorWriteEndNs: Long? = null,
)

class MotorTelemetryLog(private val capacity: Int = 20_000) {
    private val records = ArrayDeque<MotorLogRecord>()

    @Synchronized
    fun append(
        command: Int?,
        telemetry: FeetechTelemetry,
        writeTrace: MotorWriteTrace? = null,
        timestampNs: Long = System.nanoTime(),
    ) {
        records.addLast(
            MotorLogRecord(
                timestampNs = timestampNs,
                command = command,
                telemetry = telemetry,
                controlSequence = writeTrace?.sequence,
                controlSubmittedNs = writeTrace?.submittedAtNs,
                motorWriteStartNs = writeTrace?.startedAtNs,
                motorWriteEndNs = writeTrace?.finishedAtNs,
            ),
        )
        while (records.size > capacity) records.removeFirst()
    }

    @Synchronized
    fun clear() = records.clear()

    @Synchronized
    fun size(): Int = records.size

    @Synchronized
    fun toCsv(): String = buildString {
        appendLine(
            "timestamp_ns,motor_id,command,measured_velocity,load,voltage,temperature," +
                "control_sequence,control_submitted_ns,motor_write_start_ns,motor_write_end_ns",
        )
        records.forEach { record ->
            append(record.timestampNs).append(',')
                .append(record.telemetry.servoId).append(',')
                .append(record.command ?: "").append(',')
                .append(record.telemetry.velocity).append(',')
                .append(record.telemetry.load).append(',')
                .append(String.format(Locale.US, "%.2f", record.telemetry.voltage)).append(',')
                .append(record.telemetry.temperatureC).append(',')
                .append(record.controlSequence ?: "").append(',')
                .append(record.controlSubmittedNs ?: "").append(',')
                .append(record.motorWriteStartNs ?: "").append(',')
                .append(record.motorWriteEndNs ?: "").appendLine()
        }
    }
}
