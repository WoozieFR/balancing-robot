package com.woozie.balancingrobot.motor

data class ManualMotorCommand(val boundedValue: Int, val motorValues: List<Int>, val active: Boolean)

object ManualMotorSafety {
    fun command(
        requestedValue: Int,
        vmax: Int,
        motorSigns: List<Int>,
        armed: Boolean,
        held: Boolean,
    ): ManualMotorCommand {
        require(vmax >= 0) { "vmax must be non-negative" }
        require(motorSigns.all { it == -1 || it == 1 }) { "motor signs must be -1 or 1" }
        if (!armed || !held) return ManualMotorCommand(0, List(motorSigns.size) { 0 }, false)
        val bounded = requestedValue.coerceIn(-vmax, vmax)
        return ManualMotorCommand(bounded, motorSigns.map { bounded * it }, true)
    }
}
