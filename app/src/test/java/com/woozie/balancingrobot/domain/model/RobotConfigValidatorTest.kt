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

    @Test
    fun pwmModeUsesThePwmLimitAsTheControlCommandLimit() {
        val config = RobotConfig(motorControlMode = MotorControlMode.PWM, pwmMax = 700)

        assertTrue(RobotConfigValidator.validate(config).isValid)
        assertEquals(700, config.commandLimit)
    }

    @Test
    fun acceptsTenMillisecondRestQualification() {
        assertTrue(RobotConfigValidator.validate(RobotConfig(speedQuietDurationMs = 10)).isValid)
    }

    @Test
    fun rejectsInconsistentOrUnsafeSpeedLoopParameters() {
        val result = RobotConfigValidator.validate(RobotConfig(
            speedTargetCmPerSec = 11.0,
            speedTargetLimitCmPerSec = 10.0,
            speedTargetSlewRateCmPerSec = 0.0,
            speedKevDegPerCmPerSec = -0.1,
            speedLoopRateHz = 101,
            wheelDiameterMm = 0.0,
            driveRatio = 0.0,
        ))

        assertEquals(
            setOf(
                "speedTargetCmPerSec",
                "speedTargetSlewRateCmPerSec",
                "speedKevDegPerCmPerSec",
                "speedLoopRateHz",
                "wheelDiameterMm",
                "driveRatio",
            ),
            result.errors.map { it.field }.toSet(),
        )
    }
}
