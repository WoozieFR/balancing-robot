package com.woozie.balancingrobot.gamepad

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

object AndroidGamepadMapper {
    fun isGamepadEvent(event: MotionEvent): Boolean =
        event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD

    fun isGamepadEvent(event: KeyEvent): Boolean =
        event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

    fun updateMotion(current: GamepadInputState, event: MotionEvent): GamepadInputState {
        val device = event.device ?: return current
        if (!isGamepadEvent(event)) return current
        val rightAxis = if (device.getMotionRange(MotionEvent.AXIS_Z, event.source) != null) {
            MotionEvent.AXIS_Z
        } else {
            MotionEvent.AXIS_RX
        }
        val rTrigger = device.getMotionRange(MotionEvent.AXIS_RTRIGGER, event.source)
            ?.let { event.getAxisValue(MotionEvent.AXIS_RTRIGGER) }
        val gas = device.getMotionRange(MotionEvent.AXIS_GAS, event.source)
            ?.let { event.getAxisValue(MotionEvent.AXIS_GAS) }
        val triggerHeld = (rTrigger ?: 0f) >= R2_THRESHOLD || (gas ?: 0f) >= R2_THRESHOLD
        return current.copy(
            deviceId = event.deviceId,
            deviceName = device.name,
            speedAxis = event.getAxisValue(MotionEvent.AXIS_Y),
            yawAxis = event.getAxisValue(rightAxis),
            turboAxisHeld = triggerHeld,
            available = true,
        )
    }

    fun updateKey(current: GamepadInputState, event: KeyEvent, down: Boolean): GamepadInputState {
        if (!isGamepadEvent(event)) return current
        val device = event.device
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_R1 -> current.copy(
                deviceId = event.deviceId,
                deviceName = device?.name,
                deadmanHeld = down,
                available = true,
            )
            KeyEvent.KEYCODE_BUTTON_L1 -> current.copy(
                deviceId = event.deviceId,
                deviceName = device?.name,
                precisionHeld = down,
                available = true,
            )
            KeyEvent.KEYCODE_BUTTON_R2 -> current.copy(
                deviceId = event.deviceId,
                deviceName = device?.name,
                turboKeyHeld = down,
                available = true,
            )
            else -> current.copy(
                deviceId = event.deviceId,
                deviceName = device?.name,
                available = true,
            )
        }
    }

    fun isEmergencyDisarm(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_BUTTON_B && event.action == KeyEvent.ACTION_DOWN

    fun isBalanceArm(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_BUTTON_X && event.action == KeyEvent.ACTION_DOWN

    private const val R2_THRESHOLD = 0.5f
}
