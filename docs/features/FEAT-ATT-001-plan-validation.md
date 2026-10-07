# FEAT-ATT-001 — Plan de validation

**Version :** 1.0  
**Date :** 2026-10-07  
**Statut :** à exécuter après validation documentaire et codage  
**Documents amont :** cahier des charges, spécification et conception FEAT-ATT-001

## 1. Validation documentaire avant codage

La revue utilisateur doit confirmer :

- les deux modes et leurs libellés ;
- le filtre historique comme repli en l'absence de préférence ;
- la persistance du dernier choix ;
- la disponibilité du sélecteur dans Android et le Web ;
- l'interdiction de commuter hors `DISARMED` et `READY` ;
- la projection du gyro sur la verticale du monde pour le yaw quaternion ;
- l'absence de magnétomètre et de correction adaptative d'accélération ;
- le maintien intégral des contrôleurs et gains existants.

Aucun codage ne commence avant cette validation.

## 2. Tests JVM du filtre historique

- rejouer tous les tests existants de `ComplementaryEstimator` sans modifier
  leurs valeurs attendues ;
- comparer directement l'ancien runtime et le wrapper historique sur les mêmes
  séries accel/gyro ;
- vérifier initialisation accel, intégration gyro, `alpha = 0`, `alpha = 1` et
  changement d'alpha sans reset ;
- vérifier X, Y, Z, signe et offset ;
- vérifier que le yaw rate historique reste exactement `degrees(gyro.z)` ;
- vérifier que le mode historique ne modifie aucune sortie de contrôle à
  configuration et échantillons identiques.

## 3. Tests JVM des primitives quaternion

- identité, conjugué, multiplication et norme ;
- rotation de vecteurs sur 90° autour de X, Y et Z ;
- composition de rotations non commutatives ;
- invariance des rotations entre `q` et `-q` ;
- delta quaternion pour vitesse nulle, petit angle et angle fini ;
- rotation minimale entre vecteurs parallèles et orthogonaux ;
- rejet explicite du cas antiparallèle ambigu à l'initialisation ;
- `slerp` aux poids 0, 0,5 et 1 avec chemin court ;
- absence de `NaN` et quaternion final normalisé à `1 ± 1e-12` sur une longue
  séquence synthétique.

## 4. Tests JVM du filtre quaternion

### Attitudes statiques

- téléphone à plat : `theta = 0°` avant offset ;
- roll X à `+30°` et `-30°` ;
- pitch Y à `+30°` et `-30°` ;
- combinaison roll/pitch connue ;
- application exacte de `imuSign` et `zeroOffsetDeg` ;
- compatibilité des angles X/Y statiques avec `accelAngleDeg` historique.

### Dynamique

- intégration d'une rotation constante sur chacun des trois axes ;
- retour progressif vers l'inclinaison accel pour `0 < alpha < 1` ;
- gyro seul après initialisation pour `alpha = 1` ;
- correction complète d'inclinaison pour `alpha = 0` ;
- changement d'alpha sans discontinuité provoquée par un reset ;
- `reset()` exige une nouvelle initialisation et n'intègre aucun temps ancien ;
- données non finies, `dt <= 0`, accel nul et quaternion dégénéré donnent une
  estimation invalide.

### Vitesse de yaw

- à plat, yaw estimé égal au gyro Z ;
- téléphone incliné de 90° autour de X, rotation pure autour de la verticale du
  monde : projection correcte malgré un gyro Z téléphone différent ;
- rotation de pitch pure : absence de yaw monde à la tolérance numérique ;
- correction accel sans rotation autour de la verticale ;
- absence de promesse sur le cap absolu après une longue intégration.

## 5. Tests de sélection et configuration

- configuration par défaut égale à `LEGACY_COMPLEMENTARY` ;
- clé DataStore absente ou inconnue vers le filtre historique ;
- persistance et restauration de chacun des deux modes ;
- protocole Web ancien sans champ conserve le mode courant ;
- commande Web avec chaque enum valide ;
- valeur Web inconnue rejetée sans modifier la configuration ;
- changement `DISARMED` et `READY` accepté, reset unique et état
  `INITIALIZING` ;
- changement `MANUAL_ARMED`, `BALANCE_ARMED` et `FAULT_LATCHED` refusé sans
  persistance ni reset ;
