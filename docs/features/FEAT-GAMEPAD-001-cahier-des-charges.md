# FEAT-GAMEPAD-001 — Cahier des charges du pilotage DualShock 4

**Version :** 1.0  
**Date :** 2026-10-04  
**Statut :** proposition pour validation avant codage  
**Périmètre :** pilotage du robot par une manette PlayStation 4 connectée en Bluetooth au téléphone

## 1. Objet

Ajouter à l'application Android native une source de pilotage par manette
DualShock 4. La manette commande uniquement les consignes de translation et de
rotation déjà consommées par les boucles de contrôle. Elle ne remplace ni
l'équilibrage, ni les sécurités, ni l'armement explicite.

Dans ce document, **DOIT** désigne une exigence obligatoire, **DEVRAIT** une
exigence souhaitée et **PEUT** une capacité facultative.

## 2. Hypothèses et environnement

- La DualShock 4 est jumelée depuis les réglages Bluetooth d'Android.
- Android la présente comme périphérique HID avec les sources `GAMEPAD` et/ou
  `JOYSTICK`.
- L'application ne recherche pas et ne jumelle pas elle-même les périphériques
  Bluetooth ; aucune permission Bluetooth de scan ou de connexion n'est donc
  requise pour cette feature.
- Le téléphone cible exécute Android API 26 ou ultérieure.
- Le pilotage manette n'est actif que lorsque l'activité Android est visible et
  possède le focus. Le service d'équilibrage peut rester actif en arrière-plan,
  mais les consignes opérateur reviennent alors à zéro.

## 3. Périmètre fonctionnel

### 3.1 Inclus

- détection des contrôleurs Android compatibles ;
- sélection d'une DualShock 4 lorsqu'elle est présente ;
- activation explicite du mode manette dans l'IHM Android ;
- commande de vitesse avec le stick gauche vertical ;
- commande de vitesse de lacet avec le stick droit horizontal ;
- deadman par maintien de `R1` ;
- désarmement immédiat par le bouton `Cercle` ;
- retour automatique des deux consignes à zéro en cas de relâchement,
  déconnexion, perte de focus ou timeout ;
- zone morte, courbe de réponse, limites et signes configurables ;
- mode précision par maintien de `L1` ;
- diagnostic temps réel dans l'IHM Android et dans `/diagnostics` ;
- journalisation dans le CSV de contrôle ;
- tests unitaires, instrumentés et procédure de validation sur téléphone.

### 3.2 Hors périmètre

- armement depuis la manette ;
- jumelage Bluetooth depuis l'application ;
- pilotage manette lorsque l'application n'est pas au premier plan ;
- Gamepad API dans la page Web ;
- vibration ou retour haptique ;
- remappage libre de tous les boutons ;
- gestion simultanée de plusieurs opérateurs ou plusieurs manettes ;
- support garanti de modèles autres que la DualShock 4 dans ce lot.

## 4. Mapping opérateur

| Entrée | Fonction | Comportement |
| --- | --- | --- |
| Stick gauche vertical | Vitesse cible | Haut = marche avant positive ; bas = marche arrière |
| Stick droit horizontal | Vitesse yaw cible | Droite = rotation positive ; gauche = rotation négative |
| `R1` maintenu | Deadman | Autorise les consignes non nulles |
| `L1` maintenu | Précision | Multiplie les deux amplitudes par `0,35` |
| `Cercle` | Désarmement | Désarme immédiatement tout mode moteur armé |

Les boutons `PS`, `Share`, `Options`, `Croix`, `Carré` et `Triangle` n'ont pas
de fonction dans ce lot.

## 5. Exigences fonctionnelles

- **PAD-001 —** Le mode manette DOIT être inactif après chaque démarrage de
  l'application et du service.
- **PAD-002 —** L'activation DOIT être explicite depuis l'IHM Android et DOIT
  être refusée si aucune manette compatible n'est détectée.
- **PAD-003 —** La manette ne DOIT jamais armer les moteurs.
- **PAD-004 —** Les commandes physiques ne DOIVENT être transmises que lorsque
  le robot est déjà en `BALANCE_ARMED`.
- **PAD-005 —** Lorsque le mode manette est actif mais `R1` relâché, les
  consignes effectives de vitesse et de yaw DOIVENT valoir zéro.
- **PAD-006 —** Le relâchement de `R1` DOIT demander zéro sans attendre le
  watchdog.
