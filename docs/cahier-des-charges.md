# Cahier des charges — Application Android native du robot équilibré

**Version :** 0.1  
**Date :** 2026-10-01  
**Statut :** première base à valider  
**Périmètre :** premier jalon de l'application Android native

## 1. Objet du document

Ce document définit le besoin fonctionnel et les critères de validation de la
première application Android native du robot auto-équilibré à deux roues.

Il décrit ce que le système doit faire, ses contraintes et la progression des
essais. Il ne constitue pas une conception détaillée et n'impose pas encore le
découpage du code, les bibliothèques ou le protocole réseau définitif.

Dans ce document :

- **DOIT** désigne une exigence obligatoire ;
- **DEVRAIT** désigne une exigence souhaitée, qui peut être différée avec une
  justification explicite ;
- **PEUT** désigne une capacité facultative.

## 2. Références et constats d'entrée

Les références utilisées sont :

- le dépôt `spike-imu-pd-servo`, branche `feature/FEAT-PD-001` ;
- la synthèse de conversation
  `conversation-filtre-complementaire.md` archivée avec le projet ;
- les décisions prises avec l'utilisateur lors de la préparation de ce cahier
  des charges.

Le dépôt de référence est une donnée d'entrée : il ne doit pas être modifié.
Ses formules, son protocole STS3215, ses essais et son interface sont des bases
de comparaison, pas du code à exécuter dans l'application finale.

### 2.1 Système expérimental précédent

La chaîne précédente était :

```text
termux-sensor
  → accéléromètre et gyroscope
  → dédoublonnage
  → filtre complémentaire
  → correcteur PD
  → Goal_Velocity
  → deux servos STS3215
```

Le contrôleur Python et le navigateur fonctionnaient correctement comme preuve
de concept. La limite principale concernait l'acquisition IMU :
`termux-sensor` pouvait réémettre une ancienne valeur avec un nouvel
horodatage. La cadence observée pouvait alors représenter la cadence de
sondage, et non la cadence réelle du capteur. Le terme dérivé du PD utilisait
donc parfois une vitesse angulaire périmée.

### 2.2 Décision structurante

L'application cible ne passera pas par le contrôleur Python existant. Toute la
chaîne critique sera native dans Android :

```text
SensorEvent Android
  → normalisation et sélection des axes
  → estimation de l'angle
  → filtre complémentaire
  → correcteur PD
  → commande synchronisée des STS3215
```

Chaque étage devra toutefois pouvoir être observé, activé, neutralisé ou testé
séparément afin de conserver une mise au point incrémentale.

## 3. Objectifs

### 3.1 Objectif produit

Le produit final doit permettre au robot à deux roues de maintenir son
équilibre en utilisant l'IMU du téléphone Android et deux servos STS3215.

### 3.2 Objectif du premier jalon

Le premier jalon doit fournir une chaîne native complète et testable
progressivement, depuis les événements IMU jusqu'à l'écriture des consignes
moteur.

Le jalon sera considéré comme atteint lorsque chaque étage aura été validé
isolément, puis intégré à l'étage suivant avec les sécurités prévues. La tenue
stable du robot en équilibre après réglage fin ne constitue pas un critère de
sortie de ce premier jalon.

### 3.3 Objectifs secondaires

- permettre une itération rapide entre modification, compilation,
  installation et essai sur le téléphone ;
- fournir une interface Android complète ;
- conserver une page web de contrôle accessible depuis le réseau local ;
- rendre les fréquences, latences, défauts et commandes observables ;
- produire des journaux exploitables pour caractériser l'IMU et les moteurs.

## 4. Utilisateur et environnement cible

- **USR-001 —** Le système est destiné en premier lieu au propriétaire et
  développeur du robot.
- **USR-002 —** La validation initiale DOIT être faite sur le téléphone
  actuellement monté sur le robot.
- **USR-003 —** L'architecture DEVRAIT rester portable vers d'autres appareils
  Android à partir de l'API 26, sans imposer leur qualification dans le premier
  jalon.
- **USR-004 —** L'application DOIT pouvoir fonctionner sans Python, Termux ni
  connexion à un serveur externe.
- **USR-005 —** Le contrôle critique DOIT rester fonctionnel lorsque l'écran
  est éteint ou que l'activité graphique n'est plus au premier plan, tant que
  le service de contrôle reste actif.

