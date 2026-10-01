const log = document.getElementById('log');
const write = (value) => { log.textContent += `${log.textContent ? '\n' : ''}${value}`; };
const text = (id, value) => { document.getElementById(id).textContent = value; };
const number = (value, unit = '') =>
  value === undefined || value === null ? '—' : `${Number(value).toFixed(2)}${unit}`;

const renderDiagnostics = (diagnostics) => {
  text('status', diagnostics.status || (diagnostics.serviceRunning ? 'Service actif' : 'Service arrêté'));
  text('requested-rate', `${diagnostics.requestedRateHz ?? '—'} Hz`);
  text('rates', `${number(diagnostics.accelRateHz, ' Hz')} / ${number(diagnostics.gyroRateHz, ' Hz')}`);
  text('angles', `${number(diagnostics.accelAngleDeg, '°')} / ${number(diagnostics.estimatedAngleDeg, '°')}`);
  text('dt', number(diagnostics.dtMs, ' ms'));
  text('jitter', `${number(diagnostics.accelJitterMs, ' ms')} / ${number(diagnostics.gyroJitterMs, ' ms')}`);
  text('samples', `${diagnostics.acceptedSamples ?? '—'} / ${diagnostics.rejectedSamples ?? '—'}`);
  text('journal', `${diagnostics.journalSamples ?? '—'} échantillons`);
  text('control-recording', diagnostics.controlRecording ? 'active' : 'arrêtée');
  text('control-recording-samples', diagnostics.controlRecordingSamples ?? 0);
  document.getElementById('start-recording').disabled = Boolean(diagnostics.controlRecording);
  document.getElementById('stop-recording').disabled = !diagnostics.controlRecording;
  text('motor-state', `${diagnostics.motorArmState ?? '—'} · ${diagnostics.motorConnected ? 'USB connecté' : 'USB arrêté'} · ${diagnostics.motorQualified ? 'groupe qualifié' : 'groupe non qualifié'}`);
  text('motor-command', `${diagnostics.motorCommand ?? '—'} · deadman ${diagnostics.motorDeadmanHeld ? 'tenu' : 'relâché'}`);
  text('motor-telemetry', `${diagnostics.motorTelemetryCount ?? 0} moteur(s) télémétrés`);
  const balanceArmed = diagnostics.motorArmState === 'BALANCE_ARMED';
  ['axis', 'imu-sign', 'zero-offset', 'vmax', 'torque-limit', 'imu-timeout',
    'fall-angle', 'fall-duration', 'manual-timeout'].forEach((id) => {
    const input = document.getElementById(id);
    if (input) input.disabled = balanceArmed;
  });
  if (diagnostics.axis) {
    const axis = document.getElementById('axis');
    if (axis && document.activeElement !== axis) axis.value = diagnostics.axis;
  }
  [['imu-sign', diagnostics.imuSign], ['alpha', diagnostics.alpha], ['target', diagnostics.targetDeg],
    ['kp', diagnostics.kp], ['kd', diagnostics.kd], ['zero-offset', diagnostics.zeroOffsetDeg],
    ['vmax', diagnostics.vmax], ['torque-limit', diagnostics.torqueLimit],
    ['imu-timeout', diagnostics.imuTimeoutMs], ['fall-angle', diagnostics.fallAngleDeg],
    ['fall-duration', diagnostics.fallDurationMs], ['manual-timeout', diagnostics.manualTimeoutMs]].forEach(([id, value]) => {
    if (value === undefined || value === null) return;
    const input = document.getElementById(id);
    const output = document.getElementById(`${id}-value`);
    if (input && document.activeElement !== input) input.value = value;
    if (output) output.textContent = id === 'imu-sign' ? (Number(value) > 0 ? '+1' : '-1') : Number(value).toFixed(2);
  });
};

const refresh = () => fetch('/diagnostics')
  .then((response) => response.json())
  .then(renderDiagnostics)
  .catch((error) => write(`diagnostic error: ${error}`));

fetch('/health')
  .then((response) => response.json())
  .then((health) => write(`health: ${JSON.stringify(health)}`))
  .catch((error) => write(`health error: ${error}`));

