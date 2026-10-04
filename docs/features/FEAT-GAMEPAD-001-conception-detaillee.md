# FEAT-GAMEPAD-001 — Conception détaillée

**Version :** 1.0  
**Date :** 2026-10-04  
**Statut :** proposition pour validation avant codage  
**Documents amont :** cahier des charges et spécification FEAT-GAMEPAD-001

## 1. Décisions structurantes

- Les événements HID sont capturés par `MainActivity`, seule fenêtre Android
  susceptible de recevoir les mouvements de joystick.
- Toute transformation mathématique et tout arbitrage sont placés dans des
  classes Kotlin pures testables en JVM.
- Le service possède l'état de commande effectif et le watchdog. L'activité ne
  peut pas contourner les bornes ni l'état d'armement.
- Les consignes manette sont séparées de `RobotConfig` et de `RobotSettings`.
- La perte de la manette neutralise la translation et le yaw, mais ne modifie
  pas le trim ni les gains.
- La feature n'ajoute aucune dépendance externe.

## 2. Sources à ajouter

```text
app/src/main/java/com/woozie/balancingrobot/
├── domain/gamepad/
│   ├── GamepadModels.kt
│   ├── GamepadAxisTransform.kt
│   └── DriveSetpointArbiter.kt
├── gamepad/
│   ├── AndroidGamepadDetector.kt
│   ├── AndroidGamepadMapper.kt
│   └── GamepadInputState.kt
├── service/
│   └── GamepadDiagnosticState.kt
└── settings/
    └── GamepadSettings.kt
```

Fichiers existants à modifier :

- `MainActivity.kt` : capture HID, lifecycle, heartbeat et IHM ;
- `RobotControlService.kt` : API binder, arbitre, timeout et diagnostics ;
- `VelocityOuterLoop.kt` ou son appelant : cible vitesse effective injectée ;
- `SimulatedControlRuntime.kt` ou son appelant : cible yaw effective injectée ;
- `ControlSessionLog.kt` : colonnes de diagnostic ;
- `web/index.html` et `web/app.js` : état manette en lecture seule ;
- `RobotServiceState` : snapshot `gamepad` et source effective.

## 3. Domaine pur

### 3.1 Transformation d'axe

```kotlin
fun transformGamepadAxis(
    raw: Double,
    configuredDeadZone: Double,
    hardwareFlat: Double,
    exponent: Double,
    sign: Int,
): Double
```

La fonction valide toutes les entrées, utilise la zone morte maximale, applique
la courbe de la spécification et retourne une valeur dans `[-1, 1]`.

### 3.2 Arbitre

`DriveSetpointArbiter` est propriétaire de :

- l'activation du mode manette ;
- la dernière commande validée ;
- le dernier numéro de séquence ;
- la disponibilité de l'activité et du périphérique ;
- le latch de neutralisation après désactivation.

API pure proposée :

```kotlin
class DriveSetpointArbiter(
    private val timeoutNs: Long = 250_000_000L,
) {
    fun enableGamepad(deviceId: Int)
    fun disableGamepad()
    fun setAvailable(available: Boolean, reason: GamepadNeutralReason?)
    fun submit(command: GamepadDriveCommand): Boolean
    fun resolve(nowNs: Long, parameters: ParameterDriveSetpoint): EffectiveDriveSetpoint
    fun acknowledgeParameterTakeover()
}
```

Après `disableGamepad`, `resolve` retourne zéro tant que
`acknowledgeParameterTakeover` n'a pas été déclenché par une nouvelle action
explicite Android/Web. Cette règle évite la reprise d'une ancienne consigne.

## 4. Adaptateur Android

### 4.1 Détection

`AndroidGamepadDetector` encapsule `InputManager` et son
`InputDeviceListener`. Il expose un snapshot immuable des candidats. Il est
enregistré dans `onStart` et désenregistré dans `onStop`.

La détection ne fait aucune opération Bluetooth : elle énumère uniquement les
`InputDevice` déjà visibles par Android.

### 4.2 Événements de mouvement

`MainActivity.dispatchGenericMotionEvent` intercepte uniquement :

```text
event.action == ACTION_MOVE
et source contient SOURCE_JOYSTICK
et event.deviceId == selectedDeviceId
```

`AndroidGamepadMapper` lit les `MotionRange` du périphérique, choisit l'axe
droit et met à jour `GamepadInputState`. Aucun appel au service n'est effectué
directement depuis chaque événement de mouvement.

### 4.3 Boutons

`MainActivity.dispatchKeyEvent` traite les événements du périphérique
sélectionné :

- `R1` met à jour le deadman ;
- `L1` met à jour le mode précision ;
- `Cercle` sur `ACTION_DOWN` avec `repeatCount == 0` appelle le désarmement.

