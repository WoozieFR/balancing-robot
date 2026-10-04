# FEAT-GAMEPAD-001 — Spécification fonctionnelle et technique

**Version :** 1.0  
**Date :** 2026-10-04  
**Statut :** proposition pour validation avant codage  
**Document amont :** `FEAT-GAMEPAD-001-cahier-des-charges.md`

## 1. Modèle de données

Les réglages persistants sont séparés des commandes opérateur éphémères.

```kotlin
data class GamepadConfig(
    val maxSpeedCmPerSec: Double = 5.0,
    val maxYawDegPerSec: Double = 90.0,
    val deadZone: Double = 0.12,
    val responseExponent: Double = 1.5,
    val precisionScale: Double = 0.35,
    val speedSign: Int = -1,
    val yawSign: Int = 1,
)

enum class DriveCommandSource { NONE, PARAMETERS, GAMEPAD }

data class GamepadDriveCommand(
    val sequence: Long,
    val deviceId: Int,
    val speedTargetCmPerSec: Double,
    val yawTargetDegPerSec: Double,
    val deadmanHeld: Boolean,
    val precisionHeld: Boolean,
    val submittedAtNs: Long,
)

data class EffectiveDriveSetpoint(
    val source: DriveCommandSource,
    val speedTargetCmPerSec: Double,
    val yawTargetDegPerSec: Double,
    val fresh: Boolean,
    val neutralReason: String?,
)
```

`GamepadDriveCommand` et `EffectiveDriveSetpoint` ne sont jamais sérialisés dans
DataStore. `GamepadConfig` l'est après validation et avec un debounce normal,
jamais à la cadence des événements HID.

## 2. Normalisation des axes

Pour une valeur brute `x ∈ [-1, 1]`, une zone morte `d` et un exposant `p` :

```text
if abs(x) <= d:
    normalized = 0
else:
    linear = (abs(x) - d) / (1 - d)
    normalized = sign(x) × linear^p
```

La zone morte réellement utilisée vaut :

```text
max(GamepadConfig.deadZone, MotionRange.flat)
```

Les consignes sont ensuite :

```text
scale = precisionHeld ? precisionScale : 1
speedTarget = clamp(speedSign × normalizedLeftY × maxSpeed × scale,
                    -speedTargetLimit, +speedTargetLimit)
yawTarget = clamp(yawSign × normalizedRightX × maxYaw × scale,
                  -360, +360)
```

La fonction pure DOIT être continue au bord de la zone morte, monotone,
symétrique et bornée.

## 3. Détection et mapping Android

Un périphérique est candidat si :

```text
sources contient SOURCE_GAMEPAD ou SOURCE_JOYSTICK
et au moins AXIS_Y est présent
```

Sélection V1 :

1. conserver le périphérique sélectionné tant qu'il reste connecté ;
2. sinon choisir le premier périphérique candidat dont le nom contient
   `Wireless Controller`, `DualShock` ou `Sony` ;
3. sinon afficher les autres candidats mais ne pas les activer automatiquement.

Mapping axes :

- stick gauche vertical : `MotionEvent.AXIS_Y` ;
- stick droit horizontal : `AXIS_Z` en priorité, puis `AXIS_RX` si `Z` est
  absent ;
- deadman : `KeyEvent.KEYCODE_BUTTON_R1` ;
- précision : `KeyEvent.KEYCODE_BUTTON_L1` ;
- désarmement : `KeyEvent.KEYCODE_BUTTON_B`.

Les événements sont acceptés uniquement s'ils proviennent du `deviceId`
sélectionné. Les axes et keycodes observés sont exposés dans le diagnostic pour
permettre de corriger le mapping après le premier essai matériel.

## 4. Machine d'état

```text
DISABLED
  └─ activation IHM + périphérique présent ─► ENABLED_NEUTRAL

ENABLED_NEUTRAL
  ├─ R1 appuyé + activité au premier plan ─► ENABLED_DRIVING
  ├─ déconnexion/perte focus ───────────────► ENABLED_UNAVAILABLE
  └─ désactivation IHM ─────────────────────► DISABLED

ENABLED_DRIVING
  ├─ heartbeat 50 Hz ───────────────────────► ENABLED_DRIVING
  ├─ R1 relâché ────────────────────────────► ENABLED_NEUTRAL
  ├─ timeout/perte focus/déconnexion ───────► ENABLED_UNAVAILABLE
  └─ Cercle ─► désarmement + ENABLED_NEUTRAL

ENABLED_UNAVAILABLE
  ├─ périphérique revenu + focus ───────────► ENABLED_NEUTRAL
  └─ désactivation IHM ─────────────────────► DISABLED
```

Toute entrée dans `ENABLED_NEUTRAL`, `ENABLED_UNAVAILABLE` ou `DISABLED`
publie immédiatement une consigne manette nulle. Une reconnexion ne restaure
jamais automatiquement `deadmanHeld` ni une ancienne position de stick.

