package com.woozie.balancingrobot.domain.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YawControllerTest {
    @Test
    fun zeroTargetDisablesTurnEvenWhenTheRobotIsRotating() {
        val output = yawTurnStep(0.0, 25.0, 2.0, 1000)

        assertFalse(output.active)
        assertEquals(0, output.boundedCommand)
        assertEquals(0.0, output.errorDegPerSec, 1e-9)
    }

    @Test
    fun proportionalYawCommandUsesGyroZError() {
        val output = yawTurnStep(60.0, 10.0, 2.0, 1000)

        assertTrue(output.active)
        assertEquals(50.0, output.errorDegPerSec, 1e-9)
        assertEquals(100, output.boundedCommand)
    }

    @Test
    fun differentialMixKeepsBalanceAndAddsOppositeTurn() {
        val moving = mixDifferential(40, 10, 1000)
        assertEquals(50, moving.left)
        assertEquals(30, moving.right)

        val inPlace = mixDifferential(0, 10, 1000)
        assertEquals(10, inPlace.left)
        assertEquals(-10, inPlace.right)
    }

    @Test
    fun differentialMixClipsEachWheelToTheConfiguredLimit() {
        val mix = mixDifferential(90, 30, 100)

        assertEquals(100, mix.left)
        assertEquals(60, mix.right)
        assertTrue(mix.saturated)
    }
}
