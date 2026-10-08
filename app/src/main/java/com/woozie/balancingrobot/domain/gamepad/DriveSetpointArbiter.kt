package com.woozie.balancingrobot.domain.gamepad

/** Thread-safe arbiter between persistent parameters and ephemeral gamepad input. */
class DriveSetpointArbiter(
    private val timeoutMs: Long = 250L,
) {
    private var enabled = false
    private var deviceId: Int? = null
    private var available = false
    private var focused = false
    private var takeoverLatched = false
    private var lastCommand: GamepadDriveCommand? = null

    @Synchronized
    fun enable(deviceId: Int): Boolean {
        if (deviceId < 0) return false
        enabled = true
        this.deviceId = deviceId
        available = true
        focused = true
        takeoverLatched = false
        lastCommand = null
        return true
    }

    @Synchronized
    fun disable() {
        enabled = false
        takeoverLatched = true
        available = false
        focused = false
        lastCommand = null
    }

    @Synchronized
    fun acknowledgeParameterTakeover() {
        if (!enabled) takeoverLatched = false
    }

    @Synchronized
    fun setAvailable(value: Boolean) {
        available = value
        if (!value) lastCommand = null
    }

    @Synchronized
    fun setFocused(value: Boolean) {
        focused = value
        if (!value) lastCommand = null
    }

    @Synchronized
    fun submit(command: GamepadDriveCommand): Boolean {
        if (!enabled || command.deviceId != deviceId || command.sequence < 0) return false
        val previous = lastCommand
        if (previous != null && command.sequence <= previous.sequence) return false
        available = true
        focused = true
        lastCommand = command
        return true
    }

    @Synchronized
    fun resolve(nowNs: Long, parameters: ParameterDriveSetpoint): EffectiveDriveSetpoint {
        if (!enabled) {
            return if (takeoverLatched) {
                EffectiveDriveSetpoint(0.0, 0.0, DriveCommandSource.NEUTRAL, GamepadNeutralReason.DISABLED)
            } else {
                EffectiveDriveSetpoint(
                    parameters.speedTargetCmPerSec,
                    parameters.yawTargetDegPerSec,
                    DriveCommandSource.PARAMETERS,
                )
            }
        }
        if (!available) return neutral(GamepadNeutralReason.DISCONNECTED)
        if (!focused) return neutral(GamepadNeutralReason.FOCUS_LOST)
        val command = lastCommand ?: return neutral(GamepadNeutralReason.TIMEOUT)
        val ageNs = (nowNs - command.timestampNs).coerceAtLeast(0L)
        val ageMs = ageNs / 1_000_000.0
        if (ageNs > timeoutMs * 1_000_000L) return neutral(GamepadNeutralReason.TIMEOUT, ageMs)
        if (!command.deadmanHeld) return neutral(GamepadNeutralReason.DEADMAN_RELEASED, ageMs, command)
        return EffectiveDriveSetpoint(
            command.speedTargetCmPerSec,
            command.yawTargetDegPerSec,
            DriveCommandSource.GAMEPAD,
            ageMs = ageMs,
            sequence = command.sequence,
            deadmanHeld = true,
            precisionHeld = command.precisionHeld,
            turboHeld = command.turboHeld,
        )
    }

    @Synchronized
    fun reset() {
        enabled = false
        deviceId = null
        available = false
        focused = false
        takeoverLatched = false
        lastCommand = null
    }

    private fun neutral(
        reason: GamepadNeutralReason,
        ageMs: Double? = null,
        command: GamepadDriveCommand? = null,
    ) = EffectiveDriveSetpoint(
        speedTargetCmPerSec = 0.0,
        yawTargetDegPerSec = 0.0,
        source = DriveCommandSource.NEUTRAL,
        neutralReason = reason,
        ageMs = ageMs,
        sequence = command?.sequence,
        deadmanHeld = command?.deadmanHeld == true,
        precisionHeld = command?.precisionHeld == true,
    )
}
