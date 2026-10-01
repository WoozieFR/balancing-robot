# Validation Lot 2 — Service, IMU et diagnostic

**Date :** 2026-10-01  
**Statut :** validé par compilation et tests locaux ; test téléphone à faire

Un correctif complémentaire a remplacé `SENSOR_DELAY_FASTEST` (0 µs), qui
déclenchait une `SecurityException` sur le téléphone cible, par
une demande explicite de 5 000 µs (200 Hz), avec repli contrôlé si le pilote
refuse cette cadence. La cadence réellement reçue reste mesurée à l'écran.

## Visible dans l'application

Après démarrage du service, l'écran affiche désormais :

- présence de l'accéléromètre et du gyroscope ;
- valeurs brutes accel et gyro (gyro converti en degrés/s) ;
- angle accéléromètre, angle filtré et `dt` ;
- cadence et jitter séparés pour accel et gyro ;
- compteurs de mesures acceptées/rejetées et taille du journal IMU borné ;
- formulaire **Simulation PD** avec calcul de la commande bornée et des deux
  sorties moteur simulées.

L'acquisition est réalisée par un `SensorEventListener` Android sur un
`HandlerThread` `robot-control`. Les timestamps capteur sont conservés et les
valeurs sont copiées avant traitement. Une accélération absente ou trop ancienne
réinitialise l'estimateur et n'émet aucune sortie physique.

## Journal et sécurité

Le journal de diagnostic est un anneau borné en mémoire et exportable en CSV
par l'API de domaine. Le runtime Lot 1 est utilisé uniquement comme calculateur
simulé ; aucun adaptateur USB, bus Feetech ou écriture servo n'est appelé.

## Vérifications exécutées

```bash
./tools/android-build-debug.sh
source tools/android-env.sh && ./gradlew --no-daemon :app:lintDebug
source tools/android-env.sh && adb devices -l
```

Résultat : **25 tests JVM réussis**, lint debug réussi, APK debug construit.
La commande `adb devices -l` ne détectait aucun téléphone au moment de la
validation ; la présence réelle des capteurs doit donc être vérifiée sur le
téléphone lors de l'installation.

APK : `dist/balancing-robot-debug.apk`  
SHA-256 : `5116a41a1456849eabd78acb6e61fb82c854fa126a774ffab4b93a7b1b0e9814`

## Limites

Le journal n'est pas encore exposé par un bouton de partage Android. Le prochain
lot pourra ajouter l'export depuis l'IHM et la page web, puis le transport USB
et les essais roues levées.
