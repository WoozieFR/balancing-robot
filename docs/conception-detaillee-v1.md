# Conception détaillée V1 — Application Android du robot équilibré

**Version :** 0.1  
**Date :** 2026-10-01  
**Statut :** proposition à valider  
**Documents amont :** `docs/cahier-des-charges.md`, `docs/specification-v1.md`

## 1. Objet et décisions principales

Ce document décrit comment implémenter la V1 sans laisser de choix structurel
à l'implémentation. Il couvre l'architecture, les responsabilités, les threads,
les contrats internes, le protocole web, le stockage, la journalisation, les
tests et l'ordre de réalisation.

Décisions principales :

- un seul module Gradle `:app` pour réduire le temps de build et d'itération ;
- séparation interne stricte entre domaine pur et adaptateurs Android ;
- état métier modifié par un seul thread de contrôle ;
- calcul IMU, filtre et PD déclenché par les vrais callbacks gyro ;
- I/O USB sur un thread distinct, avec une seule consigne moteur en attente ;
- application native en Jetpack Compose ;
- serveur HTTP/WebSocket Ktor CIO embarqué ;
- page web HTML/CSS/JavaScript sans framework ni construction Node ;
- persistance des réglages par Preferences DataStore ;
- journal compact en mémoire, sans base de données ;
- injection de dépendances manuelle, sans Hilt ;
- aucune dépendance Python ou Termux.

## 2. Base technique

### 2.1 Projet et niveaux Android

| Élément | Choix |
| --- | --- |
| Module | `:app` unique |
| Namespace | `com.woozie.balancingrobot` |
| `minSdk` | 26 |
| `targetSdk` | 36 |
| `compileSdk` | 36 |
| AGP | 9.4.0 |
| Gradle | 9.6.0 |
| JDK | 17 |
| Kotlin | support intégré AGP, sans `org.jetbrains.kotlin.android` |

AGP 9 active Kotlin nativement ; le plugin `org.jetbrains.kotlin.android` ne
doit pas être ajouté. La configuration Compose doit utiliser le mécanisme
compatible avec Kotlin intégré à AGP 9.

### 2.2 Dépendances retenues

Les versions ci-dessous sont celles retenues à la date du document. Elles sont
centralisées dans `gradle/libs.versions.toml` lors de l'implémentation.

| Usage | Dépendance | Version |
| --- | --- | ---: |
| UI | Compose BOM | `2025.12.00` |
| Activity Compose | `androidx.activity:activity-compose` | `1.13.0` |
| Lifecycle/ViewModel | `androidx.lifecycle:*` | `2.10.0` |
| Préférences | `androidx.datastore:datastore-preferences` | `1.2.1` |
| Coroutines Android | `kotlinx-coroutines-android` | `1.11.0` |
| JSON par arbre | `kotlinx-serialization-json` | `1.9.0` |
| Serveur | Ktor server core/CIO/WebSockets | `3.6.0` |
| USB série | `usb-serial-for-android` | `3.11.0` |
| Tests | JUnit 4 + coroutines-test | `4.13.2` / `1.11.0` |

Choix complémentaires :

- Material 3 est pris depuis le BOM Compose ;
- les courbes Compose utilisent `Canvas`, sans bibliothèque graphique ;
- la page web utilise `<canvas>`, sans dépendance JavaScript ;
- `kotlinx-serialization-json` est utilisé avec `JsonElement` et des codecs
  manuels : aucun plugin de génération de sérialiseurs n'est nécessaire ;
- le dépôt JitPack est ajouté uniquement pour `usb-serial-for-android`.

Références de versions : documentation AndroidX et Compose, documentation Ktor
3.6 et dépôt officiel `usb-serial-for-android`. Le poste de développement ne
disposant actuellement que de `platforms;android-36`, le BOM Compose `2026.08.00`
et Lifecycle `2.11.0` (qui exigent API 37) sont remplacés pour le Lot 0 par les
versions compatibles ci-dessus. Ils pourront être remontés dès que l'image SDK
37 sera installée, sans changement d'API applicative.

### 2.3 Validation préalable du serveur Android

Ktor CIO est le choix principal. Avant d'implémenter l'IHM web complète, un
test technique doit vérifier sur le téléphone cible :

1. démarrage et arrêt du serveur depuis un service Android ;
2. accès à `GET /health` depuis un second appareil du LAN ;
3. échange WebSocket bidirectionnel pendant dix minutes ;
4. écran du téléphone éteint ;
5. arrêt sans fuite de thread ni port conservé.

Si ce test ne peut pas compiler ou fonctionner de façon stable sur Android, le
repli est fixé : implémenter un adaptateur `ServerSocket` limité aux routes V1
et au framing RFC 6455 déjà éprouvé dans le dépôt de référence. Aucun autre
framework serveur ne doit être choisi en cours d'implémentation sans réviser ce
document.

Lors de la première validation sur téléphone, le démarrage Ktor CIO 3.6 a
produit `JobCancellationException: LazyStandaloneCoroutine is cancelling`.
Le Lot 0 utilise donc le repli `SocketRobotWebServer`, qui couvre `/health`,
les assets web et le framing WebSocket nécessaire au smoke test. Ktor reste
isolé comme candidat à revalider ultérieurement, mais n'est plus sur le chemin
de démarrage du service. Le port est configurable depuis l'application, avec
`8766` proposé par défaut et mémorisé. Le serveur tente exclusivement le port
choisi et retourne une erreur explicite s'il est déjà occupé.