## 5. Périmètre fonctionnel

### 5.1 Inclus dans le premier jalon

- acquisition native de l'accéléromètre et du gyroscope ;
- sélection des axes, des signes et du zéro mécanique ;
- mesure du temps réel entre événements ;
- filtre complémentaire ;
- correcteur PD avec sortie sélectionnable en vitesse ou PWM ;
- communication USB avec l'adaptateur CH340 ;
- protocole Feetech et commande de deux STS3215 ;
- commande synchronisée des moteurs ;
- modes progressifs de diagnostic et d'intégration ;
- interface Android complète ;
- page web complète sur le réseau local ;
- télémétrie et courbes temps réel ;
- journal borné en mémoire et export CSV ;
- détection des défauts et remise en état sûr ;
- workflow de construction et de distribution des APK de débogage.

### 5.2 Hors du premier jalon

- réutilisation du contrôleur Python en production ;
- accès au robot à travers Internet ;
- exposition d'un port de contrôle ou d'ADB par tunnel public ;
- authentification ou chiffrement HTTPS de la page locale ;
- publication sur un magasin d'applications ;
- correcteur PID ou autres algorithmes de contrôle ;
- filtres alternatifs au filtre complémentaire ;
- visualisation et comparaison historiques des journaux ;
- validation de plusieurs modèles d'adaptateurs USB ;
- garantie matérielle d'arrêt en cas de disparition brutale d'Android.

## 6. Architecture fonctionnelle attendue

### 6.1 Composants activables

L'application DOIT exposer les composants fonctionnels suivants :

1. acquisition IMU ;
2. estimation et filtre complémentaire ;
3. calcul du correcteur PD ;
4. connexion USB et bus STS3215 ;
5. sortie des commandes vers les moteurs ;
6. télémétrie moteur ;
7. journalisation ;
8. serveur web local.

- **MOD-001 —** Chaque composant DOIT avoir un état visible : désactivé,
  initialisation, actif ou défaut.
- **MOD-002 —** L'utilisateur DOIT pouvoir activer séparément les composants
  compatibles avec l'état courant.
- **MOD-003 —** Les dépendances DOIVENT être vérifiées avant activation. Par
  exemple, la sortie moteur ne peut pas être activée sans estimation valide,
  correcteur actif, bus disponible et moteurs identifiés.
- **MOD-004 —** La désactivation d'un composant amont DOIT désactiver ses
  consommateurs et, si nécessaire, désarmer les moteurs.
- **MOD-005 —** Activer un composant ne DOIT jamais entraîner à lui seul un
  mouvement moteur. La sortie physique reste soumise à un armement explicite.

### 6.2 Préréglages de mise au point

L'application DOIT fournir au minimum les préréglages suivants :

| Préréglage | Fonctions principales | Sortie moteur |
| --- | --- | --- |
| Capteurs | IMU, fréquences et valeurs brutes | Interdite |
| Filtre | IMU, angle accéléromètre, angle filtré | Interdite |
| Moteurs manuels | USB, identification, télémétrie, commande maintenue | Autorisée après armement |
| Simulation PD | IMU, filtre, PD et commande calculée | Interdite |
| Équilibrage | Chaîne native complète | Autorisée après armement |

- **MOD-006 —** Le passage d'un préréglage à un autre DOIT remettre la commande
  physique à zéro avant de reconfigurer la chaîne.
- **MOD-007 —** Les préréglages ne doivent pas empêcher l'activation manuelle
  des composants pendant le diagnostic.

## 7. Acquisition IMU

- **IMU-001 —** L'application DOIT utiliser les événements Android natifs de
  l'accéléromètre et du gyroscope.
- **IMU-002 —** Chaque événement DOIT conserver le `timestamp` matériel fourni
  par Android. Un horodatage logiciel ne doit pas remplacer un timestamp
  matériel valide.
- **IMU-003 —** L'application DOIT produire au plus un échantillon interne par
  callback matériel et ne doit pas fabriquer d'échantillons pour atteindre une
  cadence demandée.
- **IMU-004 —** Les valeurs brutes des trois axes DOIVENT être accessibles pour
  diagnostic, avec les unités explicites.
- **IMU-005 —** L'axe utilisé pour l'équilibrage et son signe DOIVENT être
  configurables et persistants.