- **PAD-007 —** La perte du périphérique, du focus ou du heartbeat DOIT ramener
  les consignes à zéro au plus tard après 250 ms.
- **PAD-008 —** `Cercle` DOIT demander un désarmement explicite en moins de
  100 ms après réception de l'événement Android.
- **PAD-009 —** L'inhibition des désarmements automatiques ne DOIT jamais
  inhiber le deadman, le timeout manette, la perte de focus ou `Cercle`.
- **PAD-010 —** Les consignes manette DOIVENT être temporaires et ne DOIVENT
  jamais être écrites dans DataStore.
- **PAD-011 —** Une manette active DOIT rester propriétaire des consignes de
  déplacement même deadman relâché. Le système ne DOIT pas reprendre une
  ancienne consigne Android ou Web non nulle.
- **PAD-012 —** La désactivation volontaire du mode manette DOIT laisser les
  consignes effectives à zéro ; toute reprise par Android/Web exige une nouvelle
  action explicite de l'opérateur.
- **PAD-013 —** Les réglages de calibration PEUVENT être persistés, mais l'état
  actif, le deadman, les axes et les consignes courantes ne le sont jamais.
- **PAD-014 —** Le passage au second plan ou l'extinction de l'écran DOIT
  neutraliser les commandes manette. Tant que le mode manette est actif,
  l'application DEVRAIT garder l'écran éveillé.

## 6. Réglages

| Réglage | Défaut | Domaine | Persisté |
| --- | ---: | ---: | :---: |
| Vitesse maximale manette | 5 cm/s | 0 à 20 cm/s | Oui |
| Yaw maximal manette | 90 °/s | 0 à 360 °/s | Oui |
| Zone morte | 0,12 | 0,05 à 0,30 | Oui |
| Exposant de réponse | 1,5 | 1 à 3 | Oui |
| Facteur précision | 0,35 | 0,10 à 1 | Oui |
| Signe vitesse | -1 | -1 ou +1 | Oui |
| Signe yaw | +1 | -1 ou +1 | Oui |
| Timeout heartbeat | 250 ms | fixe dans ce lot | Non |
| Cadence heartbeat | 50 Hz | fixe dans ce lot | Non |

La vitesse manette effective est en plus bornée par
`speedTargetLimitCmPerSec`. La consigne yaw est bornée par le domaine global
`[-360, +360] °/s`.

## 7. IHM et observabilité

L'IHM Android DOIT afficher :

- nom, identifiant, vendor ID et product ID du périphérique sélectionné ;
- état connecté, mode actif, focus et deadman ;
- axes bruts, axes après zone morte et courbe de réponse ;
- mode précision ;
- consignes vitesse/yaw demandées et effectives ;
- âge du dernier heartbeat et cadence observée ;
- source propriétaire actuelle des consignes ;
- dernier motif de neutralisation.

La page Web affiche ces mêmes diagnostics en lecture seule. Les commandes Web
de vitesse et yaw restent visibles, mais l'IHM DOIT indiquer qu'elles sont
supplantées lorsque la manette possède la consigne.

## 8. Critères d'acceptation

1. Une DualShock 4 déjà jumelée est détectée dans les deux secondes suivant
   son arrivée dans la portée Bluetooth.
2. Aucun mouvement n'est demandé tant que `R1` n'est pas maintenu.
3. À `R1` maintenu, les sticks produisent des consignes continues, bornées et
   sans saut en sortie de zone morte.
4. Stick gauche au neutre et stick droit déplacé : la translation reste nulle
   et le robot demande une rotation sur place.
5. Le relâchement de `R1` produit zéro en moins de 100 ms.
6. Une déconnexion, une perte de focus ou un blocage du producteur produit zéro
   en moins de 250 ms.
7. Le bouton `Cercle` désarme sans dépendre du réglage d'inhibition des
   sécurités.
8. Aucun heartbeat ne provoque d'écriture DataStore.
9. Une ancienne consigne Web non nulle ne réapparaît pas après relâchement ou
   déconnexion de la manette.
10. Les données manette sont présentes dans les diagnostics et le CSV.

## 9. Risques restant à valider sur téléphone

- mapping réel de l'axe horizontal droit selon la version Android (`Z` ou
  `RX`) ;
- traduction réelle de `Cercle` et `R1` en keycodes Android ;
- cadence et jitter Bluetooth ;
- comportement lors d'une perte radio et d'une reconnexion ;
- coexistence avec les touches système propres au constructeur du téléphone.

