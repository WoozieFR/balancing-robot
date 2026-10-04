package com.woozie.balancingrobot.service

data class BalanceDiagnosticState(
    val lastErrorDeg: Double? = null,
    val lastRawCommand: Double? = null,
    val lastCommand: Int = 0,
    val saturated: Boolean = false,
    val yawTargetDegPerSec: Double = 0.0,
    val yawRateDegPerSec: Double = 0.0,
    val yawErrorDegPerSec: Double = 0.0,
    val turnCommand: Int = 0,
    val motorCommand0: Int = 0,
    val motorCommand1: Int = 0,
    val controlLatencyMs: Double? = null,
    val faultMessage: String? = null,
)
