package com.woozie.balancingrobot.domain.gamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadControlTest {
    @Test
    fun deadZoneIsRemappedAndExponentShapesResponse() {
        assertEquals(0.0, transformGamepadAxis(0.1f, 0.12), 1e-9)
        val output = transformGamepadAxis(0.56f, 0.12, responseExponent = 1.5)
        assertTrue(output > 0.0)
        assertTrue(output < 0.56)
    }

    @Test
    fun signsAndPrecisionScaleAreAppliedToTargets() {
        val config = GamepadConfig(maxSpeedCmPerSec = 5.0, maxYawDegPerSec = 90.0, speedSign = -1)
        val (speed, yaw) = gamepadSetpoints(1f, -1f, config, precisionHeld = true)
        assertEquals(-1.0, speed.normalized, 1e-9)
        assertEquals(-0.35, speed.target, 1e-9)
        assertEquals(-0.35, yaw.target / config.maxYawDegPerSec, 1e-9)
    }

    @Test
    fun arbiterKeepsDeadmanAndTimesOut() {
        val arbiter = DriveSetpointArbiter(timeoutMs = 250)
        arbiter.enable(7)
        arbiter.submit(GamepadDriveCommand(7, 1, 1_000_000_000L, 0.4, 10.0, true, false))
        assertEquals(DriveCommandSource.GAMEPAD, arbiter.resolve(1_100_000_000L, ParameterDriveSetpoint(0.0, 0.0)).source)
        assertEquals(DriveCommandSource.NEUTRAL, arbiter.resolve(1_300_000_000L, ParameterDriveSetpoint(0.0, 0.0)).source)
        assertEquals(GamepadNeutralReason.TIMEOUT, arbiter.resolve(1_300_000_000L, ParameterDriveSetpoint(0.0, 0.0)).neutralReason)
    }

    @Test
    fun disablingLatchesNeutralUntilExplicitParameterTakeover() {
        val arbiter = DriveSetpointArbiter()
        arbiter.enable(1)
        arbiter.disable()
        assertEquals(DriveCommandSource.NEUTRAL, arbiter.resolve(1L, ParameterDriveSetpoint(4.0, 5.0)).source)
        arbiter.acknowledgeParameterTakeover()
        val output = arbiter.resolve(1L, ParameterDriveSetpoint(4.0, 5.0))
        assertEquals(DriveCommandSource.PARAMETERS, output.source)
        assertEquals(4.0, output.speedTargetCmPerSec, 0.0)
    }
}
