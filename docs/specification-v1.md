# Spécification fonctionnelle V1 — Application Android du robot équilibré

**Version :** 0.1  
**Date :** 2026-10-01  
**Statut :** proposition à valider  
**Document amont :** `docs/cahier-des-charges.md`

## 1. Objet

Cette spécification transforme les exigences du cahier des charges en
comportements observables et non ambigus. Elle précise les états du système,
les enchaînements fonctionnels, les paramètres, les opérations accessibles aux
interfaces et les réactions aux erreurs.

Elle ne choisit pas encore :

- les bibliothèques Android ;
- le découpage en modules, classes ou fichiers ;
- le format JSON définitif ;
- le rendu graphique précis des écrans ;
- la stratégie détaillée de tests automatisés.

Ces choix relèveront de la conception détaillée après validation de la présente
spécification.

## 2. Terminologie

| Terme | Définition |
| --- | --- |
| Commande calculée | Résultat brut du correcteur PD avant saturation |
| Commande bornée | Commande calculée limitée à `[-vmax, +vmax]` |
| Commande moteur | Commande bornée après application du signe propre au moteur |
| Sortie moteur | Écriture physique d'une commande sur le bus STS3215 |
| Armement | Autorisation temporaire d'émettre des commandes moteur non nulles |
| Désarmement | Interdiction des commandes non nulles, avec demande de zéro |
| Défaut critique | Événement imposant zéro, coupure du couple si possible et verrouillage |
| Défaut verrouillé | Défaut mémorisé jusqu'à acquittement explicite |
| Échantillon frais | Événement matériel valide, au timestamp strictement croissant |
| Snapshot | Vue cohérente et immuable de l'état complet à un instant donné |
| Session | Période comprise entre le démarrage et l'arrêt du service de contrôle |

Tous les temps internes utilisent une horloge monotone. L'heure civile est
réservée à l'affichage et à l'export.

## 3. Vue fonctionnelle

Le système comporte une chaîne critique et des consommateurs non critiques :

```text
SensorEvent accel ───────────────┐
                                 ├─► estimation ─► PD ─► saturation
SensorEvent gyro ─► validation ──┘                       │
                                                        ▼
                                              signes par moteur
                                                        │
                                                        ▼
                                             écriture synchronisée

Snapshots ─► interface Android
          ├► page web locale
          ├► courbes décimées
          └► journal borné
```

La chaîne critique ne dépend jamais de la présence d'une interface, d'un
navigateur ou d'un journal actif.

## 4. États du service

Le service de contrôle possède les états suivants :

| État | Description |
| --- | --- |
| `STOPPED` | Aucun capteur, serveur ou accès USB détenu |
| `STARTING` | Initialisation du service et publication de la notification |
| `RUNNING` | Service actif, composants utilisables |
| `STOPPING` | Mise à zéro, coupure du couple et libération des ressources |
| `FAILED` | Échec empêchant le fonctionnement du service lui-même |

### 4.1 Transitions

| Origine | Événement | Destination | Action obligatoire |
| --- | --- | --- | --- |
| `STOPPED` | Démarrage explicite depuis l'application | `STARTING` | Créer une nouvelle session désarmée |
| `STARTING` | Initialisation réussie | `RUNNING` | Publier le snapshot initial |
| `STARTING` | Initialisation impossible | `FAILED` | Libérer les ressources et afficher la cause |
| `RUNNING` | Arrêt explicite | `STOPPING` | Zéro, couple off si possible, arrêt des composants |
| `RUNNING` | Erreur interne fatale | `STOPPING` | Même séquence de sécurité |
| `FAILED` | Acquittement | `STOPPED` | Permettre une nouvelle tentative |
| `STOPPING` | Ressources libérées | `STOPPED` | Retirer la notification persistante |

Le service ne redémarre jamais automatiquement après `FAILED`, arrêt forcé,
redémarrage du téléphone ou mise à jour de l'application.

## 5. États d'armement

L'armement est indépendant de l'état du service.

