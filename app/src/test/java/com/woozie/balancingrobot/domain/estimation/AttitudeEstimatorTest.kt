package com.woozie.balancingrobot.domain.estimation

import com.woozie.balancingrobot.domain.model.AttitudeFilterMode
import com.woozie.balancingrobot.domain.model.Axis
import com.woozie.balancingrobot.domain.model.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class AttitudeEstimatorTest {
    @Test
    fun legacyEstimatorKeepsTheHistoricalOutputs() {
        val estimator = LegacyComplementaryAttitudeEstimator(1.0)
        val result = estimator.step(
            accelBodyMps2 = Vector3(0.0, sin(Math.toRadians(10.0)), cos(Math.toRadians(10.0))),
            gyroBodyRadPerSec = Vector3(Math.toRadians(2.0), 0.0, 0.0),
            dtSec = 0.01,
            axis = Axis.X,
            imuSign = 1,
            zeroOffsetDeg = 0.0,
        )

        assertNotNull(result)
        assertEquals(10.0, result!!.thetaDeg, 1e-9)
        assertEquals(2.0, result.pitchRateDegPerSec, 1e-9)
        assertEquals(0.0, result.yawRateDegPerSec, 1e-9)
        assertEquals(AttitudeFilterMode.LEGACY_COMPLEMENTARY, result.filterMode)
    }

    @Test
    fun quaternionEstimatorInitializesKnownRollFromGravity() {
        val estimator = QuaternionAttitudeEstimator(0.98)
        val angle = Math.toRadians(25.0)
        val result = estimator.step(
            accelBodyMps2 = Vector3(0.0, sin(angle), cos(angle)),
            gyroBodyRadPerSec = ZERO,
            dtSec = 0.01,
            axis = Axis.X,
            imuSign = 1,
            zeroOffsetDeg = 0.0,
        )

        assertNotNull(result)
        assertEquals(25.0, result!!.thetaDeg, 1e-9)
        assertEquals(AttitudeFilterMode.QUATERNION_COMPLEMENTARY, result.filterMode)
        assertTrue(estimator.initialized)
    }

    @Test
    fun quaternionYawRateIsProjectedOnWorldVerticalWhenPhoneIsTilted() {
        val tilt = Math.toRadians(60.0)
        val half = tilt * 0.5
        val orientation = Quaternion(cos(half), sin(half), 0.0, 0.0)
        val worldUp = Vector3(0.0, 0.0, 1.0)
        val accelBody = orientation.conjugate().rotate(worldUp)
        val worldYawRate = Math.toRadians(40.0)
        val gyroBody = orientation.conjugate().rotate(Vector3(0.0, 0.0, worldYawRate))
        val estimator = QuaternionAttitudeEstimator(1.0)

        val first = estimator.step(accelBody, ZERO, 0.01, Axis.X, 1, 0.0)
        val second = estimator.step(accelBody, gyroBody, 0.01, Axis.X, 1, 0.0)

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(40.0, second!!.yawRateDegPerSec, 1e-6)
    }

    @Test
    fun quaternionRejectsInvalidSamplesAndResetRequiresReinitialization() {
        val estimator = QuaternionAttitudeEstimator(0.98)
        assertNull(estimator.step(ZERO, ZERO, 0.01, Axis.X, 1, 0.0))
        val valid = estimator.step(Vector3(0.0, 0.0, 1.0), ZERO, 0.01, Axis.X, 1, 0.0)
        assertNotNull(valid)
        estimator.reset()
        assertTrue(!estimator.initialized)
    }

    @Test
    fun quaternionAlphaCanChangeWithoutResettingState() {
        val estimator = QuaternionAttitudeEstimator(1.0)
        estimator.step(Vector3(0.0, 0.0, 1.0), ZERO, 0.01, Axis.X, 1, 0.0)
        estimator.updateAlpha(0.0)

        val result = estimator.step(
            Vector3(0.0, sin(Math.toRadians(10.0)), cos(Math.toRadians(10.0))),
            ZERO,
            0.01,
            Axis.X,
            1,
            0.0,
        )

        assertNotNull(result)
        assertEquals(10.0, result!!.thetaDeg, 1e-6)
        assertEquals(0L, estimator.resetCount)
    }
}

private val ZERO = Vector3(0.0, 0.0, 0.0)
