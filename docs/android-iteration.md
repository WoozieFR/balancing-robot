# Workflow d’itération Android

## Chaîne installée

- JDK 17 : `/home/woozie/robot/android-toolchain/jdk-17`
- Gradle 9.6.0 : `/home/woozie/robot/android-toolchain/gradle-9.6.0`
- Android SDK : `/home/woozie/robot/android-sdk`
- Android Platform 36 et Build Tools 36.0.0
- `adb` via les Platform Tools
- `cloudflared` pour publier temporairement les APK debug

Le projet sera basé sur AGP 9.4 et Gradle 9.6.0. AGP 9 active Kotlin intégré : le futur module Android ne devra donc pas appliquer `org.jetbrains.kotlin.android`.

Charger l’environnement avec :

```bash
source tools/android-env.sh
```

## Boucle de développement prévue

1. Modifier le code Kotlin.
2. Construire l’APK debug :

   ```bash
   tools/android-build-debug.sh
   ```

3. Si le téléphone est relié en USB ou en Wi‑Fi adb :

   ```bash
   tools/android-install-debug.sh
   ```

4. Sinon publier le dernier APK par tunnel :

   ```bash
   tools/android-publish-debug.sh
   ```

   Le script affiche une URL HTTPS temporaire vers `balancing-robot-debug.apk`.

Le tunnel est destiné aux builds de développement uniquement. Pour les itérations fréquentes, `adb install -r` reste plus rapide et conserve généralement les données de l’application.

## Architecture de la première version

L’application native recevra directement les `SensorEvent` Android. Le runtime
de contrôle, le serveur WebSocket et la page web de contrôle seront intégrés à
l’application ; le contrôleur Python/Termux existant reste uniquement une
référence historique et ne fait pas partie de l’exécution cible.

Le Lot 0 vérifie déjà le service au premier plan, `/health`, le WebSocket et la
détection USB sans activer les moteurs. Le Lot 1 ajoute le domaine IMU/filtre/PD,
la validation de configuration, les règles de sécurité et un replay JVM jusqu'à
la commande moteur simulée. Le Lot 2 raccorde les capteurs Android réels et
affiche la simulation PD. Le Lot 3 ajoute la fréquence IMU réglable et la page
web miroir. Le Lot 4 ajoute le codec Feetech, le transport CH340, les
permissions, la qualification du groupe 6/7, la télémétrie, l'ordonnanceur I/O,
la commande manuelle avec deadman et les paliers. Le Lot 5 ajoute le raccordement
PD, le watchdog IMU/chute, l'arrêt sûr, les mesures de latence et l'onglet de
réglages à curseurs (dont alpha). Aucun moteur n'est armé automatiquement ; la
validation physique reste une action opérateur roues levées.
