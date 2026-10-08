package com.woozie.balancingrobot.domain.gamepad

import kotlin.math.abs
import kotlin.math.pow

/** Maps a raw Android stick value to a bounded normalized value. */
fun transformGamepadAxis(
    raw: Float,
    configuredDeadZone: Double,
    hardwareFlat: Float = 0f,
    responseExponent: Double = 1.0,
    sign: Int = 1,
): Double {
    require(configuredDeadZone in 0.0..0.95) { "dead zone must be in [0, 0.95]" }
    require(responseExponent >= 1.0 && responseExponent.isFinite()) { "response exponent must be >= 1" }
    require(sign == -1 || sign == 1) { "axis sign must be -1 or 1" }
    if (!raw.isFinite()) return 0.0
    val deadZone = maxOf(configuredDeadZone, hardwareFlat.toDouble().coerceIn(0.0, 0.95))
    val magnitude = abs(raw.toDouble()).coerceIn(0.0, 1.0)
    if (magnitude <= deadZone) return 0.0
    val remapped = ((magnitude - deadZone) / (1.0 - deadZone)).coerceIn(0.0, 1.0)
    return sign * raw.toDouble().coerceIn(-1.0, 1.0).let { signed ->
        kotlin.math.sign(signed) * remapped.pow(responseExponent)
    }
}

fun gamepadSetpoints(
    speedAxis: Float,
    yawAxis: Float,
    config: GamepadConfig,
    speedHardwareFlat: Float = 0f,
    yawHardwareFlat: Float = 0f,
    precisionHeld: Boolean = false,
    turboHeld: Boolean = false,
): Pair<GamepadAxisOutput, GamepadAxisOutput> {
    val precision = if (precisionHeld) config.precisionScale else 1.0
    val speed = transformGamepadAxis(
        raw = speedAxis,
        configuredDeadZone = config.deadZone,
        hardwareFlat = speedHardwareFlat,
        responseExponent = config.responseExponent,
        sign = config.speedSign,
    )
    val yaw = transformGamepadAxis(
        raw = yawAxis,
        configuredDeadZone = config.deadZone,
        hardwareFlat = yawHardwareFlat,
        responseExponent = config.responseExponent,
        sign = config.yawSign,
    )
    val selectedSpeedLimit = if (turboHeld) config.turboMaxSpeedCmPerSec else config.maxSpeedCmPerSec
    return GamepadAxisOutput(speed, speed * selectedSpeedLimit * precision) to
        GamepadAxisOutput(yaw, yaw * config.maxYawDegPerSec * precision)
}
