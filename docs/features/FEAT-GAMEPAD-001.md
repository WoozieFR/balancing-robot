# FEAT-GAMEPAD-001 — Dossier d'entrée au codage

**Statut :** prêt pour revue utilisateur  
**Date :** 2026-10-04

## Documents à lire dans l'ordre

1. [`FEAT-GAMEPAD-001-cahier-des-charges.md`](FEAT-GAMEPAD-001-cahier-des-charges.md)
   — besoin, périmètre, mapping et critères d'acceptation.
2. [`FEAT-GAMEPAD-001-specification.md`](FEAT-GAMEPAD-001-specification.md)
   — modèles, règles mathématiques, machine d'état et contrats.
3. [`FEAT-GAMEPAD-001-conception-detaillee.md`](FEAT-GAMEPAD-001-conception-detaillee.md)
   — découpage du code, threads, lifecycle et ordre d'implémentation.
4. [`FEAT-GAMEPAD-001-plan-validation.md`](FEAT-GAMEPAD-001-plan-validation.md)
   — tests JVM/Android et essais sur téléphone puis roues levées.

## Décisions fixées pour le codage

- stick gauche vertical pour la vitesse, stick droit horizontal pour le yaw ;
- `R1` deadman, `L1` précision, `Cercle` désarmement ;
- armement impossible depuis la manette ;
- commandes éphémères séparées des réglages persistants ;
- heartbeat 50 Hz et timeout service 250 ms ;
- neutralisation sur relâchement, timeout, déconnexion et perte de focus ;
- priorité manette latchée : aucune ancienne consigne Web/Android ne revient
  sans nouvelle action explicite ;
- diagnostic Android complet, diagnostic Web en lecture seule et export CSV ;
- aucune nouvelle dépendance et aucune permission Bluetooth applicative.

## Validation attendue avant codage

La revue doit confirmer le mapping des commandes, les amplitudes par défaut,
le deadman `R1`, le bouton de désarmement `Cercle` et la règle de priorité des
consignes. Le mapping Android exact des axes/keycodes reste volontairement un
point de qualification sur le téléphone réel.