- demande du mode déjà actif idempotente ;
- Android et Web convergent sur le snapshot confirmé du service ;
- changement d'alpha sans changement de mode ne reset pas le filtre.

## 6. Tests d'intégration du runtime

- l'estimation précède la boucle vitesse et les contrôleurs sur le même tick ;
- le PD pitch reçoit `theta` actif et le gyro pitch brut sélectionné ;
- le contrôleur yaw reçoit le yaw rate de l'estimateur actif ;
- le critère d'arrêt physique yaw reçoit la même valeur ;
- une estimation invalide bloque toute nouvelle commande moteur ;
- aucun changement des formules PD, vitesse, yaw ou mixage ;
- diagnostics et CSV associent mode, entrées et sorties au même timestamp ;
- reset de service et reset explicite reconstruisent le mode persisté.

## 7. Non-régression logicielle future

Après codage :

```bash
source tools/android-env.sh
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon lintDebug
./gradlew --no-daemon assembleDebug
```

Vérifier également :

- boucle vitesse/checkpoint de la branche de base ;
- yaw et mixage différentiel ;
- armement, désarmement et défauts IMU ;
- réglage `alpha` en direct ;
- export CSV ;
- page Web et reconnexion WebSocket ;
- persistance de tous les autres champs `RobotConfig`.

## 8. Validation téléphone sans moteurs

1. Installer l'APK debug et laisser les moteurs non connectés.
2. Vérifier le filtre historique par défaut sur une installation sans clé.
3. Observer à plat puis sous inclinaisons X/Y les angles des deux modes.
4. Tourner le téléphone à plat puis incliné et comparer les yaw rates.
5. Modifier `alpha` dans Android puis dans le Web sans reset inattendu.
6. Changer de filtre depuis Android et vérifier immédiatement le Web, puis
   effectuer l'opération inverse.
7. Redémarrer application et service pour vérifier la persistance.
8. Simuler un état armé sans mouvement et vérifier le refus sur les deux IHM.
9. Enregistrer et exporter un CSV dans chaque mode.
10. Mesurer fréquence IMU, latence p50/p95 et charge CPU sur au moins 60 s.

Critères performance : maintien de la cadence IMU cible et surcoût de latence
de contrôle p95 inférieur ou égal à 1 ms face au filtre historique sur le même
téléphone.

## 9. Validation roues levées

Préconditions : roues levées, alimentation coupable rapidement, limites de
commande réduites et arrêt d'urgence accessible.

1. Démarrer en filtre historique et reproduire une séquence d'équilibrage déjà
   connue.
2. Désarmer, sélectionner le quaternion, attendre son état `ACTIVE`, puis
   réarmer explicitement.
3. Vérifier les signes pitch et yaw à faible commande.
4. Vérifier qu'aucune commutation n'est acceptée pendant l'armement.
5. Vérifier qu'un reset ou une estimation invalide produit la réaction sûre
   existante.
6. Comparer les CSV historique/quaternion avec les mêmes paramètres.

Aucun essai roues au sol n'est autorisé par la seule réussite de cette étape.

## 10. Validation au sol ultérieure

Elle exige une validation séparée de l'utilisateur. Commencer sans consigne de
translation ni yaw, avec une zone dégagée et des limites conservatrices. Les
gains ne sont pas modifiés entre les deux filtres lors du premier comparatif.

Observer :

- stabilité de `theta` ;
- comportement sous accélération linéaire ;
- yaw pur téléphone incliné ;
- faux défauts et timeouts ;
- cadence, latence et saturation des commandes.

## 11. Preuves à conserver

- modèle du téléphone et version Android ;
- fréquence IMU demandée et mesurée ;
- commit, version APK et SHA-256 ;
- résultats Gradle ;
- captures Android/Web des deux modes et d'un refus armé ;
- CSV de chaque filtre avec paramètres identiques ;
- mesures de latence et charge CPU ;
- description séparée des faits mesurés, hypothèses et décisions de tuning.

## 12. Critère de sortie

La feature est prête à coder lorsque les quatre documents amont sont validés.
Elle est terminée seulement si le filtre historique reste numériquement
compatible, le filtre quaternion satisfait ses tests géométriques, le
sélecteur est cohérent et persisté sur Android/Web, la commutation armée est
impossible et les essais progressifs ne révèlent aucune régression de sécurité
ou de cadence.

