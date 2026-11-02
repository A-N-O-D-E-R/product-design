# Licence et réutilisation des skills de Matt Pocock

Type: research
Status: resolved
Blocked by: 

## Question

Sous quelle licence sont publiés les skills mattpocock (grilling, wayfinder, domain-modeling, research, prototype, to-spec, to-tickets) ? Peut-on reprendre leur contenu tel quel ou adapté comme fichiers de Méthode dans productDesigner (usage perso, possible SaaS) ? Obligations d'attribution ?

## Answer

**Licence MIT** (« Copyright (c) 2026 Matt Pocock »), sur tout le dépôt, skills compris. Aucune licence spécifique par skill : ni frontmatter `license:` dans les SKILL.md, ni mention dans les README.

- Réutilisation **verbatim ou adaptée : oui**, usage perso comme SaaS commercial. La MIT permet « use, copy, modify, merge, publish, distribute, sublicense, and/or sell ».
- **Seule obligation** : inclure la notice de copyright et le texte de la licence MIT dans « all copies or substantial portions ». Concrètement, dans productDesigner : un fichier `THIRD_PARTY_NOTICES` / `LICENSES/mattpocock-skills.txt` avec le texte MIT, et un en-tête court (« Adapté de mattpocock/skills, MIT, © 2026 Matt Pocock ») dans chaque fichier de Méthode dérivé.
- Cas SaaS : avec un prompt servi côté serveur, on ne distribue pas de copie, donc l'obligation s'applique peu. Garder la notice reste la pratique sûre, d'autant plus si les prompts sont exposés ou livrés au client.
- Pas de garantie (« AS IS »). La MIT ne cède aucun droit sur le nom ou la marque « Matt Pocock » : ne pas présenter l'app comme approuvée par lui.
- Les skills cités (grilling, wayfinder, domain-modeling, research, prototype, to-spec, to-tickets) sont tous dans ce dépôt, sous cette licence (v1.3.1).

Sources :
- https://github.com/mattpocock/skills/blob/main/LICENSE (l'API GitHub renvoie spdx_id = MIT)
- Copie locale : `~/.claude/plugins/cache/claude-plugins-official/mattpocock-skills/1.3.1/LICENSE`, et `.claude-plugin/plugin.json` + `package.json` (`"license": "MIT"`)
