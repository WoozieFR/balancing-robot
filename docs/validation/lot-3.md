# Validation Lot 3 — Réglages et diagnostic distant

**Date :** 2026-10-01  
**Statut :** validé par compilation et tests locaux ; test réseau à faire

## Fonctionnalités livrées

- champ **Fréquence IMU demandée (Hz)** dans l'application ;
- valeur par défaut `200 Hz`, plage `20..200 Hz` et mémorisation DataStore ;
- transmission de la fréquence choisie au service à chaque démarrage ;
- conversion fréquence → période capteur contrôlée par `ImuRatePolicy` ;
- nouvelle route `GET /diagnostics` ;
- navigation Compose entre serveur, IMU et simulation PD ;
- courbe locale accel/filtré ;
- export CSV du journal depuis Android et `GET /log.csv` ;
- commandes WebSocket `diagnostics` et `export_log` ;
- page web miroir qui actualise l'état IMU, cadence réelle, angles, jitter,
  compteurs et taille du journal chaque seconde.

La fréquence affichée reste la fréquence effectivement reçue. Le téléphone peut
donc rester sous la valeur demandée si son capteur ou son pilote limite la
cadence.

## Vérifications exécutées

```bash
./tools/android-build-debug.sh
source tools/android-env.sh && ./gradlew --no-daemon :app:lintDebug
```

Résultat : **29 tests JVM réussis**, lint debug réussi, APK debug construit.
Le test direct sur téléphone et navigateur distant reste à faire ; aucun moteur
physique n'est activé.

APK : `dist/balancing-robot-debug.apk`  
SHA-256 : `22153187a9f91246e48869420047f873667ef56fa9d7a491c57bb9a061546520`

## Limites

La page web reste en lecture seule pour les commandes moteur. Les commandes de
contrôle et le transport USB restent réservés aux lots suivants.