- **IMU-006 —** Le système DOIT mesurer séparément la cadence des callbacks
  accéléromètre, la cadence des callbacks gyroscope et la cadence du contrôle.
- **IMU-007 —** Le système DOIT mesurer le `dt` réel entre événements gyro
  consécutifs, ainsi que son minimum, maximum, moyenne et dispersion sur une
  fenêtre glissante.
- **IMU-008 —** Un timestamp non croissant, une valeur non finie ou un
  échantillon incomplet DOIT être ignoré et comptabilisé.
- **IMU-009 —** Une mesure gyro trop ancienne pendant que la commande moteur
  est armée DOIT déclencher un défaut critique.
- **IMU-010 —** Avant le premier essai d'équilibrage, le téléphone cible DOIT
  démontrer une cadence gyro matérielle stable d'au moins 100 Hz. Si cette
  cadence n'est pas atteinte, le mode Équilibrage reste bloqué et le résultat
  est documenté.

## 8. Estimation de l'angle

- **EST-001 —** L'angle accéléromètre de référence DOIT être calculé avec une
  formule documentée, initialement équivalente à `atan2(ay, az)` après
  application de la configuration d'axe et de signe.
- **EST-002 —** Le gyroscope DOIT être converti de radians par seconde en degrés
  par seconde avant utilisation par le filtre et le PD.
- **EST-003 —** Le filtre complémentaire initial DOIT appliquer :

  ```text
  angle = alpha × (angle_précédent + vitesse_gyro × dt)
          + (1 − alpha) × angle_accéléromètre
  ```

- **EST-004 —** Le premier angle valide DOIT être initialisé depuis
  l'accéléromètre.
- **EST-005 —** `alpha` DOIT être configurable entre 0 et 1, avec une valeur
  initiale de `0,98`.
- **EST-006 —** Le système DOIT utiliser le `dt` issu des timestamps gyro. Une
  valeur imposée manuellement PEUT être proposée uniquement comme fonction de
  diagnostic clairement signalée.
- **EST-007 —** L'utilisateur DOIT pouvoir réinitialiser l'estimation sans
  redémarrer l'application.
- **EST-008 —** L'angle brut, l'angle filtré, la vitesse angulaire et le `dt`
  effectivement utilisé DOIVENT être observables et journalisables.

## 9. Correcteur PD

- **CTL-001 —** Le correcteur initial DOIT appliquer la formule :

  ```text
  commande = Kp × (angle_cible − angle_mesuré) − Kd × vitesse_gyro
  ```

- **CTL-002 —** `Kp`, `Kd` et l'angle cible DOIVENT être configurables depuis
  les deux interfaces.
- **CTL-003 —** Les gains initiaux DOIVENT valoir zéro afin qu'une première
  ouverture de l'application ne produise aucune commande.
- **CTL-004 —** La sortie calculée DOIT être saturée symétriquement. La limite
  initiale de référence est `6000` unités `Goal_Velocity` en mode vitesse et
  `1000` unités PWM en mode PWM.
- **CTL-005 —** Le calcul DOIT utiliser un événement gyro frais. Une vitesse
  absente ou invalide ne doit pas être silencieusement remplacée par zéro
  lorsque les moteurs sont armés.
- **CTL-006 —** Le mode Simulation PD DOIT calculer, afficher et journaliser la
  commande sans effectuer d'écriture moteur.
- **CTL-007 —** Le système DOIT distinguer commande calculée, commande saturée
  et commandes signées envoyées à chacun des moteurs.
- **CTL-008 —** Une modification des gains ou de la cible pendant le
  fonctionnement DOIT être bornée, validée et appliquée de manière atomique.

### 9.1 Boucle externe de vitesse

- **VEL-001 —** Une boucle externe DOIT transformer une consigne de vitesse du
  robot en angle cible pour le PD interne.
- **VEL-002 —** La vitesse de chaque roue DOIT provenir de `PresentVelocity`,
  après application du signe moteur configuré.
- **VEL-003 —** La vitesse physique DOIT être calculée en cm/s avec 4096 pas
  par tour, le diamètre de roue (40 mm par défaut) et le rapport moteur/roue
  (1,0 par défaut).
- **VEL-004 —** La vitesse robot DOIT valoir `(vitesse_gauche +
  vitesse_droite) / 2`.
