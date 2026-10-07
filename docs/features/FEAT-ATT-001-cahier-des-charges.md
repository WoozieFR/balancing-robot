# FEAT-ATT-001 — Cahier des charges de l'estimation d'attitude sélectionnable

**Version :** 1.0  
**Date :** 2026-10-07  
**Statut :** spécification validée pour implémentation, validation finale en attente  
**Base :** `fix/checkpoint-reset-on-release` au commit `3ee1f9d`

## 1. Objet

Ajouter un nouvel estimateur d'attitude fondé sur un quaternion tout en
conservant intégralement le filtre complémentaire scalaire actuel. L'opérateur
choisit le filtre actif depuis l'application Android ou la page Web.

Le remplacement doit être transparent pour les contrôleurs. Quel que soit le
filtre choisi, la chaîne de contrôle reçoit les mêmes grandeurs et unités :

- l'angle d'équilibrage estimé `theta`, en degrés ;
- la vitesse de lacet estimée, en degrés par seconde.

Dans ce document, **DOIT** désigne une exigence obligatoire, **DEVRAIT** une
exigence souhaitée et **PEUT** une capacité facultative.

## 2. Contexte

Le filtre historique estime un seul angle avec :

```text
theta = alpha × (theta_precedent + gyro_selectionne × dt)
        + (1 - alpha) × theta_accelerometre
```

La vitesse de yaw utilisée aujourd'hui correspond au gyro Z du téléphone. Ce
fonctionnement doit rester disponible sans changement afin de conserver une
référence expérimentale connue et de permettre un retour immédiat au
comportement antérieur.

Le nouvel estimateur maintient l'attitude complète du téléphone. Son objectif
est d'utiliser les trois axes du gyroscope, de corriger l'inclinaison par la
gravité et d'obtenir une vitesse de yaw autour de la verticale du monde même
quand le téléphone est incliné.

## 3. Périmètre

### 3.1 Inclus

- conservation du filtre complémentaire scalaire historique ;
- ajout d'un filtre complémentaire quaternion ;
- sélection du filtre depuis Android et le Web ;
- persistance du dernier filtre sélectionné ;
- initialisation et réinitialisation sûres ;
- utilisation du même `alpha` pour les deux filtres ;
- alimentation inchangée du PD de pitch et du contrôleur de yaw ;
- exposition du filtre configuré, du filtre actif et de son état dans les
  diagnostics et le CSV ;
- tests unitaires, tests d'intégration et protocole d'essai sur téléphone puis
  roues levées.

### 3.2 Hors périmètre

- suppression ou modification mathématique du filtre historique ;
- fusion simultanée des sorties des deux filtres ;
- exécution permanente des deux filtres en parallèle ;
- magnétomètre, cap absolu ou correction de dérive du cap ;
- Madgwick, Mahony ou bibliothèque externe d'estimation ;
- détection adaptative des accélérations linéaires ;
- modification du PD de pitch, de la boucle vitesse, du contrôleur yaw, du
  mixage moteur, des gains ou des seuils de sécurité ;
- changement des axes Android, des unités capteur ou de la fréquence IMU.

## 4. Modes proposés

| Identifiant stable | Libellé IHM | Comportement |
| --- | --- | --- |
| `LEGACY_COMPLEMENTARY` | Complémentaire historique | Formule scalaire existante et gyro Z historique pour le yaw |
| `QUATERNION_COMPLEMENTARY` | Complémentaire quaternion | Attitude 3D et projection du gyro sur la verticale du monde |

Un seul mode est actif à un instant donné. Le mode inactif ne maintient aucun
état caché susceptible de réapparaître lors d'une commutation.

## 5. Sélection et persistance

- **ATT-SEL-001 —** Android et le Web DOIVENT proposer les deux mêmes choix et
  modifier le même réglage de service.
- **ATT-SEL-002 —** Le dernier choix validé DOIT être persisté et restauré au
  prochain démarrage du service ou de l'application.
- **ATT-SEL-003 —** Si aucune préférence n'existe, si sa valeur est inconnue
  ou si elle ne peut pas être lue, `LEGACY_COMPLEMENTARY` DOIT être utilisé.
- **ATT-SEL-004 —** Un changement DOIT être accepté uniquement lorsque l'état
  d'armement est `DISARMED` ou `READY`.
- **ATT-SEL-005 —** Android et le Web DOIVENT désactiver visuellement le
  sélecteur dans tout autre état. Le service reste l'autorité et DOIT rejeter
  une commande contournant cette restriction.
- **ATT-SEL-006 —** Une commutation acceptée DOIT réinitialiser l'estimateur
  sélectionné. Sa première sortie valide est produite seulement après les
  échantillons IMU nécessaires à son initialisation.
- **ATT-SEL-007 —** Une commutation refusée ne DOIT modifier ni la préférence
  persistée, ni le filtre actif, ni son état interne.
- **ATT-SEL-008 —** Le filtre configuré et le filtre réellement actif DOIVENT
  être visibles afin de diagnostiquer un refus ou une initialisation en cours.

