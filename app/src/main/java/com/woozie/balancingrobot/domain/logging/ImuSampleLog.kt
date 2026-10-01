package com.woozie.balancingrobot.domain.logging

data class ImuLogRecord(
    val timestampNs: Long,
    val accelAngleDeg: Double?,
    val estimatedAngleDeg: Double?,
    val gyroRateDegPerSec: Double,
    val dtSec: Double,
)

/** Bounded, allocation-free-on-append ring semantics for the Lot 2 diagnostic log. */
class ImuSampleLog(private val capacity: Int = 2_000) {
    private val records = arrayOfNulls<ImuLogRecord>(capacity)
    private var nextIndex = 0
    private var count = 0

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    @Synchronized
    fun append(record: ImuLogRecord) {
        records[nextIndex] = record
        nextIndex = (nextIndex + 1) % capacity
        if (count < capacity) count++
    }

    @Synchronized
    fun size(): Int = count

    @Synchronized
    fun clear() {
        records.fill(null)
        nextIndex = 0
        count = 0
    }

    @Synchronized
    fun snapshot(): List<ImuLogRecord> = buildList(count) {
        val first = if (count == capacity) nextIndex else 0
        repeat(count) { offset ->
            records[(first + offset) % capacity]?.let(::add)
        }
    }

    @Synchronized
    fun toCsv(): String = buildString {
        appendLine("timestamp_ns,accel_angle_deg,estimated_angle_deg,gyro_rate_dps,dt_s")
        snapshot().forEach { record ->
            append(record.timestampNs).append(',')
                .append(record.accelAngleDeg ?: "").append(',')
                .append(record.estimatedAngleDeg ?: "").append(',')
                .append(record.gyroRateDegPerSec).append(',')
                .append(record.dtSec).appendLine()
        }
    }
}