refresh();
window.setInterval(refresh, 1000);

const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
const socket = new WebSocket(`${scheme}://${window.location.host}/ws`);
socket.addEventListener('open', () => {
  write('websocket: connecté');
  socket.send(JSON.stringify({ v: 1, id: 'web-diagnostic', type: 'smoke', payload: {} }));
});
socket.addEventListener('message', (event) => write(`ws: ${event.data}`));
socket.addEventListener('error', () => write('websocket: erreur'));

const sendCommand = (type, payload = {}) => {
  const id = `web-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  if (socket.readyState !== WebSocket.OPEN) {
    write('commande ignorée : WebSocket non connecté');
    return;
  }
  socket.send(JSON.stringify({ v: 1, id, type, payload }));
};

const currentParameters = () => ({
  axis: document.getElementById('axis').value,
  imuSign: Number(document.getElementById('imu-sign').value),
  alpha: Number(document.getElementById('alpha').value),
  targetDeg: Number(document.getElementById('target').value),
  kp: Number(document.getElementById('kp').value),
  kd: Number(document.getElementById('kd').value),
  zeroOffsetDeg: Number(document.getElementById('zero-offset').value),
  vmax: Number(document.getElementById('vmax').value),
  torqueLimit: Number(document.getElementById('torque-limit').value),
  imuTimeoutMs: Number(document.getElementById('imu-timeout').value),
  fallAngleDeg: Number(document.getElementById('fall-angle').value),
  fallDurationMs: Number(document.getElementById('fall-duration').value),
  manualTimeoutMs: Number(document.getElementById('manual-timeout').value),
});

let parameterUpdateTimer = null;
const scheduleParameterUpdate = () => {
  if (parameterUpdateTimer !== null) window.clearTimeout(parameterUpdateTimer);
  parameterUpdateTimer = window.setTimeout(() => {
    parameterUpdateTimer = null;
    sendCommand('update_parameters', currentParameters());
  }, 80);
};

const deviceId = () => Number(document.getElementById('usb-device-id').value || 0);
document.getElementById('start-recording').addEventListener('click', () => sendCommand('start_recording'));
document.getElementById('stop-recording').addEventListener('click', () => sendCommand('stop_recording'));
document.querySelectorAll('[data-command]').forEach((button) => {
  button.addEventListener('click', () => {
    const type = button.dataset.command;
    if (type === 'connect_usb' || type === 'scan_bus') sendCommand(type, { deviceId: deviceId() });
    else if (type === 'arm_manual') sendCommand(type, { safeTestConfirmed: document.getElementById('safe-test').checked });
    else if (type === 'arm_balance') sendCommand(type, { safeTestConfirmed: document.getElementById('safe-test').checked });
    else sendCommand(type);
  });
});

['imu-sign', 'alpha', 'target', 'kp', 'kd', 'zero-offset', 'vmax', 'torque-limit',
  'imu-timeout', 'fall-angle', 'fall-duration', 'manual-timeout'].forEach((id) => {
  const input = document.getElementById(id);
  const output = document.getElementById(`${id}-value`);
  input.addEventListener('input', () => {
    output.textContent = id === 'imu-sign' ? (Number(input.value) > 0 ? '+1' : '-1') : Number(input.value).toFixed(2);
    scheduleParameterUpdate();
  });
});
document.getElementById('axis').addEventListener('change', scheduleParameterUpdate);

document.querySelectorAll('.hold-command').forEach((button) => {
  const value = Number(button.dataset.value);
  let renewTimer = null;
  const press = (event) => {
    event.preventDefault();
    sendCommand('manual_command', { value, held: true });
    renewTimer = window.setInterval(() => sendCommand('manual_command', { value, held: true }), 100);
  };
  const release = (event) => {
    event.preventDefault();
    if (renewTimer !== null) window.clearInterval(renewTimer);
    renewTimer = null;
    sendCommand('manual_command', { value: 0, held: false });
  };
  button.addEventListener('pointerdown', press);
  button.addEventListener('pointerup', release);
  button.addEventListener('pointercancel', release);
  button.addEventListener('pointerleave', release);
});
