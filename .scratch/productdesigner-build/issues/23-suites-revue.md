# Suites de la revue critique

Type: task
Status: resolved
Jalon: hors Roadmap
Blocked by: 

## Quoi

Points mineurs relevés par la revue du 2026-11-02, non corrigés :

- **« Réessayer » absent après rechargement** (`SessionPage.tsx`) : calculé sur les messages visibles ; si la relance `record` (message masqué) a échoué avant tout texte, le bouton manque alors que le serveur accepterait. Exposer `reessayable` dans `SessionDetail`, calculé sur l'historique.
- **Bulle utilisateur optimiste conservée sur refus** (`SessionPage.tsx`) : un 409 (Session close, réponse en cours) laisse le message affiché. Le retirer, ou recharger le détail, sur erreur HTTP.
- **Export Jira** (`JiraController`, `JiraClient`) : `jira_projet` mémorisé avant toute création réussie (une mauvaise clé fige la cible, champ verrouillé dans l'UI) ; un 5xx ou un timeout pendant une création dont Jira a peut-être tenu compte peut produire des doublons au ré-export (chercher par JQL avant de recréer).

Corrigés dans la même passe : écoute sur 127.0.0.1, filtre Host/Origin (CSRF, DNS rebinding), verrou d'une réponse à la fois par Session, transaction de la régénération de Roadmap, `pause_turn` dans les Sessions interactives, blocages par Question hors périmètre et cycles, « Relancer » qui effaçait des Décisions, research bloquée « en cours », `reessayable` faux après la fin du tour, section de Spec reconnue par préfixe, rapport de research coupé par une pause.

## Comments

- 2026-11-02 : corrigé.
  - `SessionDetail.reessayable` calculé côté serveur sur l'historique complet (messages masqués compris) ; une research en échec reste sur « Relancer ».
  - Envoi refusé (409…) ou stream coupé côté client : le fil est rechargé depuis le serveur, ce qui retire le message affiché par anticipation.
  - Jira : `jira_projet` mémorisé seulement après une création ou une récupération réussie. Création à l'issue inconnue (5xx, timeout, réseau ; pas les 4xx) : élément marqué `jira_incertain` (V16) ; au ré-export, recherche `GET /rest/api/3/search/jql` (projet, type, titre en phrase, puis égalité exacte du titre et de l'Epic parent côté app) avant de recréer. Un lot bulk entier en échec ne fait plus planter l'export. L'endpoint `search/jql` n'est **pas vérifié contre un vrai Jira**.
  - Tests : `SessionTest.reessayableCalculeSurLHistoriqueMasque`, `JiraTest.creationIncertaineRetrouveeAuReexportSansDoublon`, `mauvaiseCleDeProjetNeFigePasLaCible`.