- **VEL-005 —** La vitesse moyenne DOIT être filtrée par une EMA dont l'alpha
  est réglable, avec `0,5` par défaut.
- **VEL-006 —** La loi de commande DOIT être
  `angle_cible = trim + Kev × (vitesse_cible − vitesse_filtrée)`.
- **VEL-007 —** L'angle cible effectif DOIT être saturé symétriquement, à
  ±10° par défaut et dans une plage réglable de ±1° à ±15°.
- **VEL-008 —** La boucle DOIT fonctionner à 50 Hz par défaut, avec une cadence
  réglable de 5 à 100 Hz. Une cadence supérieure à celle du retour moteur PEUT
  réutiliser la dernière paire fraîche sans refiltrer deux fois la même mesure.
- **VEL-009 —** Une paire de vitesses périmée DOIT geler le dernier angle cible
  valide. Avant la première paire valide, la cible vaut le trim borné.
- **VEL-010 —** La consigne, `Kev`, les limites, l'alpha, la cadence et le
  timeout DOIVENT être modifiables en direct pendant l'équilibrage. Le diamètre
  de roue et le rapport de transmission exigent un désarmement.
- **VEL-011 —** Toutes les entrées, conversions, valeurs filtrées, erreurs,
  corrections, saturations, cadences et âges DOIVENT être affichables et
  exportables dans le CSV de session.
- **VEL-012 —** La boucle PEUT être désactivée ; le PD utilise alors directement
  le trim d'angle.

## 10. Bus USB et moteurs STS3215

### 10.1 Matériel initial

La cible initiale est :

- adaptateur USB-série CH340 en mode USB Host Android ;
- débit série de `1 000 000` bit/s ;
- deux servos STS3215 sur un bus partagé ;
- IDs initiaux `6` et `7` ;
- signes moteurs initiaux `+1,+1`, configurables ;
- montage électrique et alimentation actuels conservés.

- **MOT-001 —** L'application DOIT demander et gérer l'autorisation USB Android
  sans root ni Termux.
- **MOT-002 —** La couche série DEVRAIT permettre l'ajout futur de CP210x et
  CDC-ACM sans modifier la logique du protocole moteur.
- **MOT-003 —** L'application DOIT identifier l'adaptateur détecté et afficher
  son VID, PID, type de pilote et débit configuré.
- **MOT-004 —** Les commandes des deux moteurs DOIVENT être envoyées dans une
  seule écriture synchronisée Feetech lorsque les deux moteurs sont actifs ;
  l'adresse de consigne est `Goal_Velocity` (46) en mode vitesse et `Goal_Time`
  / PWM (44) en mode PWM.
- **MOT-005 —** Les signes doivent être appliqués individuellement après calcul
  de la commande commune.
- **MOT-006 —** Le groupe moteur n'est opérationnel que si tous les moteurs
  attendus ont été identifiés. La présence d'un seul moteur sur deux constitue
  un défaut du groupe.
- **MOT-007 —** Les IDs, signes, mode vitesse/PWM, limites de vitesse/PWM et
  limite de couple DOIVENT être configurables uniquement lorsque le système
  est désarmé.
- **MOT-008 —** Un scan du bus DOIT être interdit pendant l'armement. Avant tout
  scan, la commande doit être nulle et le couple désactivé.
- **MOT-009 —** La télémétrie DOIT inclure au minimum présence, vitesse mesurée,
  charge, tension et température lorsque ces valeurs sont disponibles.
- **MOT-010 —** Le retour rapide de vitesse DOIT viser 50 relevés par seconde
  et par moteur. La télémétrie complète de santé DOIT viser 10 relevés par
  seconde et par moteur, sans affamer les écritures de contrôle.
- **MOT-011 —** La télémétrie et les scans ne doivent jamais conserver un verrou
  de bus susceptible de retarder indéfiniment une commande de sécurité.

### 10.2 Mode moteur manuel

- **MAN-001 —** Le mode manuel DOIT être visuellement distinct du mode PD.
- **MAN-002 —** Une commande manuelle non nulle exige l'armement et une action
  maintenue par l'opérateur.
- **MAN-003 —** La libération de la commande ou la perte de son rafraîchissement
  DOIT demander une consigne nulle dans l'unité du mode actif (vitesse ou PWM).
