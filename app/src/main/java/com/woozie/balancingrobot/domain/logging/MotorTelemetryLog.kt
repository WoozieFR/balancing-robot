package com.woozie.balancingrobot.domain.logging

import com.woozie.balancingrobot.motor.FeetechTelemetry
import java.util.ArrayDeque
import java.util.Locale

data class MotorLogRecord(
    val timestampNs: Long,
    val command: Int,
    val telemetry: FeetechTelemetry,
)

class MotorTelemetryLog(private val capacity: Int = 20_000) {
    private val records = ArrayDeque<MotorLogRecord>()

    @Synchronized
    fun append(command: Int, telemetry: FeetechTelemetry, timestampNs: Long = System.nanoTime()) {
        records.addLast(MotorLogRecord(timestampNs, command, telemetry))
        while (records.size > capacity) records.removeFirst()
    }

    @Synchronized
    fun clear() = records.clear()

    @Synchronized
    fun size(): Int = records.size

    @Synchronized
    fun toCsv(): String = buildString {
        appendLine("timestamp_ns,motor_id,command,measured_velocity,load,voltage,temperature")
        records.forEach { record ->
            append(record.timestampNs).append(',')
                .append(record.telemetry.servoId).append(',')
                .append(record.command).append(',')
                .append(record.telemetry.velocity).append(',')
                .append(record.telemetry.load).append(',')
                .append(String.format(Locale.US, "%.2f", record.telemetry.voltage)).append(',')
                .append(record.telemetry.temperatureC).appendLine()
        }
    }
}
