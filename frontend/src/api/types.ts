import type { components } from './schema'

/**
 * Les types de l'API viennent du schéma OpenAPI généré (`pnpm api:types`, `schema.d.ts`). springdoc n'y met ni nullabilité ni énumérations :
 * ce fichier les ajoute par-dessus, sans redéfinir les champs.
 */
type S = components['schemas']

/** Rend nullables les champs `K` de `T`. */
export type Nullable<T, K extends keyof T> = Omit<T, K> & { [P in K]: T[P] | null }

export type Projet = Nullable<S['Projet'], 'sourceIdeaUrl'>
/** sessionTraceId : la 1re Session trace la Map (D-6). */
export type ProjetCree = S['Cree']

export type TypeQuestion = 'grilling' | 'research' | 'prototype' | 'tache'
export type StatutQuestion = 'open' | 'claimed' | 'resolved' | 'hors_perimetre'
export type QuestionMap = Omit<S['Question'], 'type' | 'statut'> & { type: TypeQuestion; statut: StatutQuestion }
export type Ligne = S['Ligne']
export type Carte = Omit<S['Carte'], 'questions'> & { questions: QuestionMap[] }
export type QuestionEditee = S['QuestionEditee']

export type Session = Omit<Nullable<S['Session'], 'questionId' | 'question' | 'continuedFrom' | 'progression'>, 'tache'> & {
  tache: 'en_cours' | 'terminee' | 'echec' | null
}
export type Artefact = Omit<S['Artefact'], 'type'> & { type: 'research_md' | 'prototype_html' }
export type Contexte = S['Contexte']
export type QuestionRound = { id: string; titre: string; corps: string; recommandation?: string }
export type Round = { questions: QuestionRound[] }
/** `round` est du JSON libre côté serveur. */
/** Les messages construits côté client (en cours d'envoi) n'ont ni `round` ni `incomplet`. */
export type Affichage = Omit<S['Affichage'], 'role' | 'round' | 'incomplet'> & {
  role: 'user' | 'assistant'
  round?: Round | null
  incomplet?: boolean
}
export type SessionDetail = Omit<S['SessionDetail'], 'session' | 'messages'> & { session: Session; messages: Affichage[] }

export type Version = S['Version']
export type Spec = Nullable<S['Spec'], 'confluenceVersion' | 'confluenceEspace'>
export type Publication = S['Publication']

export type Ticket = Nullable<S['Ticket'], 'jiraKey'>
export type Jalon = Omit<Nullable<S['Jalon'], 'jiraKey'>, 'tickets'> & { tickets: Ticket[] }
export type Roadmap = Omit<Nullable<S['Roadmap'], 'jiraProjet'>, 'jalons'> & { jalons: Jalon[] }
export type TicketEdite = S['TicketEdite']
export type ExportJira = S['Resultat']

export type Proposee = S['Proposee']
export type Decision = S['Decision']
export type PanneauDecisions = S['Panneau']
export type Total = Nullable<S['Total'], 'cout'>
