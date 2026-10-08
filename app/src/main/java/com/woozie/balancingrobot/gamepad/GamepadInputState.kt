package com.woozie.balancingrobot.gamepad

data class GamepadInputState(
    val deviceId: Int? = null,
    val deviceName: String? = null,
    val speedAxis: Float = 0f,
    val yawAxis: Float = 0f,
    val deadmanHeld: Boolean = false,
    val precisionHeld: Boolean = false,
    val turboKeyHeld: Boolean = false,
    val turboAxisHeld: Boolean = false,
    val focused: Boolean = false,
    val available: Boolean = false,
) {
    val turboHeld: Boolean
        get() = turboKeyHeld || turboAxisHeld
}