| État | Sortie non nulle autorisée | Description |
| --- | --- | --- |
| `DISARMED` | Non | État initial et état normal de configuration |
| `READY` | Non | Tous les prérequis du mode demandé sont satisfaits |
| `MANUAL_ARMED` | Oui, avec action maintenue | Commande directe de diagnostic |
| `BALANCE_ARMED` | Oui, par le PD | Boucle d'équilibrage active |
| `FAULT_LATCHED` | Non | Défaut critique mémorisé |

### 5.1 Prérequis communs à l'armement

L'armement est accepté uniquement si :

1. le service est `RUNNING` ;
2. aucun défaut critique n'est verrouillé ;
3. l'IMU fournit des données fraîches ;
4. la cadence gyro a été qualifiée pour le mode demandé ;
5. la configuration active est valide ;
6. le bus USB est ouvert à 1 Mbit/s ;
7. les deux IDs attendus répondent ;
8. les signes moteurs et limites sont définis ;
9. la commande courante vaut zéro.

Le mode `BALANCE_ARMED` exige en plus une estimation valide et le PD actif. Le
mode `MANUAL_ARMED` n'exige ni estimateur ni PD.

### 5.2 Transitions d'armement

| Origine | Opération | Résultat |
| --- | --- | --- |
| `DISARMED` | Tous les prérequis deviennent vrais | `READY` |
| `READY` | `arm(MANUAL)` confirmé | `MANUAL_ARMED` |
| `READY` | `arm(BALANCE)` confirmé | `BALANCE_ARMED` |
| `READY` | Un prérequis disparaît | `DISARMED` |
| `MANUAL_ARMED` | `disarm` ou changement de préréglage | Zéro puis `DISARMED` |
| `BALANCE_ARMED` | `disarm` ou changement de préréglage | Zéro puis `DISARMED` |
| Tout état armé | Défaut critique | Séquence de sécurité puis `FAULT_LATCHED` |
| `FAULT_LATCHED` | `ackFault` avec cause disparue | `DISARMED` |

`ackFault` échoue si la cause est toujours présente. Un acquittement réussi ne
réarme jamais le système.

## 6. Composants et dépendances

Chaque composant possède l'un des états `OFF`, `STARTING`, `ACTIVE`, `BLOCKED`
ou `FAULT`.

| Composant | Dépendances pour devenir `ACTIVE` | Effet de sa désactivation |
| --- | --- | --- |
| IMU | Service `RUNNING`, capteurs présents | Invalide estimateur et PD, désarme |
| Estimateur | IMU active, accel et gyro valides | Invalide le PD, désarme le mode équilibre |
| PD | Estimateur actif, paramètres valides | Interdit le mode équilibre |
| USB | Service actif, permission et adaptateur disponible | Arrête télémétrie et sortie, désarme |
| Sortie moteurs | USB active, groupe complet, armement | Demande zéro avant passage à `OFF` |
| Télémétrie | USB active, groupe complet | Supprime seulement les relevés moteur |
| Journal | Service actif | Conserve le tampon existant |
| Web | Service actif, port disponible | Ferme les connexions sans affecter le contrôle |

Un composant `BLOCKED` indique la liste des dépendances manquantes. Un composant
`FAULT` expose une cause structurée.

## 7. Préréglages

Les préréglages appliquent une configuration de composants mais n'arment pas le
robot.

| Composant | Capteurs | Filtre | Moteurs manuels | Simulation PD | Équilibrage |
| --- | :---: | :---: | :---: | :---: | :---: |
| IMU | On | On | Optionnel | On | On |
| Estimateur | Off | On | Off | On | On |
| PD | Off | Off | Off | On | On |
| USB | Off | Off | On | Optionnel | On |
| Sortie moteurs | Off | Off | Prête, désarmée | Off | Prête, désarmée |
| Télémétrie | Off | Off | On | Optionnel | On |
| Journal | Inchangé | Inchangé | Inchangé | Inchangé | Inchangé |
| Web | Inchangé | Inchangé | Inchangé | Inchangé | Inchangé |

