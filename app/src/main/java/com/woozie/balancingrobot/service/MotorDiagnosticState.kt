package com.woozie.balancingrobot.service

import com.woozie.balancingrobot.motor.FeetechTelemetry
import com.woozie.balancingrobot.motor.MotorIoMetrics
import com.woozie.balancingrobot.domain.model.MotorControlMode

enum class MotorArmState { DISARMED, READY, MANUAL_ARMED, BALANCE_ARMED, FAULT_LATCHED }

data class MotorDiagnosticState(
    val scanInProgress: Boolean = false,
    val connectInProgress: Boolean = false,
    val connected: Boolean = false,
    val connectedDeviceId: Int? = null,
    val connectedDeviceName: String? = null,
    val scannedIds: List<Int> = emptyList(),
    val requiredIds: List<Int> = listOf(6, 7),
    val motorSigns: List<Int> = listOf(1, 1),
    val vmax: Int = 6000,
    val controlMode: MotorControlMode = MotorControlMode.VELOCITY,
    val pwmMax: Int = 1000,
    val torqueLimit: Int = 1023,
    val qualified: Boolean = false,
    val configured: Boolean = false,
    val armState: MotorArmState = MotorArmState.DISARMED,
    val manualCommand: Int = 0,
    val deadmanHeld: Boolean = false,
    val telemetry: List<FeetechTelemetry> = emptyList(),
    val ioMetrics: MotorIoMetrics = MotorIoMetrics(),
    val stepInProgress: Boolean = false,
    val lastAction: String? = null,
    val errorMessage: String? = null,
)
