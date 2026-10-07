# FEAT-ATT-001 — Spécification fonctionnelle et technique

**Version :** 1.0  
**Date :** 2026-10-07  
**Statut :** spécification validée pour implémentation, validation finale en attente  
**Document amont :** `FEAT-ATT-001-cahier-des-charges.md`

## 1. Modèle de configuration

La future configuration ajoute un enum stable :

```kotlin
enum class AttitudeFilterMode {
    LEGACY_COMPLEMENTARY,
    QUATERNION_COMPLEMENTARY,
}

data class RobotConfig(
    // Champs existants inchangés.
    val attitudeFilterMode: AttitudeFilterMode =
        AttitudeFilterMode.LEGACY_COMPLEMENTARY,
)
```

Le champ DataStore utilise la clé `attitude_filter_mode` et la valeur de
`enum.name`. Une clé absente ou une chaîne inconnue donne
`LEGACY_COMPLEMENTARY`. L'ajout est rétrocompatible et ne nécessite pas de
changer `RobotConfig.schemaVersion` ni la version 1 du protocole Web.

Le champ JSON est `attitudeFilterMode`. Il est ajouté aux snapshots de
configuration et accepté dans la commande de configuration existante. Une
ancienne page ne l'envoyant pas conserve la valeur courante.

## 2. Contrat commun d'estimation

Les deux implémentations satisfont le même contrat conceptuel :

```kotlin
data class AttitudeEstimate(
    val filterMode: AttitudeFilterMode,
    val accelAngleDeg: Double,
    val thetaDeg: Double,
    val pitchRateDegPerSec: Double,
    val yawRateDegPerSec: Double,
    val dtSec: Double,
)

interface AttitudeEstimator {
    val mode: AttitudeFilterMode
    fun updateAlpha(alpha: Double)
    fun reset()
    fun step(
        accelBodyMps2: Vector3,
        gyroBodyRadPerSec: Vector3,
        dtSec: Double,
        axis: Axis,
        imuSign: Int,
        zeroOffsetDeg: Double,
    ): AttitudeEstimate?
}
```

Le contrat est pur Kotlin. Il n'accepte aucun type Android et ne réalise
aucune I/O. `null` représente une estimation invalide et est converti par le
runtime en `FaultCode.ESTIMATE_INVALID`.

`accelAngleDeg` reste calculé par la fonction historique pour les diagnostics.
`pitchRateDegPerSec` reste la composante choisie par `axis`, multipliée par
`imuSign`, puis convertie en degrés par seconde.

## 3. Repères et conventions quaternion

- `B` est le repère téléphone Android.
- `W` est le repère monde dont `+Z` est la verticale haute.
- Les vecteurs gyro entrants sont en rad/s dans `B`.
- L'accéléromètre au repos pointe vers la verticale haute apparente ; sa valeur
  normalisée est notée `a_B`.
- Le quaternion unitaire `q_WB = (w, x, y, z)`, scalaire en premier, transforme
  un vecteur du téléphone vers le monde :

```text
v_W = q_WB ⊗ (0, v_B) ⊗ conj(q_WB)
```

La multiplication est hamiltonienne. Après chaque prédiction ou correction,
le quaternion est renormalisé. Les quaternions équivalents `q` et `-q` doivent
être traités comme la même attitude.

## 4. Filtre historique

`LEGACY_COMPLEMENTARY` conserve sans modification fonctionnelle :

```text
theta_acc = accelAngleDeg(accel, axis, imuSign, zeroOffsetDeg)
pitch_rate = selectedGyroRateDegPerSec(gyro, axis, imuSign)

si aucune estimation précédente :
    theta = theta_acc
sinon :
    theta = alpha × (theta_precedent + pitch_rate × dt)
            + (1 - alpha) × theta_acc

yaw_rate = degrees(gyro_B.z)
```

Ses conditions d'erreur et sa mise à jour de `alpha` restent celles du code
actuel.

## 5. Filtre complémentaire quaternion

### 5.1 Validation des entrées