L'application d'un préréglage suit toujours cet ordre :

1. demander zéro si une sortie est active ;
2. désarmer ;
3. désactiver les composants devenus inutiles en partant de l'aval ;
4. activer les nouveaux composants en partant de l'amont ;
5. évaluer les prérequis et afficher `READY` ou `DISARMED`.

## 8. Modèle des données IMU

### 8.1 Échantillon brut

Un échantillon contient au minimum :

| Champ | Type logique | Unité |
| --- | --- | --- |
| `kind` | `ACCEL` ou `GYRO` | — |
| `x`, `y`, `z` | nombre réel fini | m/s² ou rad/s à l'entrée |
| `sensorTimestampNs` | entier positif | ns monotones Android |
| `receivedTimestampNs` | entier positif | ns monotones application |

### 8.2 Validation

Un échantillon est rejeté si :

- son vecteur comporte moins de trois valeurs ;
- une valeur n'est pas finie ;
- son timestamp n'est pas strictement supérieur au dernier timestamp accepté
  du même capteur ;
- son type n'est pas attendu.

Chaque rejet incrémente un compteur par motif. Aucun timestamp de réception ne
remplace le timestamp capteur rejeté.

### 8.3 Normalisation

- L'accélération reste en m/s².
- Le gyroscope est converti en degrés par seconde avec
  `180 / π`.
- L'axe d'équilibrage est sélectionné parmi X, Y et Z.
- Un signe `+1` ou `-1` est appliqué à l'axe choisi.
- Un offset de zéro en degrés est appliqué à l'angle, pas aux valeurs brutes.

### 8.4 Cadences et fraîcheur

Les cadences accel, gyro et contrôle sont calculées indépendamment sur une
fenêtre glissante d'une seconde et exposées au moins une fois par seconde.

Le `dt` utilisé par le filtre est la différence entre les deux derniers
timestamps gyro acceptés. Un `dt <= 0` est rejeté. Un `dt > 100 ms` :

- réinitialise l'estimateur si le système est désarmé ;
- déclenche `IMU_STALE` si le système est armé.

L'accélération la plus récente est utilisée lors de chaque tick gyro. Si elle a
plus de 100 ms, l'estimation devient invalide.

## 9. Estimation

### 9.1 Angle accéléromètre initial

Pour l'axe X initial :

```text
accelAngleDeg = sign × atan2(ay, az) × 180 / π − zeroOffsetDeg
```

Les permutations nécessaires aux axes Y et Z doivent conserver une convention
documentée et testable. Le choix d'axe ne modifie jamais les données brutes.

### 9.2 Filtre complémentaire

À la première paire accel/gyro valide :

```text
estimatedAngleDeg = accelAngleDeg
```

Aux ticks suivants :

```text
predicted = previousAngleDeg + gyroRateDegPerSec × dtSec
estimatedAngleDeg = alpha × predicted + (1 − alpha) × accelAngleDeg
```

L'estimation devient invalide lorsqu'un de ses termes obligatoires est absent
ou non fini. En mode armé, cette invalidité est un défaut critique.

### 9.3 Réinitialisation

`resetEstimator` efface l'angle précédent. Le prochain angle valide est
initialisé depuis l'accéléromètre. L'opération est refusée pendant
`BALANCE_ARMED` ; l'utilisateur doit d'abord désarmer.

## 10. Correcteur PD

À chaque nouvel angle valide :

```text
errorDeg = targetDeg − estimatedAngleDeg
rawCommand = kp × errorDeg − kd × gyroRateDegPerSec
boundedCommand = round(clamp(rawCommand, -vmax, +vmax))
motorCommand[i] = motorSign[i] × boundedCommand
```

Règles :

- aucune interpolation ou répétition de tick n'est créée entre deux événements
  gyro ;
- une valeur absente ou non finie invalide la commande ;
- une commande invalide vaut zéro lorsqu'elle est affichée en simulation et
  déclenche un défaut si la sortie est armée ;
