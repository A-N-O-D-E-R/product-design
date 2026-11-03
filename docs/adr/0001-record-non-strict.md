# 0001 — Tool `record` non strict, validé côté serveur

Date : 2026-11-03 · Statut : acceptée · Révise : D-16 (« schéma unique strict »)

## Contexte

D-16 prévoyait un tool `record` au schéma strict (décodage contraint). Au premier test réel (Amazon Bedrock, Opus 5, eu-west-1), un proxy d'enregistrement du flux brut a montré qu'en mode strict le modèle échappe les caractères accentués en `\u00XX` et glisse parfois des `\n` à leur place (« t\n\ntél\n\néphone »). Le texte corrompu gagnait les rounds, les Décisions et la Map, et le modèle l'imitait aux tours suivants. Sur le même tour rejoué sans strict, les accents arrivent en UTF-8, sans échappement.

## Décision

- `record` n'est plus strict ; le schéma reste unique et décrit dans la description du tool.
- Le serveur valide l'input (`Record.erreurs`) : dans les éléments présents, champs obligatoires absents ou vides. Une liste ou un objet de haut niveau absent vaut « rien à enregistrer ».
- Input non conforme : une relance masquée, avec le `tool_result` attendu et la liste des erreurs ; si la relance n'apporte pas de `record`, l'input initial est utilisé tel quel (lecture tolérante).
- Le front tolère une question de round sans recommandation.

## Conséquences

- Plus de corruption observée sur les parcours réels suivants (Map, 23 Décisions, Spec, Roadmap).
- Un `record` incomplet coûte un appel de plus (observé une fois : recommandation intégrée au corps).
- Les structured outputs de la Roadmap restent en décodage contraint ; les accents y sont intacts, mais une sortie vide a été observée une fois : elle est rejetée sans écraser la Roadmap existante.