- **MAN-004 —** Une séquence d'essai reproductible DOIT permettre les paliers :

  ```text
  0, +500, 0, +1000, 0, +2000, 0, +4000, 0, +6000, 0
  ```

- **MAN-005 —** Chaque palier DOIT journaliser consigne, vitesse réelle, charge,
  tension et temps afin d'estimer latence, accélération, saturation et constante
  de temps.

## 11. Service Android et cycle de vie

- **SVC-001 —** La chaîne de contrôle DOIT s'exécuter dans un service au premier
  plan, visible par une notification persistante.
- **SVC-002 —** Le service DOIT être démarré par une action explicite de
  l'utilisateur. Il ne doit pas démarrer au boot, après installation ou après
  mise à jour de l'APK.
- **SVC-003 —** L'écran éteint, le changement d'activité et la fermeture de
  l'interface graphique ne doivent pas arrêter le contrôle tant que le service
  reste actif.
- **SVC-004 —** La notification DOIT afficher au minimum l'état désarmé/armé ou
  en défaut et fournir une action d'arrêt.
- **SVC-005 —** L'arrêt normal du service DOIT demander zéro puis désactiver le
  couple avant de libérer le bus USB.
- **SVC-006 —** Le callback capteur, la boucle de contrôle, le bus USB, le
  serveur web, la journalisation et l'interface ne doivent pas s'exécuter sur
  un même thread.
- **SVC-007 —** Une opération lente de télémétrie, d'export ou d'affichage ne
  doit pas créer une file non bornée de commandes moteur périmées.

## 12. Interface Android

- **APP-001 —** L'application Android DOIT exposer toutes les fonctions du
  premier jalon, et pas seulement un tableau de sécurité simplifié.
- **APP-002 —** Elle DOIT afficher clairement l'état du service, l'armement, les
  composants actifs, l'état USB, les moteurs et le défaut courant.
- **APP-003 —** Elle DOIT permettre les réglages IMU, filtre, PD, bus, moteurs,
  journal et serveur web autorisés dans l'état courant.
- **APP-004 —** L'arrêt doit rester visible et accessible depuis les écrans de
  diagnostic moteur et d'équilibrage.
- **APP-005 —** L'application DOIT proposer des courbes temps réel pour au moins
  l'angle filtré, l'angle cible, la vitesse gyro et la commande moteur.
- **APP-006 —** La cadence d'affichage DOIT être découplée de la cadence de
  contrôle. La réduction des points affichés ne doit pas réduire les données
  disponibles dans le journal.
- **APP-007 —** Les actions interdites dans l'état courant DOIVENT être
  désactivées avec une explication visible.

## 13. Page web locale

- **WEB-001 —** L'application DOIT servir une page web utilisable depuis un
  navigateur présent sur le même réseau local que le téléphone.
- **WEB-002 —** La page DOIT offrir les mêmes réglages, diagnostics, courbes,
  commandes et fonctions d'armement que l'interface Android.
- **WEB-003 —** Les deux interfaces DOIVENT partager le même état et les mêmes
  validations. La page web ne doit pas implémenter une seconde logique de
  contrôle.
- **WEB-004 —** Toute modification faite depuis une interface DOIT être
  reflétée rapidement dans l'autre.
- **WEB-005 —** La page web V1 ne comporte ni authentification ni chiffrement.
  Toute machine capable d'atteindre le téléphone sur le LAN peut donc observer
  et commander le robot, y compris l'armer.
- **WEB-006 —** Ce risque DOIT être affiché dans l'application et dans la page.
  Le réseau local est considéré comme un réseau de confiance pour ce jalon.
- **WEB-007 —** La page et son canal de commande ne DOIVENT pas être exposés par
  le tunnel utilisé pour télécharger les APK.
- **WEB-008 —** L'application DOIT afficher l'adresse locale utilisable et
  DEVRAIT proposer un QR code.
- **WEB-009 —** Plusieurs navigateurs PEUVENT observer l'état simultanément.
  L'origine des commandes et leur ordre d'application DOIVENT rester
  déterministes et observables.
- **WEB-010 —** Un ordre d'arrêt DOIT être prioritaire sur les autres ordres.

Le format précis des messages et le choix du serveur embarqué seront définis
dans une spécification technique ultérieure.

## 14. Journalisation et export