## 3. Architecture logique

```text
┌─────────────────────────────────────────────────────────────────────┐
│ MainActivity / Compose                                              │
│ WebUi <── HTTP/WebSocket server                                     │
└──────────────┬─────────────────────────┬────────────────────────────┘
               │ RobotRuntimeApi         │ snapshots / acknowledgements
               ▼                         ▼
┌─────────────────────────────────────────────────────────────────────┐
│ ControlRuntime — propriétaire unique de RobotRuntimeState           │
│ CommandDispatcher │ StateMachines │ SafetySupervisor                │
│ ImuPipeline ─► ComplementaryEstimator ─► PdController               │
└──────────┬──────────────────┬──────────────────┬────────────────────┘
           │ latest command   │ events           │ immutable snapshots
           ▼                  ▼                  ▼
┌───────────────────┐  ┌────────────────┐  ┌─────────────────────────┐
│ MotorIoScheduler  │  │ PackedEventLog │  │ UI / Web / Notification │
│ UsbSerialTransport│  │ CsvExporter    │  └─────────────────────────┘
│ FeetechBus        │  └────────────────┘
│ Sts3215Gateway    │
└───────────────────┘
```

### 3.1 Règle de dépendance

Les packages de domaine ne dépendent d'aucune classe Android, USB, Ktor ou
Compose. Les adaptateurs dépendent des contrats du domaine, jamais l'inverse.

```text
ui/web/android/sensor/usb/storage
                 │
                 ▼
          runtime interfaces
                 │
                 ▼
      domain models and pure logic
```

### 3.2 Injection des dépendances

`BalancingRobotApplication` crée un `AppContainer` contenant uniquement les
dépendances à durée de vie application : horloge, stockage des paramètres et
fabriques d'adaptateurs.

Le service crée à chaque session un `RobotSessionContainer`. Celui-ci construit
le runtime, les threads, l'IMU, le moteur, le journal et le serveur. L'arrêt du
service ferme le conteneur dans l'ordre défini à la section 17.

Aucun singleton mutable statique n'est autorisé. `AppContainer` est transmis
explicitement aux fabriques et le service expose une API locale via un binder.

## 4. Organisation des sources

Le module reste unique, avec les packages suivants :

```text
com.woozie.balancingrobot
├── BalancingRobotApplication.kt
├── MainActivity.kt
├── app/
│   ├── AppContainer.kt
│   └── RobotServiceConnector.kt
├── domain/
│   ├── model/          # états, paramètres, commandes, snapshots, défauts
│   ├── estimation/     # fonctions pures angle + filtre complémentaire
│   ├── control/        # PD, saturation, signes
│   ├── protocol/       # codec Feetech pur et registres STS3215
│   └── safety/         # règles d'armement et classification des défauts
├── runtime/
│   ├── RobotRuntimeApi.kt
│   ├── ControlRuntime.kt
│   ├── CommandDispatcher.kt
│   ├── ComponentCoordinator.kt
│   ├── SafetySupervisor.kt
│   └── SnapshotPublisher.kt
├── service/
│   ├── RobotControlService.kt
│   └── RobotNotificationController.kt
├── sensor/
│   ├── ImuSource.kt
│   ├── AndroidImuSource.kt
│   └── ImuPipeline.kt
├── motor/
│   ├── SerialTransport.kt
│   ├── AndroidUsbSerialTransport.kt
│   ├── FeetechBus.kt
│   ├── MotorIoScheduler.kt
│   └── Sts3215Gateway.kt
├── logging/
│   ├── EventLogger.kt
│   ├── PackedEventLog.kt
│   └── CsvExporter.kt
├── settings/
│   ├── SettingsRepository.kt
│   └── DataStoreSettingsRepository.kt
├── web/
│   ├── RobotWebServer.kt
│   ├── KtorRobotWebServer.kt
│   └── WebProtocolCodec.kt
└── ui/
    ├── RobotViewModel.kt
    ├── RobotApp.kt
    ├── screens/
    ├── components/
    └── charts/
```

Ressources web :

```text
app/src/main/assets/web/index.html
app/src/main/assets/web/app.js
app/src/main/assets/web/styles.css
```

Les tests reproduisent la même organisation sous `src/test` et
`src/androidTest`.

## 5. Modèles du domaine

Tous les modèles exposés sont immuables.

### 5.1 États et identifiants

```kotlin
enum class ServiceState { STOPPED, STARTING, RUNNING, STOPPING, FAILED }
enum class ArmState { DISARMED, READY, MANUAL_ARMED, BALANCE_ARMED, FAULT_LATCHED }
enum class ComponentId { IMU, ESTIMATOR, PD, USB, MOTOR_OUTPUT, TELEMETRY, LOG, WEB }
enum class ComponentStatus { OFF, STARTING, ACTIVE, BLOCKED, FAULT }
enum class Preset { SENSORS, FILTER, MANUAL_MOTORS, PD_SIMULATION, BALANCE }
enum class Axis { X, Y, Z }
```

