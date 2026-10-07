package com.woozie.balancingrobot.domain.control

import kotlin.math.round

data class YawControlBase(
    val active: Boolean,
    val errorDegPerSec: Double,
    val rawCommand: Double,
    val boundedCommand: Int,
    val saturated: Boolean,
)

/** Proportional yaw-rate controller. A zero target is an explicit no-turn request. */
fun yawTurnStep(
    targetDegPerSec: Double,
    measuredDegPerSec: Double,
    kpCommandPerDegPerSec: Double,
    commandLimit: Int,
): YawControlBase {
    require(targetDegPerSec.isFinite() && measuredDegPerSec.isFinite()) {
        "yaw inputs must be finite"
    }
    require(kpCommandPerDegPerSec.isFinite() && kpCommandPerDegPerSec >= 0.0) {
        "yaw gain must be finite and non-negative"
    }
    require(commandLimit >= 0) { "commandLimit must be non-negative" }

    if (targetDegPerSec == 0.0) {
        return YawControlBase(
            active = false,
            errorDegPerSec = 0.0,
            rawCommand = 0.0,
            boundedCommand = 0,
            saturated = false,
        )
    }

    val error = targetDegPerSec - measuredDegPerSec
    val raw = kpCommandPerDegPerSec * error
    require(raw.isFinite()) { "yaw command must be finite" }
    val limit = commandLimit.toDouble()
    val bounded = raw.coerceIn(-limit, limit)
    return YawControlBase(
        active = true,
        errorDegPerSec = error,
        rawCommand = raw,
        boundedCommand = round(bounded).toInt(),
        saturated = raw < -limit || raw > limit,
    )
}

/** Mixes the balance command with a differential turn command before motor signs. */
fun mixDifferential(
    balanceCommand: Int,
    turnCommand: Int,
    commandLimit: Int,
): DifferentialMix {
    require(commandLimit >= 0) { "commandLimit must be non-negative" }
    val yawHeadroom = (commandLimit - kotlin.math.abs(balanceCommand)).coerceAtLeast(0)
    val appliedTurn = turnCommand.coerceIn(-yawHeadroom, yawHeadroom)
    val leftRaw = balanceCommand.toLong() + appliedTurn.toLong()
    val rightRaw = balanceCommand.toLong() - appliedTurn.toLong()
    val limit = commandLimit.toLong()
    val left = leftRaw.coerceIn(-limit, limit).toInt()
    val right = rightRaw.coerceIn(-limit, limit).toInt()
    return DifferentialMix(
        left = left,
        right = right,
        saturated = appliedTurn != turnCommand || leftRaw !in -limit..limit || rightRaw !in -limit..limit,
    )
}

data class DifferentialMix(
    val left: Int,
    val right: Int,
    val saturated: Boolean,
)

fun applyMotorSigns(commands: List<Int>, signs: List<Int>): List<Int> {
    require(commands.size == signs.size) { "one sign is required for each motor command" }
    return commands.mapIndexed { index, command ->
        val sign = signs[index]
        require(sign == -1 || sign == 1) { "motor sign must be -1 or 1" }
        command * sign
    }
}
