# FEAT-ATT-001 — Conception détaillée

**Version :** 1.0  
**Date :** 2026-10-07  
**Statut :** conception en cours d'implémentation  
**Documents amont :** cahier des charges et spécification FEAT-ATT-001

## 1. Décisions structurantes

- Le filtre historique reste une implémentation de référence, sans réécriture
  mathématique opportuniste.
- Un contrat `AttitudeEstimator` isole les contrôleurs du filtre choisi.
- Un seul estimateur est instancié et mis à jour à la fois.
- Le quaternion et les opérations vectorielles sont implémentés en Kotlin pur,
  sans bibliothèque externe.
- Le service reste l'autorité pour la configuration, la persistance et le
  droit de commuter.
- Android et le Web affichent toujours la valeur confirmée par le service.
- Les contrôleurs ne connaissent pas `AttitudeFilterMode` ; ils consomment
  uniquement `AttitudeEstimate`.

## 2. Découpage futur du code

Sources de domaine proposées :

```text
domain/estimation/
├── AttitudeEstimator.kt
├── LegacyComplementaryAttitudeEstimator.kt
├── Quaternion.kt
├── QuaternionAttitudeEstimator.kt
└── AttitudeEstimatorFactory.kt
```

`ComplementaryEstimator.kt` peut être conservé comme primitive interne du
wrapper historique afin de limiter le risque de régression.

Fichiers existants concernés lors du futur codage :

- modèles et validation de `RobotConfig` ;
- `RobotSettings` pour la clé DataStore ;
- `SimulatedControlRuntime` et `RobotControlService` pour le contrat et l'ordre
  du tick ;
- diagnostics, journal de contrôle et protocole Web ;
- panneaux de réglage Android et Web ;
- tests de l'estimation, du runtime, de la configuration et du protocole.

## 3. Noyau mathématique

### 3.1 Type quaternion

Le type interne est immuable :

```kotlin
data class Quaternion(
    val w: Double,
    val x: Double,
    val y: Double,
    val z: Double,
)
```

Il fournit uniquement les opérations nécessaires et testées :

- norme et normalisation sûre ;
- conjugué ;
- produit hamiltonien ;
- rotation d'un `Vector3` ;
- quaternion incrémental depuis un vecteur de rotation ;
- rotation minimale entre deux vecteurs unitaires ;
- `slerp` identité-vers-correction avec chemin court.

Chaque opération pouvant échouer sur une entrée dégénérée retourne un résultat
explicite nullable ou contrôlé ; aucun `NaN` ne doit être propagé.

### 3.2 État du filtre quaternion

`QuaternionAttitudeEstimator` possède uniquement :

- `alpha` validé ;
- `orientationWb: Quaternion?` ;
- un compteur de resets utile au diagnostic.

`reset()` efface l'orientation. `updateAlpha()` ne l'efface pas. Le filtre ne
stocke ni commande moteur, ni cible, ni état de contrôleur.

### 3.3 Stabilité numérique

- normaliser après initialisation, prédiction et correction ;
- utiliser l'approximation petit angle pour éviter une division par une norme
  gyro quasi nulle ;
- borner dans `[-1, 1]` les produits scalaires passés à une fonction inverse ;
- choisir le signe de quaternion donnant le chemin court pour la `slerp` ;
- rejeter l'initialisation gravitaire antiparallèle ambiguë plutôt que choisir
  silencieusement un yaw arbitraire ;
- ne jamais intégrer le temps écoulé avant le premier échantillon ou pendant
  un trou de données déclaré périmé.

## 4. Adaptation du filtre historique

`LegacyComplementaryAttitudeEstimator` adapte les fonctions existantes au
nouveau contrat :

1. calcul de `accelAngleDeg` inchangé ;
2. sélection et signe gyro inchangés ;
3. appel du `ComplementaryEstimator` existant ;
4. yaw rate égal à `degrees(gyroBody.z)` comme aujourd'hui ;
5. construction d'`AttitudeEstimate`.

Les tests actuels de `ComplementaryEstimator` restent inchangés et sont
complétés par des tests de l'adaptateur. Cette stratégie évite qu'un refactoring
nécessaire au sélecteur modifie la référence historique.

## 5. Factory et changement de mode

`AttitudeEstimatorFactory.create(mode, alpha)` construit exactement une
implémentation. Le propriétaire du filtre compare le nouveau mode au mode
actif :

```text
mode identique
  → mise à jour normale des autres paramètres, aucun reset

mode différent + état DISARMED/READY
  → créer le nouvel estimateur
  → publier INITIALIZING
  → rendre le nouvel estimateur actif
  → persister la configuration acceptée

mode différent + autre état
  → refuser avant toute mutation
```

La vérification d'armement et le remplacement sont sérialisés sur le thread de
contrôle. Il ne doit exister aucun tick où le mode annoncé et l'instance active
diffèrent.

## 6. Intégration au runtime

Le runtime reçoit désormais les deux vecteurs complets. Il sépare explicitement
estimation et contrôle afin que le même résultat courant alimente tous les
consommateurs :

