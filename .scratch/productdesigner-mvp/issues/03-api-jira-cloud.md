# API Jira Cloud pour Epic et Story

Type: research
Status: resolved
Blocked by: 

## Question

Comment créer via l'API REST Jira Cloud un Epic puis des Stories liées (champ parent vs Epic Link selon type de projet team-managed/company-managed), champs obligatoires, découverte des types d'issue, auth email + API token, limites de débit ?

## Answer

**Utiliser le champ `parent` partout** (team-managed comme company-managed). L'« Epic Link » (`customfield_10014`) et le « Parent Link » sont dépréciés depuis 2021, avec un support garanti seulement jusqu'au 30/11/2022. Ne pas les utiliser.

- **Séquence Jalon → Ticket** : 1) `POST /rest/api/3/issue` pour l'Epic, qui renvoie `{id, key}` ; 2) `POST /rest/api/3/issue/bulk` pour les Stories, chacune avec `"parent": {"key": "<EPIC-KEY>"}`. La relation Epic→Story passe par `parent`, quel que soit le type de projet (annonce Atlassian : « For issues in both company-managed and team-managed projects, provide the parent field »).
- **Champs** : `fields.project` (`{key}` ou `{id}`), `fields.summary` (string), `fields.issuetype` (`{id}`). `description` reste optionnel, sauf si la config du projet l'exige. En v3, il doit être en **ADF** : `{"type":"doc","version":1,"content":[{"type":"paragraph","content":[{"type":"text","text":"..."}]}]}`. Le markdown brut est refusé, il faut donc convertir le texte des Tickets en ADF (paragraphes, listes, titres). Les champs texte d'une ligne prennent une string simple. « Epic Name » n'est plus nécessaire : c'est `summary` qui fait office de nom de l'Epic. Les champs obligatoires réels se vérifient par projet via createmeta (`required: true`).
- **Découverte des types (endpoints actuels)** : `GET /rest/api/3/issue/createmeta/{projectIdOrKey}/issuetypes` (paginé), puis `GET /rest/api/3/issue/createmeta/{projectIdOrKey}/issuetypes/{issueTypeId}` pour les champs et leur caractère obligatoire. L'ancien `GET /rest/api/3/issue/createmeta` (sans path params) est **déprécié** (CHANGE-1304). Autre possibilité : `GET /rest/api/3/issuetype/project?projectId=`.
- **Identifier le type Epic de façon robuste** : prendre le type dont `hierarchyLevel == 1`, ne pas se fier au nom « Epic » (traduit ou renommé, ids différents d'un projet à l'autre). Les Stories ont `hierarchyLevel == 0` : choisir le type nommé « Story » s'il existe, sinon le premier de niveau 0 (Task). Le niveau -1 correspond aux sous-tâches. En team-managed, `GET /rest/api/3/project/{projectId}/hierarchy` donne la hiérarchie. Le champ `type` (subtask/standard) des issue types est déprécié au profit de `hierarchyLevel`.
- **Bulk** : `POST /rest/api/3/issue/bulk` avec `{"issueUpdates":[{fields:...}, ...]}`, **50 issues maximum** par requête (découper au-delà). Le succès peut être partiel : réponse 201 si au moins une création réussit, 400 si toutes échouent. Le corps contient `issues[]` (créées) et `errors[]` (`failedElementNumber`, `status`, `elementErrors`). Il faut donc lire `errors` même en cas de 201.
- **Auth** : `Authorization: Basic base64(email:api_token)`, base URL `https://<site>.atlassian.net`. Les tokens **expirent** : 1 an par défaut, de 1 à 365 jours, depuis le 15/12/2024. Prévoir un message clair sur un 401 (« token expiré ou invalide »). Avec un token **scopé**, il faut passer par `https://api.atlassian.com/ex/jira/{cloudId}` : pour le MVP, on documente le token non scopé, plus simple.
- **Rate limits** : le trafic par API token n'est **pas** soumis aux quotas à points (ceux-ci visent les apps Forge, Connect et OAuth). Restent la limite burst (environ 100 req/s en GET/POST, 50 en PUT/DELETE par endpoint) et la limite d'écriture par issue (20 écritures/2 s, 100/30 s). Un export productDesigner reste très en dessous.
- **Gestion du 429** : respecter `Retry-After` (en secondes) comme délai minimal. Sinon, backoff exponentiel avec jitter : base 2 s, doublé à chaque tentative, multiplié par un facteur entre 0,7 et 1,3, 4 tentatives maximum, plafond de 30 s. `RateLimit-Reason` indique la limite touchée (`jira-burst-based`, `jira-per-issue-on-write`…). Ne pas rejouer un bulk entier après un succès partiel, seulement les éléments en erreur.

Sources :
- https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issues/ (create, bulk, createmeta), et la spec OpenAPI https://developer.atlassian.com/cloud/jira/platform/swagger-v3.v3.json (limite de 50, codes 201/400, `CreatedIssues.errors`, dépréciation de createmeta)
- https://community.developer.atlassian.com/t/deprecation-of-the-epic-link-parent-link-and-other-related-fields-in-rest-apis-and-webhooks/54048 (annonce du staff Atlassian, 30/11/2021)
- https://developer.atlassian.com/cloud/jira/platform/changelog/#CHANGE-1304 (dépréciation de createmeta, référencée par la spec)
- https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issue-types/ et `GET /rest/api/3/project/{projectId}/hierarchy` (hierarchyLevel)
- https://developer.atlassian.com/cloud/jira/platform/basic-auth-for-rest-apis/
- https://support.atlassian.com/atlassian-account/docs/manage-api-tokens-for-your-atlassian-account/ (expiration, tokens scopés)
- https://developer.atlassian.com/cloud/jira/platform/rate-limiting/