- le mode Simulation PD publie exactement les mêmes calculs que le mode
  Équilibrage, mais n'appelle jamais la sortie moteur ;
- une saturation incrémente un compteur et reste visible dans le snapshot.

## 11. Paramètres

| Paramètre | Défaut | Domaine V1 | Persisté | Modifiable armé |
| --- | ---: | --- | :---: | :---: |
| Axe d'équilibrage | X | X, Y ou Z | Oui | Non |
| Signe IMU | +1 | -1 ou +1 | Oui | Non |
| Offset zéro | 0° | -180° à +180° | Oui | Non |
| Alpha | 0,98 | 0 à 1 | Oui | Oui |
| Cible | 0° | -180° à +180° | Oui | Oui, limitée à ±15° |
| Kp | 0 | 0 à 2000 | Oui | Oui |
| Kd | 0 | 0 à 2000 | Oui | Oui |
| Vmax | 6000 | 0 à 20000 | Oui | Non |
| Mode moteur | vitesse | vitesse ou PWM | Oui | Non |
| PWM max | 1000 | 0 à 1000 | Oui | Non |
| IDs moteurs | 6,7 | entiers distincts 0 à 252 | Oui | Non |
| Signes moteurs | +1,+1 | un signe par ID | Oui | Non |
| Débit série | 1 000 000 | fixe en V1 | Oui | Non |
| Limite de couple | 1023 | 0 à 1023 | Oui | Non |
| Timeout IMU | 100 ms | 20 à 1000 ms | Oui | Non |
| Angle de chute | 35° | 5° à 90° | Oui | Non |
| Durée de chute | 100 ms | 20 à 1000 ms | Oui | Non |
| Timeout manuel | 300 ms | 100 à 2000 ms | Oui | Non |
| Capacité journal | 200 000 | 1 000 à 500 000 | Oui | Non |

Une modification multiple est validée intégralement avant application. Si un
champ est invalide, aucun champ de la transaction n'est modifié.

## 12. USB et bus STS3215

### 12.1 Connexion

Le composant USB suit les états `DETACHED`, `PERMISSION_REQUIRED`, `OPENING`,
`OPEN`, `ERROR`.

- Le branchement ne provoque ni armement ni activation du couple.
- Une permission manquante déclenche une demande Android visible.
- Le port est configuré à 1 Mbit/s, 8 bits, sans parité, 1 bit de stop.
- Toute reconnexion remet le groupe moteur dans un état non qualifié.
- Les deux IDs configurés doivent répondre avant que le groupe devienne prêt.

### 12.2 Configuration des moteurs

La configuration d'un moteur suit cet ordre, uniquement désarmé :

1. identifier l'ID ;
2. sélectionner et vérifier le mode vitesse ou PWM ;
3. appliquer la limite de couple demandée ;
4. vérifier que la consigne vaut zéro ;
5. laisser le couple désactivé jusqu'à l'armement.

Pour un STS3215, le mode vitesse écrit `Goal_Velocity` (registre 46, signe en
bit 15). Le mode PWM écrit le registre 44 (`Goal_Time` dans la table générique,
utilisé comme consigne PWM en mode 2), avec signe en bit 10 et magnitude
comprise entre 0 et 1000. Le mode 2 est une commande en boucle ouverte : la
vitesse réelle dépend de la charge et de la tension moteur.

La modification automatique d'un registre persistant du servo doit être
signalée séparément et ne fait pas partie d'un simple démarrage de session.

### 12.3 Écriture

Une commande aux deux moteurs utilise une écriture synchronisée unique. Elle ne
produit pas d'accusé de réception ; la réussite signifie uniquement que la
trame a été remise au transport USB sans erreur connue.

La sortie conserve une seule commande en attente : une nouvelle commande
remplace toute commande non encore écrite. Un ordre zéro de sécurité ne peut
pas être remplacé par un ordre non nul tant que l'état reste désarmé ou en
défaut.

### 12.4 Télémétrie