### 5.2 Paramètres

```kotlin
data class RobotConfig(
    val schemaVersion: Int = 1,
    val axis: Axis = Axis.X,
    val imuSign: Int = 1,
    val zeroOffsetDeg: Double = 0.0,
    val alpha: Double = 0.98,
    val targetDeg: Double = 0.0,
    val kp: Double = 0.0,
    val kd: Double = 0.0,
    val vmax: Int = 6000,
    val motorIds: List<Int> = listOf(6, 7),
    val motorSigns: List<Int> = listOf(1, 1),
    val baudRate: Int = 1_000_000,
    val torqueLimit: Int = 1023,
    val imuTimeoutMs: Long = 100,
    val fallAngleDeg: Double = 35.0,
    val fallDurationMs: Long = 100,
    val manualTimeoutMs: Long = 300,
    val logCapacity: Int = 200_000,
    val webPort: Int = 8766,
)
```

`RobotConfigValidator` applique les domaines de la spécification. Il retourne
soit une configuration entièrement validée, soit une liste d'erreurs ; aucune
correction silencieuse ou application partielle n'est admise.

### 5.3 Échantillons et calculs

```kotlin
data class Vector3(val x: Double, val y: Double, val z: Double)

data class SensorSample(
    val kind: SensorKind,
    val values: Vector3,
    val sensorTimestampNs: Long,
    val receivedTimestampNs: Long,
)

data class Estimate(
    val accelAngleDeg: Double,
    val angleDeg: Double,
    val gyroRateDegPerSec: Double,
    val dtSec: Double,
)

data class ControlOutput(
    val targetDeg: Double,
    val errorDeg: Double,
    val rawCommand: Double,
    val boundedCommand: Int,
    val motorCommands: List<Int>,
    val saturated: Boolean,
)
```

### 5.4 Défauts

```kotlin
enum class FaultCode {
    IMU_STALE,
    IMU_TIMESTAMP_INVALID,
    ESTIMATE_INVALID,
    FALL_ANGLE,
    USB_DISCONNECTED,
    MOTOR_MISSING,
    BUS_ERROR,
    CONTROL_OVERRUN,
    INTERNAL_ERROR,
}

data class RobotFault(
    val code: FaultCode,
    val message: String,
    val firstTimestampNs: Long,
    val lastTimestampNs: Long,
    val occurrences: Long,
    val details: Map<String, String>,
)
```

`details` est réservé à des valeurs bornées et non sensibles destinées au
diagnostic. Il n'est jamais utilisé pour piloter une transition.

## 6. API interne commune

```kotlin
interface RobotRuntimeApi {
    val snapshots: StateFlow<RobotSnapshot>
    val urgentEvents: SharedFlow<RobotEvent>
    suspend fun execute(command: RobotCommand): CommandResult
}
```

### 6.1 Commandes

`RobotCommand` est une interface scellée avec les variantes suivantes :

- `ApplyPreset(preset)` ;
- `SetComponent(component, enabled)` ;
- `UpdateParameters(patch)` ;
- `ResetEstimator` ;
- `ConnectUsb(deviceId?)` ;
- `DisconnectUsb` ;
- `ScanBus(range)` ;
- `ArmManual` ;
- `ArmBalance` ;
- `ManualCommand(value, held)` ;
- `Disarm(reason)` ;
- `EmergencyStop(reason)` ;
- `AcknowledgeFault` ;
- `StartLog`, `StopLog`, `ClearLog`, `ExportLog` ;
- `StopService`.

Chaque commande est enveloppée dans :

```kotlin
data class CommandEnvelope(
    val commandId: String,
    val source: CommandSource,
    val receivedTimestampNs: Long,
    val command: RobotCommand,
)
```

`CommandSource` contient `LOCAL_UI`, `WEB(clientId)`, `NOTIFICATION`,
`WATCHDOG` ou `INTERNAL`.

### 6.2 Résultat

```kotlin
data class CommandResult(
    val commandId: String,
    val accepted: Boolean,
    val stateVersion: Long,
    val errorCode: CommandErrorCode?,
    val message: String?,
)
```

Les refus attendus ne lèvent pas d'exception. Les exceptions indiquent un bug
ou un échec d'adaptateur et sont converties en erreur interne ou défaut.

### 6.3 Snapshot

`RobotSnapshot` regroupe les sous-états immuables :

- `session` ;
- `service` ;
- `arm` ;
- `components` ;
- `config` ;
- `imu` ;
- `estimate` ;
- `control` ;
- `usb` ;
- `motors` ;
- `metrics` ;
- `log` ;
- `web` ;
- `warnings` et `fault` ;
- `lastAcceptedCommand` ;
- `stateVersion`.

Chaque mutation acceptée incrémente `stateVersion`. Le snapshot ne contient
aucune collection mutable.

## 7. Modèle de concurrence

### 7.1 Threads

