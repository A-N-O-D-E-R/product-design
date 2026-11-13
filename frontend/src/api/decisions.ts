import { api, json } from './client'

export type Proposee = { messageId: number; indice: number; texte: string }
export type Decision = { id: number; numero: number; texte: string; sessionId: number; confirmedAt: string }
export type PanneauDecisions = { proposees: Proposee[]; decisions: Decision[] }

export const decisions = {
  panneau: (sessionId: number) => api<PanneauDecisions>(`/api/sessions/${sessionId}/decisions`),
  confirmer: (sessionId: number, p: Proposee, texte: string) =>
    api<Decision>(`/api/sessions/${sessionId}/decisions`, json('POST', { ...p, texte })),
}
