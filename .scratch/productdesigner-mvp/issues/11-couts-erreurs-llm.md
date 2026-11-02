# Suivi des coûts LLM et gestion des erreurs

Type: grilling
Status: resolved
Blocked by: 

## Question

Affiche-t-on tokens et coût par Session ? Comment gérer les erreurs Anthropic (429, 529, 5xx, coupure de stream) côté backend et UI ?

## Answer

- **Coûts.** On persiste le `usage` sur chaque `Message` (tokens d'entrée, de sortie et lus en cache). On affiche les tokens et le coût estimé par Session et par Projet, calculés à partir d'une table de tarifs en config. Pas de plafond de budget.
- **Retry.** On s'appuie sur les retries intégrés au SDK pour les 429, 529 et 5xx (valeurs par défaut à vérifier à l'implémentation). Pas de code maison.
- **Coupure de stream.** On conserve le texte partiel et on marque le Message `incomplet`. Un bouton « Réessayer » renvoie le dernier message de l'utilisateur. Le Message partiel est exclu de l'historique envoyé à l'API.
- **Erreurs non récupérables.** Message clair dans le fil : 401 « clé Anthropic invalide », 400 « requête trop longue » (avec renvoi vers la reprise de la Question 10).