| Exécuteur | Priorité et responsabilité |
| --- | --- |
| Thread UI principal | Compose uniquement |
| `robot-control` HandlerThread | callbacks IMU, état, filtre, PD, commandes métier |
| `robot-motor-io` thread unique | toutes les transactions USB/Feetech |
| `robot-watchdog` scheduler | contrôles de fraîcheur toutes les 20 ms |
| Ktor CIO | HTTP/WebSocket, jamais de logique métier directe |
| `robot-log` coroutine mono-thread | ingestion du journal compact |
| `Dispatchers.IO` | DataStore et génération/streaming CSV |

Le thread `robot-control` est l'unique propriétaire de `RobotRuntimeState`. Les
autres threads communiquent par messages immuables et ne modifient jamais
l'état directement.

### 7.2 Commandes ordinaires et urgentes

`CommandDispatcher` possède deux files :

- une file urgente non bloquante et bornée pour défaut, désarmement, arrêt et
  résultat critique moteur ;
- une file ordinaire bornée à 64 commandes.

Les commandes urgentes sont postées en tête du `Handler` de contrôle. Si la
file ordinaire est pleine, la nouvelle commande est refusée avec `BUSY` ; elle
n'est jamais supprimée silencieusement.

Les ticks capteur ne passent pas par la file de commandes ordinaires : ils sont
livrés directement sur le thread de contrôle par `SensorManager`.

### 7.3 Publication de l'état

`ControlRuntime` maintient un état mutable privé. `SnapshotPublisher` construit
un snapshot immuable :

- immédiatement pour changement d'armement, défaut, arrêt ou acquittement ;
- à 20 Hz maximum pour les données continues.

Le `StateFlow` conserve uniquement le dernier snapshot. Un consommateur lent ne
retarde donc jamais le contrôle.

## 8. Pipeline IMU et contrôle

### 8.1 Acquisition

`AndroidImuSource` enregistre un unique `SensorEventListener` pour
`TYPE_ACCELEROMETER` et `TYPE_GYROSCOPE` avec la cadence la plus rapide
autorisée par le téléphone. Le listener reçoit les événements sur le Handler du
thread `robot-control`.

Pour chaque événement :

1. copier les trois valeurs, car Android réutilise le tableau du callback ;
2. lire `event.timestamp` sans le remplacer ;
3. capturer `elapsedRealtimeNanos()` comme temps de réception ;
4. valider valeurs et monotonie ;
5. journaliser ou compter le rejet ;
6. stocker le dernier accel ou déclencher le tick gyro.

### 8.2 Tick gyro

```text
gyro callback
  → validation timestamp et dt
  → conversion rad/s vers deg/s
  → récupération du dernier accel frais
  → angle accel
  → filtre complémentaire
  → contrôle des seuils de chute
  → PD si actif
  → publication de la commande calculée
  → dépôt de la dernière commande moteur si armé
  → émission non bloquante vers le journal
```

La partie pure de ce chemin ne doit ni suspendre, ni verrouiller, ni allouer de
collections. Les objets de snapshot et de log sont produits après le calcul
critique.

### 8.3 Fonctions pures

Les fonctions suivantes sont sans I/O ni état global :

```kotlin
fun accelAngleDeg(values: Vector3, axis: Axis, sign: Int, offsetDeg: Double): Double?
fun complementaryStep(previous: Double?, gyroDps: Double, dtSec: Double,
                      accelAngleDeg: Double, alpha: Double): Double
fun pdStep(targetDeg: Double, angleDeg: Double, gyroDps: Double,
           kp: Double, kd: Double, vmax: Int): ControlBase
fun applyMotorSigns(base: Int, signs: List<Int>): List<Int>
```

Elles sont testées avec les vecteurs du dépôt de référence et des cas limites
numériques.

## 9. Supervision de sécurité

`SafetySupervisor` est divisé en deux parties :

- règles synchrones exécutées sur le thread de contrôle : estimation invalide,
  angle de chute, prérequis d'armement et invariants ;
- watchdog périodique : fraîcheur gyro, âge de la dernière commande moteur et
  progression du thread de contrôle.

Le watchdog lit uniquement des valeurs atomiques : dernier heartbeat du thread
de contrôle, dernier gyro, état d'armement et dernier résultat moteur. Il poste
un `CriticalFault` urgent ; il ne modifie pas l'état ni le bus lui-même.

`MotorIoScheduler` possède en plus un garde local : en état armé, une commande
dont la deadline est dépassée n'est pas écrite. Il remplace alors le slot par
zéro et signale `CONTROL_OVERRUN`.

### 9.1 Séquence de défaut

```text
détecteur → CriticalFault urgent
  → ControlRuntime bloque les commandes non nulles
  → MotorIoScheduler.enqueueSafetyStop()
  → sync_write(0, 0), puis torqueOff(6), torqueOff(7)
  → résultat best-effort retourné au contrôle
  → ArmState.FAULT_LATCHED
  → snapshot urgent + log
```

Le passage à `FAULT_LATCHED` n'attend pas la réussite du bus. Le détail de
l'échec est ajouté au défaut.

## 10. Transport USB et moteur

### 10.1 Contrat série

```kotlin
interface SerialTransport : Closeable {
    val identity: SerialIdentity
    fun write(bytes: ByteArray, timeoutMs: Int): Int
    fun read(target: ByteArray, timeoutMs: Int): Int
    fun purgeInput()
}
```