Le mode normal vise 10 relevés par seconde et par moteur. Le mode diagnostic
vise au moins 20 relevés par seconde et par moteur. Une lecture regroupe autant
que possible les registres contigus.

La priorité du bus est :

1. zéro et coupure du couple ;
2. commandes synchronisées ;
3. configuration explicitement demandée ;
4. télémétrie ;
5. scan.

Une absence de réponse télémétrie ponctuelle ne déclenche pas seule un défaut
moteur : le garde-fou attend trois cycles consécutifs manqués avant de couper le
groupe. Une réponse complète réinitialise ce compteur.

## 13. Commande manuelle

La commande manuelle est disponible uniquement avec le préréglage Moteurs
manuels et l'état `MANUAL_ARMED`.

Une opération manuelle fournit :

- une valeur de base bornée par `vmax` ;
- un indicateur `held=true` renouvelé au maximum toutes les 100 ms ;
- l'identité de la source de commande pour diagnostic.

La commande devient immédiatement zéro si :

- `held=false` ;
- aucun renouvellement n'est reçu pendant le timeout manuel ;
- l'interface qui maintient la commande se déconnecte ;
- un ordre concurrent de désarmement ou d'arrêt arrive ;
- un défaut critique survient.

La séquence de paliers standard s'arrête entre chaque palier et exige une
confirmation avant de passer au suivant.

## 14. Défauts et avertissements

### 14.1 Catalogue des défauts critiques

| Code | Déclencheur | Condition de sortie |
| --- | --- | --- |
| `IMU_STALE` | Aucun gyro frais avant le timeout | Gyro à nouveau frais |
| `IMU_TIMESTAMP_INVALID` | Timestamps incohérents persistants | Nouvelle séquence valide après redémarrage IMU |
| `ESTIMATE_INVALID` | Angle non calculable en mode équilibre | Estimateur réinitialisé puis valide |
| `FALL_ANGLE` | Seuil angulaire dépassé pendant la durée configurée | Angle revenu sous le seuil |
| `USB_DISCONNECTED` | Adaptateur absent ou fermé | USB reconnecté et qualifié |
| `MOTOR_MISSING` | Un ID attendu ne répond plus | Deux moteurs à nouveau identifiés |
| `BUS_ERROR` | Erreurs de protocole ou I/O persistantes | Bus rouvert et moteurs qualifiés |
| `CONTROL_OVERRUN` | Commandes périmées répétées ou latence excessive | Cadence redevenue normale, système désarmé |
| `INTERNAL_ERROR` | Invariant interne rompu | Redémarrage du service |

Chaque défaut contient : code, message, timestamp, état précédent, mesures
utiles, première occurrence et nombre d'occurrences.

### 14.2 Avertissements

Une cadence IMU insuffisante lorsque le système est désarmé, une tension basse,
une température élevée, une saturation fréquente ou une perte d'événements de
journal déclenchent un avertissement. Un avertissement est visible et journalisé
mais ne désarme pas, sauf promotion explicite en défaut critique par une
spécification ultérieure.

### 14.3 Séquence de sécurité

Lors d'un défaut critique :

1. bloquer immédiatement toute nouvelle commande non nulle ;
2. demander une écriture synchronisée zéro si le bus est utilisable ;
3. demander la coupure du couple sur les deux moteurs si le bus est utilisable ;
4. passer à `FAULT_LATCHED` même si une écriture échoue ;
5. publier et journaliser le défaut ;
6. exiger disparition de la cause, acquittement et nouvel armement.

Cette séquence est une meilleure tentative logicielle, pas une garantie en cas
de disparition brutale du processus ou du téléphone.

## 15. Commandes des interfaces

Toutes les interfaces utilisent le même service logique de commandes. Une
commande contient un identifiant, une source et l'instant de réception. Elle
reçoit toujours un résultat `ACCEPTED` ou `REJECTED` avec un motif.

### 15.1 Catalogue des opérations

