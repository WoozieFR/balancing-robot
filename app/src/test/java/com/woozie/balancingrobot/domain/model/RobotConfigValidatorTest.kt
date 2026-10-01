package com.woozie.balancingrobot.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RobotConfigValidatorTest {
    @Test
    fun defaultsAreValid() {
        val result = RobotConfigValidator.validate(RobotConfig())

        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun invalidValuesAreReportedWithoutCorrection() {
        val config = RobotConfig(alpha = 1.5, motorIds = listOf(6, 6), webPort = 80)

        val result = RobotConfigValidator.validate(config)

        assertEquals(setOf("alpha", "motorIds", "webPort"), result.errors.map { it.field }.toSet())
        assertEquals(1.5, config.alpha, 0.0)
        assertEquals(80, config.webPort)
    }

    @Test
    fun motorSignsMustMatchTheTwoMotorIds() {
        val result = RobotConfigValidator.validate(RobotConfig(motorSigns = listOf(1)))

        assertTrue(result.errors.any { it.field == "motorSigns" })
    }
}
