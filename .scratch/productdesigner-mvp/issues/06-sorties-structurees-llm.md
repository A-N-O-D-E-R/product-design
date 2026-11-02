# Extraction des sorties structurées du LLM

Type: grilling
Status: resolved
Blocked by: 02

## Question

Comment l'app extrait Décisions, Questions, rounds et recommandations des réponses du LLM : tool calls, JSON structuré, ou parsing markdown ? Comment la Méthode (fichier markdown) déclare ces sorties ?

## Answer

- **Canal : tool `record`, en plus du texte libre.** Le texte de l'interview est streamé en direct vers le front. Le LLM appelle `record(decisions[], questions[], round{questions[], recommandations[]})`, dont le résultat arrive typé et validé par son schéma.
  - Rejeté, réponse entièrement en JSON : le JSON partiel n'est pas affichable pendant le streaming, et le ton de l'interview devient rigide.
  - Rejeté, parsing du markdown : trop fragile.
- **Déclaration par la Méthode : schéma unique, fixé dans le code et commun à toutes les Méthodes.**
  - Le frontmatter du fichier de Méthode liste les champs utilisés (ex. `outputs: [round, decision]`).
  - Le prompt de la Méthode explique quand appeler `record`.
  - Rejeté, un schéma par Méthode : chaque schéma demanderait son propre code de persistance (YAGNI).
- **Réponse à un round : par question.**
  - Chaque question du round porte un id. L'utilisateur répond champ par champ dans l'UI.
  - Les réponses sont renvoyées au LLM dans le message suivant, sous la forme `Q3: …`.
  - Une Décision n'est persistée qu'après confirmation de l'utilisateur : le LLM propose, l'app enregistre.
- **Si le LLM n'appelle pas `record` :** une relance automatique (« appelle record »). Si rien ne vient, afficher le texte seul avec un avertissement : le panneau Décisions / Questions ouvertes n'est alors pas mis à jour.
