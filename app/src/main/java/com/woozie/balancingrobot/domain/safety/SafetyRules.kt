package com.woozie.balancingrobot.domain.safety

import com.woozie.balancingrobot.domain.model.FaultCode
import kotlin.math.abs

object SafetyRules {
    fun isTimestampStrictlyIncreasing(previousNs: Long?, currentNs: Long): Boolean =
        currentNs > 0L && (previousNs == null || currentNs > previousNs)

    fun isGyroFresh(nowNs: Long, lastGyroTimestampNs: Long?, timeoutMs: Long): Boolean {
        if (nowNs <= 0L || lastGyroTimestampNs == null || lastGyroTimestampNs <= 0L) return false
        if (timeoutMs < 0L || nowNs < lastGyroTimestampNs) return false
        return nowNs - lastGyroTimestampNs <= timeoutMs * 1_000_000L
    }

    fun isFallAngleExceeded(angleDeg: Double, thresholdDeg: Double): Boolean =
        angleDeg.isFinite() && thresholdDeg.isFinite() && thresholdDeg >= 0.0 && abs(angleDeg) >= thresholdDeg

    fun isFallDurationExceeded(fallStartedNs: Long?, nowNs: Long, durationMs: Long): Boolean {
        if (fallStartedNs == null || fallStartedNs <= 0L || nowNs < fallStartedNs || durationMs < 0L) return false
        return nowNs - fallStartedNs >= durationMs * 1_000_000L
    }

    fun classifyImu(nowNs: Long, lastGyroTimestampNs: Long?, timeoutMs: Long): FaultCode? =
        if (isGyroFresh(nowNs, lastGyroTimestampNs, timeoutMs)) null else FaultCode.IMU_STALE

    fun classifyAngle(angleDeg: Double, thresholdDeg: Double): FaultCode? =
        if (isFallAngleExceeded(angleDeg, thresholdDeg)) FaultCode.FALL_ANGLE else null
}