- **LOG-001 —** L'utilisateur DOIT pouvoir démarrer, arrêter et vider le journal
  depuis les deux interfaces.
- **LOG-002 —** Le journal V1 DOIT être un tampon borné en mémoire. Il n'est pas
  restauré après l'arrêt du processus.
- **LOG-003 —** La capacité initiale de référence est de 200 000 événements. Un
  dépassement doit remplacer les données les plus anciennes sans bloquer la
  boucle de contrôle.
- **LOG-004 —** L'export DOIT produire un CSV valide même lorsque le journal est
  vide.
- **LOG-005 —** Le CSV DOIT contenir, selon le type d'événement :
  - temps monotone et temps de session ;
  - accéléromètre et gyroscope sur trois axes ;
  - angle accéléromètre et angle filtré ;
  - `dt`, alpha et paramètres de calibration ;
  - cible, erreur, Kp, Kd et commande calculée ;
  - commandes signées envoyées aux moteurs ;
  - vitesse, charge, tension et température de chaque moteur ;
  - fréquences, latences, changements d'état et défauts.
- **LOG-006 —** La construction et le téléchargement du CSV DOIVENT se faire
  hors de la boucle de contrôle.
- **LOG-007 —** L'application et la page web DOIVENT permettre de télécharger
  ou partager le journal courant.
- **LOG-008 —** La visualisation historique, le zoom sur des journaux sauvegardés
  et la comparaison de sessions sont reportés après le premier jalon.

## 15. Configuration et persistance

- **CFG-001 —** Les IDs, signes, axes, calibration, alpha, gains, cible, limites
  et préférences d'interface DOIVENT pouvoir être persistés.
- **CFG-002 —** L'armement, le couple actif, la dernière commande manuelle et
  les sorties moteur ne doivent jamais être restaurés après un redémarrage.
- **CFG-003 —** Après démarrage ou mise à jour de l'application, la commande
  effective DOIT être zéro et le système DOIT être désarmé.
- **CFG-004 —** Une configuration invalide ou incomplète DOIT être refusée sans
  remplacer la dernière configuration valide.
- **CFG-005 —** La configuration active et ses unités DOIVENT être visibles et
  incluses dans les exports de diagnostic.

## 16. Sécurité fonctionnelle

### 16.1 États d'armement

Le système DOIT distinguer au minimum :

- désarmé ;
- prêt à armer ;
- armé en mode manuel ;
- armé en mode équilibrage ;
- défaut verrouillé.

- **SAF-001 —** Le démarrage se fait toujours dans l'état désarmé.
- **SAF-002 —** L'armement DOIT être une action volontaire et explicite depuis
  l'application ou la page web.
- **SAF-003 —** Avant armement, le système DOIT vérifier au minimum : IMU
  fraîche, estimation valide, bus connecté, deux moteurs présents, paramètres
  valides et absence de défaut verrouillé.
- **SAF-004 —** Un défaut critique DOIT provoquer, dans cet ordre lorsque le bus
  reste utilisable : commande de vitesse nulle, désactivation du couple,
  verrouillage du défaut.
- **SAF-005 —** Un défaut verrouillé exige un acquittement puis un nouvel
  armement. Le simple retour d'une mesure valide ne redémarre pas les moteurs.
- **SAF-006 —** La perte d'un seul moteur arrête le groupe complet.
- **SAF-007 —** Une perte IMU, un timestamp incohérent persistant, une perte USB,
  une erreur de protocole persistante ou un dépassement d'angle de chute sont
  des défauts critiques.
- **SAF-008 —** Les seuils de timeout et de chute DOIVENT être configurables
  hors armement et journalisés. Les valeurs initiales à qualifier sont 100 ms
  sans gyro frais et 35° d'écart maximal pendant 100 ms.
- **SAF-009 —** Aucun essai moteur ne DOIT démarrer automatiquement après
  installation, lancement ou reconnexion USB.
- **SAF-010 —** L'arrêt depuis l'application, la notification ou la page web
  DOIT toujours être accepté, même si l'état courant contient une erreur.

### 16.2 Limite acceptée du logiciel seul

Les STS3215 peuvent conserver leur dernière consigne et une écriture
synchronisée ne fournit pas d'accusé de réception. Si Android plante, si le
téléphone s'éteint ou si la communication disparaît immédiatement après une
commande non nulle, le logiciel ne peut plus envoyer zéro.

