package com.woozie.balancingrobot.domain.metrics

import java.util.ArrayDeque
import kotlin.math.sqrt

data class SampleRateMetrics(
    val frequencyHz: Double = 0.0,
    val intervalMs: Double = 0.0,
    val jitterMs: Double = 0.0,
)

/** Small bounded timestamp window used by the IMU diagnostic screen. */
class SampleRateMeter(private val windowSize: Int = 32) {
    private val intervalsNs = ArrayDeque<Long>()
    private var previousTimestampNs: Long? = null

    init {
        require(windowSize >= 2) { "windowSize must be at least 2" }
    }

    fun reset() {
        intervalsNs.clear()
        previousTimestampNs = null
    }

    fun record(timestampNs: Long): SampleRateMetrics {
        val previous = previousTimestampNs
        if (timestampNs <= 0L || previous != null && timestampNs <= previous) return metrics()
        previousTimestampNs = timestampNs
        if (previous == null) return metrics()

        intervalsNs.addLast(timestampNs - previous)
        while (intervalsNs.size > windowSize) intervalsNs.removeFirst()
        return metrics()
    }

    private fun metrics(): SampleRateMetrics {
        if (intervalsNs.isEmpty()) return SampleRateMetrics()
        val meanNs = intervalsNs.average()
        val varianceNs = intervalsNs
            .map { delta -> (delta - meanNs) * (delta - meanNs) }
            .average()
        return SampleRateMetrics(
            frequencyHz = 1_000_000_000.0 / meanNs,
            intervalMs = meanNs / 1_000_000.0,
            jitterMs = sqrt(varianceNs) / 1_000_000.0,
        )
    }
}