| Opération | Disponibilité | Effet |
| --- | --- | --- |
| `startService` | Application Android seulement | Démarre une session désarmée |
| `stopService` | Application, notification, web | Exécute la séquence d'arrêt |
| `applyPreset` | Désarmé | Applique un préréglage |
| `setComponent` | Selon dépendances | Active ou désactive un composant |
| `updateParameters` | Selon tableau des paramètres | Transaction de configuration |
| `resetEstimator` | Hors équilibre armé | Réinitialise le filtre |
| `connectUsb` | Désarmé | Ouvre ou demande la permission USB |
| `disconnectUsb` | Désarmé | Ferme proprement le bus |
| `scanBus` | Désarmé, sortie off | Cherche les IDs autorisés |
| `armManual` | État `READY` manuel | Passe à `MANUAL_ARMED` |
| `armBalance` | État `READY` équilibre | Passe à `BALANCE_ARMED` |
| `manualCommand` | `MANUAL_ARMED` | Renouvelle la commande maintenue |
| `disarm` | Tout état armé | Zéro puis désarmement |
| `emergencyStop` | Toujours | Zéro, couple off et désarmement |
| `ackFault` | `FAULT_LATCHED` | Acquitte si la cause a disparu |
| `startLog` | Service actif | Vide puis démarre le tampon |
| `stopLog` | Journal actif | Fige le contenu courant |
| `clearLog` | Journal inactif | Vide le tampon |
| `exportLog` | Service actif | Produit un CSV hors boucle critique |

### 15.2 Arbitrage

- Les commandes valides sont sérialisées selon leur ordre de réception par le
  service.
- La dernière modification acceptée d'un paramètre gagne.
- `emergencyStop`, `stopService`, `disarm` et les commandes internes de sécurité
  ont priorité sur toute commande ordinaire déjà en attente.
- Une commande ordinaire reçue après le début d'un arrêt ou d'un défaut est
  rejetée.
- Toutes les sources du LAN ont la même autorité en V1 ; aucune identité n'est
  authentifiée.
- Le snapshot expose la source et l'instant de la dernière commande acceptée.

## 16. Snapshot partagé

Le snapshot contient au minimum :

- identifiant et durée de la session ;
- version de l'application ;
- état du service et état d'armement ;
- état, dépendances manquantes et erreur de chaque composant ;
- préréglage courant ;
- configuration complète active ;
- dernier accel et gyro bruts ;
- dernier angle accel et angle filtré ;
- `dt`, cadences et latences ;
- cible, erreur, gains, commandes brute et bornée ;
- commande et télémétrie de chaque moteur ;
- avertissements et défaut verrouillé ;
- état et taille du journal ;
- état du serveur web et adresses locales ;
- compteurs de diagnostic ;
- dernière commande acceptée et sa source.

Un snapshot est cohérent : ses champs représentent une seule version logique de
l'état, même si l'affichage est rafraîchi moins vite que le contrôle.

## 17. Interfaces utilisateur

### 17.1 Organisation fonctionnelle

Les interfaces Android et web comportent les mêmes zones :

1. **Tableau de bord** : service, armement, préréglage, défauts, arrêt ;
2. **IMU** : valeurs brutes, axes, cadence, jitter et calibration ;
3. **Filtre et PD** : angle, cible, alpha, gains, saturation et simulation ;
4. **Moteurs** : USB, IDs, signes, télémétrie, scan et mode manuel ;
5. **Courbes** : angle, gyro, cible, commandes et événements ;
6. **Journal** : démarrer, arrêter, vider et exporter ;
7. **Web et diagnostic** : adresses, connexions et compteurs.

L'arrêt est visible sans navigation supplémentaire dans tout écran permettant
une sortie moteur.

### 17.2 Parité

Une fonction est considérée paritaire lorsque :

- elle produit la même commande logique depuis Android et le web ;
- elle applique les mêmes validations ;
- son résultat et son erreur sont visibles dans les deux interfaces ;
- un changement est reflété dans l'autre interface au prochain snapshot.

