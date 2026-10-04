package com.woozie.balancingrobot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.app.NotificationCompat
import com.woozie.balancingrobot.domain.logging.ImuLogRecord
import com.woozie.balancingrobot.domain.logging.ImuSampleLog
import com.woozie.balancingrobot.domain.logging.MotorTelemetryLog
import com.woozie.balancingrobot.domain.logging.ControlLogRecord
import com.woozie.balancingrobot.domain.logging.ControlSessionLog
import com.woozie.balancingrobot.domain.metrics.SampleRateMeter
import com.woozie.balancingrobot.domain.control.VelocityLoopOutput
import com.woozie.balancingrobot.domain.control.VelocityOuterLoop
import com.woozie.balancingrobot.domain.control.WheelVelocityFeedback
import com.woozie.balancingrobot.domain.control.normalizeWheelVelocity
import com.woozie.balancingrobot.domain.model.Axis
import com.woozie.balancingrobot.domain.model.MotorControlMode
import com.woozie.balancingrobot.domain.model.RobotConfig
import com.woozie.balancingrobot.domain.model.RobotConfigValidator
import com.woozie.balancingrobot.domain.model.Vector3
import com.woozie.balancingrobot.domain.estimation.gyroRateDegPerSec
import com.woozie.balancingrobot.domain.estimation.selectedGyroRateDegPerSec
import com.woozie.balancingrobot.domain.sensor.ImuRatePolicy
import com.woozie.balancingrobot.domain.safety.SafetyRules
import com.woozie.balancingrobot.motor.AndroidUsbSerialTransport
import com.woozie.balancingrobot.motor.FeetechBus
import com.woozie.balancingrobot.motor.FeetechProtocol
import com.woozie.balancingrobot.motor.FeetechTelemetry
import com.woozie.balancingrobot.motor.MotorIoScheduler
import com.woozie.balancingrobot.motor.OpenedUsbSerialPort
import com.woozie.balancingrobot.runtime.SimulatedControlRuntime
import com.woozie.balancingrobot.sensor.AndroidImuSource
import com.woozie.balancingrobot.sensor.ImuCapabilities
import com.woozie.balancingrobot.sensor.ImuListener
import com.woozie.balancingrobot.MainActivity
import com.woozie.balancingrobot.settings.RobotSettings
import com.woozie.balancingrobot.web.SocketRobotWebServer
import com.woozie.balancingrobot.web.WebCommandFrame
import com.woozie.balancingrobot.web.WebProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.NetworkInterface
import java.util.ArrayDeque

data class RobotServiceState(
    val running: Boolean = false,
    val serverUrl: String? = null,
    val errorMessage: String? = null,
    val config: RobotConfig = RobotConfig(),
    val imu: ImuDiagnosticState = ImuDiagnosticState(),
    val balance: BalanceDiagnosticState = BalanceDiagnosticState(),
    val speedLoop: SpeedLoopDiagnosticState = SpeedLoopDiagnosticState(),
    val controlRecording: ControlRecordingState = ControlRecordingState(),
    val motors: MotorDiagnosticState = MotorDiagnosticState(),
)

class RobotControlService : Service() {
    class LocalBinder(val service: RobotControlService) : Binder()

    companion object {
        private const val CHANNEL_ID = "robot_control"
        private const val NOTIFICATION_ID = 1001
        private const val DEFAULT_PORT = 8766
        private const val DEFAULT_IMU_RATE_HZ = ImuRatePolicy.DEFAULT_HZ
        const val ACTION_STOP = "com.woozie.balancingrobot.STOP"
        const val EXTRA_PORT = "com.woozie.balancingrobot.WEB_PORT"
        const val EXTRA_IMU_RATE_HZ = "com.woozie.balancingrobot.IMU_RATE_HZ"
    }

