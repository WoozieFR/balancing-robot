package com.woozie.balancingrobot.service

import com.woozie.balancingrobot.domain.gamepad.DriveCommandSource
import com.woozie.balancingrobot.domain.gamepad.GamepadNeutralReason

data class GamepadDiagnosticState(
    val modeEnabled: Boolean = false,
    val deviceId: Int? = null,
    val deviceName: String? = null,
    val connected: Boolean = false,
    val focused: Boolean = false,
    val source: DriveCommandSource = DriveCommandSource.PARAMETERS,
    val neutralReason: GamepadNeutralReason = GamepadNeutralReason.NONE,
    val lastSequence: Long? = null,
    val lastCommandAgeMs: Double? = null,
    val deadmanHeld: Boolean = false,
    val precisionHeld: Boolean = false,
    val rawSpeedAxis: Float = 0f,
    val rawYawAxis: Float = 0f,
    val normalizedSpeed: Double = 0.0,
    val normalizedYaw: Double = 0.0,
    val effectiveSpeedTargetCmPerSec: Double = 0.0,
    val effectiveYawTargetDegPerSec: Double = 0.0,
    val lastEvent: String? = null,
)

