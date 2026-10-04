package com.woozie.balancingrobot.gamepad

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice

data class GamepadDeviceInfo(
    val id: Int,
    val name: String,
    val hasJoystick: Boolean,
)

class AndroidGamepadDetector(context: Context) {
    private val inputManager = context.getSystemService(InputManager::class.java)

    fun devices(): List<GamepadDeviceInfo> = InputDevice.getDeviceIds().toList().mapNotNull { id ->
        val device = InputDevice.getDevice(id) ?: return@mapNotNull null
        val hasJoystick = device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        val hasGamepad = device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        if (!hasJoystick && !hasGamepad) return@mapNotNull null
        GamepadDeviceInfo(id, device.name, hasJoystick)
    }.sortedBy { it.id }

    fun register(listener: InputManager.InputDeviceListener) {
        inputManager?.registerInputDeviceListener(listener, null)
    }

    fun unregister(listener: InputManager.InputDeviceListener) {
        inputManager?.unregisterInputDeviceListener(listener)
    }
}
