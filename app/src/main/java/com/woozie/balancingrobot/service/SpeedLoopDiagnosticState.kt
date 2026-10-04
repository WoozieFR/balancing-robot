package com.woozie.balancingrobot.service

data class SpeedLoopDiagnosticState(
    val enabled: Boolean = true,
    val stale: Boolean = true,
    val feedbackSequence: Long? = null,
    val feedbackAgeMs: Double? = null,
    val feedbackRateHz: Double = 0.0,
    val loopRateHz: Double = 0.0,
    val leftRawStepsPerSec: Int? = null,
    val rightRawStepsPerSec: Int? = null,
    val leftCmPerSec: Double? = null,
    val rightCmPerSec: Double? = null,
    val meanCmPerSec: Double? = null,
    val filteredCmPerSec: Double? = null,
    val targetCmPerSec: Double = 0.0,
    val errorCmPerSec: Double? = null,
    val correctionDeg: Double = 0.0,
    val integralCorrectionDeg: Double = 0.0,
    val trimDeg: Double = 0.0,
    val effectiveTargetDeg: Double = 0.0,
    val saturated: Boolean = false,
    val slewLimited: Boolean = false,
)