Les événements reconnus sont consommés. Les événements clavier ou provenant
d'un autre périphérique suivent le comportement Android normal.

### 4.4 Heartbeat

Une coroutine liée à l'état `RESUMED` :

1. lit le dernier `GamepadInputState` ;
2. calcule les axes transformés et les consignes ;
3. force zéro si deadman relâché ;
4. incrémente la séquence ;
5. appelle `submitGamepadCommand` ;
6. attend 20 ms avec une cadence basée sur une horloge monotone.

Le service reste responsable du timeout réel. Une dérive de la coroutine ne
peut donc pas maintenir indéfiniment une ancienne commande.

## 5. Intégration service

Le service reçoit les commandes du binder sur le thread appelant et les place
dans une référence atomique ou les sérialise sur son thread de contrôle. Aucun
appel USB n'a lieu depuis le binder.

À chaque tick gyro :

```text
parameterSetpoint = (activeConfig.speedTargetCmPerSec,
                     activeConfig.yawTargetDegPerSec)
effectiveSetpoint = driveSetpointArbiter.resolve(receivedTimestampNs,
                                                 parameterSetpoint)
speedOutput = updateSpeedLoop(receivedTimestampNs,
                              effectiveSetpoint.speedTargetCmPerSec)
control = imuRuntime.step(...,
                          yawTargetDegPerSec = effectiveSetpoint.yawTargetDegPerSec)
```

`VelocityOuterLoop.step` ne doit plus dépendre implicitement de la consigne
stockée dans sa configuration. Il reçoit la cible effective comme argument ou
une configuration de tick immuable. Les gains et limites restent dans
`RobotConfig`.

Le diagnostic manette est publié au maximum à 20 Hz pour ne pas provoquer une
recomposition Compose à chaque heartbeat.

## 6. Interaction avec Android/Web

- L'activation du mode est disponible uniquement dans Android V1.
- Les réglages de calibration sont éditables dans Android.
- Le Web reçoit les diagnostics, mais ne peut ni activer le mode ni injecter
  de faux événements gamepad.
- Quand la source effective est `GAMEPAD`, les sliders vitesse/yaw Web portent
  l'indication « remplacé par la manette ».
- Une modification Web de vitesse/yaw pendant la propriété manette met à jour
  la consigne paramètre mais ne devient jamais effective immédiatement.
- Après désactivation de la manette, une nouvelle modification Android/Web est
  nécessaire pour reprendre la main.

## 7. Persistance

`GamepadSettings` utilise Preferences DataStore pour les sept valeurs de
`GamepadConfig`. Les clés ont le préfixe `gamepad_`.

Ne sont jamais persistés :

- mode manette actif ;
- périphérique sélectionné ;
- axes et boutons ;
- séquence et heartbeat ;
- source effective ;
- consignes temporaires ;
- état de focus.

## 8. Sécurité et concurrence

- Le deadman et le watchdog manette sont des neutralisations de commande, pas
  des sécurités inhibables.
- Le désarmement `Cercle` appelle un chemin unique qui met zéro, coupe le couple
  selon la politique existante et met à jour l'état.
- `onPause`, `onStop` et `onWindowFocusChanged(false)` publient zéro avant tout
  unbind du service.
- Le timeout du service couvre le cas où ce callback n'est pas exécuté.
- Les valeurs reçues sont bornées une seconde fois dans le service.
- Le calcul de contrôle ne bloque jamais sur un verrou UI ou Bluetooth.
- Une reconnexion démarre toujours avec axes neutres logiques et deadman faux.

## 9. Ordre d'implémentation

1. Modèles purs, transformation des axes et tests JVM.
2. Arbitre de consignes, timeout, neutralisation et tests JVM.
3. Détection/mapping Android et écran de diagnostic sans moteur.
4. Heartbeat vers le service avec diagnostics, toujours neutralisé.
5. Injection de la cible vitesse effective et tests de non-régression.
6. Injection de la cible yaw effective et tests de mélange existants.
7. Deadman, perte de focus, déconnexion et désarmement `Cercle`.
8. CSV et diagnostics Web en lecture seule.
9. Tests instrumentés, build, lint puis validation téléphone roues levées.

## 10. Interdictions d'implémentation

- Ne pas modifier `RobotConfig.speedTargetCmPerSec` ou
  `yawTargetDegPerSec` à 50 Hz.
- Ne pas écrire DataStore depuis le heartbeat.
- Ne pas appeler le bus moteur depuis `MainActivity`.
- Ne pas armer depuis un bouton de manette.
- Ne pas laisser une ancienne consigne paramètre réapparaître après une perte
  de manette.
- Ne pas désactiver le watchdog manette lorsque les sécurités générales sont
  inhibées.

