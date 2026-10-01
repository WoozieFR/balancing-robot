package com.woozie.balancingrobot.runtime

import com.woozie.balancingrobot.domain.model.RobotConfig
import com.woozie.balancingrobot.domain.model.MotorControlMode
import com.woozie.balancingrobot.domain.model.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sin
import kotlin.math.cos

class SimulatedControlRuntimeTest {
    @Test
    fun pwmModeBoundsThePdOutputToTheConfiguredDuty() {
        val runtime = SimulatedControlRuntime(
            RobotConfig(
                targetDeg = 20.0,
                kp = 100.0,
                alpha = 1.0,
                motorControlMode = MotorControlMode.PWM,
                pwmMax = 250,
            ),
        )

        val tenDegrees = Math.toRadians(10.0)
        val step = runtime.step(Vector3(0.0, sin(tenDegrees), cos(tenDegrees)), 0.0, 0.01)

        assertEquals(250, step.control!!.boundedCommand)
    }

    @Test
    fun replaysAnImuStepWithoutTouchingAndroidOrUsb() {
        val runtime = SimulatedControlRuntime(
            RobotConfig(targetDeg = 0.0, kp = 10.0, kd = 0.5, motorSigns = listOf(1, -1)),
        )

        val step = runtime.step(Vector3(0.0, 0.0, 1.0), gyroRateDegPerSec = 0.0, dtSec = 0.01)

        assertNotNull(step.estimate)
        assertEquals(0.0, step.estimate!!.angleDeg, 1e-9)
        assertEquals(0, step.control!!.boundedCommand)
        assertEquals(listOf(0, 0), step.control.motorCommands)
        assertNull(step.fault)
    }

    @Test
    fun singularImuSampleStopsTheChainWithAnEstimateFault() {
        val runtime = SimulatedControlRuntime(RobotConfig(kp = 10.0))

        val step = runtime.step(Vector3(0.0, 0.0, 0.0), 0.0, 0.01)

        assertNull(step.estimate)
        assertNull(step.control)
        assertEquals(com.woozie.balancingrobot.domain.model.FaultCode.ESTIMATE_INVALID, step.fault)
    }

    @Test
    fun liveTuningUpdatesGainsAndAlphaWithoutResettingTheEstimate() {
        val runtime = SimulatedControlRuntime(RobotConfig(alpha = 0.98, kp = 0.0))
        val tenDegrees = Math.toRadians(10.0)

        runtime.step(Vector3(0.0, sin(tenDegrees), cos(tenDegrees)), 0.0, 0.01)
        runtime.updateConfig(RobotConfig(alpha = 1.0, kp = 10.0))

        val step = runtime.step(Vector3(0.0, 0.0, 1.0), 0.0, 0.01)

        assertEquals(10.0, step.estimate!!.angleDeg, 1e-6)
        assertEquals(-100, step.control!!.boundedCommand)
    }
}