`AndroidUsbSerialTransport` encapsule un `UsbSerialPort` de
`usb-serial-for-android`. Il demande la permission via `UsbManager`, sélectionne
le CH340, ouvre le port et configure `1_000_000 / 8N1`.

Les événements attach/detach sont reçus par des receivers dynamiques actifs
uniquement pendant la session. Un detach signale immédiatement
`USB_DISCONNECTED`.

### 10.2 Codec Feetech

`FeetechPacketCodec` est un composant pur qui porte depuis la référence :

- construction et validation des paquets ;
- checksum ;
- entiers sign-magnitude ;
- ping, read, write et sync-write ;
- constantes de registres STS3215 utilisées.

Le codec ne connaît ni USB ni coroutines. Les paquets capturés dans les tests du
dépôt de référence deviennent des vecteurs de test locaux avec mention de leur
provenance.

### 10.3 Ordonnanceur moteur

Toutes les opérations bus sont exécutées sur `robot-motor-io`. L'ordonnanceur
contient :

- `AtomicReference<MotorFrame?> latestControlFrame` ;
- une file urgente pour zéro et couple off ;
- une file courte de transactions administratives ;
- un planificateur de télémétrie.

Une nouvelle trame de contrôle remplace la précédente si celle-ci n'a pas
encore été écrite. Le compteur `supersededMotorFrames` est incrémenté. Les
transactions administratives ne sont acceptées que désarmé.

Ordre de service à chaque réveil :

1. vider toutes les opérations urgentes ;
2. écrire la dernière trame de contrôle valide ;
3. exécuter au plus une transaction administrative ;
4. lire au plus un bloc de télémétrie arrivé à échéance ;
5. recommencer s'il reste du travail.

### 10.4 Groupe moteur

`Sts3215Gateway` qualifie les deux IDs. `ready=true` seulement si les deux
répondent. Une écriture de contrôle produit un unique paquet sync-write sur
`Goal_Velocity` contenant les deux valeurs signées.

Le mode vitesse et la limite de couple sont vérifiés désarmé. Aucune écriture
persistante en EEPROM n'est automatique : si le mode doit être changé, une
action distincte avec confirmation sera ajoutée ultérieurement.

## 11. Service Android

`RobotControlService` est un service démarré et lié :

- `START_NOT_STICKY` ;
- type foreground `specialUse` avec sous-type documenté « local robot control » ;
- promotion foreground immédiatement après démarrage ;
- notification mise à jour sur changement d'armement ou défaut ;
- binder local exposant `RobotRuntimeApi` ;
- pas de démarrage depuis `BOOT_COMPLETED` ou USB attach.

### 11.1 Verrous système

Pendant une session :

- un `PARTIAL_WAKE_LOCK` non référencé garde le CPU disponible ;
- un `WifiLock` haute performance est acquis uniquement lorsque le serveur web
  est actif et au moins un client est connecté ;
- chaque verrou a un timeout défensif et est renouvelé par le service ;
- tous les verrous sont libérés dans `finally` lors de l'arrêt.

### 11.2 Manifest

Le manifeste devra déclarer :

```text
android.permission.INTERNET
android.permission.ACCESS_NETWORK_STATE
android.permission.ACCESS_WIFI_STATE
android.permission.CHANGE_WIFI_STATE
android.permission.WAKE_LOCK
android.permission.FOREGROUND_SERVICE
android.permission.FOREGROUND_SERVICE_SPECIAL_USE
android.permission.POST_NOTIFICATIONS
android.hardware.usb.host (required=true)
```

L'application autorise explicitement le trafic HTTP clair pour le serveur LAN
V1. Le service n'est pas exporté. Le receiver de permission USB utilise une
action propre au package et n'est pas exporté.

## 12. Persistance

`DataStoreSettingsRepository` implémente :

```kotlin
interface SettingsRepository {
    val config: Flow<RobotConfig>
    suspend fun save(config: RobotConfig)
}
```

Les clés portent un préfixe `config_v1_`. `schemaVersion` permet une migration
ultérieure. Au premier lancement, les valeurs par défaut de `RobotConfig` sont
utilisées.

Séquence d'une mise à jour :

1. le dispatcher valide le patch et construit une nouvelle configuration ;
2. il applique atomiquement la configuration au runtime ;
3. il envoie la configuration complète dans un canal conflated de persistance ;
4. DataStore enregistre hors du thread de contrôle ;
5. un échec de persistance produit un avertissement, sans revenir sur la
   configuration de la session ;
6. le dernier enregistrement réussi sera restauré à la prochaine session.

Les états d'armement, sorties et défauts ne possèdent aucune clé DataStore.

## 13. Journal compact

### 13.1 Représentation

`PackedEventLog` évite 200 000 objets Kotlin riches. Il utilise des tableaux
primitifs circulaires de capacité fixe :

- `LongArray` pour temps et timestamps ;
- `ByteArray`/`IntArray` pour type, états, IDs, commandes et codes ;
- `FloatArray` pour capteurs, angles, gains et télémétrie ;
- `NaN` ou une sentinelle documentée pour un champ absent.

Les types et codes sont des tables stables versionnées. La cible mémoire est
inférieure à 32 Mio pour 200 000 lignes.

### 13.2 Ingestion

