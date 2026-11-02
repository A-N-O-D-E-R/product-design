# Proposition et versions de la Spec

Type: task
Status: resolved
Jalon: J4 · Spec et Roadmap
Blocked by: 13
Exigences: E-19, E-22
Décisions: D-7

## Quoi

L'app propose la génération quand aucune Question n'est `open` ou `claimed`. Chaque génération crée une nouvelle version ; les versions précédentes restent consultables.

## Critères d'acceptation

- [x] Map sans Question ouverte : la proposition de génération apparaît.
- [x] Deux générations donnent les versions 1 et 2, toutes deux consultables.

## Comments

- 2026-11-02 : fait avec le Ticket 13. Table `spec` (V11), version = max + 1 par Projet ; sélecteur de version sur la page Projet ; bandeau « c'est le moment de générer la Spec » quand toutes les Questions sont `resolved` ou `hors_perimetre`. Testé : 2 générations donnent les versions 1 et 2, la 1 reste lisible.
