# productDesigner

Logiciel d'interview guidée par LLM qui aide un utilisateur à préciser sa pensée sur un projet, puis produit une spec, une roadmap et des tickets.

## Language

**Projet**:
L'idée que l'utilisateur veut préciser ; conteneur de ses sessions, décisions et livrables.
_Avoid_: Idée, produit, initiative

**Map**:
Index persistant d'un projet : destination, décisions prises, questions ouvertes, brouillard et hors périmètre. Chaque projet en a exactement une.
_Avoid_: Plan, carte, board

**Destination**:
Ce à quoi la map doit aboutir ; fixe le périmètre du projet.
_Avoid_: Objectif, but

**Question**:
Unité de décision dans une map, d'un type donné (grilling, research, prototype, tâche) ; se résout en une décision.
_Avoid_: Ticket, tâche, issue

**Brouillard**:
Zone dans le périmètre de la destination, pressentie mais pas encore assez nette pour devenir une question.
_Avoid_: Backlog, TODO

**Méthode**:
Procédure d'interview appliquée pendant une session selon le type de la question (grilling, wayfinder, research, prototype).
_Avoid_: Skill, mode

**Session**:
Une conversation continue entre l'utilisateur et le LLM, rattachée à un projet ; trace la map ou résout une seule question.
_Avoid_: Interview, chat, conversation

**Interview**:
La méthode de questionnement (rounds, frontière, recommandations), pas un objet stocké.
_Avoid_: Questionnaire

**Décision**:
Une question tranchée par l'utilisateur au cours d'une session.
_Avoid_: Réponse, choix

**Spec**:
Document produit à partir des décisions d'un projet, décrivant ce qu'il faut construire.
_Avoid_: Cahier des charges, PRD

**Roadmap**:
Séquence ordonnée de jalons dérivée de la spec.
_Avoid_: Planning, plan

**Jalon**:
Étape de la roadmap regroupant des tickets ; devient un Epic dans Jira.
_Avoid_: Milestone, phase, epic

**Ticket**:
Unité de travail d'implémentation exportable, issue de la spec et rattachée à un jalon ; devient une Story dans Jira.
_Avoid_: Question, tâche, issue