    private val binder = LocalBinder(this)
    private val _state = MutableStateFlow(RobotServiceState())
    val state: StateFlow<RobotServiceState> = _state.asStateFlow()
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var server: SocketRobotWebServer? = null
    private var controlThread: HandlerThread? = null
    private var imuSource: AndroidImuSource? = null
    @Volatile
    private var imuRuntime: SimulatedControlRuntime? = null
    private var motorPort: OpenedUsbSerialPort? = null
    private var motorBus: FeetechBus? = null
    private var motorScheduler: MotorIoScheduler? = null
    @Volatile
    private var activeConfig = RobotConfig()
    private var balanceWatchdogJob: Job? = null
    private var configPersistenceJob: Job? = null
    private var lastGyroReceivedNs: Long? = null
    private var fallStartedNs: Long? = null
    private var usbReceiverRegistered = false
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val detached = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (detached?.deviceId == _state.value.motors.connectedDeviceId) {
                onMotorFault("Adaptateur USB déconnecté")
                serverScope.launch(Dispatchers.IO) { closeMotorSession() }
            }
        }
    }
    private var latestAccel: Vector3? = null
    private var latestAccelTimestampNs: Long? = null
    private var previousAccelTimestampNs: Long? = null
    private var previousGyroTimestampNs: Long? = null
    private val accelRateMeter = SampleRateMeter()
    private val gyroRateMeter = SampleRateMeter()
    private val speedLoopRateMeter = SampleRateMeter()
    private val speedFeedbackRateMeter = SampleRateMeter()
    private var velocityOuterLoop = VelocityOuterLoop(RobotConfig())
    private var lastSpeedFeedbackSequence: Long? = null
    private var speedLoopActualRateHz = 0.0
    private var speedFeedbackActualRateHz = 0.0
    private val imuLog = ImuSampleLog()
    private val motorLog = MotorTelemetryLog()
    private val controlLog = ControlSessionLog()
    private val trace = ArrayDeque<ImuTracePoint>()
    private var lastTracePublishNs = 0L
    private var lastRecordingStatePublishNs = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerReceiver(usbReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
        usbReceiverRegistered = true
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRobot()
            stopSelf()
            return START_NOT_STICKY
        }

        return try {
            startForeground(NOTIFICATION_ID, buildNotification("Démarrage du serveur local…"))
            if (server == null) {
                val port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
                require(port in 1024..65535) { "Port invalide : $port" }
                val imuRateHz = intent?.getIntExtra(EXTRA_IMU_RATE_HZ, DEFAULT_IMU_RATE_HZ)
                    ?: DEFAULT_IMU_RATE_HZ
                require(ImuRatePolicy.isValid(imuRateHz)) { "Fréquence IMU invalide : $imuRateHz" }
                val config = runBlocking { RobotSettings.robotConfig(applicationContext).first() }
                startServer(port, imuRateHz, config)
            }
            START_NOT_STICKY
        } catch (error: Exception) {
            failStart(error)
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        stopRobot()
        serverScope.cancel()
        if (usbReceiverRegistered) {
            runCatching { unregisterReceiver(usbReceiver) }
            usbReceiverRegistered = false
        }
        super.onDestroy()
    }

    fun stopRobot() {
        runCatching { server?.stop() }
        stopImu()
        balanceWatchdogJob?.cancel()
        balanceWatchdogJob = null
        controlLog.stop()
        stopMotors()
        server = null
        _state.value = RobotServiceState()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    fun imuLogCsv(): String = imuLog.toCsv()

    fun motorLogCsv(): String = motorLog.toCsv()

    fun startControlRecording() {
        if (!_state.value.running) return
        controlLog.start()
        lastRecordingStatePublishNs = 0L
        _state.update { it.copy(controlRecording = ControlRecordingState(recording = true, samples = 0)) }
    }

    fun stopControlRecording() {
        controlLog.stop()
        _state.update { it.copy(
            controlRecording = ControlRecordingState(recording = false, samples = controlLog.size()),
        ) }
    }

    fun controlLogCsv(): String = controlLog.toCsv()

    fun connectMotors(deviceId: Int) {
        if (!_state.value.running) return
        if (_state.value.motors.armState == MotorArmState.MANUAL_ARMED ||
            _state.value.motors.armState == MotorArmState.BALANCE_ARMED ||
            _state.value.motors.armState == MotorArmState.FAULT_LATCHED
        ) return
        _state.update { it.copy(motors = it.motors.copy(connectInProgress = true, errorMessage = null)) }
        serverScope.launch(Dispatchers.IO) {
            val result = runCatching {
                closeMotorSession()
                val port = AndroidUsbSerialTransport(applicationContext).open(deviceId)
                motorPort = port
                motorBus = FeetechBus(port)
                port
            }
            _state.update { current ->
                current.copy(
                    motors = result.fold(
                        onSuccess = {
                            current.motors.copy(
                                connectInProgress = false,
                                connected = true,
                                connectedDeviceId = deviceId,
                                connectedDeviceName = "USB device $deviceId",
                                scannedIds = emptyList(),
                                qualified = false,
                                configured = false,
                                armState = MotorArmState.DISARMED,
                                lastAction = "Port USB ouvert, groupe à qualifier",
                                errorMessage = null,
                            )
                        },
                        onFailure = { error ->
                            MotorDiagnosticState(
                                connectInProgress = false,
                                errorMessage = "Connexion USB impossible (${error.message ?: error.javaClass.simpleName})",
                            )
                        },
                    ),
                )
            }
        }
    }

    fun disconnectMotors() {
        serverScope.launch(Dispatchers.IO) {
            closeMotorSession()
            _state.update { it.copy(motors = MotorDiagnosticState()) }
        }
    }

    fun updateMotorConfig(
        ids: List<Int>,
        signs: List<Int>,
        vmax: Int,
        torqueLimit: Int,
        controlMode: MotorControlMode,
        pwmMax: Int,
    ) {
        val valid = ids.size == 2 && ids.distinct().size == ids.size &&
            ids.all { it in 1..FeetechProtocol.MAX_ID } && signs.size == ids.size &&
            signs.all { it == -1 || it == 1 } && vmax in 0..20_000 &&
            torqueLimit in 0..1_023 && pwmMax in 0..FeetechProtocol.PWM_MAX
        if (!valid) {
            _state.update { it.copy(motors = it.motors.copy(errorMessage = "Configuration moteur invalide")) }
            return
        }
        if (_state.value.motors.armState == MotorArmState.MANUAL_ARMED ||
            _state.value.motors.armState == MotorArmState.BALANCE_ARMED ||
            _state.value.motors.armState == MotorArmState.FAULT_LATCHED
        ) return
        motorScheduler?.close()
        motorScheduler = null
        activeConfig = activeConfig.copy(
            motorIds = ids,
            motorSigns = signs,
            vmax = vmax,
            motorControlMode = controlMode,
            pwmMax = pwmMax,
            torqueLimit = torqueLimit,
        )
        _state.update { it.copy(motors = it.motors.copy(
            requiredIds = ids,
            motorSigns = signs,
            vmax = vmax,
            controlMode = controlMode,
            pwmMax = pwmMax,
            torqueLimit = torqueLimit,
            scannedIds = emptyList(),
            qualified = false,
            configured = false,
            armState = MotorArmState.DISARMED,
            lastAction = "Configuration moteur modifiée, nouveau scan requis",
            errorMessage = null,
        ), config = activeConfig) }
        serverScope.launch { runCatching { RobotSettings.saveRobotConfig(applicationContext, activeConfig) } }
        // Applying a known-ID configuration is itself an explicit action. A
        // bus scan is optional once the operator has supplied the IDs.
        if (_state.value.motors.connected) configureMotors()
    }

    fun updateRobotConfig(config: RobotConfig): Boolean {
        val validation = RobotConfigValidator.validate(config)
        val current = _state.value
        val currentArm = current.motors.armState
        val protectedChanged = activeConfig.axis != config.axis ||
            activeConfig.imuSign != config.imuSign ||
            activeConfig.zeroOffsetDeg != config.zeroOffsetDeg ||
            activeConfig.vmax != config.vmax ||
            activeConfig.motorIds != config.motorIds ||
            activeConfig.motorSigns != config.motorSigns ||
            activeConfig.torqueLimit != config.torqueLimit ||
            activeConfig.motorControlMode != config.motorControlMode ||
            activeConfig.pwmMax != config.pwmMax ||
            activeConfig.imuTimeoutMs != config.imuTimeoutMs ||
            activeConfig.fallAngleDeg != config.fallAngleDeg ||
            activeConfig.fallDurationMs != config.fallDurationMs ||
            activeConfig.manualTimeoutMs != config.manualTimeoutMs ||
            activeConfig.wheelDiameterMm != config.wheelDiameterMm ||
            activeConfig.driveRatio != config.driveRatio
        val targetLimit = if (config.speedLoopEnabled) config.speedAbsoluteAngleLimitDeg else 15.0
        val forbiddenWhileArmed = currentArm == MotorArmState.BALANCE_ARMED &&
            (protectedChanged || kotlin.math.abs(config.targetDeg) > targetLimit)
        if (!validation.isValid || currentArm == MotorArmState.MANUAL_ARMED ||
            currentArm == MotorArmState.FAULT_LATCHED || forbiddenWhileArmed
        ) {
            val message = when {
                !validation.isValid -> validation.errors.joinToString { error -> "${error.field}: ${error.message}" }
                forbiddenWhileArmed ->
                    "Pendant l'équilibrage, la géométrie, l'IMU, les moteurs et les sécurités restent verrouillés"
                else -> "Réglages indisponibles pendant le mode manuel ou un défaut"
            }
            _state.update { it.copy(errorMessage = message) }
            return false
        }
        val motorConfigChanged = activeConfig.motorIds != config.motorIds ||
            activeConfig.motorSigns != config.motorSigns ||
            activeConfig.vmax != config.vmax ||
            activeConfig.torqueLimit != config.torqueLimit ||
            activeConfig.motorControlMode != config.motorControlMode ||
            activeConfig.pwmMax != config.pwmMax ||
            activeConfig.manualTimeoutMs != config.manualTimeoutMs
        if (motorConfigChanged) {
            // The I/O worker captures these limits at construction time. Stop
            // it before publishing the new configuration, then explicitly
            // re-apply known IDs without requiring a discovery scan.
            motorScheduler?.close()
            motorScheduler = null
        }
        activeConfig = config
        if (imuRuntime == null) imuRuntime = SimulatedControlRuntime(config)
        else imuRuntime?.updateConfig(config)
        velocityOuterLoop.updateConfig(config)
        _state.update { state ->
            state.copy(
                config = config,
                errorMessage = null,
                speedLoop = state.speedLoop.copy(
                    enabled = config.speedLoopEnabled,
                    targetCmPerSec = config.speedTargetCmPerSec,
                    trimDeg = config.targetDeg,
                ),
                motors = if (motorConfigChanged) state.motors.copy(
                    requiredIds = config.motorIds,
                    motorSigns = config.motorSigns,
                    vmax = config.vmax,
                    controlMode = config.motorControlMode,
                    pwmMax = config.pwmMax,
                    torqueLimit = config.torqueLimit,
                    configured = false,
                    qualified = false,
                    armState = MotorArmState.DISARMED,
                    lastAction = "Réglages moteur modifiés, application demandée",
                ) else state.motors,
            )
        }
        configPersistenceJob?.cancel()
        configPersistenceJob = serverScope.launch {
            delay(200)
            runCatching { RobotSettings.saveRobotConfig(applicationContext, config) }
        }
        if (motorConfigChanged && current.motors.connected) configureMotors()
        return true
    }

    fun armBalance(safeTestConfirmed: Boolean) {
        val current = _state.value
        if (!safeTestConfirmed || current.motors.armState != MotorArmState.READY ||
            !current.motors.qualified || !current.motors.configured || motorScheduler == null ||
            !current.imu.gyroscopeAvailable || current.imu.estimatedAngleDeg == null ||
            !SafetyRules.isGyroFresh(SystemClock.elapsedRealtimeNanos(), lastGyroReceivedNs, activeConfig.imuTimeoutMs) ||
            kotlin.math.abs(current.imu.estimatedAngleDeg) >= activeConfig.fallAngleDeg ||
            kotlin.math.abs(activeConfig.targetDeg) > if (activeConfig.speedLoopEnabled) {
                activeConfig.speedAbsoluteAngleLimitDeg
            } else 15.0
        ) {
            _state.update { it.copy(motors = it.motors.copy(errorMessage = "Prérequis d'équilibrage non satisfaits")) }
            return
        }
        serverScope.launch(Dispatchers.IO) {
            val ok = motorScheduler?.arm() == true
            _state.update { state ->
                state.copy(motors = state.motors.copy(
                    armState = if (ok) MotorArmState.BALANCE_ARMED else MotorArmState.FAULT_LATCHED,
                    lastAction = if (ok) "Boucle d'équilibrage armée" else null,
                    errorMessage = if (ok) null else "Armement équilibrage impossible",
                ))
            }
        }
    }

    fun disarmBalance() {
        motorScheduler?.disarm()
        _state.update { it.copy(motors = it.motors.copy(
            armState = when {
                it.motors.armState == MotorArmState.FAULT_LATCHED -> MotorArmState.FAULT_LATCHED
                it.motors.qualified -> MotorArmState.READY
                else -> MotorArmState.DISARMED
            },
            manualCommand = 0,
            deadmanHeld = false,
            lastAction = "Désarmement équilibrage demandé",
        )) }
    }

    private fun checkBalanceWatchdog() {
        if (_state.value.motors.armState != MotorArmState.BALANCE_ARMED) return
        val now = SystemClock.elapsedRealtimeNanos()
        if (!SafetyRules.isGyroFresh(now, lastGyroReceivedNs, activeConfig.imuTimeoutMs)) {
            triggerBalanceFault("Gyroscope périmé : arrêt équilibrage")
            return
        }
        val angle = _state.value.imu.estimatedAngleDeg ?: run {
            triggerBalanceFault("Estimation absente : arrêt équilibrage")
            return
        }
        if (SafetyRules.isFallAngleExceeded(angle, activeConfig.fallAngleDeg)) {
            if (fallStartedNs == null) fallStartedNs = now
            if (SafetyRules.isFallDurationExceeded(fallStartedNs, now, activeConfig.fallDurationMs)) {
                triggerBalanceFault("Angle de chute dépassé : arrêt équilibrage")
            }
        } else {
            fallStartedNs = null
        }
    }

    private fun triggerBalanceFault(message: String) {
        if (_state.value.motors.armState != MotorArmState.BALANCE_ARMED) return
        if (activeConfig.inhibitSafetyAutoDisarm) {
            _state.update { state ->
                state.copy(balance = state.balance.copy(
                    faultMessage = "Sécurité inhibée : $message",
                ))
            }
            return
        }
        motorScheduler?.safetyStop()
        _state.update { it.copy(
            motors = it.motors.copy(
                armState = MotorArmState.FAULT_LATCHED,
                manualCommand = 0,
                deadmanHeld = false,
                errorMessage = message,
            ),
            balance = it.balance.copy(faultMessage = message, lastCommand = 0),
        ) }
    }

    fun scanMotors(deviceId: Int) {
        if (!_state.value.running) return
        if (_state.value.motors.armState == MotorArmState.MANUAL_ARMED ||
            _state.value.motors.armState == MotorArmState.BALANCE_ARMED
        ) return
        _state.update { it.copy(motors = it.motors.copy(scanInProgress = true, errorMessage = null)) }
        serverScope.launch(Dispatchers.IO) {
            motorScheduler?.close()
            motorScheduler = null
            var temporaryPort: OpenedUsbSerialPort? = null
            val result = runCatching {
                val bus = if (motorBus != null && _state.value.motors.connectedDeviceId == deviceId) {
                    motorBus!!
                } else {
                    closeMotorSession()
                    temporaryPort = AndroidUsbSerialTransport(applicationContext).open(deviceId)
                    motorPort = temporaryPort
                    motorBus = FeetechBus(temporaryPort!!)
                    motorBus!!
                }
                bus.scanIds(first = 1, last = 20)
            }
            if (temporaryPort != null && result.isFailure) closeMotorSession()
            _state.update { current ->
                current.copy(
                    motors = result.fold(
                        onSuccess = { ids ->
                            val required = current.motors.requiredIds
                            val qualified = required.all(ids::contains)
                            current.motors.copy(
                                scanInProgress = false,
                                connected = true,
                                connectedDeviceId = deviceId,
                                scannedIds = ids,
                                qualified = qualified,
                                armState = when {
                                    current.motors.armState == MotorArmState.FAULT_LATCHED -> MotorArmState.FAULT_LATCHED
                                    qualified -> MotorArmState.READY
                                    else -> MotorArmState.DISARMED
                                },
                                lastAction = if (qualified) "Groupe moteur qualifié" else "Moteur attendu absent",
                                errorMessage = if (qualified) null else "Le groupe exige les IDs ${required.joinToString()}",
                            )
                        },
                        onFailure = { error ->
                            current.motors.copy(
                                scanInProgress = false,
                                connected = motorPort != null,
                                connectedDeviceId = if (motorPort != null) deviceId else null,
                                errorMessage = "Scan impossible (${error.message ?: error.javaClass.simpleName})",
                            )
                        },
                    ),
                )
            }
            if (result.isSuccess && _state.value.motors.qualified) startMotorScheduler()
        }
    }

    fun configureMotors() {
        if (!_state.value.running) return
        val current = _state.value.motors
        if (!current.connected || current.armState == MotorArmState.MANUAL_ARMED ||
            current.armState == MotorArmState.BALANCE_ARMED ||
            current.armState == MotorArmState.FAULT_LATCHED
        ) return
        serverScope.launch(Dispatchers.IO) {
            motorScheduler?.close()
            motorScheduler = null
            val result = runCatching {
                motorBus?.configureControlMode(current.requiredIds, current.controlMode, current.torqueLimit)
                    ?: error("Bus moteur non connecté")
            }
            _state.update { state ->
                state.copy(motors = state.motors.copy(
                    configured = result.isSuccess,
                    qualified = result.isSuccess,
                    armState = if (result.isSuccess) MotorArmState.READY else state.motors.armState,
                    lastAction = result.fold(
                        { "Mode ${current.controlMode.label} appliqué aux IDs ${current.requiredIds.joinToString()} · couple coupé" },
                        { null },
                    ),
                    errorMessage = result.exceptionOrNull()?.let { "Configuration impossible : ${it.message}" },
                ))
            }
            if (result.isSuccess && _state.value.motors.qualified) startMotorScheduler()
        }
    }

    fun armManual(safeTestConfirmed: Boolean) {
        val current = _state.value.motors
        if (!safeTestConfirmed || current.armState != MotorArmState.READY ||
            !current.qualified || !current.configured || motorScheduler == null
        ) {
            _state.update { it.copy(motors = it.motors.copy(errorMessage = "Prérequis d'armement non satisfaits")) }
            return
        }
        serverScope.launch(Dispatchers.IO) {
            val ok = motorScheduler?.arm() == true
            _state.update { state ->
                state.copy(motors = state.motors.copy(
                    armState = if (ok) MotorArmState.MANUAL_ARMED else MotorArmState.FAULT_LATCHED,
                    lastAction = if (ok) "Mode manuel armé, deadman requis" else null,
                    errorMessage = if (ok) null else "Armement refusé ou impossible",
                ))
            }
        }
    }

    fun disarmManual() {
        motorScheduler?.disarm()
        _state.update { it.copy(motors = it.motors.copy(
            armState = when {
                it.motors.armState == MotorArmState.FAULT_LATCHED -> MotorArmState.FAULT_LATCHED
                it.motors.qualified -> MotorArmState.READY
                else -> MotorArmState.DISARMED
            },
            manualCommand = 0,
            deadmanHeld = false,
            lastAction = "Désarmement demandé",
        )) }
    }

    fun acknowledgeMotorFault() {
        _state.update { state ->
            if (state.motors.armState != MotorArmState.FAULT_LATCHED) state
            else state.copy(motors = state.motors.copy(
                armState = if (state.motors.qualified) MotorArmState.READY else MotorArmState.DISARMED,
                errorMessage = null,
                lastAction = "Défaut acquitté, réarmement volontaire requis",
            ))
        }
    }

    fun manualCommand(value: Int, held: Boolean) {
        if (_state.value.motors.armState != MotorArmState.MANUAL_ARMED) {
            motorScheduler?.submitManual(0, false)
            return
        }
        val limit = when (_state.value.motors.controlMode) {
            MotorControlMode.VELOCITY -> _state.value.motors.vmax
            MotorControlMode.PWM -> _state.value.motors.pwmMax
        }
        val bounded = value.coerceIn(-limit, limit)
        motorScheduler?.submitManual(bounded, held)
        _state.update { it.copy(motors = it.motors.copy(
            manualCommand = if (held) bounded else 0,
            deadmanHeld = held,
        )) }
    }

    fun runManualStepSequence() {
        if (_state.value.motors.armState != MotorArmState.MANUAL_ARMED) return
        serverScope.launch(Dispatchers.IO) {
            val limit = when (_state.value.motors.controlMode) {
                MotorControlMode.VELOCITY -> _state.value.motors.vmax
                MotorControlMode.PWM -> _state.value.motors.pwmMax
            }
            val steps = listOf(0, limit / 12, 0, limit / 6, 0, limit / 3, 0, (limit * 2) / 3, 0, limit, 0)
            _state.update { it.copy(motors = it.motors.copy(stepInProgress = true, lastAction = "Séquence de paliers en cours")) }
            for (value in steps) {
                if (_state.value.motors.armState != MotorArmState.MANUAL_ARMED) break
                motorScheduler?.submitManual(value, value != 0)
                _state.update { it.copy(motors = it.motors.copy(manualCommand = value, deadmanHeld = value != 0)) }
                kotlinx.coroutines.delay(if (value == 0) 400 else 800)
            }
            motorScheduler?.submitManual(0, false)
            _state.update { it.copy(motors = it.motors.copy(stepInProgress = false, manualCommand = 0, deadmanHeld = false)) }
        }
    }

    private fun failStart(error: Exception) {
        Log.e("RobotControlService", "Impossible de démarrer le service", error)
        runCatching { server?.stop() }
        stopImu()
        server = null
        val detail = error.message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
        _state.value = RobotServiceState(
            errorMessage = "Démarrage impossible (${error.javaClass.simpleName})$detail",
        )
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun startServer(port: Int, imuRateHz: Int, config: RobotConfig) {
        motorLog.clear()
        activeConfig = config
        server = SocketRobotWebServer(
            assets = assets,
            scope = serverScope,
            isServiceRunning = { _state.value.running },
            diagnosticsJson = ::diagnosticsJson,
            logCsv = ::imuLogCsv,
            motorLogCsv = ::motorLogCsv,
            controlLogCsv = ::controlLogCsv,
            commandHandler = ::handleWebCommand,
            port = port,
        ).also { it.start() }

        val url = "http://${localAddress()}:${server?.boundPort}"
        _state.value = RobotServiceState(
            running = true,
            serverUrl = url,
            config = config,
            speedLoop = SpeedLoopDiagnosticState(
                enabled = config.speedLoopEnabled,
                targetCmPerSec = config.speedTargetCmPerSec,
                trimDeg = config.targetDeg,
                effectiveTargetDeg = if (config.speedLoopEnabled) {
                    config.targetDeg.coerceIn(
                        -config.speedAbsoluteAngleLimitDeg,
                        config.speedAbsoluteAngleLimitDeg,
                    )
                } else config.targetDeg,
            ),
            imu = ImuDiagnosticState(
                requestedRateHz = imuRateHz,
                status = "Initialisation des capteurs…",
            ),
            motors = MotorDiagnosticState(
                requiredIds = config.motorIds,
                motorSigns = config.motorSigns,
                vmax = config.vmax,
                controlMode = config.motorControlMode,
                pwmMax = config.pwmMax,
                torqueLimit = config.torqueLimit,
            ),
        )
        startImu(imuRateHz)
        balanceWatchdogJob?.cancel()
        balanceWatchdogJob = serverScope.launch {
            while (true) {
                delay(50)
                checkBalanceWatchdog()
            }
        }
        updateNotification("Serveur local actif sur $url · IMU diagnostic")
    }

    private fun startImu(imuRateHz: Int) {
        stopImu()
        latestAccel = null
        latestAccelTimestampNs = null
        previousAccelTimestampNs = null
        previousGyroTimestampNs = null
        lastGyroReceivedNs = null
        fallStartedNs = null
        accelRateMeter.reset()
        gyroRateMeter.reset()
        speedLoopRateMeter.reset()
        speedFeedbackRateMeter.reset()
        speedLoopActualRateHz = 0.0
        speedFeedbackActualRateHz = 0.0
        lastSpeedFeedbackSequence = null
        velocityOuterLoop = VelocityOuterLoop(activeConfig)
        imuLog.clear()
        trace.clear()
        lastTracePublishNs = 0L
        imuRuntime = SimulatedControlRuntime(activeConfig)

        val thread = HandlerThread("robot-control").also { it.start() }
        controlThread = thread
        val handler = Handler(thread.looper)
        val source = AndroidImuSource(applicationContext, handler, imuRateHz)
        imuSource = source
        source.start(object : ImuListener {
            override fun onCapabilities(capabilities: ImuCapabilities) {
                updateImu {
                    it.copy(
                        accelerometerAvailable = capabilities.accelerometerAvailable,
                        gyroscopeAvailable = capabilities.gyroscopeAvailable,
                        status = when {
                            !capabilities.accelerometerAvailable && !capabilities.gyroscopeAvailable ->
                                "Aucun capteur IMU disponible"
                            !capabilities.accelerometerAvailable -> "Accéléromètre indisponible"
                            !capabilities.gyroscopeAvailable -> "Gyroscope indisponible"
                            else -> "Capteurs actifs, demande ${imuRateHz} Hz…"
                        },
                    )
                }
            }

            override fun onAccelerometer(values: Vector3, timestampNs: Long, receivedTimestampNs: Long) {
                onAccelSample(values, timestampNs)
            }

            override fun onGyroscope(values: Vector3, timestampNs: Long, receivedTimestampNs: Long) {
                onGyroSample(values, timestampNs, receivedTimestampNs)
            }
        })
    }

    private fun stopImu() {
        imuSource?.stop()
        imuSource = null
        controlThread?.quitSafely()
        controlThread = null
        imuRuntime = null
    }

    private fun startMotorScheduler() {
        motorScheduler?.close()
        val bus = motorBus ?: return
        val config = activeConfig
        motorScheduler = MotorIoScheduler(
            bus = bus,
            motorIds = _state.value.motors.requiredIds,
            motorSigns = _state.value.motors.motorSigns,
            controlMode = _state.value.motors.controlMode,
            commandLimit = activeConfig.commandLimit,
            manualTimeoutMs = config.manualTimeoutMs,
            onTelemetry = ::onMotorTelemetry,
            onFault = ::onMotorFault,
            clockNs = SystemClock::elapsedRealtimeNanos,
        ).also { it.start() }
    }

    private fun onMotorTelemetry(values: List<FeetechTelemetry>) {
        val writeTrace = motorScheduler?.lastWriteTrace()
        values.forEachIndexed { index, telemetry ->
            motorLog.append(
                command = writeTrace?.values?.getOrNull(index),
                telemetry = telemetry,
                writeTrace = writeTrace,
            )
        }
        _state.update { it.copy(motors = it.motors.copy(
            telemetry = values,
            ioMetrics = motorScheduler?.metrics() ?: it.motors.ioMetrics,
        )) }
    }

    private fun onMotorFault(message: String) {
        if (activeConfig.inhibitSafetyAutoDisarm &&
            (_state.value.motors.armState == MotorArmState.MANUAL_ARMED ||
                _state.value.motors.armState == MotorArmState.BALANCE_ARMED)
        ) {
            _state.update { state ->
                state.copy(motors = state.motors.copy(
                    errorMessage = "Sécurité inhibée : $message",
                    lastAction = "Défaut moteur signalé, désarmement automatique inhibé",
                ))
            }
            return
        }
        motorScheduler?.safetyStop()
        _state.update { it.copy(motors = it.motors.copy(
            armState = MotorArmState.FAULT_LATCHED,
            manualCommand = 0,
            deadmanHeld = false,
            errorMessage = message,
        )) }
    }

    private fun closeMotorSession() {
        motorScheduler?.close()
        motorScheduler = null
        runCatching { motorPort?.close() }
        motorPort = null
        motorBus = null
    }

    private fun stopMotors() {
        closeMotorSession()
    }

    private fun onAccelSample(values: Vector3, timestampNs: Long) {
        if (!values.isFinite() || timestampNs <= 0L ||
            previousAccelTimestampNs?.let { timestampNs <= it } == true
        ) {
            updateImu { it.copy(rejectedSamples = it.rejectedSamples + 1, status = "Mesure accel rejetée") }
            return
        }
        previousAccelTimestampNs = timestampNs
        latestAccel = values
        latestAccelTimestampNs = timestampNs
        val rate = accelRateMeter.record(timestampNs)
        updateImu {
            it.copy(
                accelerometerX = values.x,
                accelerometerY = values.y,
                accelerometerZ = values.z,
                accelRateHz = rate.frequencyHz,
                accelJitterMs = rate.jitterMs,
                acceptedSamples = it.acceptedSamples + 1,
                status = "Mesures IMU reçues",
            )
        }
    }

    private fun onGyroSample(values: Vector3, timestampNs: Long, receivedTimestampNs: Long) {
        if (!values.isFinite() || timestampNs <= 0L ||
            previousGyroTimestampNs?.let { timestampNs <= it } == true
        ) {
            appendControlRecord(
                timestampNs = timestampNs,
                receivedTimestampNs = receivedTimestampNs,
                gyroValues = values,
                sampleStatus = "GYRO_REJECTED",
            )
            updateImu { it.copy(rejectedSamples = it.rejectedSamples + 1, status = "Mesure gyro rejetée") }
            return
        }
        val previous = previousGyroTimestampNs
        previousGyroTimestampNs = timestampNs
        lastGyroReceivedNs = receivedTimestampNs
        val rate = gyroRateMeter.record(timestampNs)
        val gyroDps = selectedGyroRateDegPerSec(values, activeConfig.axis, activeConfig.imuSign)
        val gyroXDps = gyroRateDegPerSec(values.x)
        val gyroYDps = gyroRateDegPerSec(values.y)
        val gyroZDps = gyroRateDegPerSec(values.z)
        updateImu {
            it.copy(
                gyroXDegPerSec = gyroXDps,
                gyroYDegPerSec = gyroYDps,
                gyroZDegPerSec = gyroZDps,
                gyroRateHz = rate.frequencyHz,
                gyroJitterMs = rate.jitterMs,
                acceptedSamples = it.acceptedSamples + 1,
            )
        }
        if (previous == null || gyroDps == null) {
            appendControlRecord(
                timestampNs = timestampNs,
                receivedTimestampNs = receivedTimestampNs,
                gyroValues = values,
                gyroXDps = gyroXDps,
                gyroYDps = gyroYDps,
                gyroZDps = gyroZDps,
                sampleStatus = if (previous == null) "WARMUP" else "GYRO_INVALID",
            )
            return
        }

        val dtSec = (timestampNs - previous).toDouble() / 1_000_000_000.0
        val accel = latestAccel
        val accelTimestamp = latestAccelTimestampNs
        val fresh = accel != null && accelTimestamp != null &&
            timestampNs - accelTimestamp <= activeConfig.imuTimeoutMs * 1_000_000L
        if (!fresh) {
            imuRuntime?.reset()
            appendControlRecord(
                timestampNs = timestampNs,
                receivedTimestampNs = receivedTimestampNs,
                accel = accel,
                gyroValues = values,
                gyroXDps = gyroXDps,
                gyroYDps = gyroYDps,
                gyroZDps = gyroZDps,
                gyroRateDps = gyroDps,
                dtSec = dtSec,
                sampleStatus = "IMU_STALE",
            )
            updateImu {
                it.copy(
                    dtMs = dtSec * 1_000.0,
                    accelAngleDeg = null,
                    estimatedAngleDeg = null,
                    status = "IMU stale : accélération absente ou trop ancienne",
                )
            }
            return
        }

        val speedOutput = updateSpeedLoop(receivedTimestampNs)
        val step = imuRuntime?.step(
            accel!!,
            gyroDps,
            dtSec,
            targetDeg = speedOutput.effectiveTargetDeg,
        )
        val estimate = step?.estimate
        val accelAngle = estimate?.accelAngleDeg
        val estimatedAngle = estimate?.angleDeg
        val shouldPublishTrace = estimate != null &&
            (lastTracePublishNs == 0L || timestampNs - lastTracePublishNs >= 50_000_000L)
        if (estimate != null) {
            trace.addLast(
                ImuTracePoint(timestampNs, estimate.accelAngleDeg, estimate.angleDeg, estimate.gyroRateDegPerSec),
            )
            while (trace.size > 160) trace.removeFirst()
            if (shouldPublishTrace) lastTracePublishNs = timestampNs
        }
        imuLog.append(
            ImuLogRecord(
                timestampNs = timestampNs,
                accelAngleDeg = accelAngle,
                estimatedAngleDeg = estimatedAngle,
                gyroRateDegPerSec = gyroDps,
                dtSec = dtSec,
            ),
        )
        updateImu {
            it.copy(
                accelAngleDeg = accelAngle,
                estimatedAngleDeg = estimatedAngle,
                dtMs = dtSec * 1_000.0,
                journalSamples = imuLog.size(),
                trace = if (shouldPublishTrace) trace.toList() else it.trace,
                status = if (step?.fault == null) "Estimation active · moteurs simulés" else "Estimation invalide",
            )
        }
        val control = step?.control
        val latencyMs = control?.let {
            (SystemClock.elapsedRealtimeNanos() - receivedTimestampNs).coerceAtLeast(0L) / 1_000_000.0
        }
        if (control != null) {
            _state.update { state ->
                state.copy(balance = state.balance.copy(
                    lastErrorDeg = control.errorDeg,
                    lastRawCommand = control.rawCommand,
                    lastCommand = control.boundedCommand,
                    saturated = control.saturated,
                    controlLatencyMs = latencyMs,
                    faultMessage = null,
                ))
            }
            if (_state.value.motors.armState == MotorArmState.BALANCE_ARMED) {
                motorScheduler?.submitControl(control.motorCommands)
            }
        } else if (_state.value.motors.armState == MotorArmState.BALANCE_ARMED) {
            triggerBalanceFault("Commande PD invalide")
        }
        appendControlRecord(
            timestampNs = timestampNs,
            receivedTimestampNs = receivedTimestampNs,
            accel = accel,
            gyroValues = values,
            gyroXDps = gyroXDps,
            gyroYDps = gyroYDps,
            gyroZDps = gyroZDps,
            accelAngleDeg = accelAngle,
            estimatedAngleDeg = estimatedAngle,
            gyroRateDps = gyroDps,
            dtSec = dtSec,
            control = control,
            controlLatencyMs = latencyMs,
            speedOutput = speedOutput,
            sampleStatus = step?.fault?.name,
        )
    }

    private fun updateSpeedLoop(nowNs: Long): VelocityLoopOutput {
        val snapshot = motorScheduler?.velocitySnapshot()
        val feedback = if (
            snapshot != null && snapshot.values.size >= 2 && snapshot.timestampsNs.size >= 2 &&
            activeConfig.motorSigns.size >= 2
        ) {
            if (snapshot.sequence != lastSpeedFeedbackSequence) {
                lastSpeedFeedbackSequence = snapshot.sequence
                speedFeedbackActualRateHz = speedFeedbackRateMeter
                    .record(snapshot.timestampsNs.maxOrNull() ?: nowNs)
                    .frequencyHz
            }
            WheelVelocityFeedback(
                sequence = snapshot.sequence,
                leftStepsPerSec = normalizeWheelVelocity(
                    snapshot.values[0],
                    activeConfig.motorSigns[0],
                ),
                rightStepsPerSec = normalizeWheelVelocity(
                    snapshot.values[1],
                    activeConfig.motorSigns[1],
                ),
                leftTimestampNs = snapshot.timestampsNs[0],
                rightTimestampNs = snapshot.timestampsNs[1],
            )
        } else null
        val output = velocityOuterLoop.step(nowNs, feedback)
        if (output.updated) {
            speedLoopActualRateHz = speedLoopRateMeter.record(nowNs).frequencyHz
            _state.update { state ->
                state.copy(speedLoop = SpeedLoopDiagnosticState(
                    enabled = output.enabled,
                    stale = output.stale,
                    feedbackSequence = output.feedbackSequence,
                    feedbackAgeMs = output.feedbackAgeMs,
                    feedbackRateHz = speedFeedbackActualRateHz,
                    loopRateHz = speedLoopActualRateHz,
                    leftRawStepsPerSec = output.leftRawStepsPerSec,
                    rightRawStepsPerSec = output.rightRawStepsPerSec,
                    leftCmPerSec = output.leftCmPerSec,
                    rightCmPerSec = output.rightCmPerSec,
                    meanCmPerSec = output.meanCmPerSec,
                    filteredCmPerSec = output.filteredCmPerSec,
                    targetCmPerSec = output.targetCmPerSec,
                    errorCmPerSec = output.errorCmPerSec,
                    correctionDeg = output.correctionDeg,
                    integralCorrectionDeg = output.integralCorrectionDeg,
                    trimDeg = output.trimDeg,
                    effectiveTargetDeg = output.effectiveTargetDeg,
                    saturated = output.saturated,
                    slewLimited = output.slewLimited,
                ))
            }
        }
        return output
    }

    private fun appendControlRecord(
        timestampNs: Long,
        receivedTimestampNs: Long,
        accel: Vector3? = latestAccel,
        gyroValues: Vector3? = null,
        gyroXDps: Double? = gyroValues?.x?.let(::gyroRateDegPerSec),
        gyroYDps: Double? = gyroValues?.y?.let(::gyroRateDegPerSec),
        gyroZDps: Double? = gyroValues?.z?.let(::gyroRateDegPerSec),
        accelAngleDeg: Double? = null,
        estimatedAngleDeg: Double? = null,
        gyroRateDps: Double? = null,
        dtSec: Double? = null,
        control: com.woozie.balancingrobot.domain.model.ControlOutput? = null,
        controlLatencyMs: Double? = null,
        speedOutput: VelocityLoopOutput? = null,
        sampleStatus: String? = null,
    ) {
        if (!controlLog.isRecording()) return
        val config = activeConfig
        controlLog.append(
            ControlLogRecord(
                timestampNs = timestampNs,
                receivedTimestampNs = receivedTimestampNs,
                accelX = accel?.x,
                accelY = accel?.y,
                accelZ = accel?.z,
                gyroXDegPerSec = gyroXDps,
                gyroYDegPerSec = gyroYDps,
                gyroZDegPerSec = gyroZDps,
                accelAngleDeg = accelAngleDeg,
                estimatedAngleDeg = estimatedAngleDeg,
                gyroRateDegPerSec = gyroRateDps,
                dtSec = dtSec,
                config = config,
                errorDeg = control?.errorDeg,
                rawCommand = control?.rawCommand,
                boundedCommand = control?.boundedCommand,
                saturated = control?.saturated,
                motorCommand0 = control?.motorCommands?.getOrNull(0),
                motorCommand1 = control?.motorCommands?.getOrNull(1),
                controlLatencyMs = controlLatencyMs,
                armState = _state.value.motors.armState.name,
                sampleStatus = sampleStatus,
                effectiveTargetDeg = control?.targetDeg,
                speedLoopEnabled = speedOutput?.enabled,
                speedFeedbackSequence = speedOutput?.feedbackSequence,
                speedFeedbackAgeMs = speedOutput?.feedbackAgeMs,
                speedLeftRawStepsPerSec = speedOutput?.leftRawStepsPerSec,
                speedRightRawStepsPerSec = speedOutput?.rightRawStepsPerSec,
                speedLeftCmPerSec = speedOutput?.leftCmPerSec,
                speedRightCmPerSec = speedOutput?.rightCmPerSec,
                speedMeanCmPerSec = speedOutput?.meanCmPerSec,
                speedFilteredCmPerSec = speedOutput?.filteredCmPerSec,
                speedTargetCmPerSec = speedOutput?.targetCmPerSec,
                speedErrorCmPerSec = speedOutput?.errorCmPerSec,
                speedCorrectionDeg = speedOutput?.correctionDeg,
                speedIntegralCorrectionDeg = speedOutput?.integralCorrectionDeg,
                speedTargetSlewLimited = speedOutput?.slewLimited,
                speedStale = speedOutput?.stale,
                speedTargetSaturated = speedOutput?.saturated,
                speedLoopActualRateHz = speedLoopActualRateHz,
                speedFeedbackActualRateHz = speedFeedbackActualRateHz,
            ),
        )
        val now = SystemClock.elapsedRealtimeNanos()
        if (now - lastRecordingStatePublishNs >= 250_000_000L) {
            lastRecordingStatePublishNs = now
            _state.update { state ->
                state.copy(controlRecording = state.controlRecording.copy(samples = controlLog.size()))
            }
        }
    }

    private fun updateImu(transform: (ImuDiagnosticState) -> ImuDiagnosticState) {
        _state.update { current -> current.copy(imu = transform(current.imu)) }
    }

    private fun diagnosticsJson(): String {
        val imu = _state.value.imu
        val motors = _state.value.motors
        val config = _state.value.config
        val balance = _state.value.balance
        val speed = _state.value.speedLoop
        return buildJsonObject {
            put("serviceRunning", _state.value.running)
            put("requestedRateHz", imu.requestedRateHz)
            put("accelerometerAvailable", imu.accelerometerAvailable)
            put("gyroscopeAvailable", imu.gyroscopeAvailable)
            put("accelRateHz", imu.accelRateHz)
            put("gyroRateHz", imu.gyroRateHz)
            put("accelJitterMs", imu.accelJitterMs)
            put("gyroJitterMs", imu.gyroJitterMs)
            put("acceptedSamples", imu.acceptedSamples)
            put("rejectedSamples", imu.rejectedSamples)
            put("journalSamples", imu.journalSamples)
            put("status", imu.status)
            imu.accelAngleDeg?.let { put("accelAngleDeg", it) }
            imu.estimatedAngleDeg?.let { put("estimatedAngleDeg", it) }
            imu.dtMs?.let { put("dtMs", it) }
            put("motorConnected", motors.connected)
            put("motorQualified", motors.qualified)
            put("motorConfigured", motors.configured)
            put("motorArmState", motors.armState.name)
            put("motorCommand", motors.manualCommand)
            put("motorDeadmanHeld", motors.deadmanHeld)
            put("motorTelemetryCount", motors.telemetry.size)
            put("motorWrittenFrames", motors.ioMetrics.writtenFrames)
            put("motorTelemetryFrames", motors.ioMetrics.telemetryFrames)
            put("motorVelocityFeedbackFrames", motors.ioMetrics.velocityFeedbackFrames)
            put("motorVelocityFeedbackSkips", motors.ioMetrics.velocityFeedbackSkips)
            put("motorSupersededFrames", motors.ioMetrics.supersededFrames)
            put("motorBusBusySkips", motors.ioMetrics.busBusySkips)
            motors.ioMetrics.lastWriteLatencyMs?.let { put("motorLastWriteLatencyMs", it) }
            motors.ioMetrics.lastWriteSequence?.let { put("motorLastWriteSequence", it) }
            put("controlRecording", _state.value.controlRecording.recording)
            put("controlRecordingSamples", _state.value.controlRecording.samples)
            put("axis", config.axis.name)
            put("imuSign", config.imuSign)
            put("alpha", config.alpha)
            put("zeroOffsetDeg", config.zeroOffsetDeg)
            put("targetDeg", config.targetDeg)
            put("kp", config.kp)
            put("kd", config.kd)
            put("speedLoopEnabled", config.speedLoopEnabled)
            put("speedTargetCmPerSec", config.speedTargetCmPerSec)
            put("speedTargetLimitCmPerSec", config.speedTargetLimitCmPerSec)
            put("speedKevDegPerCmPerSec", config.speedKevDegPerCmPerSec)
            put("speedLoopRateHz", config.speedLoopRateHz)
            put("speedFilterAlpha", config.speedFilterAlpha)
            put("speedTargetAngleLimitDeg", config.speedTargetAngleLimitDeg)
            put("speedIntegralGainDegPerCmPerSecSec", config.speedIntegralGainDegPerCmPerSecSec)
            put("speedAbsoluteAngleLimitDeg", config.speedAbsoluteAngleLimitDeg)
            put("speedTargetSlewRateDegPerSec", config.speedTargetSlewRateDegPerSec)
            put("speedFeedbackTimeoutMs", config.speedFeedbackTimeoutMs)
            put("wheelDiameterMm", config.wheelDiameterMm)
            put("driveRatio", config.driveRatio)
            put("speedFeedbackStale", speed.stale)
            put("speedFeedbackRateHz", speed.feedbackRateHz)
            put("speedLoopActualRateHz", speed.loopRateHz)
            speed.feedbackSequence?.let { put("speedFeedbackSequence", it) }
            speed.feedbackAgeMs?.let { put("speedFeedbackAgeMs", it) }
            speed.leftRawStepsPerSec?.let { put("speedLeftRawStepsPerSec", it) }
            speed.rightRawStepsPerSec?.let { put("speedRightRawStepsPerSec", it) }
            speed.leftCmPerSec?.let { put("speedLeftCmPerSec", it) }
            speed.rightCmPerSec?.let { put("speedRightCmPerSec", it) }
            speed.meanCmPerSec?.let { put("speedMeanCmPerSec", it) }
            speed.filteredCmPerSec?.let { put("speedFilteredCmPerSec", it) }
            speed.errorCmPerSec?.let { put("speedErrorCmPerSec", it) }
            put("speedCorrectionDeg", speed.correctionDeg)
            put("speedIntegralCorrectionDeg", speed.integralCorrectionDeg)
            put("speedEffectiveTargetDeg", speed.effectiveTargetDeg)
            put("speedTargetSaturated", speed.saturated)
            put("speedTargetSlewLimited", speed.slewLimited)
            put("vmax", config.vmax)
            put("motorControlMode", config.motorControlMode.name)
            put("pwmMax", config.pwmMax)
            put("commandLimit", config.commandLimit)
            put("torqueLimit", config.torqueLimit)
            put("imuTimeoutMs", config.imuTimeoutMs)
            put("fallAngleDeg", config.fallAngleDeg)
            put("fallDurationMs", config.fallDurationMs)
            put("manualTimeoutMs", config.manualTimeoutMs)
            put("controlCommand", balance.lastCommand)
            balance.lastErrorDeg?.let { put("controlErrorDeg", it) }
            balance.controlLatencyMs?.let { put("controlLatencyMs", it) }
            put("inhibitSafetyAutoDisarm", config.inhibitSafetyAutoDisarm)
            balance.faultMessage?.let { put("safetyWarning", it) }
            val nowNs = SystemClock.elapsedRealtimeNanos()
            put("gyroFresh", SafetyRules.isGyroFresh(nowNs, lastGyroReceivedNs, config.imuTimeoutMs))
            lastGyroReceivedNs?.let { timestampNs ->
                put("gyroAgeMs", (nowNs - timestampNs).coerceAtLeast(0L) / 1_000_000.0)
            }
            motors.errorMessage?.let { put("motorError", it) }
            motors.telemetry.forEachIndexed { index, telemetry ->
                put("motor${index}Id", telemetry.servoId)
                put("motor${index}Velocity", telemetry.velocity)
                put("motor${index}Load", telemetry.load)
                put("motor${index}Voltage", telemetry.voltage)
                put("motor${index}Temperature", telemetry.temperatureC)
            }
        }.toString()
    }

    private fun handleWebCommand(command: WebCommandFrame): String {
        val motors = _state.value.motors
        return when (command.type) {
            "connect_usb" -> {
                val deviceId = WebProtocol.payloadInt(command, "deviceId")
                if (deviceId == null) WebProtocol.ack(command.id, false, error = "DEVICE_ID_REQUIRED")
                else {
                    connectMotors(deviceId)
                    WebProtocol.ack(command.id, true, message = "Connexion USB demandée")
                }
            }
            "disconnect_usb" -> {
                disconnectMotors()
                WebProtocol.ack(command.id, true, message = "Déconnexion demandée")
            }
            "scan_bus" -> {
                val deviceId = WebProtocol.payloadInt(command, "deviceId") ?: motors.connectedDeviceId
                if (deviceId == null) WebProtocol.ack(command.id, false, error = "DEVICE_ID_REQUIRED")
                else {
                    scanMotors(deviceId)
                    WebProtocol.ack(command.id, true, message = "Scan demandé")
                }
            }
            "configure_motors" -> {
                configureMotors()
                WebProtocol.ack(command.id, true, message = "Configuration demandée")
            }
            "arm_manual" -> {
                val confirmed = WebProtocol.payloadBoolean(command, "safeTestConfirmed") == true
                armManual(confirmed)
                WebProtocol.ack(command.id, true, message = "Armement demandé")
            }
            "disarm" -> {
                disarmManual()
                WebProtocol.ack(command.id, true, message = "Désarmement demandé")
            }
            "ack_fault" -> {
                acknowledgeMotorFault()
                WebProtocol.ack(command.id, true, message = "Acquittement demandé")
            }
            "set_speed_target" -> {
                val target = WebProtocol.payloadDouble(command, "speedTargetCmPerSec")
                if (target == null) {
                    WebProtocol.ack(command.id, false, error = "SPEED_TARGET_REQUIRED")
                } else {
                    val applied = updateRobotConfig(_state.value.config.copy(speedTargetCmPerSec = target))
                    WebProtocol.ack(
                        command.id,
                        applied,
                        error = if (applied) null else "SPEED_TARGET_REJECTED",
                        message = if (applied) "Consigne vitesse appliquée" else (_state.value.errorMessage ?: "Consigne vitesse refusée"),
                    )
                }
            }
            "update_parameters" -> {
                val axis = WebProtocol.payloadString(command, "axis")
                    ?.let { value -> runCatching { Axis.valueOf(value) }.getOrNull() }
                val config = _state.value.config.copy(
                    axis = axis ?: _state.value.config.axis,
                    imuSign = WebProtocol.payloadInt(command, "imuSign") ?: _state.value.config.imuSign,
                    alpha = WebProtocol.payloadDouble(command, "alpha") ?: _state.value.config.alpha,
                    targetDeg = WebProtocol.payloadDouble(command, "targetDeg") ?: _state.value.config.targetDeg,
                    kp = WebProtocol.payloadDouble(command, "kp") ?: _state.value.config.kp,
                    kd = WebProtocol.payloadDouble(command, "kd") ?: _state.value.config.kd,
                    speedLoopEnabled = WebProtocol.payloadBoolean(command, "speedLoopEnabled")
                        ?: _state.value.config.speedLoopEnabled,
                    speedTargetCmPerSec = WebProtocol.payloadDouble(command, "speedTargetCmPerSec")
                        ?: _state.value.config.speedTargetCmPerSec,
                    speedTargetLimitCmPerSec = WebProtocol.payloadDouble(command, "speedTargetLimitCmPerSec")
                        ?: _state.value.config.speedTargetLimitCmPerSec,
                    speedKevDegPerCmPerSec = WebProtocol.payloadDouble(command, "speedKevDegPerCmPerSec")
                        ?: _state.value.config.speedKevDegPerCmPerSec,
                    speedLoopRateHz = WebProtocol.payloadInt(command, "speedLoopRateHz")
                        ?: _state.value.config.speedLoopRateHz,
                    speedFilterAlpha = WebProtocol.payloadDouble(command, "speedFilterAlpha")
                        ?: _state.value.config.speedFilterAlpha,
                    speedTargetAngleLimitDeg = WebProtocol.payloadDouble(command, "speedTargetAngleLimitDeg")
                        ?: _state.value.config.speedTargetAngleLimitDeg,
                    speedIntegralGainDegPerCmPerSecSec = WebProtocol.payloadDouble(command, "speedIntegralGainDegPerCmPerSecSec")
                        ?: _state.value.config.speedIntegralGainDegPerCmPerSecSec,
                    speedAbsoluteAngleLimitDeg = WebProtocol.payloadDouble(command, "speedAbsoluteAngleLimitDeg")
                        ?: _state.value.config.speedAbsoluteAngleLimitDeg,
                    speedTargetSlewRateDegPerSec = WebProtocol.payloadDouble(command, "speedTargetSlewRateDegPerSec")
                        ?: _state.value.config.speedTargetSlewRateDegPerSec,
                    speedFeedbackTimeoutMs = (
                        WebProtocol.payloadInt(command, "speedFeedbackTimeoutMs")
                            ?: _state.value.config.speedFeedbackTimeoutMs.toInt()
                        ).toLong(),
                    wheelDiameterMm = WebProtocol.payloadDouble(command, "wheelDiameterMm")
                        ?: _state.value.config.wheelDiameterMm,
                    driveRatio = WebProtocol.payloadDouble(command, "driveRatio")
                        ?: _state.value.config.driveRatio,
                    zeroOffsetDeg = WebProtocol.payloadDouble(command, "zeroOffsetDeg") ?: _state.value.config.zeroOffsetDeg,
                    vmax = WebProtocol.payloadInt(command, "vmax") ?: _state.value.config.vmax,
                    motorControlMode = WebProtocol.payloadString(command, "motorControlMode")
                        ?.let { runCatching { MotorControlMode.valueOf(it) }.getOrNull() }
                        ?: _state.value.config.motorControlMode,
                    pwmMax = WebProtocol.payloadInt(command, "pwmMax") ?: _state.value.config.pwmMax,
                    torqueLimit = WebProtocol.payloadInt(command, "torqueLimit") ?: _state.value.config.torqueLimit,
                    imuTimeoutMs = (WebProtocol.payloadInt(command, "imuTimeoutMs") ?: _state.value.config.imuTimeoutMs.toInt()).toLong(),
                    fallAngleDeg = WebProtocol.payloadDouble(command, "fallAngleDeg") ?: _state.value.config.fallAngleDeg,
                    fallDurationMs = (WebProtocol.payloadInt(command, "fallDurationMs") ?: _state.value.config.fallDurationMs.toInt()).toLong(),
                    manualTimeoutMs = (WebProtocol.payloadInt(command, "manualTimeoutMs") ?: _state.value.config.manualTimeoutMs.toInt()).toLong(),
                    inhibitSafetyAutoDisarm = WebProtocol.payloadBoolean(command, "inhibitSafetyAutoDisarm")
                        ?: _state.value.config.inhibitSafetyAutoDisarm,
                )
                val applied = updateRobotConfig(config)
                WebProtocol.ack(
                    command.id,
                    applied,
                    error = if (applied) null else "PARAMETERS_REJECTED",
                    message = if (applied) "Paramètres appliqués" else (_state.value.errorMessage ?: "Paramètres refusés"),
                )
            }
            "arm_balance" -> {
                armBalance(WebProtocol.payloadBoolean(command, "safeTestConfirmed") == true)
                WebProtocol.ack(command.id, true, message = "Armement équilibrage demandé")
            }
            "disarm_balance" -> {
                disarmBalance()
                WebProtocol.ack(command.id, true, message = "Désarmement équilibrage demandé")
            }
            "start_recording" -> {
                startControlRecording()
                WebProtocol.ack(command.id, true, message = "Enregistrement RAM démarré")
            }
            "stop_recording" -> {
                stopControlRecording()
                WebProtocol.ack(command.id, true, message = "Enregistrement RAM arrêté")
            }
            "export_control_log" -> WebProtocol.ack(command.id, true, message = "GET /control-log.csv")
            "manual_command" -> {
                val value = WebProtocol.payloadInt(command, "value")
                val held = WebProtocol.payloadBoolean(command, "held")
                if (value == null || held == null) {
                    WebProtocol.ack(command.id, false, error = "VALUE_AND_HELD_REQUIRED")
                } else {
                    manualCommand(value, held)
                    WebProtocol.ack(command.id, true)
                }
            }
            "step_sequence" -> {
                runManualStepSequence()
                WebProtocol.ack(command.id, true, message = "Séquence demandée")
            }
            else -> WebProtocol.ack(command.id, true)
        }
    }

    private fun localAddress(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .flatMap { it.inetAddresses.toList().asSequence() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress
    }.getOrNull() ?: "127.0.0.1"

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Robot control",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(content: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, RobotControlService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Balancing Robot")
            .setContentText(content)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, "Arrêter", stopIntent)
            .build()
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(content))
    }
}
