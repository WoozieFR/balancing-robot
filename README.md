# Balancing Robot

Application Android native du robot auto-équilibré. Les Lots 0 à 3 fournissent
une base exécutable sans commande moteur physique ; le Lot 4 ajoute une sortie
manuelle explicitement armable : écran Compose, service au
premier plan, serveur local (`/health` et WebSocket), page web de smoke test,
diagnostic USB série CH340, acquisition IMU native et chaîne de contrôle pure
rejouable en JVM.

Le Lot 1 ajoute les modèles et bornes de configuration, l'angle accéléromètre,
le filtre complémentaire, le PD saturé, les signes moteurs, les règles de
fraîcheur/chute et un runtime simulé. Les sorties moteur de ce runtime restent
des entiers en mémoire : aucun accès USB ni servo n'est activé.

Le Lot 2 affiche les valeurs accéléromètre/gyroscope, angles, `dt`, cadence,
jitter, compteurs de rejets et taille du journal IMU. Le Lot 3 ajoute une
fréquence IMU demandée réglable et mémorisée (20 à 200 Hz), navigation et courbe
Compose, export CSV, protocole de diagnostic et page web miroir via
`/diagnostics`. Les commandes restent explicitement simulées.

Le Lot 4 ajoute le codec Feetech, le transport USB CH340, la demande de
permission, le scan/qualification du groupe, la télémétrie, l'ordonnanceur I/O,
la configuration, l'armement manuel, le deadman, les paliers et le journal
moteur. Le choix de mode STS3215 vitesse/PWM est disponible dans l'onglet
**Moteurs** et sur la page web. Le scan n'envoie que des `PING` ; le couple et
les commandes ne sont activés qu'après configuration, confirmation roues
levées et armement explicite.

Le Lot 5 raccorde l'estimation et le PD à l'ordonnanceur moteur sous un état
`BALANCE_ARMED` séparé. Il ajoute le watchdog de fraîcheur IMU, la détection de
chute, l'arrêt zéro/couple-off, la mesure de latence et la persistance de tous
les réglages. L'onglet **Réglages** expose un curseur pour chaque paramètre de
la boucle (axe/signe, offset, alpha du filtre complémentaire, cible, Kp/Kd,
limites et temporisations). Alpha, cible, Kp et Kd sont appliqués en direct
pendant l'équilibrage ; les paramètres structurels et de sécurité restent
verrouillés jusqu'au désarmement. L'armement reste toujours explicite et
désarmé par défaut.

Le bouton **Démarrer capture** ouvre une session de contrôle en RAM. Chaque
échantillon peut contenir les entrées capteurs, tous les paramètres actifs,
l'angle accéléromètre et filtré, le PD (erreur, brut, borné, saturation), la
latence et les sorties moteur. **Arrêter capture** fige la session ; le CSV est
alors partageable depuis Android ou via `/control-log.csv` sur la page web.

Le Lot 6 ajoute une boucle externe de vitesse, active par défaut mais avec un
gain `Kev` et un intégrateur `Ki` nuls. Elle estime la vitesse du robot à partir
de la moyenne signée des deux `PresentVelocity`, convertie en cm/s pour des
roues de 40 mm et un rapport moteur/roue de 1. La correction est calculée autour
du trim : `clamp(Kev × erreur + autoTrim, ±limite_correction)`, puis une limite
absolue et une pente maximale sont appliquées à la cible PD. L'auto-trim
intègre `Ki × erreur × dt` avec anti-windup ; un retour vitesse périmé efface
la correction apprise et ramène progressivement la cible vers le trim.
Consigne, gains, filtre, fréquence (50 Hz par défaut), limites, pente et timeout
sont réglables en direct depuis Android et le Web. Le diamètre et le rapport de
transmission restent modifiables uniquement désarmé.

La boucle de rotation complète la commande d'équilibrage sans la remplacer.
Une consigne yaw nulle désactive explicitement cette boucle ; sinon le gyro Z
fournit la vitesse de lacet et le correcteur proportionnel calcule `u_turn`.
La sortie différentielle est ensuite `uL = u_balance + u_turn` et
`uR = u_balance - u_turn`, avec saturation indépendante de chaque roue puis
application des signes moteurs. La consigne yaw et son gain sont modifiables
en direct sur Android et le Web, et la commande, l'erreur et la vitesse Z sont
présentes dans le diagnostic et le CSV.

## Build et tests

Depuis la racine du projet :

```bash
./tools/android-build-debug.sh
```

Le script exécute les tests JVM, compile l'APK debug et le copie dans
`dist/balancing-robot-debug.apk`, avec son SHA-256 dans
`dist/balancing-robot-debug.build-info`.

Pour installer sur un téléphone avec ADB déjà connecté :

```bash
./tools/android-install-debug.sh
```

Pour publier l'APK via un tunnel Cloudflare temporaire :

```bash
./tools/android-publish-debug.sh
```

Le téléphone doit lancer le service depuis l'application ; aucun démarrage
automatique ni réarmement après reconnexion USB n'est autorisé. Les essais
moteurs et d'équilibrage doivent être réalisés roues levées avec coupure
d'urgence accessible. La tenue stable du robot reste une caractérisation
matérielle distincte de ce jalon logiciel.

Le champ de port est prérempli à `8766` et mémorise le dernier choix. Le port
doit être compris entre `1024` et `65535` ; s'il est déjà occupé, l'application
affiche l'erreur afin de permettre de choisir explicitement une autre valeur.

Les choix d'architecture et les contrats sont documentés dans
`docs/conception-detaillee-v1.md`.