Le thread de contrôle appelle `EventLogger.tryRecord(record)`. Ce contrat ne
bloque jamais. Un canal borné de 8192 enregistre les événements vers le worker
`robot-log`. En cas de saturation, le plus ancien événement en attente est
perdu, un compteur atomique est incrémenté et un avertissement apparaît.

Quand le journal est arrêté, les métriques de sécurité continuent d'exister
dans le snapshot mais ne sont pas ajoutées au tampon.

### 13.3 Export

L'export est sérialisé : un seul export à la fois. Le worker de journal copie
les index et tableaux utiles vers un snapshot compact, puis libère le journal.
La conversion CSV s'effectue sur `Dispatchers.IO` vers un fichier du cache.

- l'application partage le fichier par `FileProvider` ;
- `GET /log.csv` streame le même fichier ;
- le fichier temporaire n'est pas une persistance de session et est supprimé au
  prochain export ou après 24 heures ;
- un export vide contient l'en-tête ;
- les timestamps nanosecondes sont écrits en entiers décimaux.

## 14. Serveur web et protocole

### 14.1 Routes

| Méthode | Route | Réponse |
| --- | --- | --- |
| GET | `/` | `index.html` |
| GET | `/app.js` | JavaScript embarqué |
| GET | `/styles.css` | Styles embarqués |
| GET | `/health` | État minimal sans effet de bord |
| GET | `/log.csv` | Export courant ou erreur explicite |
| WS | `/ws` | Commandes et télémétrie V1 |

Les fichiers sont servis depuis `assets/web`, avec cache désactivé en debug.
Il n'y a pas de CORS global. Le WebSocket accepte uniquement un `Origin`
correspondant au `Host` demandé ou une absence d'Origin pour les tests.

### 14.2 Enveloppes JSON

Commande client :

```json
{
  "v": 1,
  "id": "4dcf58d3-8d91-4df8-a20f-7fdfec67a523",
  "type": "update_parameters",
  "payload": {"kp": 12.0, "kd": 0.8}
}
```

Acquittement :

```json
{
  "v": 1,
  "type": "ack",
  "id": "4dcf58d3-8d91-4df8-a20f-7fdfec67a523",
  "ok": true,
  "stateVersion": "42"
}
```

Erreur :

```json
{
  "v": 1,
  "type": "ack",
  "id": "4dcf58d3-8d91-4df8-a20f-7fdfec67a523",
  "ok": false,
  "stateVersion": "42",
  "error": {"code": "NOT_DISARMED", "message": "Action interdite pendant l'armement"}
}
```

Événement serveur :

```json
{
  "v": 1,
  "type": "snapshot",
  "seq": "93",
  "serverTsNs": "2481299000123",
  "payload": {}
}
```

Les `Long` sont encodés en chaînes décimales afin d'éviter la perte de précision
JavaScript. Les nombres physiques restent des nombres JSON.

### 14.3 Commandes web V1

Les types snake_case sont :

```text
apply_preset, set_component, update_parameters, reset_estimator,
connect_usb, disconnect_usb, scan_bus,
arm_manual, arm_balance, manual_command,
disarm, emergency_stop, acknowledge_fault,
start_log, stop_log, clear_log, export_log, stop_service
```

`WebProtocolCodec` utilise une whitelist stricte par type : version inconnue,
champ obligatoire absent, type incorrect, valeur hors domaine ou champ payload
inconnu entraînent un rejet. La taille maximale d'une frame texte est 64 Kio.

### 14.4 Diffusion et clients lents

Chaque client possède :

- une file fiable de 32 messages pour ack, défaut et transition critique ;
- un slot remplaçable pour le dernier snapshot continu.

Un client dont la file fiable déborde est fermé. Les snapshots sont diffusés à
20 Hz maximum. Les commandes sont limitées à 50 par seconde et par connexion ;
les heartbeats manuels normaux sont envoyés à 10 Hz.

La connexion web n'est pas une autorité de sécurité. Toutes les commandes
valides sont ordonnées par réception ; les arrêts restent prioritaires.

## 15. Interface Compose

### 15.1 État et commandes

`RobotViewModel` :

- observe le `StateFlow<RobotSnapshot>` du binder ;
- expose un `UiState` dérivé, sans dupliquer l'état métier ;
- transmet les actions à `RobotRuntimeApi.execute` ;
- expose les résultats de commandes comme événements UI temporaires ;
- ne calcule ni filtre, ni PD, ni état d'armement.

Quand le service est arrêté, le ViewModel affiche un snapshot local minimal et
propose uniquement `startService`.

### 15.2 Navigation

Une activité Compose contient cinq destinations :

1. Tableau de bord ;
2. IMU et contrôle ;
3. Moteurs ;
4. Courbes et journal ;
5. Réseau et diagnostic.

Une barre persistante en état armé affiche l'armement et un bouton STOP. Sur les
écrans larges, les destinations peuvent devenir des panneaux côte à côte sans
changer leur modèle de données.

### 15.3 Courbes

`ChartBuffer` conserve uniquement la fenêtre visible, par défaut 30 secondes à
20 Hz. Il reçoit les snapshots et stocke des tableaux primitifs circulaires.
Compose `Canvas` dessine les séries, limites et marqueurs. Aucune donnée du
`ChartBuffer` n'est utilisée par le contrôle ou le CSV.

