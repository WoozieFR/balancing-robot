const $ = (id) => document.getElementById(id);
const log = $('log');
let socket = null;
let lastDiagnostics = null;
let parameterUpdateTimer = null;
let pendingParameterExpiryTimer = null;
const pendingParameters = new Map();
const parameterCommandIds = new Set();

const valuesMatch = (expected, actual) => {
  if (typeof expected === 'number' && typeof actual === 'number') {
    return Number.isFinite(expected) && Number.isFinite(actual) && Math.abs(expected - actual) < 1e-6;
  }
  return expected === actual;
};

const markParameterPending = (id, value) => {
  pendingParameters.set(id, value);
  if (pendingParameterExpiryTimer !== null) window.clearTimeout(pendingParameterExpiryTimer);
  pendingParameterExpiryTimer = window.setTimeout(() => {
    pendingParameterExpiryTimer = null;
    if (pendingParameters.size === 0) return;
    write('paramètres Web non confirmés : nouvelle lecture demandée');
    pendingParameters.clear();
    refresh();
  }, 3000);
};

const clearPendingParameter = (id) => {
  pendingParameters.delete(id);
  if (pendingParameters.size === 0 && pendingParameterExpiryTimer !== null) {
    window.clearTimeout(pendingParameterExpiryTimer);
    pendingParameterExpiryTimer = null;
  }
};

const write = (value) => {
  if (!log) return;
  log.textContent += `${log.textContent ? '\n' : ''}${value}`;
  log.scrollTop = log.scrollHeight;
};

const text = (id, value) => {
  const node = $(id);
  if (node) node.textContent = value;
};

const number = (value, unit = '') =>
  value === undefined || value === null || Number.isNaN(Number(value))
    ? '—'
    : `${Number(value).toFixed(2)}${unit}`;

const setSocketState = (connected, message) => {
  $('dot')?.classList.toggle('ok', connected);
  text('out', message);
};

const syncRange = (id, value) => {
  if (value === undefined || value === null) return;
  const input = $(id);
  if (!input) return;
  const editor = $(`${id}-number`);
  const output = $(`${id}-value`);
  const pending = pendingParameters.get(id);
  if (pending !== undefined) {
    if (valuesMatch(pending, Number(value))) clearPendingParameter(id);
    else {
      if (output) output.textContent = Number(input.value).toFixed(2);
      if (editor && document.activeElement !== editor) editor.value = input.value;
      return;
    }
  }
  if (document.activeElement !== input) input.value = value;
  if (editor && document.activeElement !== editor) editor.value = value;
  if (output) output.textContent = id === 'imu-sign'
    ? (Number(value) > 0 ? '+1' : '−1')
    : Number(value).toFixed(2);
};

const renderMotorCards = (diagnostics) => {
  const container = $('motor-cards');
  if (!container) return;
  const cards = [];
  for (let index = 0; index < 2; index += 1) {
    const id = diagnostics[`motor${index}Id`];
    if (id === undefined || id === null) continue;
    const value = (key, unit = '') => diagnostics[key] === undefined || diagnostics[key] === null
      ? '—' : `${Number(diagnostics[key]).toFixed(2)}${unit}`;
    cards.push(`<article class="motor-card"><h4>ID ${id} <span>${diagnostics.motorArmState || '—'}</span></h4><dl>` +
      `<dt>vitesse</dt><dd>${value(`motor${index}Velocity`, ' pas/s')}</dd>` +
      `<dt>charge</dt><dd>${value(`motor${index}Load`)}</dd>` +
      `<dt>tension</dt><dd>${value(`motor${index}Voltage`, ' V')}</dd>` +
      `<dt>température</dt><dd>${value(`motor${index}Temperature`, ' °C')}</dd></dl></article>`);
  }
  container.innerHTML = cards.length ? cards.join('') : '<div class="empty-state">Aucune télémétrie moteur reçue.</div>';
};