- **SAF-011 —** Le premier jalon accepte explicitement cette limite et n'impose
  ni watchdog matériel ni coupure électrique commandée.
- **SAF-012 —** L'application et la documentation DOIVENT signaler qu'un arrêt
  logiciel ne garantit pas l'immobilisation lors d'une disparition brutale du
  contrôleur.
- **SAF-013 —** Les premiers essais de chaque nouvelle fonction moteur DOIVENT
  être réalisés roues levées ou dans une zone dégagée, avec un moyen rapide de
  couper manuellement l'alimentation disponible pour l'opérateur, même si ce
  moyen ne fait pas partie du logiciel livré.

## 17. Performances et observabilité

- **PERF-001 —** La boucle de contrôle ne doit pas s'exécuter sur le thread de
  l'interface.
- **PERF-002 —** Le callback capteur ne doit réaliser ni accès réseau, ni export,
  ni lecture de télémétrie moteur, ni autre opération bloquante.
- **PERF-003 —** La commande doit être recalculée à chaque événement gyro frais,
  sauf décimation explicitement configurée et affichée.
- **PERF-004 —** L'application DOIT mesurer la latence entre le callback gyro et
  la remise de la commande au transport USB.
- **PERF-005 —** La cible initiale est une latence inférieure à 15 ms au 95e
  percentile et à 30 ms au 99e percentile sur le téléphone de référence.
- **PERF-006 —** Toute commande devenue obsolète avant écriture doit être
  remplacée par la commande la plus récente, et non exécutée tardivement.
- **PERF-007 —** Les courbes DEVRAIENT être rafraîchies à 20 Hz au maximum sans
  modifier la cadence de calcul ni celle du journal.
- **PERF-008 —** L'état doit exposer les compteurs de mesures ignorées,
  commandes écrasées, erreurs USB, défauts et pertes de journal.

## 18. Workflow d'itération Android

- **DEV-001 —** Un build debug DOIT pouvoir être produit par une commande
  reproductible depuis le poste de développement.
- **DEV-002 —** Lorsque ADB est disponible, la mise à jour DOIT utiliser une
  installation conservant les données de l'application.
- **DEV-003 —** Lorsque ADB n'est pas disponible, le dernier APK debug DOIT
  pouvoir être téléchargé depuis une URL HTTPS temporaire fournie par le poste
  de développement.
- **DEV-004 —** Le tunnel de téléchargement ne doit servir que l'APK et les
  informations associées. Il ne doit exposer ni ADB ni le contrôle du robot.
- **DEV-005 —** Chaque APK de test DOIT afficher une version permettant
  d'identifier la construction installée.
- **DEV-006 —** Une livraison de test DEVRAIT fournir l'empreinte de l'APK et le
  résultat des vérifications exécutées.

## 19. Stratégie de validation future

Cette section décrit les essais qui devront être exécutés pendant
l'implémentation. Aucun de ces essais n'est réalisé dans le cadre de la seule
rédaction du présent document.

### 19.1 Validation sans moteur

| ID | Scénario | Critère d'acceptation |
| --- | --- | --- |
| VAL-IMU-01 | Téléphone immobile puis mouvements connus | Événements matériels monotones, valeurs et unités cohérentes |
| VAL-IMU-02 | Mesure de cadence pendant plusieurs minutes | Gyro réel ≥ 100 Hz, cadence et jitter journalisés |
| VAL-EST-01 | Rejeu de données connues | Résultat conforme à la formule du filtre |
| VAL-PD-01 | Valeurs synthétiques d'angle et gyro | Résultat conforme à la formule PD et aux saturations |
| VAL-PD-02 | Mode Simulation PD | Aucune trame moteur malgré une commande calculée non nulle |
| VAL-UI-01 | Même réglage depuis Android puis le web | Même état observé dans les deux interfaces |
| VAL-LOG-01 | Démarrage, remplissage et export | CSV complet, cohérent et exporté hors boucle critique |

### 19.2 Validation USB et moteurs, roues levées