Les permissions système qui exigent un dialogue Android peuvent être
déclenchées depuis le web, mais doivent être confirmées physiquement sur le
téléphone.

## 18. Serveur web local

Le serveur web :

- démarre et s'arrête comme composant du service ;
- écoute uniquement sur les interfaces réseau locales configurées ;
- sert les ressources de la page et un canal bidirectionnel de commandes et
  télémétrie ;
- envoie un snapshot complet à chaque connexion ;
- envoie ensuite les changements et séries temps réel à une cadence maximale de
  20 Hz ;
- acquitte chaque commande avec son identifiant ;
- permet le téléchargement du CSV courant ;
- n'inclut ni compte, ni mot de passe, ni chiffrement en V1.

L'application affiche au moins une URL IPv4 utilisable. La disparition du
réseau ferme les clients mais ne modifie pas un mode équilibre autonome déjà
armé. En revanche, la disparition du client maintenant une commande manuelle
provoque immédiatement zéro.

## 19. Courbes temps réel

Les courbes affichent une fenêtre glissante configurable et au minimum :

- angle filtré et angle cible sur la même échelle ;
- vitesse gyro ;
- commande bornée et commandes par moteur ;
- marqueurs d'armement, désarmement, saturation et défaut.

Les points destinés à l'affichage sont décimés ou agrégés hors de la boucle de
contrôle. L'agrégation ne modifie ni le journal ni les statistiques complètes.
Un affichage lent peut perdre des points visuels sans jamais ralentir la chaîne
critique.

## 20. Journal et CSV

Le journal est une suite bornée d'événements typés. `startLog` repart d'un
tampon vide. Lorsque la capacité est atteinte, le plus ancien événement est
remplacé et un compteur de débordement est incrémenté.

Le CSV utilise un en-tête fixe contenant au minimum :

```text
session_ns,wall_time,event,sensor_ts_ns,
ax,ay,az,gx,gy,gz,accel_angle_deg,estimated_angle_deg,gyro_rate_dps,dt_s,
target_deg,error_deg,kp,kd,raw_cmd,bounded_cmd,
motor_id,motor_cmd,measured_speed,load,voltage,temperature,
control_sequence,control_submitted_ns,motor_write_start_ns,motor_write_end_ns,
service_state,arm_state,preset,fault_code,source
```

Pour la boucle vitesse, le journal ajoute le diamètre, le rapport de
transmission, la consigne et sa limite, `Kev`, `Ki`, l'auto-trim, l'alpha, la
cadence demandée, la limite de correction autour du trim, la limite absolue et
la pente maximale, les deux vitesses brutes, les deux conversions en cm/s, la
moyenne, l'EMA, l'erreur, la correction angulaire, la cible PD effective, la
saturation, la limitation de pente, l'âge du retour et les cadences mesurées.

Les colonnes non applicables à un événement restent vides. L'export réalise une
copie cohérente du tampon et ne le bloque pas pendant toute la conversion.

Le fichier moteur `/motor-log.csv` renseigne `motor_cmd` avec la valeur de la
dernière trame effectivement écrite au bus, et non avec la commande manuelle
affichée. Les quatre colonnes de traçabilité associent cette mesure à la
séquence de contrôle et aux timestamps monotoniques de remise au transport
USB. La télémétrie est volontairement découplée de la boucle d'écriture afin
qu'un timeout de lecture ne masque pas la cadence réelle de sortie.

## 21. Persistance et redémarrage

Sont persistés après validation :

- paramètres du tableau de la section 11 ;
- dernier préréglage sélectionné ;
- préférences d'affichage non critiques.

Ne sont jamais persistés comme états actifs :

- service démarré ;
- armement ;
- couple activé ;
- commande calculée ou manuelle ;
- permission USB supposée acquise ;
- contenu du tampon de journal ;
- défaut acquitté.

Au démarrage de l'application, le service est arrêté. Au démarrage d'une
session, l'état est désarmé, les sorties valent zéro et la présence du matériel
est réévaluée.

## 22. Mesures de performance

Le système calcule et expose :