const renderDiagnostics = (diagnostics) => {
  lastDiagnostics = diagnostics;
  const serviceRunning = Boolean(diagnostics.serviceRunning);
  const armState = diagnostics.motorArmState || 'DISARMED';
  const balanceArmed = armState === 'BALANCE_ARMED';
  const manualArmed = armState === 'MANUAL_ARMED';

  text('status', diagnostics.status || (serviceRunning ? 'Service actif' : 'Service arrêté'));
  text('run-badge', serviceRunning ? 'ACTIF' : 'ARRÊTÉ');
  $('run-badge')?.classList.toggle('ok', serviceRunning);
  text('arm-state', armState.replace('_', ' '));
  $('arm-state')?.classList.toggle('armed', armState === 'BALANCE_ARMED' || armState === 'MANUAL_ARMED');
  $('arm-state')?.classList.toggle('fault', armState === 'FAULT_LATCHED');
  const safetyWarning = diagnostics.safetyWarning || diagnostics.motorError || '';
  text('safety-warning', safetyWarning);
  $('safety-warning')?.classList.toggle('inhibited', Boolean(diagnostics.inhibitSafetyAutoDisarm));
  document.querySelectorAll('[data-command="arm_manual"], [data-command="arm_balance"]').forEach((button) => {
    button.disabled = armState !== 'READY';
  });
  document.querySelectorAll('[data-command="disarm"], [data-command="disarm_balance"]').forEach((button) => {
    button.disabled = armState !== 'MANUAL_ARMED' && armState !== 'BALANCE_ARMED';
  });
  document.querySelectorAll('.hold-command').forEach((button) => { button.disabled = !manualArmed; });

  text('v-angle', number(diagnostics.estimatedAngleDeg));
  text('v-cmd', diagnostics.controlCommand ?? diagnostics.motorCommand ?? 0);
  text('v-gyro', number(diagnostics.gyroRateDegPerSec ?? diagnostics.gyroZDegPerSec));
  text('v-err', number(diagnostics.controlErrorDeg));
  text('v-freq', number(diagnostics.speedLoopActualRateHz ?? diagnostics.gyroRateHz));
  text('v-speed', number(diagnostics.speedFilteredCmPerSec, ' cm/s'));
  text('v-target-effective', number(diagnostics.speedEffectiveTargetDeg, '°'));
  const gyroState = diagnostics.gyroFresh === false
    ? `gyro périmé · âge ${number(diagnostics.gyroAgeMs, ' ms')}`
    : `gyro OK · âge ${number(diagnostics.gyroAgeMs, ' ms')}`;
  text('imu-signal', `${number(diagnostics.gyroRateHz, ' Hz')} · ${gyroState}`);
  text('angles', `${number(diagnostics.accelAngleDeg, '°')} / ${number(diagnostics.estimatedAngleDeg, '°')}`);

  text('speed-left', number(diagnostics.speedLeftCmPerSec, ' cm/s'));
  text('speed-right', number(diagnostics.speedRightCmPerSec, ' cm/s'));
  text('speed-filtered', number(diagnostics.speedFilteredCmPerSec, ' cm/s'));
  text('speed-feedback-state', !diagnostics.speedLoopEnabled
    ? 'Boucle désactivée'
    : diagnostics.speedFeedbackStale
      ? `Retour périmé · retour progressif vers le trim · âge ${number(diagnostics.speedFeedbackAgeMs, ' ms')}`
      : `Retour frais · ${number(diagnostics.speedFeedbackRateHz, ' Hz')}${diagnostics.speedTargetSlewLimited ? ' · pente limitée' : ''}`);
  text('speed-effective-target', number(diagnostics.speedEffectiveTargetDeg, '°'));
  text('speed-correction', number(diagnostics.speedCorrectionDeg, '°'));
  text('speed-integral', number(diagnostics.speedIntegralCorrectionDeg, '°'));
  const yawTarget = Number(diagnostics.yawTargetDegPerSec || 0);
  text('yaw-feedback-state', yawTarget === 0
    ? 'inactive · consigne 0'
    : `actif · gyro Z ${number(diagnostics.yawRateDegPerSec, ' °/s')} · erreur ${number(diagnostics.yawErrorDegPerSec, ' °/s')}`);
  text('turn-command', diagnostics.turnCommand ?? 0);

  text('motor-state', `${armState} · ${diagnostics.motorConnected ? 'USB connecté' : 'USB arrêté'}`);
  text('motor-command', `${diagnostics.motorCommand ?? 0} · deadman ${diagnostics.motorDeadmanHeld ? 'tenu' : 'relâché'}`);
  text('motor-telemetry', `${diagnostics.motorTelemetryCount ?? 0} moteurs · ${diagnostics.motorWrittenFrames ?? 0} écritures · ${diagnostics.motorVelocityFeedbackFrames ?? 0} paires vitesse · ${diagnostics.motorVelocityFeedbackSkips ?? 0} retours manqués`);
  renderMotorCards(diagnostics);

  text('rates', `${number(diagnostics.accelRateHz, ' Hz')} / ${number(diagnostics.gyroRateHz, ' Hz')}`);
  text('dt', number(diagnostics.dtMs, ' ms'));
  text('jitter', `${number(diagnostics.accelJitterMs, ' ms')} / ${number(diagnostics.gyroJitterMs, ' ms')}`);
  text('samples', `${diagnostics.acceptedSamples ?? '—'} / ${diagnostics.rejectedSamples ?? '—'}`);
  text('journal', `${diagnostics.journalSamples ?? '—'} échantillons`);
  text('control-recording', diagnostics.controlRecording ? 'active' : 'arrêtée');
  text('control-recording-samples', diagnostics.controlRecordingSamples ?? 0);
  $('recording-dot')?.classList.toggle('active', Boolean(diagnostics.controlRecording));
  if ($('start-recording')) $('start-recording').disabled = Boolean(diagnostics.controlRecording);
  if ($('stop-recording')) $('stop-recording').disabled = !diagnostics.controlRecording;

  const guardedIds = ['axis', 'imu-sign', 'zero-offset', 'vmax', 'motor-control-mode', 'pwm-max', 'torque-limit', 'imu-timeout', 'fall-angle', 'fall-duration', 'manual-timeout', 'wheel-diameter', 'drive-ratio'];
  guardedIds.forEach((id) => {
    if ($(id)) $(id).disabled = balanceArmed;
    if ($(`${id}-number`)) $(`${id}-number`).disabled = balanceArmed;
  });
  const speedLimit = Number(diagnostics.speedTargetLimitCmPerSec);
  if ($('speed-target') && Number.isFinite(speedLimit) && speedLimit > 0) {
    $('speed-target').min = -speedLimit;
    $('speed-target').max = speedLimit;
    if ($('speed-target-number')) {
      $('speed-target-number').min = -speedLimit;
      $('speed-target-number').max = speedLimit;
    }
  }
  [['alpha', diagnostics.alpha], ['target', diagnostics.targetDeg], ['kp', diagnostics.kp], ['kd', diagnostics.kd],
    ['speed-target-limit', diagnostics.speedTargetLimitCmPerSec], ['speed-target', diagnostics.speedTargetCmPerSec],
    ['speed-kev', diagnostics.speedKevDegPerCmPerSec], ['speed-loop-rate', diagnostics.speedLoopRateHz],
    ['speed-filter-alpha', diagnostics.speedFilterAlpha], ['speed-angle-limit', diagnostics.speedTargetAngleLimitDeg],
    ['speed-integral-gain', diagnostics.speedIntegralGainDegPerCmPerSecSec],
    ['speed-absolute-angle-limit', diagnostics.speedAbsoluteAngleLimitDeg],
    ['speed-target-slew', diagnostics.speedTargetSlewRateDegPerSec],
    ['speed-feedback-timeout', diagnostics.speedFeedbackTimeoutMs],
    ['yaw-target', diagnostics.yawTargetDegPerSec], ['yaw-kp', diagnostics.yawKpCommandPerDegPerSec],
    ['wheel-diameter', diagnostics.wheelDiameterMm],
    ['drive-ratio', diagnostics.driveRatio], ['zero-offset', diagnostics.zeroOffsetDeg], ['vmax', diagnostics.vmax],
    ['pwm-max', diagnostics.pwmMax], ['torque-limit', diagnostics.torqueLimit], ['imu-timeout', diagnostics.imuTimeoutMs],
    ['fall-angle', diagnostics.fallAngleDeg], ['fall-duration', diagnostics.fallDurationMs], ['manual-timeout', diagnostics.manualTimeoutMs]]
    .forEach(([id, value]) => syncRange(id, value));
  if ($('axis') && document.activeElement !== $('axis') && diagnostics.axis && !pendingParameters.has('axis')) $('axis').value = diagnostics.axis;
  if ($('imu-sign') && diagnostics.imuSign !== undefined && document.activeElement !== $('imu-sign') && !pendingParameters.has('imu-sign')) $('imu-sign').value = diagnostics.imuSign;
  if ($('speed-loop-enabled') && document.activeElement !== $('speed-loop-enabled') && !pendingParameters.has('speed-loop-enabled')) $('speed-loop-enabled').checked = Boolean(diagnostics.speedLoopEnabled);
  if ($('motor-control-mode') && document.activeElement !== $('motor-control-mode') && diagnostics.motorControlMode && !pendingParameters.has('motor-control-mode')) $('motor-control-mode').value = diagnostics.motorControlMode;
  if ($('safety-inhibition') && document.activeElement !== $('safety-inhibition')) {
    if (!pendingParameters.has('safety-inhibition')) $('safety-inhibition').checked = Boolean(diagnostics.inhibitSafetyAutoDisarm);
  }

};