| ID | Scénario | Critère d'acceptation |
| --- | --- | --- |
| VAL-USB-01 | Branchement et autorisation CH340 | Adaptateur identifié et configuré à 1 Mbit/s |
| VAL-MOT-01 | Scan désarmé | IDs 6 et 7 détectés, aucun mouvement |
| VAL-MOT-02 | Commande zéro et test des signes | Les deux moteurs répondent dans le sens attendu |
| VAL-MOT-03 | Paliers manuels | Consigne, vitesse, charge et tension enregistrées |
| VAL-MOT-04 | Écriture synchronisée | Une commande commune produit deux valeurs signées cohérentes |
| VAL-MOT-05 | Retrait ou silence d'un moteur | Groupe en défaut, commande zéro puis couple coupé si possible |
| VAL-USB-02 | Déconnexion USB pendant une commande | Défaut verrouillé, aucun réarmement automatique |

### 19.3 Validation intégrée

| ID | Scénario | Critère d'acceptation |
| --- | --- | --- |
| VAL-INT-01 | Activation successive des préréglages | Aucun étage aval ne s'active sans ses prérequis |
| VAL-INT-02 | Passage Simulation PD vers Équilibrage | Armement explicite requis, aucune commande transitoire ancienne |
| VAL-INT-03 | Gyro périmé | Défaut détecté et réaction de sécurité déclenchée |
| VAL-INT-04 | Angle de chute dépassé | Zéro, couple coupé si possible et défaut verrouillé |
| VAL-INT-05 | Écran éteint, service actif | Boucle et mesures continuent sans interruption anormale |
| VAL-WEB-01 | Contrôle depuis un autre appareil du LAN | Fonctions complètes et retour d'état en temps réel |
| VAL-WEB-02 | Tentative depuis l'extérieur du LAN | Aucun accès fourni par le projet |

### 19.4 Critères de sortie du premier jalon

Le premier jalon sera terminé lorsque :

1. les exigences obligatoires du présent document seront implémentées ou les
   écarts explicitement acceptés ;
2. les étapes Capteurs, Filtre, Moteurs manuels, Simulation PD et Équilibrage
   auront été validées dans cet ordre ;
3. les fréquences et latences réelles auront été mesurées sur le téléphone
   cible ;
4. le CH340 et les deux STS3215 auront été validés roues levées ;
5. les défauts détectables auront été injectés et observés ;
6. les deux interfaces auront démontré un comportement cohérent ;
7. un APK debug identifiable aura été produit et installé ;
8. les risques résiduels, notamment l'absence de watchdog matériel, auront été
   consignés.

La capacité du robot à tenir durablement en équilibre fera l'objet d'une phase
de caractérisation et de réglage distincte.

## 20. Risques et points de vigilance

| Risque | Conséquence | Réponse prévue dans ce cahier des charges |
| --- | --- | --- |
| Cadence IMU insuffisante | Terme dérivé inefficace ou instable | Mesure native, gate à 100 Hz avant équilibrage |
| Latence ou blocage USB | Commande périmée | Mesure de latence, commandes bornées, dernière commande gagnante |
| STS3215 conserve la dernière vitesse | Mouvement après disparition d'Android | Risque accepté et avertissement explicite |
| Télémétrie trop lente | Mauvaise caractérisation des transitoires | Mode diagnostic visant ≥ 20 Hz par moteur |
| Alimentation 5 V insuffisante | Chute de tension, accélération faible ou reset | Journaliser tension/charge ; montage actuel inchangé |
| Centre de gravité bas | Dynamique rapide et réglage difficile | Caractériser les moteurs avant réglage fin du PD |
| Page web sans authentification | Toute machine du LAN peut commander | LAN déclaré de confiance, avertissement visible, aucune exposition Internet |
| Concurrence entre interfaces | Commandes contradictoires | État partagé et arbitrage déterministe à spécifier |
| Affichage ou export trop coûteux | Jitter de la boucle | Découplage strict des tâches non critiques |

## 21. Décisions différées

Les éléments suivants devront être traités dans une spécification ou une
conception ultérieure, sans bloquer la validation du présent besoin :

- découpage des modules et classes Android ;
- bibliothèque USB exacte et abstraction de transport ;
- technologie d'interface Android ;
- serveur HTTP/WebSocket embarqué ;
- format JSON et versionnement du protocole web ;
- méthode d'arbitrage détaillée entre plusieurs clients ;
- stockage concret des préférences ;
- mise en œuvre des graphiques temps réel ;
- seuils définitifs issus des mesures réelles ;
- évolution future vers un accès distant sécurisé.
