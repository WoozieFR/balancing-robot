# FEAT-ATT-001 — Dossier d'entrée au codage

**Statut :** implémentation en cours, validation utilisateur requise  
**Version :** 1.0  
**Date :** 2026-10-07  
**Branche documentaire :** `feature/FEAT-ATT-001-quaternion-filter`

## Documents à lire dans l'ordre

1. [`FEAT-ATT-001-cahier-des-charges.md`](FEAT-ATT-001-cahier-des-charges.md)
   — besoin, périmètre, comportement du sélecteur et critères d'acceptation.
2. [`FEAT-ATT-001-specification.md`](FEAT-ATT-001-specification.md)
   — contrats, conventions de repères et algorithmes des deux estimateurs.
3. [`FEAT-ATT-001-conception-detaillee.md`](FEAT-ATT-001-conception-detaillee.md)
   — intégration future dans la configuration, le runtime, les interfaces et les journaux.
4. [`FEAT-ATT-001-plan-validation.md`](FEAT-ATT-001-plan-validation.md)
   — tests JVM, Android et essais progressifs sur le téléphone puis le robot.

## Décisions fixées pour le codage

- deux modes sélectionnables : filtre complémentaire scalaire historique et
  filtre complémentaire quaternion ;
- un seul estimateur actif et exécuté à la fois ;
- sélecteur disponible dans l'application Android et la page Web ;
- dernier choix persisté ; en l'absence de préférence valide, le filtre
  historique est utilisé ;
- changement de filtre autorisé uniquement dans les états `DISARMED` et
  `READY` ;
- toute commutation autorisée réinitialise l'estimateur sélectionné ;
- mêmes sorties de contrôle : angle `theta` et vitesse de yaw ;
- le filtre quaternion utilise les trois axes gyro et l'accéléromètre, sans
  magnétomètre ni dépendance externe ;
- les contrôleurs, leurs gains, les unités et les politiques de sécurité ne
  sont pas modifiés par cette feature.

## État de ce dossier

Le codage de la première version est engagé sur cette branche. Les documents
restent la référence normative ; toute divergence constatée pendant les tests
doit être reportée avant une publication de l'APK.
