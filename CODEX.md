# CODEX.md — Gouvernance du projet Balancing Robot

**Version :** 1.0  
**Date :** 2026-10-01  
**Statut :** actif

Ce document guide Codex lorsqu’il travaille dans ce dépôt. Les instructions système, développeur et utilisateur de la session restent prioritaires.

## 1. Objectif du projet

Construire progressivement un robot auto-équilibré à deux roues, piloté par une application Android native et deux servos STS3215.

La chaîne cible est :

```text
SensorEvent Android → estimation d’angle et vitesse → contrôle → commande moteurs
```

L’application doit d’abord permettre de mesurer correctement l’IMU et d’itérer rapidement sur le téléphone. Le contrôle et les fonctions de sécurité seront déplacés ou répartis entre Android et Python uniquement après décision explicite et validation expérimentale.

## 2. Références externes

Ces éléments sont des données d’entrée et des références ; ils ne doivent pas être modifiés :

- dépôt de référence : `/tmp/spike-imu-pd-servo` ;
- conversation de référence : `/tmp/balancing-robot-reference/conversation-filtre-complementaire.md` ;
- lien source de la conversation : `https://chatgpt.com/share/6abe2b40-4fe0-83eb-82d9-c5872c1a79bf?ogimg=plain`.

Le dépôt de travail est exclusivement :

```text
/home/woozie/robot/balancing-robot
```

## 3. Fonctionnement de Codex

Codex :

- inspecte l’état réel du dépôt avant de modifier quoi que ce soit ;
- explique les choix importants et les risques avant une action externe ;
- modifie les fichiers, exécute les vérifications et rapporte les résultats ;
- ne prétend jamais avoir installé, testé ou déployé quelque chose sans preuve ;
- n’envoie pas de message externe et ne déclenche pas de déploiement distant sans demande explicite ;
- n’utilise pas de sous-agent par défaut. Une délégation n’est faite que si elle est explicitement demandée ou clairement nécessaire et autorisée par la session.

Les validations de l’utilisateur se font dans cette conversation. Aucun envoi Telegram ou autre canal n’est requis.

## 4. Modes de travail

### 4.1 Prototype / exploration (`EXP-`)

Pour les sondes, Hello World, essais de capteur et expérimentations rapides :

- pas de cycle documentaire complet obligatoire ;
- code et notes peuvent évoluer rapidement ;
- chaque résultat doit rester reproductible et être accompagné de la commande ou du test exécuté ;
- une expérimentation qui commande les moteurs doit respecter les règles de sécurité de la section 8 ;
- lorsqu’une exploration devient une fonction durable, elle est convertie en feature gouvernée.

### 4.2 Feature ou bug (`FEAT-` / `BUG-`)

Pour une fonctionnalité destinée à rester dans le produit :

1. formuler le besoin et le périmètre ;
2. définir les critères d’acceptation ;
3. implémenter avec les tests associés ;
4. exécuter build, lint et tests ;
5. effectuer un test sur téléphone si pertinent ;
6. présenter les écarts, risques et résultat à l’utilisateur ;
7. ne merger, taguer ou publier une release que sur demande explicite.

Pour une feature complexe, utiliser `docs/` avec un CDC, une spécification et une conception détaillée versionnés par feature. Une validation explicite de l’utilisateur est requise avant de figer une décision produit importante.

## 5. Git et changements persistants

- Ne jamais réinitialiser ou écraser les changements existants sans accord explicite.
- Ne jamais faire de `git reset --hard`, de suppression massive ou de checkout destructif sans demande explicite.
- Les branches de travail suivent `feature/FEAT-XXX-*`, `bugfix/BUG-XXX-*` ou `exp/EXP-XXX-*`.
- `main` représente la base stable ; `dev` est la branche d’intégration lorsqu’elle existe.
- Codex ne fait pas de merge, de tag, de push ou de déploiement de release sans demande explicite de l’utilisateur.
- Les commits ne sont créés que lorsque l’utilisateur les demande ou lorsqu’il les inclut explicitement dans la tâche.
- Les messages de commit, lorsqu’ils sont demandés, utilisent : `FEAT-XXX:`, `BUG-XXX:`, `DOC:`, `CHORE:`, `REFACTOR:` ou `TEST:`.
- Un conflit de merge non trivial est signalé à l’utilisateur ; il n’est pas résolu à l’aveugle.

## 6. Environnement Android et itération

L’environnement installé est :

- JDK 17 : `/home/woozie/robot/android-toolchain/jdk-17` ;
- Gradle 9.6.0 : `/home/woozie/robot/android-toolchain/gradle-9.6.0` ;
- Android SDK Platform 36 et Build Tools 36.0.0 ;
- `adb` et `cloudflared`.

Avant un build :