- fréquences accel, gyro, estimation, PD, écritures moteur et télémétrie ;
- distribution du `dt` gyro ;
- latence callback gyro → commande calculée ;
- latence callback gyro → fin de remise au transport USB ;
- nombre de commandes remplacées avant écriture ;
- nombre d'échantillons rejetés par motif ;
- erreurs et timeouts USB ;
- nombre de saturations ;
- événements perdus par le journal ou l'affichage.

Pour qualifier le téléphone cible :

- gyro frais : au moins 100 Hz sur une minute ;
- latence gyro → transport : p95 < 15 ms et p99 < 30 ms ;
- aucune file de commandes en croissance ;
- retour vitesse rapide : cible 50 Hz par moteur ;
- télémétrie complète de santé : cible 10 Hz par moteur ;
- aucun défaut `CONTROL_OVERRUN` dans les conditions nominales.

## 23. Scénarios fonctionnels de référence

### 23.1 Qualification IMU

1. Démarrer le service.
2. Appliquer le préréglage Capteurs.
3. Observer accel, gyro, timestamps, cadences et jitter pendant une minute.
4. Bouger le téléphone puis l'immobiliser.
5. Exporter le journal.

Résultat : aucun échantillon artificiel, gyro ≥ 100 Hz, timestamps croissants et
valeurs cohérentes.

### 23.2 Filtre et simulation PD

1. Appliquer le préréglage Filtre et vérifier l'initialisation accel.
2. Modifier alpha et réinitialiser l'estimateur.
3. Appliquer Simulation PD.
4. Régler une cible et des gains non nuls.
5. Vérifier calculs, saturation et absence de trame moteur.

### 23.3 Qualification moteurs

1. Placer le robot roues levées.
2. Appliquer Moteurs manuels.
3. Autoriser l'USB, identifier le CH340 puis les IDs 6 et 7.
4. Vérifier les signes à faible commande.
5. Armer manuellement et exécuter les paliers prévus.
6. Relâcher la commande entre chaque palier.
7. Exporter le journal.

### 23.4 Boucle complète

1. Appliquer Équilibrage.
2. Vérifier tous les prérequis et l'état `READY`.
3. Commencer avec Kp et Kd nuls puis des limites conservatrices.
4. Armer explicitement.
5. Vérifier la commande synchronisée et les métriques.
6. Désarmer avant toute reconfiguration structurelle.

### 23.5 Injection de défauts

Pendant un essai roues levées, injecter successivement : gyro périmé, perte USB,
silence d'un moteur, angle de chute et surcharge de la chaîne de contrôle. Pour
chaque cas, vérifier zéro si possible, couple off si possible, défaut verrouillé
et absence de réarmement automatique.

## 24. Traçabilité vers le cahier des charges

| Groupe d'exigences CDC | Sections de la présente spécification |
| --- | --- |
| USR | 3, 4, 21 |
| MOD | 5, 6, 7 |
| IMU | 8, 22, 23.1 |
| EST | 9, 23.2 |
| CTL | 10, 11, 23.2 |
| MOT | 12, 22, 23.3 |
| MAN | 13, 23.3 |
| SVC | 4, 21 |
| APP | 16, 17, 19 |
| WEB | 15, 17, 18 |
| LOG | 19, 20 |
| CFG | 11, 21 |
| SAF | 5, 14, 23.5 |
| PERF | 8, 12, 19, 22 |
| DEV | À préciser dans la conception et le plan d'implémentation |

## 25. Points à valider avant conception détaillée

La validation de cette spécification confirme notamment :

1. les deux automates service et armement ;
2. les dépendances et le contenu des cinq préréglages ;
3. les domaines et valeurs initiales des paramètres ;
4. le maintien de l'équilibrage lors de la perte du seul client web ;
5. l'arbitrage « dernière commande valide reçue », avec priorité aux arrêts ;
6. le catalogue de défauts et leur procédure de récupération ;
7. les cadences et seuils de qualification ;
8. le schéma logique du journal CSV.