## 16. Page web

`app.js` maintient un état local issu exclusivement des snapshots et ack :

- connexion/reconnexion WebSocket avec backoff 0,5 s à 5 s ;
- envoi d'une commande avec UUID et attente de son ack ;
- désactivation visuelle d'une action pendant son ack ;
- remplacement complet de l'état à chaque snapshot ;
- tampon graphique local de 30 secondes ;
- heartbeat manuel à 10 Hz uniquement pendant l'appui ;
- arrêt du heartbeat sur `pointerup`, `pointercancel`, `blur`,
  `visibilitychange` ou fermeture WebSocket.

La page ne mémorise aucun paramètre dans `localStorage` : l'application Android
est l'unique source de configuration persistante.

## 17. Démarrage et arrêt d'une session

### 17.1 Démarrage

```text
MainActivity.startForegroundService
  → RobotControlService.startForeground
  → créer RobotSessionContainer
  → démarrer control thread et watchdog
  → charger RobotConfig validée
  → créer runtime désarmé
  → démarrer les composants du dernier préréglage sans USB ni sortie automatique
  → publier RUNNING/DISARMED
```

Le dernier préréglage peut être restauré, mais ses composants matériels restent
désactivés jusqu'à une action explicite.

### 17.2 Arrêt

```text
bloquer les commandes ordinaires
  → passer STOPPING
  → demander zéro puis torque off (best effort)
  → arrêter télémétrie et USB
  → arrêter IMU
  → arrêter serveur web
  → figer/fermer journal
  → annuler scopes et watchdog
  → libérer wake locks
  → fermer les threads
  → retirer notification et stopSelf
```

Chaque étape possède un timeout. L'échec d'une étape est journalisé mais
n'empêche pas les suivantes.

## 18. Gestion des erreurs

- Les erreurs attendues deviennent `CommandResult` refusés ou `RobotWarning`.
- Les erreurs capteur ou moteur pertinentes deviennent des `RobotFault`.
- Une coroutine enfant ne peut pas annuler silencieusement toute la session :
  les scopes utilisent une supervision explicite et remontent les exceptions.
- Aucune exception n'est absorbée sans compteur, log ou résultat utilisateur.
- Les messages UI sont en français ; codes, noms de champs et logs techniques
  restent stables en anglais.
- Les logs Android ne contiennent ni secret, ni contenu massif de CSV, ni dump
  permanent de chaque échantillon en fonctionnement normal.

## 19. Tests

### 19.1 Tests JVM purs

Obligatoires pour :

- `RobotConfigValidator` ;
- machines d'états service/composants/armement ;
- dépendances et préréglages ;
- angle accéléromètre sur X/Y/Z et signes ;
- filtre complémentaire, initialisation et timestamps ;
- formule PD, saturation et signes moteurs ;
- classification et verrouillage des défauts ;
- priorité des commandes de sécurité ;
- codec Feetech, checksum et sign-magnitude ;
- paquets sync-write pour IDs 6 et 7 ;
- `WebProtocolCodec` et whitelist stricte ;
- journal circulaire, débordement et CSV ;
- calcul des métriques et percentiles.

Les tests du dépôt de référence sont portés comme résultats attendus, sans
copier sa structure Python.

### 19.2 Tests d'intégration JVM

Créer les doubles suivants :

- `FakeClock` contrôlable ;
- `ReplayImuSource` ;
- `FakeSerialTransport` ;
- `MockSts3215Bus` simulant deux IDs ;
- `InMemorySettingsRepository` ;
- `FakeWebServer` ;
- `InMemoryEventLogger`.

Scénarios : replay IMU → filtre → PD → trame sync-write, moteur manquant, gyro
périmé, changement de préréglage, commande web → même dispatcher, et arrêt
pendant une transaction bus.

### 19.3 Tests instrumentés Android

- création/arrêt du foreground service ;
- notification et action STOP ;
- liaison activité/service après recréation de l'activité ;
- DataStore et migration de schéma ;
- serveur Ktor et WebSocket sur loopback ;
- permissions USB via adaptateur abstrait ;
- rendu et actions principales Compose ;
- export via `FileProvider`.

### 19.4 Tests sur téléphone sans moteur

- cadence réelle accel/gyro ;
- écran éteint pendant 30 minutes ;
- latence et jitter sous interface Android, web et journal actifs ;
- serveur accessible sur le LAN ;
- reconnexion après changement de réseau ;
- consommation mémoire avec 200 000 lignes ;
- absence de croissance de threads ou buffers.

### 19.5 Tests matériels

Les tests matériels suivent strictement l'ordre du CDC : identification CH340,
scan désarmé, signes à faible vitesse, paliers roues levées, télémétrie,
simulation PD, défauts injectés puis boucle complète.

Aucun test moteur automatique n'est exécuté par la suite de tests standard.

## 20. Critères de qualité et instrumentation

Le build doit fournir :

- `assembleDebug` ;
- tests JVM ;
- lint Android ;
- tests instrumentés sélectionnés ;
- APK copié dans `dist/balancing-robot-debug.apk` ;
- SHA-256 ;
- version, date et révision Git affichées dans l'écran diagnostic.

