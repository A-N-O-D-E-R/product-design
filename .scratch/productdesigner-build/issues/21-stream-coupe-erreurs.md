# Stream coupé et erreurs Anthropic

Type: task
Status: resolved
Jalon: J1 · Socle et Session grilling de bout en bout
Blocked by: 03
Exigences: E-34, E-35
Décisions: D-30

## Quoi

Configurer les retries du SDK (vérifier les valeurs par défaut). Sur une coupure de stream, conserver le texte partiel, marquer le Message `incomplet` et proposer « Réessayer », qui renvoie le dernier message de l'utilisateur. Le Message partiel est exclu de l'historique envoyé à l'API. Les erreurs 401 et 400 s'affichent avec un message clair dans le fil.

## Critères d'acceptation

- [x] Couper le réseau en cours de stream : le texte partiel reste, avec un bouton « Réessayer ».
- [x] « Réessayer » produit une réponse complète ; l'historique envoyé ne contient pas le partiel.
- [x] Clé invalide : « clé Anthropic invalide » s'affiche dans le fil.

## Comments

- 2026-11-01 : fait.
  - Retries : défaut du SDK conservé (2 retries sur 408/409/429/5xx et erreurs réseau, d'après la doc du skill claude-api) ; `SessionTest.surchargeRetenteeParLeSdk` vérifie qu'un 529 est retenté.
  - Coupure : le texte déjà reçu est persisté en message assistant `incomplet = 1` (V6), affiché « (réponse interrompue) », exclu de l'historique envoyé. `POST /api/sessions/{id}/reessayer` répond à nouveau au dernier message utilisateur (409 si rien à réessayer).
  - 401 : « Clé Anthropic invalide… » ; 400 : « Requête trop longue… » si l'API le dit, sinon le message de l'API.
  - Le bouton Réessayer s'affiche après une erreur, ou au chargement si la Session finit par un message utilisateur ou une réponse incomplète. Logique front non couverte par un test automatisé.