```text
accel_B + gyro_B + dt
          │
          ▼
  AttitudeEstimator actif
          │
          ├─ thetaDeg ───────────────► boucle vitesse + PD pitch
          ├─ pitchRateDegPerSec ─────► terme D du PD pitch
          └─ yawRateDegPerSec ───────► boucle yaw + arrêt physique
```

Le calcul de la boucle vitesse intervient après l'estimation du tick courant.
Le PD continue à utiliser le gyro de pitch brut sélectionné et non une dérivée
numérique de `theta`. Le contrôleur yaw et le critère d'arrêt yaw utilisent la
sortie du filtre actif.

Si l'estimateur est `INITIALIZING` ou `INVALID`, le runtime ne calcule aucune
nouvelle commande de contrôle et suit le traitement de faute existant.

## 7. Configuration, validation et persistance

`RobotConfigValidator` accepte les deux valeurs typées de
`AttitudeFilterMode`. La désérialisation DataStore est tolérante : clé absente
ou inconnue vers `LEGACY_COMPLEMENTARY`, sans invalider tous les autres
réglages.

Le flux de changement est :

```text
Android/Web
  → RobotConfig candidate
  → validation de tous les champs
  → contrôle de l'état d'armement si le mode change
  → application atomique au runtime
  → persistance hors thread de contrôle
  → snapshot confirmé vers Android/Web
```

Si la persistance échoue après application, le défaut est exposé et la valeur
reste active pour la session ; le système ne commute pas une seconde fois en
plein tick. Le prochain démarrage retombe sur la dernière valeur persistée
valide.

## 8. Interfaces utilisateur

### 8.1 Android

Dans l'onglet IMU, avant le réglage `Alpha filtre complémentaire` :

- sélecteur à choix unique ;
- texte du filtre actif et état d'initialisation ;
- sélecteur désactivé hors `DISARMED`/`READY` ;
- message « Désarmez le robot pour changer de filtre » lorsqu'il est bloqué.

Le changement est envoyé immédiatement comme les autres paramètres éditables,
mais l'IHM attend le snapshot du service avant de considérer le choix appliqué.

### 8.2 Web

Le panneau IMU reproduit les deux choix et la même règle d'activation. Le
JavaScript ne maintient pas une seconde logique d'estimation. À chaque snapshot,
il remplace la valeur locale par `attitudeFilterMode` confirmé. Une erreur de
commande restaure visuellement le choix actif et affiche le motif.

## 9. Diagnostics et CSV

Le snapshot IMU est enrichi sans renommer les champs publics existants. Les
nouveaux champs de provenance sont ajoutés à Android, au JSON Web et au CSV.

Le CSV doit permettre de comparer deux sessions en conservant :

- timestamps capteur et reçu ;
- accel XYZ et gyro XYZ bruts ;
- `dt` ;
- `alpha` ;
- mode actif ;
- angle accel historique ;
- `theta` estimé ;
- gyro pitch utilisé ;
- yaw rate estimé ;
- état et nombre de resets de l'estimateur.

Aucune écriture supplémentaire n'est effectuée depuis le callback IMU : le
mécanisme de journal en mémoire existant est réutilisé.

## 10. Concurrence et performance

- calcul sur le thread de contrôle existant ;
- aucune coroutine, aucun verrou UI et aucune I/O dans `step()` ;
- configuration remplacée de façon atomique ou sérialisée ;
- aucune instance inactive mise à jour en arrière-plan ;
- allocations par tick réduites aux objets déjà acceptés par le runtime, avec
  possibilité d'optimisation seulement après mesure ;
- temps estimateur et cadence IMU mesurés pendant la validation téléphone.

Le budget d'acceptation est l'absence de baisse mesurable de la cadence IMU
cible de 200 Hz et l'absence d'augmentation de la latence de contrôle p95 de
plus de 1 ms par rapport au filtre historique sur le même téléphone.

## 11. Ordre de codage futur

1. Enum, contrat commun et tests de compatibilité du wrapper historique.
2. Primitives quaternion et tests mathématiques purs.
3. Estimateur quaternion et tests de trajectoires synthétiques.
4. Factory, sélection, reset et tests de transition désarmée/armée.
5. Réordonnancement du runtime avec vecteurs gyro/accel complets.
6. Persistance et protocole Web rétrocompatible.
7. Sélecteurs Android et Web avec retour d'erreur confirmé.
8. Diagnostics et CSV.
9. Tests complets, mesures téléphone, puis essais roues levées.

## 12. Interdictions d'implémentation

- Ne pas remplacer silencieusement le filtre historique.
- Ne pas commuter sur une valeur locale avant confirmation du service.
- Ne pas faire tourner les deux filtres en permanence pour faciliter le debug.
- Ne pas transférer approximativement l'état scalaire vers le quaternion.
- Ne pas réinitialiser l'estimateur lors d'un simple changement d'`alpha`.
- Ne pas utiliser l'accéléromètre comme référence absolue de yaw.
- Ne pas ajouter de magnétomètre ou de bibliothèque AHRS dans cette feature.
- Ne pas modifier les gains de contrôle ou les réglages utilisateur existants.
