package com.woozie.balancingrobot.domain.gamepad

/** Persistent limits and shaping parameters for a paired gamepad. */
data class GamepadConfig(
    val maxSpeedCmPerSec: Double = 5.0,
    /** Speed limit used while the DualShock R2 modifier is held. */
    val turboMaxSpeedCmPerSec: Double = 25.0,
    val maxYawDegPerSec: Double = 90.0,
    val deadZone: Double = 0.12,
    val responseExponent: Double = 1.5,
    val precisionScale: Double = 0.35,
    val speedSign: Int = -1,
    val yawSign: Int = 1,
    val heartbeatHz: Int = 50,
    val commandTimeoutMs: Long = 250L,
) {
    companion object {
        const val MAX_TURBO_SPEED_CM_PER_SEC = 100.0
    }
}

enum class DriveCommandSource { PARAMETERS, GAMEPAD, NEUTRAL }

enum class GamepadNeutralReason {
    NONE,
    DISABLED,
    DEADMAN_RELEASED,
    TIMEOUT,
    DISCONNECTED,
    FOCUS_LOST,
    NO_DEVICE,
    NOT_ARMED,
}

data class ParameterDriveSetpoint(
    val speedTargetCmPerSec: Double,
    val yawTargetDegPerSec: Double,
)

data class GamepadDriveCommand(
    val deviceId: Int,
    val sequence: Long,
    val timestampNs: Long,
    val speedTargetCmPerSec: Double,
    val yawTargetDegPerSec: Double,
    val deadmanHeld: Boolean,
    val precisionHeld: Boolean,
    val turboHeld: Boolean = false,
)

data class EffectiveDriveSetpoint(
    val speedTargetCmPerSec: Double,
    val yawTargetDegPerSec: Double,
    val source: DriveCommandSource,
    val neutralReason: GamepadNeutralReason = GamepadNeutralReason.NONE,
    val ageMs: Double? = null,
    val sequence: Long? = null,
    val deadmanHeld: Boolean = false,
    val precisionHeld: Boolean = false,
    val turboHeld: Boolean = false,
)

data class GamepadAxisOutput(
    val normalized: Double,
    val target: Double,
)
