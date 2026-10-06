package com.woozie.balancingrobot.domain.gamepad

/** Persistent limits and shaping parameters for a paired gamepad. */
data class GamepadConfig(
    val maxSpeedCmPerSec: Double = 5.0,
    val maxYawDegPerSec: Double = 90.0,
    val deadZone: Double = 0.12,
    val responseExponent: Double = 1.5,
    val precisionScale: Double = 0.35,
    val speedSign: Int = -1,
    val yawSign: Int = 1,
    val heartbeatHz: Int = 50,
    val commandTimeoutMs: Long = 250L,
)

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
    /** Normalized forward/backward command in [-1, 1]. */
    val forwardNormalized: Double,
    val yawTargetDegPerSec: Double,
    val deadmanHeld: Boolean,
    val precisionHeld: Boolean,
)

data class EffectiveDriveSetpoint(
    val speedTargetCmPerSec: Double,
    val yawTargetDegPerSec: Double,
    /** Non-null only when the active source is the gamepad. */
    val forwardNormalized: Double? = null,
    val source: DriveCommandSource,
    val neutralReason: GamepadNeutralReason = GamepadNeutralReason.NONE,
    val ageMs: Double? = null,
    val sequence: Long? = null,
    val deadmanHeld: Boolean = false,
    val precisionHeld: Boolean = false,
)

data class GamepadAxisOutput(
    val normalized: Double,
    val target: Double,
)