## 5. Arbitrage des consignes

Le service possède un arbitre unique, lu par les boucles vitesse et yaw :

```text
si neutralisationLatchee après sortie du mode manette:
    cible = (0 cm/s, 0 °/s)
sinon si modeManetteActif:
    si commande fraîche ET deadman tenu ET activité au premier plan:
        cible = commande manette
    sinon:
        cible = (0 cm/s, 0 °/s)
sinon:
    cible = paramètres Android/Web actuels
```

La fraîcheur est calculée avec une horloge monotone :

```text
nowNs - submittedAtNs <= 250 ms
```

L'arbitre ne lit pas l'heure civile. La source `GAMEPAD` reste propriétaire
tant que le mode est actif, y compris quand sa sortie vaut zéro. Désactiver la
manette produit d'abord zéro ; le retour aux paramètres n'est autorisé qu'après
une nouvelle action explicite sur une consigne Android/Web, qui acquitte la
neutralisation latchée.

## 6. Intégration aux boucles existantes

Le point de substitution se situe avant les contrôleurs, sans modifier leurs
formules :

```text
Gamepad/UI/Web
      ↓
DriveSetpointArbiter
      ├─ speedTargetCmPerSec ─► VelocityOuterLoop ─► target angle
      └─ yawTargetDegPerSec ──► YawController ─────► u_turn

PD balance ─► u_balance
u_left  = clamp(u_balance + u_turn)
u_right = clamp(u_balance - u_turn)
```

Les consignes manette sont calculées et observables même désarmé, mais elles ne
peuvent atteindre le bus que si l'état moteur est `BALANCE_ARMED`.

## 7. Heartbeat et lifecycle

- `MainActivity` maintient les dernières valeurs d'axes et de boutons.
- Lorsque le mode manette est actif et l'activité en `RESUMED`, une coroutine
  publie `GamepadDriveCommand` à 50 Hz.
- `onPause`, `onStop`, perte du focus, changement du périphérique et
  déconnexion publient immédiatement zéro.
- Le service applique en parallèle le timeout de 250 ms ; la correction ne
  dépend donc pas de la bonne exécution du callback lifecycle.
- `FLAG_KEEP_SCREEN_ON` est actif uniquement pendant le mode manette et retiré
  à sa désactivation.
- L'absence d'événement de mouvement lorsque le stick reste immobile n'est pas
  un problème : le heartbeat republie la dernière position tant que `R1` reste
  réellement maintenu.

## 8. API locale du service

```kotlin
fun setGamepadModeEnabled(enabled: Boolean, deviceId: Int?): Boolean
fun submitGamepadCommand(command: GamepadDriveCommand)
fun notifyGamepadUnavailable(reason: GamepadNeutralReason)
fun emergencyDisarmFromGamepad()
```

Contraintes :

- validation complète des valeurs et du `deviceId` ;
- rejet des séquences non croissantes ;
- bornage répété côté service, même si l'activité l'a déjà fait ;
- aucune I/O, DataStore ou opération bloquante dans `submitGamepadCommand` ;
- `emergencyDisarmFromGamepad` utilise le même chemin que le bouton de
  désarmement Android, et non un chemin moteur parallèle.

## 9. Diagnostics et CSV

Champs minimum :

```text
gamepad_mode_enabled
gamepad_connected
gamepad_device_id
gamepad_name
gamepad_vendor_id
gamepad_product_id
gamepad_has_focus
gamepad_deadman_held
gamepad_precision_held
gamepad_left_y_raw
gamepad_right_x_raw
gamepad_left_y_normalized
gamepad_right_x_normalized
gamepad_speed_target_cmps
gamepad_yaw_target_dps
gamepad_command_age_ms
gamepad_heartbeat_hz
drive_command_source
drive_neutral_reason
effective_speed_target_cmps
effective_yaw_target_dps
```

Les diagnostics HTTP sont en lecture seule. Le CSV associe ces valeurs au même
timestamp monotone que l'échantillon de contrôle.

## 10. Gestion des erreurs

| Situation | Réaction obligatoire |
| --- | --- |
| Valeur d'axe non finie | Rejet de l'échantillon et zéro |
| Axe droit introuvable | Mode non activable, diagnostic explicite |
| Périphérique déconnecté | Zéro immédiat puis état indisponible |
| Heartbeat périmé | Zéro, défaut visible, pas de reprise automatique |
| Perte de focus/lifecycle | Zéro immédiat |
| R1 relâché | Zéro immédiat |
| Cercle | Désarmement explicite |
| Commande hors bornes | Rejet ou bornage sûr côté service, compteur incrémenté |

L'inhibition générale des désarmements automatiques ne modifie aucune ligne de
ce tableau. Elle peut conserver l'état `BALANCE_ARMED`, mais les consignes
manette périmées valent toujours zéro.
