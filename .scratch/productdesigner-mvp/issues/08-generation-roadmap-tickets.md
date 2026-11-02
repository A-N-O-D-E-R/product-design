# Règles de génération roadmap et Tickets

Type: grilling
Status: resolved
Blocked by: 03, 04

## Question

Comment dériver Jalons et Tickets de la Spec : granularité d'un Ticket, règles de découpage (vertical slice ?), dépendances, édition avant export, ré-export après modification ?

## Answer

- **Ticket = vertical slice.**
  - Traverse front, API et DB, avec un comportement démontrable. Taille : un jour au plus.
  - Contient des critères d'acceptation et cite les exigences couvertes (`E-n`).
  - Pas de Ticket purement technique, sauf le socle initial.
- **Jalon = incrément démontrable de bout en bout**, de 3 à 10 Tickets. Ordre : par valeur et par risque, le plus central ou le plus risqué en premier.
- **Dépendances :** `blocked_by` entre Tickets, en nombre minimal.
  - Export Jira : issue link « Blocks ».
  - Pas de dépendance entre Jalons : leur ordre suffit.
- **Génération :** un appel LLM, structured output `roadmap{jalons[{titre, tickets[{titre, corps, critères, exigences[], blocked_by[]}]}]}`, lié à une version de Spec. Les critères sont stockés dans le corps du Ticket (markdown).
- **Édition avant export :** renommer, éditer, ajouter, supprimer, déplacer un Ticket entre Jalons. Pas de régénération partielle : on régénère tout.
- **Ré-export via `jira_key` :**
  - Pas de clé : création.
  - Clé présente : `PUT` du titre et de la description.
  - Ticket supprimé localement : jamais supprimé dans Jira, un avertissement liste les clés concernées.
  - Nouvelle version de Spec : nouvelle Roadmap sans clés. Son export crée de nouveaux Epics et Stories ; les anciens restent intacts dans Jira.
