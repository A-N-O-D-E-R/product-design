# Gabarit de la spec

Type: grilling
Status: resolved
Blocked by: 

## Question

Quelles sections et quel niveau de détail doit contenir la Spec générée (problème, utilisateurs, parcours, périmètre/hors périmètre, modèle de domaine, architecture, risques…) ? Gabarit fixe ou adapté au Projet ?

## Answer

- **Socle fixe + sections optionnelles.** Les sections du socle sont toujours présentes : si rien n'est décidé, la section affiche « non décidé ». Le LLM n'ajoute une section optionnelle que si des Décisions la couvrent. Un gabarit stable rend la dérivation Roadmap/Tickets (issue 08) prévisible.
- **Socle, dans l'ordre :**
  1. Problème et Destination
  2. Utilisateurs et parcours clés
  3. Périmètre / Hors périmètre (repris de la Map)
  4. Modèle de domaine (glossaire du Projet)
  5. Exigences fonctionnelles (numérotées, une par comportement testable)
  6. Décisions structurantes
  7. Questions ouvertes / Brouillard restant
- **Sections optionnelles :** architecture, contraintes non fonctionnelles, risques, données/migration, UI.
- **Niveau de détail : le quoi, pas le comment.** Les exigences décrivent un comportement observable, sans noms de classes ni schéma SQL. La section architecture n'apparaît que si une Décision l'a tranchée. Le détail d'implémentation va dans les Tickets.
- **Traçabilité :** chaque exigence cite la ou les Décisions dont elle découle (`[D-12]`), ce qui permet de régénérer la Spec quand une Décision change.
