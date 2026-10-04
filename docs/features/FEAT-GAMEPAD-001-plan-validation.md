# FEAT-GAMEPAD-001 — Plan de validation

**Version :** 1.0  
**Date :** 2026-10-04  
**Statut :** à exécuter après codage

## 1. Tests JVM obligatoires

### Transformation des axes

- neutre exact et valeurs dans la zone morte donnent zéro ;
- continuité à `±deadZone` ;
- `±1` donnent `±1` après signe ;
- symétrie positif/négatif ;
- exposants 1, 1,5 et 3 ;
- prise en compte du `MotionRange.flat` supérieur au réglage ;
- rejet de `NaN`, infini, signe invalide et domaine invalide.

### Mise à l'échelle

- haut du stick gauche donne la vitesse avant positive avec le signe par défaut ;
- droite du stick droit donne le yaw positif ;
- précision applique exactement le facteur configuré ;
- les limites manette et globales sont toutes respectées.

### Arbitre

- paramètres utilisés lorsque la manette est désactivée ;
- manette active et deadman relâché donne zéro ;
- commande fraîche avec deadman donne les deux consignes ;
- timeout à 250 ms donne zéro ;
- déconnexion, perte de focus et séquence non croissante donnent zéro/rejet ;
- désactivation produit zéro et empêche la reprise d'une ancienne consigne ;
- nouvelle action paramètre autorise explicitement la reprise ;
- inhibition des sécurités sans effet sur la neutralisation manette.

## 2. Tests instrumentés Android

- détection d'un `InputDevice` synthétique ou test de l'adaptateur avec facade ;
- sélection des axes `Y`, `Z`, puis fallback `RX` ;
- mapping `R1`, `L1`, `BUTTON_B` ;
- arrêt du heartbeat sur `onPause` et `onStop` ;
- maintien de l'écran éveillé uniquement pendant le mode actif ;
- aucune écriture DataStore lors de 60 secondes de heartbeat ;
- diagnostics Compose limités à la cadence prévue.

## 3. Non-régression logicielle

```bash
source tools/android-env.sh
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon lintDebug
./gradlew --no-daemon assembleDebug
```

Vérifier également :

- pilotage Android et Web existant sans manette ;
- boucle vitesse et auto-trim ;
- boucle yaw et mélange différentiel ;
- armement/désarmement manuel ;
- inhibition des désarmements automatiques ;
- export CSV ;
- absence de nouvelle permission Bluetooth dans le manifeste.

## 4. Validation téléphone sans moteurs

1. Jumeler la DualShock 4 dans Android.
2. Ouvrir l'application et vérifier le nom, vendor/product ID et les axes.
3. Déplacer chaque stick et noter les axes Android réellement utilisés.
4. Vérifier les boutons `R1`, `L1` et `Cercle` sans service moteur connecté.
5. Vérifier zone morte, courbe et signes dans le diagnostic.
6. Laisser les sticks immobiles avec `R1` maintenu pendant 60 secondes : le
   heartbeat doit rester proche de 50 Hz.
7. Passer l'application en arrière-plan, éteindre l'écran et couper le
   Bluetooth : chaque cas doit produire zéro en moins de 250 ms.
8. Reconnecter la manette : aucune ancienne commande ne doit réapparaître.

## 5. Validation roues levées

Préconditions : roues levées, zone dégagée, alimentation coupable rapidement,
vitesses et couple réduits.

1. Démarrer le service, connecter/configurer les moteurs et armer l'équilibrage
   depuis l'IHM.
2. Activer le mode manette avec sticks neutres et `R1` relâché : commandes
   opérateur nulles.
3. Maintenir `R1`, demander une faible vitesse avant puis arrière.
4. Stick gauche neutre, demander un faible yaw dans chaque sens et vérifier les
   commandes différentielles.
5. Tester `L1` et vérifier le facteur de précision.
6. Relâcher `R1` à commande non nulle : zéro en moins de 100 ms.
7. Couper le Bluetooth à commande non nulle : zéro en moins de 250 ms.
8. Avec l'inhibition générale active, répéter la perte Bluetooth : les
   consignes doivent quand même revenir à zéro.
9. Appuyer sur `Cercle` : l'état doit quitter le mode armé et les sorties être
   nulles.
10. Enregistrer puis exporter le CSV et vérifier tous les champs manette.

## 6. Preuves à conserver

- modèle et version Android du téléphone ;
- version matérielle/firmware de la DualShock 4 si identifiable ;
- mapping axes/keycodes observé ;
- captures des diagnostics connecté, conduite, timeout et déconnexion ;
- CSV d'un essai roues levées ;
- latences mesurées de relâchement, déconnexion et désarmement ;
- commit, SHA-256 de l'APK et résultats Gradle.

## 7. Critère de sortie

La feature est codable lorsque les documents amont sont validés. Elle est
terminée uniquement lorsque les tests JVM et Android passent, que le mapping
réel de la DualShock 4 est confirmé sur le téléphone et que les essais roues
levées démontrent les neutralisations dans les délais spécifiés.

