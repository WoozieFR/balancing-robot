package com.woozie.balancingrobot.domain.estimation

import com.woozie.balancingrobot.domain.model.AttitudeFilterMode
import com.woozie.balancingrobot.domain.model.Axis
import com.woozie.balancingrobot.domain.model.Vector3
import kotlin.math.atan2
import kotlin.math.sqrt

class QuaternionAttitudeEstimator(initialAlpha: Double) : AttitudeEstimator {
    private var alpha = validateAlpha(initialAlpha)
    private var orientationWb: Quaternion? = null
    private var initialYawRad = 0.0
    private var resets = 0L

    override val mode: AttitudeFilterMode = AttitudeFilterMode.QUATERNION_COMPLEMENTARY
    override val initialized: Boolean get() = orientationWb != null
    override val resetCount: Long get() = resets

    override fun updateAlpha(alpha: Double) {
        this.alpha = validateAlpha(alpha)
    }

    override fun reset() {
        orientationWb = null
        initialYawRad = 0.0
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
        if (!accelBodyMps2.isFinite() || !gyroBodyRadPerSec.isFinite() ||
            !dtSec.isFinite() || dtSec <= 0.0 || (imuSign != -1 && imuSign != 1) ||
            !zeroOffsetDeg.isFinite()
        ) return null

        val accelUnit = accelBodyMps2.normalizedOrNull() ?: return null
        val upWorld = Vector3(0.0, 0.0, 1.0)
        val previous = orientationWb
        val predicted = if (previous == null) {
            Quaternion.fromTo(accelUnit, upWorld)
        } else {
            val delta = Quaternion.fromAngularVelocity(gyroBodyRadPerSec, dtSec) ?: return null
            (previous * delta).normalized()
        } ?: return null

        val accelInWorld = predicted.rotate(accelUnit).normalizedOrNull() ?: return null
        val correction = Quaternion.fromTo(accelInWorld, upWorld) ?: return null
        val weightedCorrection = Quaternion.slerp(Quaternion.IDENTITY, correction, 1.0 - alpha) ?: return null
        val corrected = (weightedCorrection * predicted).normalized() ?: return null
        orientationWb = corrected
        if (previous == null) initialYawRad = yawRad(corrected)

        val upBody = corrected.conjugate().rotate(upWorld)
        val rawThetaRad = when (axis) {
            Axis.X -> atan2(upBody.y, upBody.z)
            Axis.Y -> atan2(-upBody.x, sqrt(upBody.y * upBody.y + upBody.z * upBody.z))
            Axis.Z -> wrapRadians(yawRad(corrected) - initialYawRad)
        }
        val pitchRate = selectedGyroRateDegPerSec(gyroBodyRadPerSec, axis, imuSign) ?: return null
        val worldGyro = corrected.rotate(gyroBodyRadPerSec)
        val yawRate = gyroRateDegPerSec(worldGyro.z) ?: return null
        val accelAngle = accelAngleDeg(accelBodyMps2, axis, imuSign, zeroOffsetDeg) ?: return null
        return AttitudeEstimate(
            filterMode = mode,
            accelAngleDeg = accelAngle,
            thetaDeg = imuSign * Math.toDegrees(rawThetaRad) - zeroOffsetDeg,
            pitchRateDegPerSec = pitchRate,
            yawRateDegPerSec = yawRate,
            dtSec = dtSec,
        )
    }

    private fun yawRad(orientation: Quaternion): Double {
        val forwardWorld = orientation.rotate(Vector3(1.0, 0.0, 0.0))
        return atan2(forwardWorld.y, forwardWorld.x)
    }

    private fun validateAlpha(value: Double): Double {
        require(value.isFinite() && value in 0.0..1.0) { "alpha must be finite and in [0, 1]" }
        return value
    }
}

private fun Vector3.normalizedOrNull(): Vector3? {
    val norm = sqrt(x * x + y * y + z * z)
    if (!norm.isFinite() || norm <= Quaternion.EPSILON) return null
    return Vector3(x / norm, y / norm, z / norm)
}

private fun wrapRadians(value: Double): Double {
    var result = value
    val twoPi = Math.PI * 2.0
    while (result > Math.PI) result -= twoPi
    while (result < -Math.PI) result += twoPi
    return result
}
