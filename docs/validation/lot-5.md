# Validation Lot 5 — Boucle d'équilibrage et réglages

**Date :** 2026-10-01  
**Statut :** implémentation logicielle complète ; caractérisation matérielle à exécuter

## Livré

- raccordement de l'estimateur d'angle et du PD au slot de l'ordonnanceur I/O ;
- état moteur distinct `BALANCE_ARMED`, armement volontaire et confirmation de
  sécurité ;
- watchdog de fraîcheur gyro/IMU, dépassement d'angle et durée de chute ;
- arrêt sûr avec consigne nulle et désactivation du couple en cas de défaut ;
- bornage par la limite du mode sélectionné (`vmax` en vitesse ou `pwmMax` en
  PWM), signes moteurs, timeout de commande et mesure de latence ;
- configuration robot validée et persistée dans DataStore ;
- onglet Android **Réglages** avec curseurs pour tous les paramètres de la
  boucle : axe/signe, offset zéro, alpha du filtre complémentaire, cible, Kp,
  Kd, vitesse/couple, timeout IMU, angle/durée de chute et timeout manuel ;
- modification en direct de l'alpha, de la cible, de Kp et Kd pendant
  `BALANCE_ARMED`, sans bouton Appliquer ni réinitialisation de l'estimateur ;
- champ numérique précis à côté de chaque curseur, validation par Entrée ou
  perte de focus ;
- session de contrôle RAM démarrée/arrêtée explicitement, export Android et
  endpoint web `/control-log.csv` ;
- commandes WebSocket `update_parameters`, `arm_balance` et `disarm_balance` ;
- page web miroir avec curseurs alpha, cible, Kp/Kd, offset et angle de chute ;
- choix du mode moteur vitesse/PWM dans Android et sur la page web, avec limite
  PWM dédiée et exportée dans le journal de contrôle ;
- application explicite des IDs connus sans scan obligatoire après changement
  des réglages moteur.

## Vérifications

```bash
./tools/android-build-debug.sh
source tools/android-env.sh && ./gradlew --no-daemon :app:lintDebug
```

Les tests JVM, la compilation Kotlin et lint doivent réussir avant chaque
publication. L'APK debug livré est copié dans `dist/` avec son SHA-256.

## Procédure de validation sur le robot

1. Installer l'APK, démarrer le service et vérifier une cadence IMU proche de
   la consigne (200 Hz recommandé pour la mise au point).
2. Connecter l'adaptateur CH340, saisir les IDs connus et appliquer la
   configuration ; le scan est optionnel.
3. Roues levées, régler d'abord cible/offset, puis alpha, Kp et Kd avec des
   valeurs faibles. Vérifier la télémétrie et la latence affichée.
4. Cocher la confirmation de sécurité, armer explicitement, puis tester le
   désarmement et l'arrêt d'urgence. Une fois armé, alpha/cible/Kp/Kd peuvent
   encore être ajustés en direct avec les curseurs.
5. Injecter une perte IMU ou un angle supérieur au seuil et vérifier le défaut
   verrouillé et le couple coupé.

La capacité à tenir durablement l'équilibre et les gains définitifs restent
une phase de caractérisation mécanique ultérieure ; aucun armement automatique
n'est autorisé.

APK : `dist/balancing-robot-debug.apk`  
SHA-256 : `880c31a302c583865c04b4d1153762b313a26b90d135e3d1cb32c510719ac7d7`
