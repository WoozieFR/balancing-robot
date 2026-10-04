# Validation Lot 7 — Boucle de rotation yaw

**Statut :** implémentation et tests logiciels réalisés ; validation matérielle à exécuter

## Livré

- mesure de la vitesse de lacet sur l'axe Z du gyroscope, en °/s ;
- correcteur proportionnel `u_turn = Kp_yaw × (consigne_yaw − gyroZ)` ;
- désactivation explicite lorsque la consigne yaw vaut zéro ;
- mélange différentiel après l'équilibrage : `uL = u_balance + u_turn`,
  `uR = u_balance − u_turn`, avec saturation indépendante par roue ;
- application des signes moteurs après le mélange, sans modifier le mode
  manuel existant ;
- réglages live Android/Web, persistance, diagnostic `/diagnostics` et colonnes
  yaw dans le CSV de session ;
- tests unitaires de la boucle, de la rotation sur place et de la saturation.

## Vérifications automatiques

```bash
source tools/android-env.sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

## Procédure matérielle restant à exécuter

1. Garder la consigne yaw à `0 °/s` et vérifier que `u_turn = 0`.
2. Roues levées, choisir une petite consigne positive (par exemple `10 °/s`) et
   confirmer que les commandes logiques sont opposées quand l'équilibrage est
   nul.
3. Vérifier le sens réel de rotation ; inverser le signe de la consigne si la
   convention mécanique du montage est opposée.
4. Avec le robot posé et stabilisé, augmenter progressivement `Kp yaw` et
   contrôler la saturation, le gyro Z, les commandes gauche/droite et le CSV.

La stabilité au sol et le signe mécanique restent à confirmer sur le robot réel.
