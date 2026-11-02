# Sessions longues dépassant le contexte

Type: grilling
Status: resolved
Blocked by: 

## Question

Que fait l'app quand une Session approche de la limite de contexte (surtout la Session de tracé wayfinder) : résumé, compaction, nouvelle Session ? Impact sur le modèle `Message` ?

## Answer

- **L'état vit en base, jamais dans la conversation.** La Map et les Décisions sont persistées au fil de l'eau par `record`. Quand le contexte sature, on clôt la Session et on en ouvre une nouvelle, amorcée avec la Map et les Décisions. Pas de résumé maison.
- **Seuil.** Calculé à partir du `usage` de chaque réponse. Au-delà de 80 % de la fenêtre de contexte (valeur configurable), un bandeau propose « Continuer dans une nouvelle Session ».
- **Modèle.** `Session.continued_from` (optionnel). `Message` est inchangé.
- **Compaction côté API.** Pas utilisée dans le MVP. À ajouter si les saturations sont fréquentes ; son état actuel n'a pas été vérifié.