Le pas est rejeté si :

- un composant accel ou gyro n'est pas fini ;
- `dtSec` n'est pas fini ou est inférieur ou égal à zéro ;
- `alpha` n'appartient pas à `[0, 1]` ;
- `imuSign` ne vaut ni `-1` ni `+1` ;
- `zeroOffsetDeg` n'est pas fini ;
- la norme accel est nulle ou non normalisable ;
- une opération quaternion produit une valeur non finie ou non normalisable.

Le filtre n'ajoute pas de seuil haut de norme accel dans cette version. Il
utilise la même hypothèse gravitaire que le filtre historique.

### 5.2 Initialisation

À la première mesure valide après création ou `reset()` :

```text
a_B = normalize(accel_B)
up_W = (0, 0, 1)
q_WB = shortestRotation(a_B, up_W)
```

`shortestRotation(from, to)` retourne le quaternion de rotation minimale qui
aligne les deux vecteurs. Le yaw initial est donc fixé par cette convention,
sans information magnétique. Si les vecteurs sont antiparallèles à la
tolérance numérique et qu'aucun axe minimal unique ne peut être choisi de
façon sûre, l'initialisation est rejetée au lieu d'inventer un cap.

La première estimation utilise cette attitude sans intégrer rétroactivement
une période antérieure au reset.

### 5.3 Prédiction gyro

Pour `omega_B = (gx, gy, gz)` et `phi = ||omega_B|| × dt` :

```text
si ||omega_B|| est suffisamment petit :
    delta_q = normalize((1, 0.5×gx×dt, 0.5×gy×dt, 0.5×gz×dt))
sinon :
    axis_B = omega_B / ||omega_B||
    delta_q = (cos(phi/2), axis_B × sin(phi/2))

q_pred = normalize(q_previous ⊗ delta_q)
```

La multiplication à droite correspond à une vitesse angulaire exprimée dans
le repère téléphone.

### 5.4 Correction accéléromètre

La correction ne doit introduire aucune rotation volontaire autour de la
verticale du monde :

```text
a_B = normalize(accel_B)
a_W_pred = rotate(q_pred, a_B)
q_full_correction = shortestRotation(a_W_pred, up_W)
q_weighted = slerp(identity, q_full_correction, 1 - alpha)
q_new = normalize(q_weighted ⊗ q_pred)
```

La rotation minimale entre `a_W_pred` et `up_W` corrige roll et pitch sans
ajouter une référence absolue de yaw. La `slerp` utilise le chemin court et
gère le signe équivalent des quaternions.

Conséquences voulues :

- `alpha = 1` : `q_new = q_pred` après l'initialisation ;
- `alpha = 0` : correction d'inclinaison complète sur l'échantillon courant ;
- une modification de `alpha` seule ne réinitialise pas `q_previous`.

### 5.5 Extraction de l'angle d'équilibrage

La verticale du monde exprimée dans le téléphone est :

```text
up_B = rotate(conj(q_new), up_W)
```

L'angle brut suit les conventions existantes :

```text
Axis.X : atan2(up_B.y, up_B.z)
Axis.Y : atan2(-up_B.x, sqrt(up_B.y² + up_B.z²))
Axis.Z : yaw relatif extrait de q_new, référencé à l'initialisation
```

Puis :

```text
thetaDeg = imuSign × degrees(angle_brut) - zeroOffsetDeg
```

Les axes X et Y sont les axes supportés pour l'équilibrage gravitaire. L'axe Z
reste disponible pour compatibilité et diagnostic, mais son angle est relatif
et peut dériver sans magnétomètre.

### 5.6 Extraction de la vitesse de yaw

Le gyro est projeté dans le monde avec l'attitude corrigée :

```text
omega_W = rotate(q_new, gyro_B)
yawRateDegPerSec = degrees(omega_W.z)
```

Cette valeur alimente le contrôleur yaw et les critères de vitesse yaw qui
consomment actuellement le gyro Z. Elle n'est pas intégrée pour fournir un cap
absolu.

