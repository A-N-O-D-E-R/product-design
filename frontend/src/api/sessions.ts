import { api, json, postSse, type OnEvent } from './client'

export type Session = {
  id: number
  projetId: number
  methode: string
  statut: string
  createdAt: string
  questionId: number | null
  question: string | null
  continuedFrom: number | null
  tache: 'en_cours' | 'terminee' | 'echec' | null
  progression: string | null
}
export type Artefact = { id: number; type: 'research_md' | 'prototype_html'; contenu: string; createdAt: string }
export type QuestionRound = { id: string; titre: string; corps: string; recommandation?: string }
export type Round = { questions: QuestionRound[] }
export type Affichage = { id: number; role: 'user' | 'assistant'; texte: string; round?: Round | null; incomplet?: boolean }
export type SessionDetail = { session: Session; projet: string; messages: Affichage[]; reessayable: boolean }
export type Contexte = { tokens: number; fenetre: number; remplissage: number; seuilAtteint: boolean }

export const sessions = {
  lister: (projetId: number) => api<Session[]>(`/api/projets/${projetId}/sessions`),
  /** Ouvre une Question de la frontière dans une Session à la Méthode de son type (E-6). */
  ouvrir: (projetId: number, questionId: number) =>
    api<Session>(`/api/projets/${projetId}/questions/${questionId}/sessions`, json('POST')),
  detail: (id: number) => api<SessionDetail>(`/api/sessions/${id}`),
  artefacts: (id: number) => api<Artefact[]>(`/api/sessions/${id}/artefacts`),
  contexte: (id: number) => api<Contexte>(`/api/sessions/${id}/contexte`),
  clore: (id: number) => api(`/api/sessions/${id}/clore`, json('POST')),
  continuer: (id: number) => api<Session>(`/api/sessions/${id}/continuer`, json('POST')),
  relancer: (id: number) => api<Session>(`/api/sessions/${id}/relancer`, json('POST')),
  /** Réponse streamée en SSE : `delta`, `record`, `avertissement`, puis `fin` ou `erreur`. */
  envoyer: (id: number, texte: string, onEvent: OnEvent) => postSse(`/api/sessions/${id}/messages`, { texte }, onEvent),
  reessayer: (id: number, onEvent: OnEvent) => postSse(`/api/sessions/${id}/reessayer`, undefined, onEvent),
}
