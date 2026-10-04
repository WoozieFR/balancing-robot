# Validation Lot 6 — Boucle externe de vitesse

**Date :** 2026-10-02  
**Statut :** implémentation et tests logiciels réalisés ; validation matérielle à exécuter

## Livré

- lecture rapide de `PresentVelocity`, alternée entre les deux STS3215 avec une
  cible de 50 Hz par moteur ;
- paire gauche/droite atomique, séquencée et horodatée ;
- application des signes moteurs puis conversion en cm/s avec 4096 pas/tour,
  roues de 40 mm et rapport direct 1,0 par défaut ;
- moyenne des roues, EMA réglable et boucle externe à 50 Hz par défaut ;
- loi `correction = clamp(Kev × erreur + autoTrim, ±limite_correction)`, avec
  auto-trim intégral `Ki × erreur × dt`, anti-windup, limite absolue et
  limiteur de pente ;
- retour progressif de la cible vers le trim et remise à zéro de l'auto-trim sur
  retour périmé, puis réinitialisation propre du filtre lors de la reprise ;
- activation, gains, limites, cadence et timeout modifiables en direct ;
- réglages et diagnostics sur l'IHM Android et la page Web ;
- export CSV de la configuration, des entrées, des conversions et de tous les
  calculs intermédiaires.

Le gain `Kev` reste nul par défaut : l'ajout logiciel ne peut donc pas demander
spontanément un mouvement au premier démarrage.

## Vérifications automatiques

```bash
source tools/android-env.sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew lintDebug
```

Les tests purs couvrent conversion, signes, moyenne, EMA, cadence, saturation
centrée sur le trim, auto-trim intégral, limite de pente, retour vers le trim
sur donnée périmée, reprise et désactivation de la boucle. Le test Feetech
couvre la lecture signée courte de `PresentVelocity`.

## Procédure matérielle restant à exécuter

1. Installer l'APK et conserver `Kev = 0`.
2. Roues levées, armer et faire tourner les roues ; vérifier que gauche et
   droite ont le même signe physique et que la paire arrive près de 50 Hz.
3. Vérifier la conversion : 4096 pas/s doit donner environ 12,57 cm/s avec une
   roue de 40 mm et un rapport 1,0.
4. Arrêter une roue ou débrancher un moteur ; vérifier le retour progressif de
   la cible vers le trim, puis le défaut moteur confirmé par la télémétrie de
   santé.
5. Au sol, partir de `Kev = 0`, augmenter progressivement et vérifier le signe
   de la correction avant toute recherche de performance.
6. Enregistrer une session RAM, l'arrêter et contrôler toutes les colonnes
   vitesse dans `/control-log.csv`.

Aucune stabilité au sol ni cadence USB réelle n'est revendiquée tant que cette
procédure n'a pas été réalisée sur le robot.

APK : `dist/balancing-robot-debug.apk`  
SHA-256 : `d70344bd34861a101a935e01693cc8bceba186235e8429412575f377e0fc7c16`