L'instrumentation runtime expose :

- fréquences et percentiles définis dans la spécification ;
- temps de la dernière activité de chaque thread critique ;
- profondeur des files ;
- commandes moteur remplacées ;
- événements de journal perdus ;
- clients web et débit de messages ;
- mémoire estimée du journal.

## 21. Lots d'implémentation

Chaque lot doit compiler et conserver la commande moteur physiquement inactive
tant que son propre essai matériel n'est pas demandé.

### Lot 0 — Faisabilité des dépendances

- catalogue de versions et Compose Hello World ;
- Ktor `/health` + WebSocket sur le téléphone ;
- énumération Android USB Host et identification CH340 ;
- scripts build/install/publish reproductibles.

Sortie : dépendances validées sur le téléphone, aucune commande servo.

### Lot 1 — Domaine et runtime simulé

- modèles, validation, machines d'états ;
- filtre, PD, sécurité et métriques ;
- dispatcher, snapshots et doubles ;
- tests JVM issus de la référence.

Sortie : replay complet jusqu'aux commandes moteurs simulées.

### Lot 2 — Service, IMU et diagnostic

- foreground service, notification, wake lock ;
- acquisition native et qualification de cadence ;
- écran IMU, filtre, simulation PD ;
- journal compact et export.

Sortie : exigences Capteurs, Filtre et Simulation PD validées sans moteur.

### Lot 3 — Interfaces complètes

- navigation Compose et courbes ;
- protocole web, page miroir et export ;
- persistance DataStore ;
- parité fonctionnelle Android/web.

Sortie : toutes les commandes non motrices disponibles dans les deux IHM.

### Lot 4 — USB et moteurs manuels

- transport CH340, codec Feetech et groupe STS3215 ;
- ordonnanceur, télémétrie et scan ;
- armement manuel, deadman et paliers ;
- défauts USB/moteurs.

Sortie : caractérisation roues levées terminée.

### Lot 5 — Boucle complète

- raccordement PD au slot moteur ;
- watchdogs et défauts intégrés ;
- mesures de latence et essais de chute ;
- validation progressive du préréglage Équilibrage.

Sortie : premier jalon du CDC satisfait ; le réglage d'équilibre stable reste
une phase distincte.

## 22. Fichiers de documentation produits pendant l'implémentation

Chaque lot ajoute un compte rendu dans `docs/validation/` avec :

- version APK et révision ;
- téléphone et version Android ;
- matériel connecté ;
- commandes de build/test ;
- mesures observées ;
- exigences validées ;
- écarts, incidents et décision de passage au lot suivant.

Les modifications futures d'une décision structurante mettent à jour dans le
même changement le CDC, la spécification et cette conception si nécessaire.

## 23. Risques techniques et parades

| Risque | Parade de conception |
| --- | --- |
| Ktor CIO incompatible avec le runtime Android cible | Test Lot 0, repli `ServerSocket` défini |
| I/O USB bloque le contrôle | Thread moteur séparé, slot dernière commande, deadlines |
| Télémétrie affame les écritures | Ordonnanceur à priorité stricte |
| Service endormi écran éteint | Foreground service + partial wake lock |
| Wi-Fi suspendu | WifiLock seulement pendant les clients web |
| Pression mémoire du journal | Tableaux primitifs, capacité bornée, métrique mémoire |
| Client web lent | Snapshot remplaçable, file critique bornée, fermeture du client |
| Conflit de commandes entre clients | Sérialisation centrale, ordre et source visibles |
| Défaut pendant une I/O | File urgente et état verrouillé sans attendre le bus |
| Processus tué après commande non nulle | Limite connue, non résolue sans watchdog matériel |

## 24. Traçabilité

| Élément de spécification | Élément de conception |
| --- | --- |
| États service/armement | `RobotControlService`, `ControlRuntime`, machines d'états |
| Composants et préréglages | `ComponentCoordinator` |
| IMU et estimation | `AndroidImuSource`, `ImuPipeline`, `domain.estimation` |
| PD et saturation | `domain.control` |
| Défauts | `SafetySupervisor`, watchdog, file urgente |
| USB/STS3215 | transport, codec, bus, scheduler, gateway |
| Commandes partagées | `RobotRuntimeApi`, `CommandDispatcher` |
| Snapshot | `SnapshotPublisher`, `StateFlow` |
| Application Android | `RobotViewModel`, Compose |
| Page web | Ktor, codec JSON, assets web |
| Journal et CSV | `PackedEventLog`, `CsvExporter` |
| Persistance | `DataStoreSettingsRepository` |
| Performances | métriques runtime et validations par lot |

## 25. Validation requise avant implémentation

La validation de cette conception autorisera le Lot 0 uniquement. Elle confirme
notamment :

1. le maintien d'un seul module Gradle ;
2. les dépendances et versions retenues ;
3. Ktor CIO avec le repli défini ;
4. l'architecture à état mono-écrivain ;
5. la séparation contrôle et I/O moteur ;
6. les contrats Kotlin principaux ;
7. le protocole web V1 ;
8. DataStore sans base de données ;
9. le journal compact borné ;
10. les cinq lots et leurs barrières de validation.
