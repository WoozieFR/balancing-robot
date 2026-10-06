# Validation Lot 6 — Commande de mouvement et auto-trim au repos

**Date :** 2026-10-02  
**Statut :** implémentation et tests logiciels réalisés ; validation matérielle à exécuter

## Livré

- lecture rapide de `PresentVelocity`, alternée entre les deux STS3215 avec une
  cible de 50 Hz par moteur ;
- paire gauche/droite atomique, séquencée et horodatée ;
- application des signes moteurs puis conversion en cm/s avec 4096 pas/tour,
  roues de 40 mm et rapport direct 1,0 par défaut ;
- moyenne des roues, EMA réglable et boucle à 50 Hz par défaut ;
- commande joystick directe vers une inclinaison limitée et soumise à un slew ;
- états `DRIVING`, `WAIT_REST` et `REST`, avec auto-trim `Ki × (-vitesse) × dt`
  appris uniquement au repos ;
- freinage proportionnel optionnel, limite absolue et limiteurs de pente ;
- conservation de l'auto-trim sur retour périmé, sans intégration de l'intervalle
  perdu, puis réinitialisation propre de l'EMA lors de la reprise ;
- activation, gains, limites, seuil, durée de repos, cadence et timeout
  modifiables en direct ;
- réglages et diagnostics sur l'IHM Android et la page Web ;
- export CSV de la configuration, des entrées, des conversions et de tous les
  calculs intermédiaires.

L'inclinaison joystick et le freinage restent limités par défaut ; l'état initial
`REST` permet l'apprentissage du point d'équilibre avant la première commande.

## Vérifications automatiques

```bash
source tools/android-env.sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew lintDebug
```

Les tests purs couvrent conversion, signes, moyenne, EMA, cadence, inclinaison
joystick et slew, transitions d'états, auto-trim uniquement au repos, freinage
proportionnel, limite absolue, retour périmé et reprise. Le test Feetech couvre
la lecture signée courte de `PresentVelocity`.

## Procédure matérielle restant à exécuter

1. Installer l'APK et conserver l'inclinaison maximale à 3° ainsi que le gain
   d'auto-trim à une valeur prudente.
2. Roues levées, armer et faire tourner les roues ; vérifier que gauche et
   droite ont le même signe physique et que la paire arrive près de 50 Hz.
3. Vérifier la conversion : 4096 pas/s doit donner environ 12,57 cm/s avec une
   roue de 40 mm et un rapport 1,0.
4. Relâcher le joystick ; vérifier le passage `DRIVING → WAIT_REST → REST` et
   la qualification continue du seuil sur la moyenne des deux roues.
5. Ajouter une charge au repos ; vérifier que l'état reste `REST` et que
   l'auto-trim apprend sans remettre sa valeur à zéro.
6. Arrêter une roue ou débrancher un moteur ; vérifier que l'auto-trim reste
   conservé pendant le retour périmé, puis que l'EMA repart sur la mesure fraîche.
7. Enregistrer une session RAM, l'arrêter et contrôler toutes les colonnes
   vitesse dans `/control-log.csv`.

Aucune stabilité au sol ni cadence USB réelle n'est revendiquée tant que cette
procédure n'a pas été réalisée sur le robot.

APK : `app/build/outputs/apk/debug/app-debug.apk`
SHA-256 : `c9fb535f1a957ed3d1af4d378c2b728714a73345d4eb585340dc1ec9b4400145`
