package com.woozie.balancingrobot.service

data class ImuTracePoint(
    val timestampNs: Long,
    val accelAngleDeg: Double,
    val estimatedAngleDeg: Double,
    val gyroRateDegPerSec: Double,
)
