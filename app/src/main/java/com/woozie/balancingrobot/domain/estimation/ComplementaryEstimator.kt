package com.woozie.balancingrobot.domain.estimation

import com.woozie.balancingrobot.domain.model.Axis
import com.woozie.balancingrobot.domain.model.Vector3
import kotlin.math.atan2
import kotlin.math.sqrt

/** Converts Android sensor gyroscope units (rad/s) to the domain unit (deg/s). */
fun gyroRateDegPerSec(gyroRateRadPerSec: Double): Double? =
    if (gyroRateRadPerSec.isFinite()) Math.toDegrees(gyroRateRadPerSec) else null

/** Selects and signs the gyro axis used by the estimator. */
fun selectedGyroRateDegPerSec(values: Vector3, axis: Axis, sign: Int): Double? {
    if (!values.isFinite() || sign != -1 && sign != 1) return null
    val selected = when (axis) {
        Axis.X -> values.x
        Axis.Y -> values.y
        Axis.Z -> values.z
    }
    return gyroRateDegPerSec(selected)?.times(sign)
}

/** Acceleration-only reference angle. Returns null for invalid or singular samples. */
fun accelAngleDeg(values: Vector3, axis: Axis, sign: Int, offsetDeg: Double): Double? {
    if (!values.isFinite() || sign != -1 && sign != 1 || !offsetDeg.isFinite()) return null

    val angle = when (axis) {
        // The reference implementation balances around X: atan2(ay, az).
        Axis.X -> if (values.y == 0.0 && values.z == 0.0) null else atan2(values.y, values.z)
        Axis.Y -> {
            val denominator = sqrt(values.y * values.y + values.z * values.z)
            if (values.x == 0.0 && denominator == 0.0) null else atan2(-values.x, denominator)
        }
        // Z has no gravity-only yaw reference; this projection is kept useful for
        // diagnostics and remains deterministic for the simulated pipeline.
        Axis.Z -> if (values.x == 0.0 && values.y == 0.0) null else atan2(values.y, values.x)
    } ?: return null

    // The mechanical zero is subtracted from the signed sensor angle, as
    // specified by EST-001; this keeps the raw vector untouched.
    return sign * Math.toDegrees(angle) - offsetDeg
}

/**
 * One complementary-filter step. A missing previous estimate initializes from
 * the accelerometer. Invalid input returns null so the runtime can raise a
 * structured ESTIMATE_INVALID fault instead of emitting a motor command.
 */
fun complementaryStep(
    previousAngleDeg: Double?,
    gyroRateDegPerSec: Double,
    dtSec: Double,
    accelAngleDeg: Double,
    alpha: Double,
): Double? {
    if (previousAngleDeg != null && !previousAngleDeg.isFinite()) return null
    if (!gyroRateDegPerSec.isFinite() || !dtSec.isFinite() || dtSec <= 0.0) return null
    if (!accelAngleDeg.isFinite() || !alpha.isFinite() || alpha !in 0.0..1.0) return null
    if (previousAngleDeg == null) return accelAngleDeg
    return alpha * (previousAngleDeg + gyroRateDegPerSec * dtSec) +
        (1.0 - alpha) * accelAngleDeg
}

class ComplementaryEstimator(initialAlpha: Double) {
    @Volatile
    private var alpha: Double = initialAlpha
    private var previousAngleDeg: Double? = null

    init {
        require(initialAlpha.isFinite() && initialAlpha in 0.0..1.0) {
            "alpha must be finite and in [0, 1]"
        }
    }

    /** Changes the filter weight without clearing the current estimate. */
    fun updateAlpha(value: Double) {
        require(value.isFinite() && value in 0.0..1.0) {
            "alpha must be finite and in [0, 1]"
        }
        alpha = value
    }

    fun reset() {
        previousAngleDeg = null
    }

    fun step(gyroRateDegPerSec: Double, dtSec: Double, accelAngleDeg: Double): Double? {
        val result = complementaryStep(previousAngleDeg, gyroRateDegPerSec, dtSec, accelAngleDeg, alpha)
        if (result != null) previousAngleDeg = result
        return result
    }
}