```bash
source tools/android-env.sh
```

Workflow standard :

```bash
tools/android-build-debug.sh
tools/android-install-debug.sh                 # si adb est disponible
tools/android-publish-debug.sh                 # sinon, APK par tunnel HTTPS
```

Le tunnel HTTPS est réservé aux APK debug. Il ne doit jamais servir à exposer `adb`, un port de contrôle moteur ou un secret.

La connexion `adb` privilégiée est USB. Une connexion distante passe par un VPN privé ; il est interdit d’exposer directement le port ADB sur Internet.

Le projet Android utilise AGP 9 et le Kotlin intégré : ne pas appliquer `org.jetbrains.kotlin.android` dans les modules Android.

## 7. Exigences de qualité du code

### 7.1 Principes généraux

- Une fonction ou classe a une responsabilité claire.
- Les API publiques ont des types explicites et une documentation suffisante.
- Pas de variables globales mutables pour l’état de contrôle.
- Les dépendances, ports, URLs et paramètres sont injectables ou configurables ; aucun secret n’est codé en dur.
- Les erreurs sont traitées explicitement ; aucune exception silencieusement ignorée.
- La logique métier est séparée de l’interface et du point d’entrée.
- Les logs de diagnostic sont structurés et désactivables en production.
- Les chemins absolus de la machine de développement ne doivent pas se retrouver dans l’application.

### 7.2 Exigences Android / temps réel

- L’IMU native utilise `SensorEventListener` et conserve `event.timestamp`.
- Le callback capteur ne bloque pas et ne fait pas d’I/O lourde.
- La boucle de contrôle ne s’exécute pas sur le thread UI.
- Le système mesure et journalise le `dt` réel et distingue fréquence de callback et fréquence de contrôle.
- Une mesure trop ancienne, une perte de capteur, une déconnexion du bus ou une erreur de protocole impose une commande moteur sûre, normalement zéro.
- Les signes d’axe, l’angle zéro, les signes moteurs et les unités sont documentés et testés.
- Les commandes sont bornées ; tout mode diagnostic moteur est explicitement identifiable.
- Aucun test sur robot posé au sol ne démarre automatiquement après installation d’un APK.

## 8. Sécurité robotique

Avant tout essai avec moteurs alimentés :

1. roues soulevées ou zone dégagée ;
2. arrêt d’urgence accessible ;
3. limite de vitesse et de couple définie ;
4. commande zéro par défaut au démarrage ;
5. arrêt sur perte IMU, perte bus, timeout ou application en arrière-plan ;
6. validation manuelle de chaque nouveau mode moteur.

Un tunnel, un fichier téléchargé ou une commande distante ne peut jamais être considéré comme une autorisation de mise en mouvement.

## 9. Tests et critères de sortie

Chaque modification doit exécuter les vérifications pertinentes :

- compilation debug ;
- tests unitaires de la logique pure ;
- lint ou analyse statique disponible ;
- tests instrumentés Android lorsque le comportement dépend du framework ;
- test sur téléphone pour les capteurs, permissions, lifecycle et performances ;
- test matériel séparé, documenté et explicitement demandé pour les moteurs.

Une feature n’est pas considérée terminée si elle compile mais laisse un risque non documenté sur la chaîne IMU, le timing, le bus ou la sécurité moteur.

## 10. Documentation et traçabilité

Les notes de conception, décisions et résultats d’essais vivent dans `docs/`. Une note utile contient au minimum :

- objectif et périmètre ;
- matériel et versions utilisées ;
- commande exacte ;
- résultat observé ;
- limites ou hypothèses ;
- prochaine décision à prendre.

Les références externes restent séparées du code source. Toute conclusion importante doit distinguer un fait mesuré, une hypothèse et une décision.

## 11. Livraison

Pour une itération debug :

- l’APK est construit localement ;
- son checksum est produit ;
- l’installation se fait par `adb` ou téléchargement HTTPS ;
- la version, le commit et les tests exécutés sont indiqués à l’utilisateur.

Pour une release stable :

- critères de validation explicitement acceptés ;
- tests Android et matériel terminés ;
- tag Git demandé et créé ;
- déploiement ou distribution externe uniquement sur demande explicite.

## 12. Checklist avant une modification importante

- [ ] objectif et périmètre compris ;
- [ ] état du dépôt inspecté ;
- [ ] mode `EXP`, `FEAT` ou `BUG` choisi ;
- [ ] impact IMU, timing, moteurs et sécurité évalué ;
- [ ] tests prévus ;
- [ ] aucun secret ou port sensible exposé ;
- [ ] build et tests exécutés ;
- [ ] résultat et limites rapportés ;
- [ ] merge, tag ou déploiement laissés à l’utilisateur sauf demande explicite.