## 6. Sélection et machine d'état

```text
mode configuré absent/invalide
        └─► LEGACY_COMPLEMENTARY

DISARMED ou READY
  └─ demande d'un autre mode valide
       ├─ valider le mode et l'état d'armement
       ├─ remplacer l'estimateur actif
       ├─ reset
       ├─ persister la configuration acceptée
       └─ état INITIALIZING jusqu'à la première estimation valide

MANUAL_ARMED, BALANCE_ARMED ou FAULT_LATCHED
  └─ demande de changement
       └─ rejet explicite, aucun état ni préférence modifié
```

Une demande répétant le mode actif est idempotente : elle ne déclenche ni
reset ni interruption de l'estimation.

## 7. Ordre du tick de contrôle

Sur chaque nouvel événement gyro valide :

1. calculer `dt` depuis les timestamps capteur ;
2. récupérer le dernier vecteur accel complet ;
3. exécuter l'estimateur actif avec les deux vecteurs complets ;
4. arrêter le tick avec `ESTIMATE_INVALID` si aucune estimation valide n'est
   disponible ;
5. alimenter la boucle vitesse avec le `theta` courant et le yaw rate estimé ;
6. calculer le PD de pitch avec `theta` et le gyro de pitch brut sélectionné ;
7. calculer la boucle yaw avec le yaw rate de l'estimateur actif ;
8. mélanger, borner, publier les diagnostics et journaliser.

Le filtre actif doit donc être évalué avant les consommateurs de `theta` et de
yaw du même tick. Aucune sortie de l'échantillon précédent ne doit être utilisée
uniquement pour éviter ce réordonnancement.

## 8. Protocole et IHM

### 8.1 Android

Le panneau IMU/filtre ajoute un sélecteur explicite avec les deux libellés du
cahier des charges. Il est activé uniquement dans `DISARMED` et `READY`.
Changer la sélection utilise le chemin central `updateRobotConfig`, puis
affiche le mode confirmé par le service et non une valeur optimiste locale.

### 8.2 Web

La page Web ajoute le même sélecteur. La commande de configuration inclut :

```json
{ "attitudeFilterMode": "QUATERNION_COMPLEMENTARY" }
```

Le service valide l'état d'armement avant application. Le Web réaffiche la
valeur issue du snapshot confirmé. Un rejet contient un motif lisible et ne
laisse pas le sélecteur dans un état différent du service.

### 8.3 Persistance

La préférence n'est écrite qu'après validation complète et acceptation de la
commutation. Au redémarrage, le service crée directement l'estimateur du mode
persisté, qui reste non initialisé jusqu'au premier échantillon valide.

## 9. Diagnostics et journalisation

Les snapshots de diagnostic et le CSV de contrôle ajoutent au minimum :

```text
attitude_filter_configured
attitude_filter_active
attitude_estimator_state
attitude_estimator_reset_count
theta_estimated_deg
pitch_rate_used_dps
yaw_rate_estimated_dps
```

`attitude_estimator_state` prend `INITIALIZING`, `ACTIVE` ou `INVALID`. Les
colonnes historiques d'angle et de yaw restent présentes et conservent leurs
noms lorsque leur sémantique publique ne change pas. Les nouveaux champs
permettent d'interpréter leur provenance.

## 10. Gestion des erreurs

| Situation | Réaction obligatoire |
| --- | --- |
| Valeur persistée inconnue | Filtre historique et diagnostic de repli |
| Commutation demandée armé | Rejet, aucune persistance, filtre courant intact |
| Échantillon non fini | Estimation invalide et chemin de sécurité existant |
| Accel non normalisable à l'initialisation | Rester `INITIALIZING`, aucune commande fraîche |
| Quaternion non normalisable | État `INVALID`, défaut `ESTIMATE_INVALID` |
| Changement d'alpha valide | Application au filtre actif sans reset |
| Même mode redemandé | Succès idempotent sans reset |
| Redémarrage du service | Recréation depuis le choix persisté puis initialisation IMU |
