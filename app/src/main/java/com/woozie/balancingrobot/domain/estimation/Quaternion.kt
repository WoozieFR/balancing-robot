package com.woozie.balancingrobot.domain.estimation

import com.woozie.balancingrobot.domain.model.Vector3
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Small, allocation-friendly quaternion primitive used by the attitude filter. */
data class Quaternion(
    val w: Double,
    val x: Double,
    val y: Double,
    val z: Double,
) {
    fun normSquared(): Double = w * w + x * x + y * y + z * z

    private fun dot(other: Quaternion): Double =
        w * other.w + x * other.x + y * other.y + z * other.z

    fun normalized(): Quaternion? {
        val squared = normSquared()
        if (!squared.isFinite() || squared <= EPSILON * EPSILON) return null
        val scale = 1.0 / sqrt(squared)
        val result = Quaternion(w * scale, x * scale, y * scale, z * scale)
        return result.takeIf { it.w.isFinite() && it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }
    }

    fun conjugate(): Quaternion = Quaternion(w, -x, -y, -z)

    operator fun times(other: Quaternion): Quaternion = Quaternion(
        w * other.w - x * other.x - y * other.y - z * other.z,
        w * other.x + x * other.w + y * other.z - z * other.y,
        w * other.y - x * other.z + y * other.w + z * other.x,
        w * other.z + x * other.y - y * other.x + z * other.w,
    )

    fun rotate(vector: Vector3): Vector3 {
        val vectorQuaternion = Quaternion(0.0, vector.x, vector.y, vector.z)
        val rotated = this * vectorQuaternion * conjugate()
        return Vector3(rotated.x, rotated.y, rotated.z)
    }

    companion object {
        const val EPSILON = 1e-12
        val IDENTITY = Quaternion(1.0, 0.0, 0.0, 0.0)

        fun fromTo(from: Vector3, to: Vector3): Quaternion? {
            val fromUnit = from.normalized() ?: return null
            val toUnit = to.normalized() ?: return null
            val dot = fromUnit.dot(toUnit).coerceIn(-1.0, 1.0)
            if (dot > 1.0 - 1e-12) return IDENTITY
            // The shortest rotation is not unique for opposite vectors. The
            // estimator rejects this singular initialization/correction case.
            if (dot < -1.0 + 1e-12) return null
            val cross = fromUnit.cross(toUnit)
            val scale = sqrt((1.0 + dot) * 2.0)
            if (!scale.isFinite() || scale <= EPSILON) return null
            return Quaternion(
                w = scale * 0.5,
                x = cross.x / scale,
                y = cross.y / scale,
                z = cross.z / scale,
            ).normalized()
        }

        fun fromAngularVelocity(angularVelocityRadPerSec: Vector3, dtSec: Double): Quaternion? {
            if (!angularVelocityRadPerSec.isFinite() || !dtSec.isFinite() || dtSec <= 0.0) return null
            val delta = angularVelocityRadPerSec * dtSec
            val angle = delta.norm()
            if (!angle.isFinite()) return null
            if (angle <= 1e-9) {
                return Quaternion(1.0, delta.x * 0.5, delta.y * 0.5, delta.z * 0.5).normalized()
            }
            val half = angle * 0.5
            val scale = sin(half) / angle
            return Quaternion(cos(half), delta.x * scale, delta.y * scale, delta.z * scale).normalized()
        }

        fun slerp(first: Quaternion, second: Quaternion, amount: Double): Quaternion? {
            if (!amount.isFinite()) return null
            val a = first.normalized() ?: return null
            var b = second.normalized() ?: return null
            var dot = a.dot(b).coerceIn(-1.0, 1.0)
            if (dot < 0.0) {
                b = Quaternion(-b.w, -b.x, -b.y, -b.z)
                dot = -dot
            }
            val t = amount.coerceIn(0.0, 1.0)
            if (dot > 0.9995) {
                return Quaternion(
                    a.w + t * (b.w - a.w),
                    a.x + t * (b.x - a.x),
                    a.y + t * (b.y - a.y),
                    a.z + t * (b.z - a.z),
                ).normalized()
            }
            val angle = acos(dot)
            val denominator = sin(angle)
            if (!denominator.isFinite() || denominator == 0.0) return null
            val firstWeight = sin((1.0 - t) * angle) / denominator
            val secondWeight = sin(t * angle) / denominator
            return Quaternion(
                first.w * firstWeight + b.w * secondWeight,
                first.x * firstWeight + b.x * secondWeight,
                first.y * firstWeight + b.y * secondWeight,
                first.z * firstWeight + b.z * secondWeight,
            ).normalized()
        }
    }
}

private operator fun Vector3.times(scale: Double): Vector3 = Vector3(x * scale, y * scale, z * scale)
private fun Vector3.dot(other: Vector3): Double = x * other.x + y * other.y + z * other.z
private fun Vector3.cross(other: Vector3): Vector3 = Vector3(
    y * other.z - z * other.y,
    z * other.x - x * other.z,
    x * other.y - y * other.x,
)
private fun Vector3.norm(): Double = sqrt(x * x + y * y + z * z)
private fun Vector3.normalized(): Vector3? {
    val norm = norm()
    if (!norm.isFinite() || norm <= Quaternion.EPSILON) return null
    return Vector3(x / norm, y / norm, z / norm)
}
