package com.woozie.balancingrobot.domain.estimation

import com.woozie.balancingrobot.domain.model.Axis
import com.woozie.balancingrobot.domain.model.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI

class ComplementaryEstimatorTest {
    @Test
    fun referenceAngleUsesTheAxisXFormulaAndOffset() {
        val angle = accelAngleDeg(Vector3(0.0, 1.0, 0.0), Axis.X, -1, 2.0)

        assertEquals(-92.0, angle!!, 1e-9)
    }

    @Test
    fun singularOrNonFiniteAccelerationIsRejected() {
        assertNull(accelAngleDeg(Vector3(0.0, 0.0, 0.0), Axis.X, 1, 0.0))
        assertNull(accelAngleDeg(Vector3(Double.NaN, 0.0, 1.0), Axis.X, 1, 0.0))
    }

    @Test
    fun firstStepInitializesThenBlendsGyroAndAcceleration() {
        val first = complementaryStep(null, 0.0, 0.01, 10.0, 0.98)
        val second = complementaryStep(first, 10.0, 0.1, 10.0, 0.98)

        assertEquals(10.0, first!!, 1e-9)
        assertEquals(10.98, second!!, 1e-9)
    }

    @Test
    fun invalidTimeOrAlphaReturnsNull() {
        assertNull(complementaryStep(null, 0.0, 0.0, 10.0, 0.98))
        assertNull(complementaryStep(null, 0.0, 0.01, 10.0, 1.1))
    }

    @Test
    fun convertsGyroscopeRadiansToDegrees() {
        assertEquals(180.0, gyroRateDegPerSec(PI)!!, 1e-9)
        assertNull(gyroRateDegPerSec(Double.POSITIVE_INFINITY))
    }

    @Test
    fun selectsConfiguredGyroAxisAndSign() {
        val rate = selectedGyroRateDegPerSec(
            Vector3(PI, PI / 2.0, -PI),
            Axis.Y,
            -1,
        )

        assertEquals(-90.0, rate!!, 1e-9)
    }
}
