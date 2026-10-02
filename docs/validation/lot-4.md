# Validation Lot 4 — USB, Feetech et moteurs manuels

**Date :** 2026-10-01  
**Statut :** implémentation logicielle complète ; caractérisation matérielle à exécuter

## Livré

- codec Feetech pur : checksum, paquets, statut, sign-magnitude, lecture et
  `INST_SYNC_WRITE` ;
- registres STS3215 et `FeetechBus` pour ping/scan, configuration vitesse ou
  PWM/couple, lecture vitesse/charge/tension/température et écriture synchronisée ;
- transport Android `usb-serial-for-android` CH340 à 1 Mbit/s, ouvert uniquement
  sur action explicite ;
- demande de permission USB depuis l'interface ;
- onglet **Moteurs** avec connexion, scan explicite des IDs 1 à 20, IDs/signes,
  choix vitesse/PWM, limites vitesse/PWM et limite de couple configurables désarmé ;
- groupe vérifié par scan si les deux IDs attendus répondent, avec possibilité
  d'utiliser explicitement des IDs connus sans scan ;
- ordonnanceur I/O unique : dernière consigne, action urgente zéro/couple off,
  télémétrie à 20 Hz cible et détection d'un moteur silencieux après trois
  cycles consécutifs manqués (une perte ponctuelle est tolérée) ;
- armement manuel explicite après confirmation roues levées, deadman renouvelé
  toutes les 100 ms, désarmement et séquence de paliers proportionnelle à la
  limite du mode (vitesse : `0/500/1000/2000/4000/6000`, PWM :
  `0/83/166/333/666/1000`) ;
- journal CSV moteur (consigne, vitesse, charge, tension, température) ;
- commandes WebSocket distantes correspondantes et endpoint `/motor-log.csv` ;
- déconnexion USB convertie en défaut verrouillé et arrêt sûr.

Le scan n'envoie que des `PING`. La configuration, l'activation du couple et
l'armement restent des actions distinctes. Aucune écriture moteur n'est
déclenchée au démarrage ou lors d'une reconnexion USB.

Le mode vitesse écrit `Goal_Velocity` (registre 46) avec un signe en bit 15.
Le mode PWM écrit la consigne signée sur le registre `Goal_Time`/PWM (44),
avec un signe en bit 10 et une amplitude de 0 à 1000. Le changement de mode
est effectué couple coupé, avant tout armement.

## Vérifications

```bash
./tools/android-build-debug.sh
source tools/android-env.sh && ./gradlew --no-daemon :app:lintDebug
```

Résultat : **44 tests JVM réussis**, lint debug réussi, APK debug construit.
La validation CH340/STS3215 reste à exécuter roues levées, avec arrêt d'urgence
accessible.

## Reste avant validation matérielle

Vérifier les IDs 6/7, les signes à faible consigne, puis les paliers avec
coupure manuelle accessible. Le logiciel ne peut pas garantir l'immobilisation
si Android ou l'alimentation disparaît brutalement après une commande.

APK : `dist/balancing-robot-debug.apk`  
SHA-256 : `ef56c4e18035102ff44a170c9e256c49aca049013ce9c8e538e89265765f64d1`
