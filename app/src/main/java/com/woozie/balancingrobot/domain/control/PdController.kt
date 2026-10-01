package com.woozie.balancingrobot.domain.control

import kotlin.math.round

data class ControlBase(
    val errorDeg: Double,
    val rawCommand: Double,
    val boundedCommand: Int,
    val saturated: Boolean,
)

/** Pure PD controller. The derivative term is damping and is therefore subtracted. */
fun pdStep(
    targetDeg: Double,
    angleDeg: Double,
    gyroRateDegPerSec: Double,
    kp: Double,
    kd: Double,
    vmax: Int,
): ControlBase {
    require(targetDeg.isFinite() && angleDeg.isFinite() && gyroRateDegPerSec.isFinite()) {
        "PD inputs must be finite"
    }
    require(kp.isFinite() && kd.isFinite() && kp >= 0.0 && kd >= 0.0) {
        "PD gains must be finite and non-negative"
    }
    require(vmax >= 0) { "vmax must be non-negative" }

    val error = targetDeg - angleDeg
    val raw = kp * error - kd * gyroRateDegPerSec
    require(raw.isFinite()) { "PD command must be finite" }
    val limit = vmax.toDouble()
    val bounded = raw.coerceIn(-limit, limit)
    return ControlBase(
        errorDeg = error,
        rawCommand = raw,
        boundedCommand = round(bounded).toInt(),
        saturated = raw < -limit || raw > limit,
    )
}

fun applyMotorSigns(baseCommand: Int, signs: List<Int>): List<Int> =
    signs.map { sign ->
        require(sign == -1 || sign == 1) { "motor sign must be -1 or 1" }
        baseCommand * sign
    }
