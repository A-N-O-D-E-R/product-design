# Test réel de bout en bout

Type: task
Status: resolved
Jalon: hors Roadmap
Blocked by: 

## Quoi

Faire tourner le parcours complet contre les vrais services, puis corriger ce qui casse.

## Environnement

- Claude in Amazon Bedrock, compte AWS `df-prod` (SSO), région `eu-west-1`, modèle `anthropic.claude-opus-5`.
- Atlassian Cloud `audioptictradeservices`, token scopé via la passerelle (`cloud-id`), projet Jira `PDTEST` (Kanban, team-managed), espace Confluence personnel.
- Bases de données temporaires ; un proxy local enregistrait les flux bruts de Bedrock pour le diagnostic.

## Résultats

| Étape | Résultat |
|---|---|
| Tracé de la Map (wayfinder) | 4 tours ; Destination, 12 Questions typées et dépendances cohérentes, 5 Brouillard, 12 Hors périmètre |
| Question grilling | 3 rounds, 9 Décisions confirmées, Question `resolved` |
| Spec | 38 exigences, aucun avertissement de traçabilité ; une 2e version générée |
| Roadmap | 4 à 5 Jalons, 22 à 28 Tickets, dépendances résolues |
| Export Jira | 4 Epics, 22 Stories sous le bon Epic, descriptions ADF formatées, 17 liens « Blocks » |
| Ré-export | 25 mises à jour, aucun doublon ; Ticket supprimé localement laissé dans Jira et listé |
| Confluence | page créée, puis version 2 publiée avec son message |
| Research | **non testée** : pas de recherche web sur Bedrock, ni clé Anthropic ni Claude Platform on AWS disponibles |

## Défauts trouvés et corrigés

- `eu-west-3` sans endpoint `bedrock-mantle` ; Opus 5.5 / Sonnet 5.5 indisponibles pour le compte : messages d'erreur dédiés ([ADR 0002](../../../docs/adr/0002-fournisseur-bedrock.md)).
- Accents corrompus par le décodage strict du tool `record` : `record` non strict, validation serveur et relance ([ADR 0001](../../../docs/adr/0001-record-non-strict.md)).
- Spec et Roadmap absentes de la consommation du Projet : table `appel`.
- Titres de Jalon numérotés deux fois ; prérequis mal orthographié par le modèle perdu : numéro retiré, rapprochement à 3 éditions près.
- Roadmap vide qui écrasait l'existante : rejetée, sortie brute journalisée.
- Liens « Blocks » inversés : `inwardIssue` = celui qui bloque ([ADR 0003](../../../docs/adr/0003-confluence-et-token-scope.md)) ; les 17 liens inversés créés dans PDTEST ont été supprimés puis recréés.
- Clé d'espace Confluence personnel mise en majuscules.
- Tests qui lisaient `~/.productdesigner` du développeur : `user.home` isolé dans `target/test-home`.

## Reste à nettoyer côté Atlassian

L'app ne supprime rien : PDTEST (4 Epics, 22 Stories, 17 liens) et la page « Spec — Carnet de recettes » de l'espace personnel sont à supprimer à la main.
