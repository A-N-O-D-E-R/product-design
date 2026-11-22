# 0002 — Claude in Amazon Bedrock comme second fournisseur

Date : 2026-11-03 · Statut : remplacée par [0004](0004-claude-code-cli.md) · Complète : D-2, D-24

## Contexte

D-2 et D-24 supposaient l'API Claude d'Anthropic avec une clé côté serveur. L'utilisateur dispose d'un compte AWS (SSO) mais d'aucune clé Anthropic ni d'abonnement Claude Platform on AWS.

## Décision

- `productdesigner.anthropic.fournisseur` : `anthropic` (défaut) ou `bedrock`.
- Bedrock passe par `BedrockMantleBackend` (`anthropic-java-bedrock`, signature SigV4), même SDK et même surface Messages. Profil AWS (SSO compris) et région configurables ; modèle par défaut `anthropic.claude-opus-5-5`.
- Bedrock n'a aucun outil serveur : pas de recherche web, donc pas de Méthode research. Ouvrir une Question research y est refusé avec un message explicite, aucun `web_search` n'y est envoyé, et les Sessions sont prévenues de ne pas créer de Question research.

## Conséquences

- Constaté en réel : `eu-west-3` n'a pas l'endpoint `bedrock-mantle` (contrairement à la documentation) ; Opus 5.5 et Sonnet 5.5 absents pour le compte en `eu-west-1`, Opus 5 disponible. Messages d'erreur dédiés pour ces deux cas.
- Le coût estimé reste « inconnu » sur Bedrock sans tarif configuré (prix différents de l'API Anthropic).
- La research n'a été testée que contre un faux serveur.
