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
        val triggerAxis = when {
            device.getMotionRange(MotionEvent.AXIS_RTRIGGER, event.source) != null -> MotionEvent.AXIS_RTRIGGER
            device.getMotionRange(MotionEvent.AXIS_BRAKE, event.source) != null -> MotionEvent.AXIS_BRAKE
            else -> null
        }
        val triggerHeld = triggerAxis?.let { event.getAxisValue(it) >= R2_THRESHOLD } ?: current.turboHeld
        return current.copy(
            deviceId = event.deviceId,
            deviceName = device.name,
            speedAxis = event.getAxisValue(MotionEvent.AXIS_Y),
            yawAxis = event.getAxisValue(rightAxis),
            turboHeld = triggerHeld,
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
                turboHeld = down,
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
