package com.woozie.balancingrobot.domain.estimation

import com.woozie.balancingrobot.domain.model.AttitudeFilterMode
import com.woozie.balancingrobot.domain.model.Axis
import com.woozie.balancingrobot.domain.model.Vector3

data class AttitudeEstimate(
    val filterMode: AttitudeFilterMode,
    val accelAngleDeg: Double,
    val thetaDeg: Double,
    val pitchRateDegPerSec: Double,
    val yawRateDegPerSec: Double,
    val dtSec: Double,
)

interface AttitudeEstimator {
    val mode: AttitudeFilterMode
    val initialized: Boolean
    val resetCount: Long

    fun updateAlpha(alpha: Double)

    fun reset()

    fun step(
        accelBodyMps2: Vector3,
        gyroBodyRadPerSec: Vector3,
        dtSec: Double,
        axis: Axis,
        imuSign: Int,
        zeroOffsetDeg: Double,
    ): AttitudeEstimate?
}

class LegacyComplementaryAttitudeEstimator(initialAlpha: Double) : AttitudeEstimator {
    private val delegate = ComplementaryEstimator(initialAlpha)
    private var wasInitialized = false
    private var resets = 0L

    override val mode: AttitudeFilterMode = AttitudeFilterMode.LEGACY_COMPLEMENTARY
    override val initialized: Boolean get() = wasInitialized
    override val resetCount: Long get() = resets

    override fun updateAlpha(alpha: Double) = delegate.updateAlpha(alpha)

    override fun reset() {
        delegate.reset()
        wasInitialized = false
        resets += 1
    }

    override fun step(
        accelBodyMps2: Vector3,
        gyroBodyRadPerSec: Vector3,
        dtSec: Double,
        axis: Axis,
        imuSign: Int,
        zeroOffsetDeg: Double,
    ): AttitudeEstimate? {
        val accelAngle = accelAngleDeg(accelBodyMps2, axis, imuSign, zeroOffsetDeg) ?: return null
        val pitchRate = selectedGyroRateDegPerSec(gyroBodyRadPerSec, axis, imuSign) ?: return null
        val yawRate = gyroRateDegPerSec(gyroBodyRadPerSec.z) ?: return null
        val theta = delegate.step(pitchRate, dtSec, accelAngle) ?: return null
        wasInitialized = true
        return AttitudeEstimate(
            filterMode = mode,
            accelAngleDeg = accelAngle,
            thetaDeg = theta,
            pitchRateDegPerSec = pitchRate,
            yawRateDegPerSec = yawRate,
            dtSec = dtSec,
        )
    }
}

object AttitudeEstimatorFactory {
    fun create(mode: AttitudeFilterMode, alpha: Double): AttitudeEstimator = when (mode) {
        AttitudeFilterMode.LEGACY_COMPLEMENTARY -> LegacyComplementaryAttitudeEstimator(alpha)
        AttitudeFilterMode.QUATERNION_COMPLEMENTARY -> QuaternionAttitudeEstimator(alpha)
    }
}
