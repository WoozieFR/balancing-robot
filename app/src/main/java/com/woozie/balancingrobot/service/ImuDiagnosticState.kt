package com.woozie.balancingrobot.service

import com.woozie.balancingrobot.domain.model.AttitudeFilterMode

data class ImuDiagnosticState(
    val requestedRateHz: Int = 200,
    val attitudeFilterMode: AttitudeFilterMode = AttitudeFilterMode.LEGACY_COMPLEMENTARY,
    val activeAttitudeFilterMode: AttitudeFilterMode = AttitudeFilterMode.LEGACY_COMPLEMENTARY,
    val attitudeEstimatorState: String = "INITIALIZING",
    val attitudeEstimatorResetCount: Long = 0,
    val accelerometerAvailable: Boolean = false,
    val gyroscopeAvailable: Boolean = false,
    val accelerometerX: Double? = null,
    val accelerometerY: Double? = null,
    val accelerometerZ: Double? = null,
    val gyroXDegPerSec: Double? = null,
    val gyroYDegPerSec: Double? = null,
    val gyroZDegPerSec: Double? = null,
    val accelAngleDeg: Double? = null,
    val estimatedAngleDeg: Double? = null,
    val dtMs: Double? = null,
    val accelRateHz: Double = 0.0,
    val gyroRateHz: Double = 0.0,
    val accelJitterMs: Double = 0.0,
    val gyroJitterMs: Double = 0.0,
    val acceptedSamples: Long = 0,
    val rejectedSamples: Long = 0,
    val journalSamples: Int = 0,
    val trace: List<ImuTracePoint> = emptyList(),
    val status: String = "En attente du service",
)
