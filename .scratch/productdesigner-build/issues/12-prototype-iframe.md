# Prototype HTML en iframe sandbox

Type: task
Status: resolved
Jalon: J3 · Research et prototype
Blocked by: 08
Exigences: E-18
Décisions: D-8

## Quoi

Une Question prototype produit un Artefact `prototype_html`, rendu dans une iframe `sandbox` (scripts autorisés, sans `allow-same-origin`). L'utilisateur réagit au prototype dans la Session.

## Critères d'acceptation

- [x] Le HTML généré s'affiche dans l'iframe.
- [x] Un script du prototype ne peut accéder ni au DOM parent, ni aux cookies, ni à l'API de l'app.

## Comments

- 2026-11-02 : fait.
  - Méthode `methodes/prototype.md` (branches logique / variantes d'UI du skill, document HTML autonome sans ressource externe). Schéma `record` : champ `prototype_html` (chaîne vide hors prototype). Chaque prototype reçu devient un Artefact `prototype_html` ; l'UI affiche toutes les versions (dernière dépliée).
  - Isolation, trois couches : iframe `sandbox="allow-scripts"` (sans `allow-same-origin`, sans `allow-forms`) ; CSP `<meta>` placée avant le contenu (`default-src 'none'`, scripts/styles inline seulement, `form-action 'none'`) ; filtre serveur refusant toute requête `/api/` avec `Origin: null` (403).
  - Vérifié dans Chromium headless avec un prototype malveillant : DOM parent, cookies, localStorage, fetch, image externe et formulaire bloqués ; aucun Projet créé. Tests : `SessionTest.prototypeEnregistreUnArtefactHtml`, `requeteDepuisUneIframeSandboxRefusee`.
