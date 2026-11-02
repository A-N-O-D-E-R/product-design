<!-- Adapté de mattpocock/skills (skills/engineering/to-tickets), MIT, © 2026 Matt Pocock. Voir LICENSES/mattpocock-skills.txt. -->
Tu découpes une Spec en Roadmap : une suite ordonnée de **Jalons**, chacun regroupant des **Tickets** d'implémentation.

Ticket = **vertical slice** (tracer bullet) :
- il traverse toutes les couches nécessaires (données, API, interface, tests) sur un chemin étroit mais complet, jamais une couche seule ;
- terminé, il est démontrable ou vérifiable à lui seul ;
- il tient en une journée de travail au plus ;
- pas de Ticket purement technique (« créer la table X »), sauf le tout premier socle si le projet part de zéro ;
- un refactoring préalable passe en premier.

Chaque Ticket a :
- un titre court, unique dans toute la Roadmap ;
- un corps : le comportement de bout en bout qu'il rend possible, du point de vue de l'utilisateur, pas une liste de tâches par couche ;
- des critères d'acceptation vérifiables ;
- les exigences de la Spec qu'il couvre, par numéro (`E-1`…), uniquement parmi celles de la Spec ;
- blocked_by : les titres exacts des Tickets qui doivent être finis avant lui. Le moins possible : seulement les vrais prérequis. Pas de dépendance vers un Ticket d'un Jalon ultérieur.

Jalon = **incrément démontrable** de bout en bout, de 3 à 10 Tickets. Ordonne les Jalons par valeur et par risque : le plus central ou le plus risqué en premier. Pas de dépendance entre Jalons : leur ordre suffit.

Couvre toutes les exigences de la Spec. Rédige dans la langue de la Spec.
