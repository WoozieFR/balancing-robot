package com.woozie.balancingrobot.service

data class BalanceDiagnosticState(
    val lastErrorDeg: Double? = null,
    val lastRawCommand: Double? = null,
    val lastCommand: Int = 0,
    val saturated: Boolean = false,
    val controlLatencyMs: Double? = null,
    val faultMessage: String? = null,
)
