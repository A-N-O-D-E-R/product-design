# Écran de Session

Type: prototype
Status: resolved
Blocked by: 

## Question

À quoi ressemble l'écran de Session : chat avec rounds de questions/recommandations, panneau latéral Décisions + Questions ouvertes, saisie des réponses par question ? Prototype rough à faire réagir.

## Answer

Prototype validé tel quel : `prototypes/05-ecran-session.html`.

- **Fil à gauche.**
  - Le texte du LLM est streamé.
  - Chaque round s'affiche en carte. Chaque question porte un id, sa reco et une case « Accepter la reco » cochée par défaut, plus un champ de réponse libre.
  - Un seul bouton envoie toutes les réponses au format `Q1: …`.
  - Un champ de message libre, hors round, reste en bas.
- **Panneau à droite**, avec trois blocs :
  - Décisions proposées : chacune se confirme ou se modifie une par une.
  - Décisions : celles qui sont confirmées.
  - Questions ouvertes.
- Pas de « Tout confirmer ». La Destination et le Brouillard ne sont pas affichés dans le panneau. À ajouter si le besoin se fait sentir.
