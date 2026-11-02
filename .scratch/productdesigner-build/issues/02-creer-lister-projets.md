# Créer et lister les Projets

Type: task
Status: resolved
Jalon: J1 · Socle et Session grilling de bout en bout
Blocked by: 01
Exigences: E-1
Décisions: D-6, D-19

## Quoi

L'utilisateur crée un Projet (nom + description de son idée) et voit la liste de ses Projets. La création d'un Projet crée aussi sa Map vide.

## Critères d'acceptation

- [x] Un Projet créé apparaît dans la liste après rechargement.
- [x] Chaque Projet a exactement une Map, vide à la création.

## Comments

- 2026-11-01 : fait. Tables `projet` et `map` (clé primaire = `projet_id`, une Map par Projet). API `GET/POST /api/projets` ; nom vide renvoie 400. Page d'accueil : formulaire + liste. `ProjetTest` couvre les critères ; persistance vérifiée en redémarrant le jar. La migration `V1__init.sql` du socle a été remplacée par `V1__projet_map.sql` (aucune base réelle n'existait).