## 6. Exigences d'estimation

- **ATT-EST-001 —** Les deux filtres DOIVENT recevoir les timestamps monotones
  Android et utiliser le vrai intervalle entre échantillons gyro.
- **ATT-EST-002 —** Le filtre historique DOIT conserver sa formule, ses
  conventions d'axe, son signe, son offset et sa gestion d'initialisation.
- **ATT-EST-003 —** Le filtre quaternion DOIT intégrer les trois composantes
  gyro et normaliser son quaternion après chaque mise à jour.
- **ATT-EST-004 —** L'accéléromètre DOIT corriger uniquement l'inclinaison. Il
  ne doit pas créer artificiellement une référence absolue de yaw.
- **ATT-EST-005 —** Le paramètre `alpha`, dans `[0, 1]`, DOIT rester modifiable
  en direct sans reset lorsque le mode ne change pas.
- **ATT-EST-006 —** Pour le quaternion, `alpha = 1` signifie gyro seul après
  initialisation et `alpha = 0` applique toute la correction d'inclinaison
  issue de l'échantillon courant.
- **ATT-EST-007 —** L'angle `theta` DOIT respecter `axis`, `imuSign` et
  `zeroOffsetDeg` existants.
- **ATT-EST-008 —** Pour les axes X et Y, l'angle quaternion statique DOIT être
  compatible avec l'angle gravitaire historique, à la tolérance numérique et
  aux singularités près.
- **ATT-EST-009 —** En mode quaternion, la vitesse de yaw DOIT être la
  composante du vecteur gyro autour de la verticale du monde estimée.
- **ATT-EST-010 —** La dérivée du PD de pitch DOIT continuer à utiliser la
  composante gyro sélectionnée et signée actuelle ; seule l'estimation de
  `theta` et la mesure fournie au yaw dépendent du mode choisi.
- **ATT-EST-011 —** Toute entrée non finie, tout `dt` non positif ou toute
  attitude non normalisable DOIT produire une estimation invalide et suivre le
  chemin de défaut `ESTIMATE_INVALID` existant.
- **ATT-EST-012 —** Une perte de magnétomètre n'existe pas dans ce lot : aucun
  magnétomètre n'est utilisé. La dérive du cap absolu est une limite attendue.

## 7. Interfaces et observabilité

L'application Android et la page Web DOIVENT afficher :

- le sélecteur à deux valeurs ;
- le filtre configuré ;
- le filtre actif ;
- l'état `non initialisé`, `actif` ou `invalide` ;
- `theta`, la vitesse gyro de pitch utilisée par le PD et la vitesse de yaw
  fournie au contrôleur ;
- un message explicite si une commutation est refusée parce que le robot est
  armé.

Le réglage `alpha` reste visible et porte la mention qu'il s'applique au filtre
actif. Les diagnostics réseau et le CSV DOIVENT identifier le mode afin que
deux essais ne puissent pas être confondus.

## 8. Contraintes temps réel et sécurité

- Le calcul quaternion ne DOIT effectuer aucune I/O ni bloquer le callback de
  contrôle.
- Il ne DOIT pas introduire de nouvelle cadence ni de thread parallèle.
- Une sortie invalide ne DOIT jamais produire de nouvelle commande moteur.
- Aucun mode filtre ne DOIT armer automatiquement les moteurs.
- Le choix du filtre ne doit pas contourner les timeouts, la détection de chute
  ou les autres sécurités existantes.
- La commutation à chaud pendant un armement est explicitement interdite dans
  cette version.

## 9. Critères d'acceptation

1. Une installation sans préférence démarre avec le filtre historique.
2. Le choix effectué depuis Android est visible sur le Web, et réciproquement.
3. Le choix persiste après redémarrage complet de l'application et du service.
4. Une tentative de changement armé est refusée sans modifier l'état courant.
5. Le filtre historique reproduit les résultats actuels sur les jeux de tests
   existants.
6. À plat et sous inclinaisons X/Y connues, le quaternion fournit un `theta`
   cohérent avec la géométrie attendue.
7. Lorsque le téléphone est incliné, une rotation autour de la verticale du
   monde donne une vitesse de yaw correcte, contrairement à une simple lecture
   systématique du gyro Z du téléphone.
8. Les sorties conservent les unités et contrats attendus par les contrôleurs.
9. Les diagnostics et le CSV permettent d'identifier sans ambiguïté le filtre
   actif et son état d'initialisation.
10. Aucun réglage de contrôle existant n'est modifié par l'installation de la
    feature.

## 10. Limites connues à rappeler à l'opérateur

- Sans magnétomètre, le cap absolu n'est ni observé ni corrigé.
- Les accélérations linéaires contaminent la direction de gravité, comme pour
  le filtre historique ; cette feature n'ajoute pas de rejet adaptatif.
- `alpha` reste un poids par échantillon et non une constante de temps rendue
  indépendante de la fréquence IMU.
- L'axe Z n'a pas de référence gravitaire absolue. En mode quaternion, son
  angle éventuel est un yaw relatif à l'initialisation.
