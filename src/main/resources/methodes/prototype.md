---
nom: prototype
outputs: [round, decision, prototype]
---
<!-- Adapté de mattpocock/skills (skills/engineering/prototype : SKILL.md, LOGIC.md, UI.md), MIT, © 2026 Matt Pocock. Voir LICENSES/mattpocock-skills.txt. -->

Un prototype est un **artefact jetable qui répond à une question**. Ici, il répond à la Question de la Map à résoudre dans cette Session, et l'utilisateur réagit dessus pour trancher.

Choisis la branche selon la question :

- **« Cette logique, ce modèle d'état, tient-il la route ? »** : une démo de logique. En haut, un titre et la question en une phrase. Ensuite, l'état courant complet, affiché lisiblement (champs nommés, pas de JSON brut) et réaffiché après chaque clic, en signalant ce qui vient de changer. Puis des boutons de jeu libre, un par action. Enfin des scénarios guidés, un par onglet : une courte description, puis les boutons à presser dans l'ordre ; lancer un scénario repart d'un état initial connu. Choisis des scénarios qui montrent les cas délicats : le cas nominal, un cas limite, une tentative qui devrait être interdite. La logique tient dans un module pur (réducteur, machine à états ou fonctions pures), sans DOM ; la page l'appelle.
- **« À quoi ça doit ressembler ? »** : 3 variantes d'interface **radicalement différentes** (mise en page, hiérarchie de l'information, action principale ; pas seulement des couleurs), 5 au plus, avec une barre flottante en bas pour passer de l'une à l'autre. Utilise des données réalistes du Projet.

Si la question est ambiguë, choisis la branche la plus probable et annonce l'hypothèse en haut du prototype.

Contraintes techniques, impératives :
- **Un seul document HTML autonome** : CSS et JavaScript inline, aucune ressource externe (pas de CDN, de police web, d'image distante), aucun appel réseau. Il s'affiche dans une iframe isolée qui bloque tout le reste.
- Pas de persistance : l'état vit en mémoire.
- Pas de finition : pas de gestion d'erreur au-delà du nécessaire. Sobre et lisible : typographie propre, espacement généreux, une seule couleur d'accent, pas d'animation.
- Libellés dans la langue du domaine et de l'utilisateur, pas dans celle du code.

Déroulé :
1. Si la question n'est pas assez précise pour construire, pose d'abord un court round.
2. Construis le prototype et transmets le document HTML complet dans record (prototype_html). Ton texte, court, explique quoi regarder ; ne recopie pas le HTML.
3. Recueille les réactions par rounds. Fais évoluer le prototype si l'utilisateur le demande (renvoie alors le document complet).
4. Quand une conclusion émerge, propose-la comme décision via record. La décision compte, pas le prototype.

Réponds dans la langue de l'utilisateur.
