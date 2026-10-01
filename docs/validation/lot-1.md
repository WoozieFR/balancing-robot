# Validation Lot 1 — Domaine et runtime simulé

**Date :** 2026-10-01  
**Statut :** validé côté JVM, sans essai moteur

## Périmètre

Le lot couvre la chaîne pure et rejouable :

```text
Vector3 accélération → angle de référence → filtre complémentaire → PD borné
                                                   → signes moteurs simulés
```

Il couvre aussi les bornes de `RobotConfig`, la fraîcheur IMU, la détection
d'angle de chute et la classification du défaut `ESTIMATE_INVALID`.

## Implémentation

- `domain/model` : états, configuration, capteurs, estimations, sorties et
  défauts immuables ;
- `domain/estimation` : `atan2` par axe, offset/signe et filtre complémentaire ;
- `domain/control` : PD `Kp·erreur − Kd·gyro`, saturation `[-vmax,+vmax]`,
  arrondi et signes propres aux deux moteurs ;
- `domain/safety` : timestamps monotones, timeout IMU et seuil/durée de chute ;
- `runtime/SimulatedControlRuntime` : replay sans Android, USB, réseau ni
  écriture servo.

## Vérifications exécutées

Depuis la racine du dépôt :

```bash
./tools/android-build-debug.sh
source tools/android-env.sh && ./gradlew --no-daemon :app:lintDebug
```

Résultat : **22 tests JVM réussis**, lint debug réussi, APK debug construit.

APK produit : `dist/balancing-robot-debug.apk`  
SHA-256 : `48136e30e8546b1398edb204e808033fbf4e09176739f0a41fd103d928bb6fc6`

## Limites et décision

Le runtime est volontairement simulé : aucun `SensorEvent` Android, adaptateur
USB, bus Feetech ou armement moteur n'est introduit dans ce lot. Le prochain
lot peut donc raccorder l'acquisition IMU et l'écran de diagnostic tout en
conservant ces tests purs comme garde-fou.