let refreshInFlight = false;
const refresh = () => {
  if (refreshInFlight) return Promise.resolve();
  refreshInFlight = true;
  return fetch('/diagnostics', { cache: 'no-store' })
    .then((response) => response.json())
    .then(renderDiagnostics)
    .catch((error) => write(`diagnostic error: ${error}`))
    .finally(() => { refreshInFlight = false; });
};

const sendCommand = (type, payload = {}) => {
  const id = `web-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  if (!socket || socket.readyState !== WebSocket.OPEN) {
    write('commande ignorée : WebSocket non connecté');
    return null;
  }
  socket.send(JSON.stringify({ v: 1, id, type, payload }));
  return id;
};

// Web controls are patches, not a stale snapshot of every input. This keeps a
// fast slider update from overwriting another value that was changed by the
// Android UI or by a concurrent Web client.
const parameterKeyById = {
  alpha: 'alpha',
  target: 'targetDeg',
  kp: 'kp',
  kd: 'kd',
  'speed-loop-enabled': 'speedLoopEnabled',
  'speed-target': 'speedTargetCmPerSec',
  'speed-target-limit': 'speedTargetLimitCmPerSec',
  'speed-kev': 'speedKevDegPerCmPerSec',
  'speed-loop-rate': 'speedLoopRateHz',
  'speed-filter-alpha': 'speedFilterAlpha',
  'speed-angle-limit': 'speedTargetAngleLimitDeg',
  'speed-integral-gain': 'speedIntegralGainDegPerCmPerSecSec',
  'speed-absolute-angle-limit': 'speedAbsoluteAngleLimitDeg',
  'speed-target-slew': 'speedTargetSlewRateDegPerSec',
  'speed-feedback-timeout': 'speedFeedbackTimeoutMs',
  'yaw-target': 'yawTargetDegPerSec',
  'yaw-kp': 'yawKpCommandPerDegPerSec',
  'zero-offset': 'zeroOffsetDeg',
  vmax: 'vmax',
  'pwm-max': 'pwmMax',
  'torque-limit': 'torqueLimit',
  'imu-timeout': 'imuTimeoutMs',
  'fall-angle': 'fallAngleDeg',
  'fall-duration': 'fallDurationMs',
  'manual-timeout': 'manualTimeoutMs',
  'wheel-diameter': 'wheelDiameterMm',
  'drive-ratio': 'driveRatio',
  axis: 'axis',
  'imu-sign': 'imuSign',
  'motor-control-mode': 'motorControlMode',
  'safety-inhibition': 'inhibitSafetyAutoDisarm',
};

const pendingPatch = () => Object.fromEntries(
  [...pendingParameters.entries()]
    .map(([id, value]) => [parameterKeyById[id], value])
    .filter(([key]) => Boolean(key)),
);

const scheduleParameterUpdate = () => {
  if (parameterUpdateTimer !== null) window.clearTimeout(parameterUpdateTimer);
  parameterUpdateTimer = window.setTimeout(() => {
    parameterUpdateTimer = null;
    const payload = pendingPatch();
    if (Object.keys(payload).length === 0) return;
    const id = sendCommand('update_parameters', payload);
    if (id) parameterCommandIds.add(id);
  }, 80);
};

const updateSpeedTargetRange = () => {
  const limit = Number($('speed-target-limit').value);
  $('speed-target').min = -limit;
  $('speed-target').max = limit;
  const target = Number($('speed-target').value);
  const nextTarget = Math.min(limit, Math.max(-limit, target));
  $('speed-target').value = nextTarget;
  if (nextTarget !== target) markParameterPending('speed-target', nextTarget);
  if ($('speed-target-number')) {
    $('speed-target-number').min = -limit;
    $('speed-target-number').max = limit;
    $('speed-target-number').value = $('speed-target').value;
  }
};

const clampTargetToAngleLimit = () => {
  if (!$('speed-loop-enabled').checked) return;
  const limit = Number($('speed-absolute-angle-limit').value);
  const target = Number($('target').value);
  const nextTarget = Math.min(limit, Math.max(-limit, target));
  $('target').value = nextTarget;
  if (nextTarget !== target) markParameterPending('target', nextTarget);
  if ($('target-number')) $('target-number').value = $('target').value;
};

const attachPrecisionInput = (range) => {
  const id = range.id;
  const editor = document.createElement('input');
  editor.type = 'number';
  editor.id = `${id}-number`;
  editor.className = 'precision-input';
  editor.min = range.min;
  editor.max = range.max;
  editor.step = range.step;
  editor.value = range.value;
  editor.setAttribute('aria-label', `${id} valeur précise`);
  range.insertAdjacentElement('afterend', editor);
  const updateOutput = () => {
    const output = $(`${id}-value`);
    if (output) output.textContent = id === 'imu-sign' ? (Number(range.value) > 0 ? '+1' : '−1') : Number(range.value).toFixed(2);
  };
  const applyRangeChange = () => {
    editor.value = range.value;
    updateOutput();
    markParameterPending(id, Number(range.value));
    if (id === 'speed-target-limit') updateSpeedTargetRange();
    if (id === 'speed-absolute-angle-limit') clampTargetToAngleLimit();
    scheduleParameterUpdate();
  };
  range.addEventListener('input', applyRangeChange);
  range.addEventListener('change', applyRangeChange);
  const applyEditor = () => {
    const value = Number(editor.value);
    if (!Number.isFinite(value)) return;
    range.value = Math.min(Number(range.max), Math.max(Number(range.min), value));
    editor.value = range.value;
    range.dispatchEvent(new Event('input', { bubbles: true }));
  };
  editor.addEventListener('input', applyEditor);
  editor.addEventListener('change', applyEditor);
};

const parameterIds = ['alpha', 'target', 'kp', 'kd', 'speed-target', 'speed-target-limit', 'speed-kev', 'speed-loop-rate', 'speed-filter-alpha', 'speed-angle-limit', 'speed-integral-gain', 'speed-absolute-angle-limit', 'speed-target-slew', 'speed-feedback-timeout', 'yaw-target', 'yaw-kp', 'zero-offset', 'vmax', 'pwm-max', 'torque-limit', 'imu-timeout', 'fall-angle', 'fall-duration', 'manual-timeout', 'wheel-diameter', 'drive-ratio'];
parameterIds.forEach((id) => attachPrecisionInput($(id)));

$('speed-loop-enabled').addEventListener('change', () => { markParameterPending('speed-loop-enabled', $('speed-loop-enabled').checked); clampTargetToAngleLimit(); scheduleParameterUpdate(); });
$('axis').addEventListener('change', () => { markParameterPending('axis', $('axis').value); scheduleParameterUpdate(); });
$('imu-sign').addEventListener('change', () => { markParameterPending('imu-sign', Number($('imu-sign').value)); scheduleParameterUpdate(); });
$('motor-control-mode').addEventListener('change', () => { markParameterPending('motor-control-mode', $('motor-control-mode').value); scheduleParameterUpdate(); });
$('safety-inhibition').addEventListener('change', () => { markParameterPending('safety-inhibition', $('safety-inhibition').checked); scheduleParameterUpdate(); });

$('start-recording').addEventListener('click', () => sendCommand('start_recording'));
$('stop-recording').addEventListener('click', () => sendCommand('stop_recording'));
document.querySelectorAll('[data-command]').forEach((button) => {
  button.addEventListener('click', () => {
    const type = button.dataset.command;
    if (type === 'connect_usb' || type === 'scan_bus') sendCommand(type, { deviceId: Number($('usb-device-id').value || 0) });
    else if (type === 'arm_manual' || type === 'arm_balance') sendCommand(type, { safeTestConfirmed: $('safe-test').checked });
    else sendCommand(type);
  });
});

const holdReleases = [];
document.querySelectorAll('.hold-command').forEach((button) => {
  const value = Number(button.dataset.value);
  let renewTimer = null;
  let active = false;
  const release = (event) => {
    event?.preventDefault();
    if (renewTimer !== null) window.clearInterval(renewTimer);
    renewTimer = null;
    if (!active) return;
    active = false;
    sendCommand('manual_command', { value: 0, held: false });
  };
  holdReleases.push(release);
  button.addEventListener('pointerdown', (event) => {
    event.preventDefault();
    if (active) return;
    active = true;
    button.setPointerCapture?.(event.pointerId);
    sendCommand('manual_command', { value, held: true });
    renewTimer = window.setInterval(() => sendCommand('manual_command', { value, held: true }), 100);
  });
  button.addEventListener('pointerup', release);
  button.addEventListener('pointercancel', release);
  button.addEventListener('lostpointercapture', release);
});
window.addEventListener('blur', () => holdReleases.forEach((release) => release()));

const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
let reconnectTimer = null;
const connectSocket = () => {
  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) return;
  try {
    socket = new WebSocket(`${scheme}://${window.location.host}/ws`);
    socket.addEventListener('open', () => {
      setSocketState(true, 'WebSocket connecté · diagnostics 4 Hz');
      write('websocket: connecté');
      socket.send(JSON.stringify({ v: 1, id: 'web-diagnostic', type: 'smoke', payload: {} }));
    });
    socket.addEventListener('close', () => {
      setSocketState(false, 'WebSocket déconnecté · reconnexion…');
      if (reconnectTimer === null) reconnectTimer = window.setTimeout(() => {
        reconnectTimer = null;
        connectSocket();
      }, 1000);
    });
    socket.addEventListener('error', () => { setSocketState(false, 'Erreur WebSocket · reconnexion…'); write('websocket: erreur'); });
    socket.addEventListener('message', (event) => {
      try {
        const message = JSON.parse(event.data);
        if (message.type === 'ack') {
          if (parameterCommandIds.delete(message.id) && message.ok === false) {
            pendingParameters.clear();
            if (pendingParameterExpiryTimer !== null) window.clearTimeout(pendingParameterExpiryTimer);
            pendingParameterExpiryTimer = null;
            refresh();
          }
          if (message.message || message.ok === false) write(`ack ${message.id || ''}: ${message.message || message.error || (message.ok ? 'OK' : 'refusé')}`);
        }
        else if (message.type === 'error') write(`ws error: ${message.code} ${message.message}`);
      } catch (_) { write(`ws: ${event.data}`); }
    });
  } catch (error) {
    setSocketState(false, 'WebSocket indisponible · reconnexion…');
    write(`websocket: ${error}`);
    if (reconnectTimer === null) reconnectTimer = window.setTimeout(() => {
      reconnectTimer = null;
      connectSocket();
    }, 1000);
  }
};
connectSocket();

fetch('/health').then((response) => response.json()).then((health) => write(`health: ${JSON.stringify(health)}`)).catch((error) => write(`health error: ${error}`));
refresh();
window.setInterval(refresh, 250);

const sections = [...document.querySelectorAll('.section-block')];
const navLinks = [...document.querySelectorAll('.section-nav a')];
if ('IntersectionObserver' in window) {
  const observer = new IntersectionObserver((entries) => entries.forEach((entry) => {
    if (!entry.isIntersecting) return;
    navLinks.forEach((link) => link.classList.toggle('active', link.getAttribute('href') === `#${entry.target.id}`));
  }), { rootMargin: '-35% 0px -55% 0px' });
  sections.forEach((section) => observer.observe(section));
}
