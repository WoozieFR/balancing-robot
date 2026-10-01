package com.woozie.balancingrobot.motor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualMotorSafetyTest {
    @Test
    fun deadmanOrDisarmedAlwaysProducesZero() {
        val command = ManualMotorSafety.command(500, 600, listOf(1, -1), armed = true, held = false)

        assertEquals(listOf(0, 0), command.motorValues)
        assertFalse(command.active)
    }

    @Test
    fun armedCommandIsBoundedAndSigned() {
        val command = ManualMotorSafety.command(900, 600, listOf(1, -1), armed = true, held = true)

        assertEquals(600, command.boundedValue)
        assertEquals(listOf(600, -600), command.motorValues)
        assertTrue(command.active)
    }
}
