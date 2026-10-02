package com.woozie.balancingrobot

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.woozie.balancingrobot.domain.control.pdStep
import com.woozie.balancingrobot.domain.model.Axis
import com.woozie.balancingrobot.domain.model.MotorControlMode
import com.woozie.balancingrobot.domain.model.RobotConfig
import com.woozie.balancingrobot.domain.sensor.ImuRatePolicy
import com.woozie.balancingrobot.motor.AndroidUsbDeviceDetector
import com.woozie.balancingrobot.motor.UsbSerialDeviceInfo
import com.woozie.balancingrobot.service.ImuDiagnosticState
import com.woozie.balancingrobot.service.MotorArmState
import com.woozie.balancingrobot.service.MotorDiagnosticState
import com.woozie.balancingrobot.service.RobotControlService
import com.woozie.balancingrobot.service.RobotServiceState
import com.woozie.balancingrobot.settings.PdSimulationSettings
import com.woozie.balancingrobot.settings.RobotSettings
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.io.File
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private var service: RobotControlService? by mutableStateOf(null)
    private var startError: String? by mutableStateOf(null)
    private var webPortText: String by mutableStateOf(RobotSettings.DEFAULT_WEB_PORT.toString())
    private var imuRateText: String by mutableStateOf(ImuRatePolicy.DEFAULT_HZ.toString())
    private var pdSettings by mutableStateOf(PdSimulationSettings())
    private var robotConfig by mutableStateOf(RobotConfig())
    private var robotConfigSaveJob: Job? = null
    private var usbDevices: List<UsbSerialDeviceInfo> by mutableStateOf(emptyList())
    private var usbScanError: String? by mutableStateOf(null)
    private val usbDetector by lazy { AndroidUsbDeviceDetector(applicationContext) }
    private var bound = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? RobotControlService.LocalBinder)?.service
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        lifecycleScope.launch {
            webPortText = RobotSettings.webPort(this@MainActivity).first().toString()
            imuRateText = RobotSettings.imuRateHz(this@MainActivity).first().toString()
            pdSettings = RobotSettings.pdSimulation(this@MainActivity).first()
            robotConfig = RobotSettings.robotConfig(this@MainActivity).first()
        }

        setContent {
            var state by remember { mutableStateOf(RobotServiceState()) }
            LaunchedEffect(service) {
                service?.state?.collectLatest { state = it }
            }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Lot2Screen(
                        state = state,
                        startError = startError,
                        portText = webPortText,
                        onPortChange = { webPortText = it },
                        imuRateText = imuRateText,
                        onImuRateChange = { imuRateText = it },
                        simulationSettings = pdSettings,
                        robotConfig = if (state.running) state.config else robotConfig,
                        onSaveSimulation = { settings ->
                            pdSettings = settings
                            lifecycleScope.launch { RobotSettings.savePdSimulation(this@MainActivity, settings) }
                        },
                        onApplyRobotConfig = { config ->
                            robotConfig = config
                            robotConfigSaveJob?.cancel()
                            robotConfigSaveJob = lifecycleScope.launch {
                                delay(200)
                                RobotSettings.saveRobotConfig(this@MainActivity, config)
                            }
                            service?.updateRobotConfig(config)
                        },
                        usbDevices = usbDevices,
                        usbScanError = usbScanError,
                        onRefreshUsb = ::refreshUsbDevices,
                        onRequestUsbPermission = ::requestUsbPermission,
                        onStart = { startRobotService(webPortText) },
                        onStop = ::stopRobotService,
                        onExportLog = ::exportLog,
                        onExportMotorLog = ::exportMotorLog,
                        onStartControlRecording = { service?.startControlRecording() },
                        onStopControlRecording = { service?.stopControlRecording() },
                        onExportControlLog = ::exportControlLog,
                        onConnectMotors = { deviceId -> service?.connectMotors(deviceId) },
                        onDisconnectMotors = { service?.disconnectMotors() },
                        onUpdateMotorConfig = { ids, signs, vmax, torqueLimit, controlMode, pwmMax ->
                            service?.updateMotorConfig(ids, signs, vmax, torqueLimit, controlMode, pwmMax)
                        },
                        onConfigureMotors = { service?.configureMotors() },
                        onScanMotors = { deviceId -> service?.scanMotors(deviceId) },
                        onArmManual = { confirmed -> service?.armManual(confirmed) },
                        onDisarmManual = { service?.disarmManual() },
                        onAcknowledgeMotorFault = { service?.acknowledgeMotorFault() },
                        onManualCommand = { value, held -> service?.manualCommand(value, held) },
                        onRunStepSequence = { service?.runManualStepSequence() },
                        onArmBalance = { confirmed -> service?.armBalance(confirmed) },
                        onDisarmBalance = { service?.disarmBalance() },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!bound) {
            bindService(
                Intent(this, RobotControlService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }
        refreshUsbDevices()
    }

    override fun onStop() {
        if (bound) {
            unbindService(connection)
            bound = false
            service = null
        }
        super.onStop()
    }

    private fun startRobotService(portText: String) {
        startError = null
        val port = portText.toIntOrNull()
        if (port == null || port !in 1024..65535) {
            startError = "Port invalide : utiliser une valeur entre 1024 et 65535"
            return
        }
        val imuRateHz = imuRateText.toIntOrNull()
        if (imuRateHz == null || !ImuRatePolicy.isValid(imuRateHz)) {
            startError = "Fréquence IMU invalide : utiliser ${ImuRatePolicy.MIN_HZ} à " +
                "${ImuRatePolicy.MAX_HZ} Hz"
            return
        }
        lifecycleScope.launch {
            RobotSettings.saveWebPort(this@MainActivity, port)
            RobotSettings.saveImuRateHz(this@MainActivity, imuRateHz)
        }
        val intent = Intent(this, RobotControlService::class.java)
            .putExtra(RobotControlService.EXTRA_PORT, port)
            .putExtra(RobotControlService.EXTRA_IMU_RATE_HZ, imuRateHz)
        try {
            ContextCompat.startForegroundService(this, intent)
            if (!bound) {
                bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }
        } catch (error: Exception) {
            startError = "Démarrage impossible (${error.javaClass.simpleName})" +
                (error.message?.let { ": $it" } ?: "")
        }
    }

    private fun stopRobotService() {
        service?.stopRobot() ?: stopService(Intent(this, RobotControlService::class.java))
    }

    private fun exportLog() {
        val csv = service?.imuLogCsv() ?: return
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/csv")
                    .putExtra(Intent.EXTRA_SUBJECT, "balancing-robot-imu.csv")
                    .putExtra(Intent.EXTRA_TEXT, csv),
                "Exporter le journal IMU",
            ),
        )
    }

    private fun exportMotorLog() {
        val csv = service?.motorLogCsv() ?: return
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/csv")
                    .putExtra(Intent.EXTRA_SUBJECT, "balancing-robot-motors.csv")
                    .putExtra(Intent.EXTRA_TEXT, csv),
                "Exporter le journal moteurs",
            ),
        )
    }

    private fun exportControlLog() {
        val csv = service?.controlLogCsv() ?: return
        val file = File(cacheDir, "balancing-robot-control-session.csv")
        runCatching { file.writeText(csv, Charsets.UTF_8) }.getOrNull() ?: return
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/csv")
                    .putExtra(Intent.EXTRA_SUBJECT, "balancing-robot-control-session.csv")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Exporter la session de contrôle",
            ),
        )
    }

    private fun refreshUsbDevices() {
        usbScanError = null
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { usbDetector.enumerate() }
            }
            result
                .onSuccess { usbDevices = it }
                .onFailure { error ->
                    usbDevices = emptyList()
                    usbScanError = "Détection USB impossible (${error.javaClass.simpleName})"
                }
        }
    }

    private fun requestUsbPermission(deviceId: Int) {
        usbDetector.requestPermission(deviceId)
        lifecycleScope.launch {
            delay(750)
            refreshUsbDevices()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun Lot2Screen(
    state: RobotServiceState,
    startError: String?,
    portText: String,
    onPortChange: (String) -> Unit,
    imuRateText: String,
    onImuRateChange: (String) -> Unit,
    simulationSettings: PdSimulationSettings,
    robotConfig: RobotConfig,
    onSaveSimulation: (PdSimulationSettings) -> Unit,
    onApplyRobotConfig: (RobotConfig) -> Unit,
    usbDevices: List<UsbSerialDeviceInfo>,
    usbScanError: String?,
    onRefreshUsb: () -> Unit,
    onRequestUsbPermission: (Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onExportLog: () -> Unit,
    onExportMotorLog: () -> Unit,
    onStartControlRecording: () -> Unit,
    onStopControlRecording: () -> Unit,
    onExportControlLog: () -> Unit,
    onConnectMotors: (Int) -> Unit,
    onDisconnectMotors: () -> Unit,
    onUpdateMotorConfig: (List<Int>, List<Int>, Int, Int, MotorControlMode, Int) -> Unit,
    onConfigureMotors: () -> Unit,
    onScanMotors: (Int) -> Unit,
    onArmManual: (Boolean) -> Unit,
    onDisarmManual: () -> Unit,
    onAcknowledgeMotorFault: () -> Unit,
    onManualCommand: (Int, Boolean) -> Unit,
    onRunStepSequence: () -> Unit,
    onArmBalance: (Boolean) -> Unit,
    onDisarmBalance: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(0) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Top,
        // The diagnostic list can grow when several USB adapters are attached.
        // Keep the controls usable on small phone screens.
    ) {
        Text("Balancing Robot", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(if (state.running) "Service actif" else "Service arrêté")
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = portText,
            onValueChange = { value ->
                if (value.all(Char::isDigit) && value.length <= 5) onPortChange(value)
            },
            label = { Text("Port HTTP/WebSocket") },
            supportingText = { Text("Par défaut : ${RobotSettings.DEFAULT_WEB_PORT}") },
            singleLine = true,
            enabled = !state.running,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = imuRateText,
            onValueChange = { value ->
                if (value.all(Char::isDigit) && value.length <= 3) onImuRateChange(value)
            },
            label = { Text("Fréquence IMU demandée (Hz)") },
            supportingText = {
                Text("Par défaut : ${ImuRatePolicy.DEFAULT_HZ} Hz · plage ${ImuRatePolicy.MIN_HZ}-${ImuRatePolicy.MAX_HZ}")
            },
            singleLine = true,
            enabled = !state.running,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        state.serverUrl?.let { url ->
            Spacer(Modifier.height(8.dp))
            Text("Interface locale : $url")
        }
        (state.errorMessage ?: startError)?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onStart,
            enabled = !state.running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Démarrer le service")
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onStop,
            enabled = state.running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Arrêter le service")
        }
        Spacer(Modifier.height(24.dp))
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            listOf("Serveur", "IMU", "Simulation PD", "Moteurs", "Réglages").forEachIndexed { index, label ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        if (selectedTab == 0) {
            Text("Diagnostic USB", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onRefreshUsb,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Actualiser les périphériques")
        }
        usbScanError?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }
        if (usbDevices.isEmpty() && usbScanError == null) {
            Spacer(Modifier.height(8.dp))
            Text("Aucun périphérique USB série détecté.")
        }
        usbDevices.forEach { device ->
            Spacer(Modifier.height(12.dp))
            Text(
                "${device.deviceName} — " +
                    if (device.isCh340) "CH340 reconnu" else "USB série détecté",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "VID/PID %04X:%04X • Pilote ${device.driverName}".format(
                    device.vendorId,
                    device.productId,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Autorisation USB : ${if (device.permissionGranted) "accordée" else "non accordée"}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!device.permissionGranted) {
                OutlinedButton(onClick = { onRequestUsbPermission(device.deviceId) }) {
                    Text("Autoriser cet adaptateur")
                }
            }
        }
        }
        if (selectedTab == 1) {
            ImuDiagnosticCard(
                state = state.imu,
                controlRecording = state.controlRecording,
                onExportLog = onExportLog,
                onStartControlRecording = onStartControlRecording,
                onStopControlRecording = onStopControlRecording,
                onExportControlLog = onExportControlLog,
            )
            Spacer(Modifier.height(12.dp))
            ImuTraceChart(state.imu.trace)
        }
        if (selectedTab == 2) {
            PdSimulationCard(simulationSettings, onSaveSimulation)
        }
        if (selectedTab == 3) {
            MotorDiagnosticCard(
                usbDevices = usbDevices,
                state = state.motors,
                serviceRunning = state.running,
                onConnectMotors = onConnectMotors,
                onDisconnectMotors = onDisconnectMotors,
                onUpdateMotorConfig = onUpdateMotorConfig,
                onConfigureMotors = onConfigureMotors,
                onExportMotorLog = onExportMotorLog,
                onScanMotors = onScanMotors,
                onArmManual = onArmManual,
                onDisarmManual = onDisarmManual,
                onAcknowledgeMotorFault = onAcknowledgeMotorFault,
                onManualCommand = onManualCommand,
                onRunStepSequence = onRunStepSequence,
            )
        }
        if (selectedTab == 4) {
            BalanceTuningCard(
                config = robotConfig,
                armState = state.motors.armState,
                balance = state.balance,
                onApply = onApplyRobotConfig,
                onArmBalance = onArmBalance,
                onDisarmBalance = onDisarmBalance,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Lot 5 : boucle PD raccordable uniquement après armement explicite. " +
                "Watchdog IMU et arrêt sûr restent actifs.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ImuDiagnosticCard(
    state: ImuDiagnosticState,
    controlRecording: com.woozie.balancingrobot.service.ControlRecordingState,
    onExportLog: () -> Unit,
    onStartControlRecording: () -> Unit,
    onStopControlRecording: () -> Unit,
    onExportControlLog: () -> Unit,
) {
    Text("Diagnostic IMU", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(state.status)
    Text(
        "Capteurs : accéléromètre ${if (state.accelerometerAvailable) "présent" else "absent"} · " +
            "gyroscope ${if (state.gyroscopeAvailable) "présent" else "absent"}",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Accel [m/s²] : ${number(state.accelerometerX)} / ${number(state.accelerometerY)} / " +
            number(state.accelerometerZ),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Gyro [°/s] : ${number(state.gyroXDegPerSec)} / ${number(state.gyroYDegPerSec)} / " +
            number(state.gyroZDegPerSec),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Angle accel : ${number(state.accelAngleDeg)}° · angle filtré : ${number(state.estimatedAngleDeg)}°",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Demande : ${state.requestedRateHz} Hz · dt : ${number(state.dtMs)} ms · cadence accel : ${number(state.accelRateHz)} Hz · " +
            "gyro : ${number(state.gyroRateHz)} Hz",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Jitter accel/gyro : ${number(state.accelJitterMs)} / ${number(state.gyroJitterMs)} ms · " +
            "échantillons acceptés/rejetés : ${state.acceptedSamples}/${state.rejectedSamples}",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Journal IMU en mémoire : ${state.journalSamples} échantillons",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onExportLog, enabled = state.journalSamples > 0) {
        Text("Exporter le journal CSV")
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Session de contrôle RAM : ${controlRecording.samples} échantillons" +
            if (controlRecording.recording) " · enregistrement actif" else " · arrêtée",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onStartControlRecording,
            enabled = !controlRecording.recording,
        ) { Text("Démarrer capture") }
        OutlinedButton(
            onClick = onStopControlRecording,
            enabled = controlRecording.recording,
        ) { Text("Arrêter capture") }
    }
    OutlinedButton(
        onClick = onExportControlLog,
        enabled = !controlRecording.recording && controlRecording.samples > 0,
    ) { Text("Télécharger session CSV") }
}

@Composable
private fun MotorDiagnosticCard(
    usbDevices: List<UsbSerialDeviceInfo>,
    state: MotorDiagnosticState,
    serviceRunning: Boolean,
    onConnectMotors: (Int) -> Unit,
    onDisconnectMotors: () -> Unit,
    onUpdateMotorConfig: (List<Int>, List<Int>, Int, Int, MotorControlMode, Int) -> Unit,
    onConfigureMotors: () -> Unit,
    onExportMotorLog: () -> Unit,
    onScanMotors: (Int) -> Unit,
    onArmManual: (Boolean) -> Unit,
    onDisarmManual: () -> Unit,
    onAcknowledgeMotorFault: () -> Unit,
    onManualCommand: (Int, Boolean) -> Unit,
    onRunStepSequence: () -> Unit,
) {
    var safeTestConfirmed by remember(state.connectedDeviceId) { mutableStateOf(false) }
    var idsText by remember(state.requiredIds) { mutableStateOf(state.requiredIds.joinToString(",")) }
    var signsText by remember(state.motorSigns) { mutableStateOf(state.motorSigns.joinToString(",")) }
    var vmaxText by remember(state.vmax) { mutableStateOf(state.vmax.toString()) }
    var pwmMaxText by remember(state.pwmMax) { mutableStateOf(state.pwmMax.toString()) }
    var torqueText by remember(state.torqueLimit) { mutableStateOf(state.torqueLimit.toString()) }
    var controlMode by remember(state.controlMode) { mutableStateOf(state.controlMode) }
    val modeEditable = state.armState != MotorArmState.MANUAL_ARMED &&
        state.armState != MotorArmState.BALANCE_ARMED &&
        state.armState != MotorArmState.FAULT_LATCHED
    Text("Moteurs manuels", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "Mode diagnostic distinct du PD. L'ouverture USB, la configuration, " +
            "l'armement et chaque commande moteur exigent une action explicite.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    Text("Bus cible : CH340 · 1 000 000 bauds · STS3215", style = MaterialTheme.typography.bodySmall)
    Text(
        "IDs attendus : ${state.requiredIds.joinToString()} · " +
            "mode ${state.controlMode.label} · ${if (state.controlMode == MotorControlMode.PWM) "PWM goal 44" else "Goal_Velocity 46"} · " +
            "signes ${state.motorSigns.joinToString()}",
        style = MaterialTheme.typography.bodySmall,
    )
    Text("État : ${state.armState.name} · commande ${state.manualCommand} · deadman ${if (state.deadmanHeld) "tenu" else "relâché"}", style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    if (usbDevices.isEmpty()) {
        Text("Aucun adaptateur USB série disponible pour un scan.", style = MaterialTheme.typography.bodySmall)
    } else {
        usbDevices.forEach { device ->
            Text(
                "${device.deviceName} · ${device.driverName} · " +
                    "permission ${if (device.permissionGranted) "accordée" else "requise"}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (device.permissionGranted) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onConnectMotors(device.deviceId) },
                        enabled = serviceRunning && !state.connectInProgress && !state.connected,
                    ) {
                        Text(if (state.connectInProgress) "Connexion…" else "Connecter")
                    }
                    if (state.connected && state.connectedDeviceId == device.deviceId) {
                        OutlinedButton(onClick = onDisconnectMotors, enabled = state.armState != MotorArmState.MANUAL_ARMED) {
                            Text("Déconnecter")
                        }
                    }
                }
                OutlinedButton(
                    onClick = { onScanMotors(device.deviceId) },
                    enabled = serviceRunning && state.connected && state.armState != MotorArmState.MANUAL_ARMED && !state.scanInProgress,
                ) {
                    Text(if (state.scanInProgress) "Scan en cours…" else "Scanner les IDs 1–20")
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        if (state.qualified && state.scannedIds.isNotEmpty()) "Groupe qualifié : tous les moteurs attendus répondent."
        else if (state.qualified) "IDs configurés explicitement : présence non vérifiée par scan."
        else "Groupe non qualifié : le scan doit trouver tous les IDs attendus.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (state.scannedIds.isNotEmpty()) {
        Text("IDs détectés : ${state.scannedIds.joinToString()}", style = MaterialTheme.typography.bodySmall)
    }
    SimulationField("IDs moteurs (ex. 6,7)", idsText) { idsText = it }
    SimulationField("Signes (ex. 1,1)", signsText) { signsText = it }
    Text("Mode de commande", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (controlMode == MotorControlMode.VELOCITY) {
            Button(onClick = { controlMode = MotorControlMode.VELOCITY }, enabled = modeEditable) {
                Text("Vitesse")
            }
        } else {
            OutlinedButton(onClick = { controlMode = MotorControlMode.VELOCITY }, enabled = modeEditable) {
                Text("Vitesse")
            }
        }
        if (controlMode == MotorControlMode.PWM) {
            Button(onClick = { controlMode = MotorControlMode.PWM }, enabled = modeEditable) {
                Text("PWM")
            }
        } else {
            OutlinedButton(onClick = { controlMode = MotorControlMode.PWM }, enabled = modeEditable) {
                Text("PWM")
            }
        }
    }
    SimulationField("Vitesse max", vmaxText) { vmaxText = it }
    SimulationField("PWM max (0..1000)", pwmMaxText) { pwmMaxText = it }
    SimulationField("Limite couple", torqueText) { torqueText = it }
    OutlinedButton(
        onClick = {
            val ids = idsText.split(',').mapNotNull { it.trim().toIntOrNull() }
            val signs = signsText.split(',').mapNotNull { it.trim().toIntOrNull() }
            val vmax = vmaxText.toIntOrNull() ?: -1
            val pwmMax = pwmMaxText.toIntOrNull() ?: -1
            val torque = torqueText.toIntOrNull() ?: -1
            onUpdateMotorConfig(ids, signs, vmax, torque, controlMode, pwmMax)
        },
        enabled = modeEditable,
    ) {
        Text("Appliquer la configuration")
    }
    OutlinedButton(
        onClick = onConfigureMotors,
        enabled = serviceRunning && state.connected && state.armState != MotorArmState.MANUAL_ARMED &&
            state.armState != MotorArmState.FAULT_LATCHED,
    ) {
        Text(if (state.configured) "Reconfigurer le mode ${state.controlMode.label}" else "Configurer le mode ${state.controlMode.label}")
    }
    Text(
        "Configuration : ${if (state.configured) "mode ${state.controlMode.label} prêt, couple coupé" else "non effectuée"} · " +
            if (state.scannedIds.isEmpty()) "IDs fournis explicitement, scan facultatif" else "IDs vérifiés par scan",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked = safeTestConfirmed, onCheckedChange = { safeTestConfirmed = it })
        Text("Roues levées, alimentation coupable rapidement", style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { onArmManual(safeTestConfirmed) },
            enabled = serviceRunning && state.armState == MotorArmState.READY && safeTestConfirmed,
        ) {
            Text("Armer manuel")
        }
        OutlinedButton(
            onClick = onDisarmManual,
            enabled = state.armState == MotorArmState.MANUAL_ARMED || state.armState == MotorArmState.FAULT_LATCHED,
        ) {
            Text("Désarmer")
        }
        if (state.armState == MotorArmState.FAULT_LATCHED) {
            OutlinedButton(onClick = onAcknowledgeMotorFault) {
                Text("Acquitter défaut")
            }
        }
    }
    Text("Maintenir un bouton enfoncé pour renouveler le deadman. Relâcher envoie zéro.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(500, 1000, 2000).forEach { value ->
            HoldMotorButton(
                label = "+$value",
                value = value,
                enabled = state.armState == MotorArmState.MANUAL_ARMED && !state.stepInProgress,
                onManualCommand = onManualCommand,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(-500, -1000, -2000).forEach { value ->
            HoldMotorButton(
                label = "$value",
                value = value,
                enabled = state.armState == MotorArmState.MANUAL_ARMED && !state.stepInProgress,
                onManualCommand = onManualCommand,
            )
        }
    }
    OutlinedButton(
        onClick = onRunStepSequence,
        enabled = state.armState == MotorArmState.MANUAL_ARMED && !state.stepInProgress,
    ) {
        Text(
            if (state.stepInProgress) "Paliers en cours…"
            else if (state.controlMode == MotorControlMode.PWM) "Exécuter les paliers PWM 0/83/166/333/666/1000"
            else "Exécuter les paliers 0/500/1000/2000/4000/6000",
        )
    }
    OutlinedButton(onClick = onExportMotorLog, enabled = state.ioMetrics.telemetryFrames > 0) {
        Text("Exporter le journal moteurs CSV")
    }
    Text(
        "I/O : ${state.ioMetrics.writtenFrames} écritures (cible 200 Hz) · " +
            "${state.ioMetrics.telemetryFrames} télémétries · " +
            "${state.ioMetrics.supersededFrames} commandes remplacées · " +
            "${state.ioMetrics.busBusySkips} créneaux occupés",
        style = MaterialTheme.typography.bodySmall,
    )
    state.telemetry.forEach { telemetry ->
        Text(
            "ID ${telemetry.servoId} · vitesse ${telemetry.velocity} · charge ${telemetry.load} · " +
                "${number(telemetry.voltage)} V · ${telemetry.temperatureC} °C",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun BalanceTuningCard(
    config: RobotConfig,
    armState: MotorArmState,
    balance: com.woozie.balancingrobot.service.BalanceDiagnosticState,
    onApply: (RobotConfig) -> Unit,
    onArmBalance: (Boolean) -> Unit,
    onDisarmBalance: () -> Unit,
) {
    var draft by remember(config) { mutableStateOf(config) }
    var safeTestConfirmed by remember { mutableStateOf(false) }
    val liveEditable = armState != MotorArmState.MANUAL_ARMED &&
        armState != MotorArmState.FAULT_LATCHED
    val guardedEditable = liveEditable && armState != MotorArmState.BALANCE_ARMED

    fun change(next: RobotConfig) {
        draft = next
        onApply(next)
    }

    Text("Lot 5 · boucle d'équilibrage", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "Alpha, cible, Kp et Kd sont appliqués en direct, même pendant " +
            "l'équilibrage. Les paramètres structurels et de sécurité restent " +
            "verrouillés une fois armé.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { change(draft.copy(axis = Axis.entries[(draft.axis.ordinal + 1) % Axis.entries.size])) },
        enabled = guardedEditable,
    ) {
        Text("Axe : ${draft.axis.name} (changer)")
    }
    OutlinedButton(onClick = { change(draft.copy(imuSign = -draft.imuSign)) }, enabled = guardedEditable) {
        Text("Signe IMU : ${if (draft.imuSign > 0) "+1" else "-1"}")
    }
    ParameterSlider("Offset zéro (°)", draft.zeroOffsetDeg, -180f..180f, 359, guardedEditable) {
        change(draft.copy(zeroOffsetDeg = it.toDouble()))
    }
    ParameterSlider("Alpha filtre complémentaire", draft.alpha, 0f..1f, 99, liveEditable) {
        change(draft.copy(alpha = it.toDouble()))
    }
    ParameterSlider(
        "Cible (°) · ±15° en équilibrage",
        draft.targetDeg,
        if (armState == MotorArmState.BALANCE_ARMED) -15f..15f else -180f..180f,
        if (armState == MotorArmState.BALANCE_ARMED) 59 else 359,
        liveEditable,
    ) {
        change(draft.copy(targetDeg = it.toDouble()))
    }
    ParameterSlider("Kp", draft.kp, 0f..2000f, 199, liveEditable) {
        change(draft.copy(kp = it.toDouble()))
    }
    ParameterSlider("Kd", draft.kd, 0f..2000f, 199, liveEditable) {
        change(draft.copy(kd = it.toDouble()))
    }
    ParameterSlider("Vitesse maximale", draft.vmax.toFloat(), 0f..20000f, 199, guardedEditable) {
        change(draft.copy(vmax = it.toInt()))
    }
    ParameterSlider("Limite de couple", draft.torqueLimit.toFloat(), 0f..1023f, 102, guardedEditable) {
        change(draft.copy(torqueLimit = it.toInt()))
    }
    ParameterSlider("Timeout IMU (ms)", draft.imuTimeoutMs.toFloat(), 20f..1000f, 98, guardedEditable) {
        change(draft.copy(imuTimeoutMs = it.toLong()))
    }
    ParameterSlider("Angle de chute (°)", draft.fallAngleDeg, 5f..90f, 85, guardedEditable) {
        change(draft.copy(fallAngleDeg = it.toDouble()))
    }
    ParameterSlider("Durée chute (ms)", draft.fallDurationMs.toFloat(), 20f..1000f, 98, guardedEditable) {
        change(draft.copy(fallDurationMs = it.toLong()))
    }
    ParameterSlider("Timeout manuel (ms)", draft.manualTimeoutMs.toFloat(), 100f..2000f, 95, guardedEditable) {
        change(draft.copy(manualTimeoutMs = it.toLong()))
    }
    Text("Réglages modifiés en direct · aucune validation supplémentaire nécessaire", style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked = safeTestConfirmed, onCheckedChange = { safeTestConfirmed = it })
        Text("Roues levées et coupure d'urgence accessible", style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { onArmBalance(safeTestConfirmed) },
            enabled = armState == MotorArmState.READY && safeTestConfirmed && abs(draft.targetDeg) <= 15.0,
        ) { Text("Armer équilibrage") }
        OutlinedButton(
            onClick = onDisarmBalance,
            enabled = armState == MotorArmState.BALANCE_ARMED,
        ) { Text("Désarmer") }
    }
    Text("État moteur : ${armState.name}", style = MaterialTheme.typography.bodySmall)
    Text(
        "PD : erreur ${number(balance.lastErrorDeg)}° · commande ${balance.lastCommand}" +
            " · latence ${number(balance.controlLatencyMs)} ms${if (balance.saturated) " · SATURÉ" else ""}",
        style = MaterialTheme.typography.bodySmall,
    )
    balance.faultMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun ParameterSlider(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    ParameterSlider(label, value.toFloat(), range, steps, enabled, onValueChange)
}

@Composable
private fun ParameterSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    var text by remember(label) { mutableStateOf(formatParameter(value)) }
    var editing by remember(label) { mutableStateOf(false) }
    LaunchedEffect(value) {
        if (!editing) text = formatParameter(value)
    }

    fun commitText() {
        val parsed = text.replace(',', '.').toFloatOrNull()
        if (parsed == null) {
            text = formatParameter(value)
        } else {
            val bounded = parsed.coerceIn(range.start, range.endInclusive)
            text = formatParameter(bounded)
            onValueChange(bounded)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { commitText() }),
            modifier = Modifier
                .width(124.dp)
                .onFocusChanged { focus ->
                    editing = focus.isFocused
                    if (!focus.isFocused) commitText()
                },
        )
    }
    Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onValueChange,
        valueRange = range,
        steps = steps,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun formatParameter(value: Float): String =
    String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')

@Composable
private fun HoldMotorButton(
    label: String,
    value: Int,
    enabled: Boolean,
    onManualCommand: (Int, Boolean) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(pressed, enabled, value) {
        if (!pressed || !enabled) {
            onManualCommand(0, false)
            return@LaunchedEffect
        }
        while (true) {
            onManualCommand(value, true)
            kotlinx.coroutines.delay(100)
        }
    }
    OutlinedButton(
        onClick = { },
        enabled = enabled,
        modifier = Modifier.pointerInput(enabled, value) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!enabled) return@awaitEachGesture
                pressed = true
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                    }
                } finally {
                    pressed = false
                }
            }
        },
    ) {
        Text(label)
    }
}

@Composable
private fun PdSimulationCard(settings: PdSimulationSettings, onSave: (PdSimulationSettings) -> Unit) {
    var target by remember(settings) { mutableStateOf(settings.targetDeg.toString()) }
    var angle by remember(settings) { mutableStateOf(settings.angleDeg.toString()) }
    var gyro by remember(settings) { mutableStateOf(settings.gyroDps.toString()) }
    var kp by remember(settings) { mutableStateOf(settings.kp.toString()) }
    var kd by remember(settings) { mutableStateOf(settings.kd.toString()) }
    var result by remember { mutableStateOf("Aucun calcul") }

    Text("Simulation PD", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "Calcule une consigne en mémoire uniquement. Les moteurs restent désactivés.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    SimulationField("Cible (°)", target) { target = it }
    SimulationField("Angle (°)", angle) { angle = it }
    SimulationField("Gyro (°/s)", gyro) { gyro = it }
    SimulationField("Kp", kp) { kp = it }
    SimulationField("Kd", kd) { kd = it }
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            val parsed = listOf(target, angle, gyro, kp, kd).map(String::toDoubleOrNull)
            if (parsed.any { it == null }) {
                result = "Valeur invalide"
            } else {
                val (targetValue, angleValue, gyroValue, kpValue, kdValue) = parsed.map { it!! }
                val output = pdStep(
                    targetValue,
                    angleValue,
                    gyroValue,
                    kpValue,
                    kdValue,
                    vmax = 6000,
                )
                result = "erreur ${number(output.errorDeg)}° · brut ${number(output.rawCommand)} · " +
                    "borné ${output.boundedCommand} · moteurs simulés [${output.boundedCommand}, " +
                    "${output.boundedCommand}]${if (output.saturated) " · SATURÉ" else ""}"
                onSave(PdSimulationSettings(targetValue, angleValue, gyroValue, kpValue, kdValue))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Calculer la commande")
    }
    Spacer(Modifier.height(8.dp))
    Text(result, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ImuTraceChart(points: List<com.woozie.balancingrobot.service.ImuTracePoint>) {
    Text("Courbe angle accel / filtré", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
    ) {
        if (points.size < 2) return@Canvas
        val values = points.flatMap { listOf(it.accelAngleDeg, it.estimatedAngleDeg) }
        val minValue = values.minOrNull() ?: -1.0
        val maxValue = values.maxOrNull() ?: 1.0
        val range = (maxValue - minValue).takeIf { it > 0.001 } ?: 1.0
        fun point(index: Int, value: Double): androidx.compose.ui.geometry.Offset {
            val x = index.toFloat() / (points.size - 1).toFloat() * size.width
            val y = (1.0 - (value - minValue) / range).toFloat() * size.height
            return androidx.compose.ui.geometry.Offset(x, y)
        }
        points.zipWithNext().forEachIndexed { index, (from, to) ->
            drawLine(
                color = androidx.compose.ui.graphics.Color(0xFF6750A4),
                start = point(index, from.accelAngleDeg),
                end = point(index + 1, to.accelAngleDeg),
                strokeWidth = 3f,
            )
            drawLine(
                color = androidx.compose.ui.graphics.Color(0xFF2E7D32),
                start = point(index, from.estimatedAngleDeg),
                end = point(index + 1, to.estimatedAngleDeg),
                strokeWidth = 3f,
            )
        }
    }
    Text("Violet : accel · vert : filtré", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SimulationField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    )
}

private fun number(value: Double?): String = value?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
