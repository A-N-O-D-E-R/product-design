import { api, json } from './client'
import type { Decision, PanneauDecisions, Proposee } from './types'

export type { Decision, PanneauDecisions, Proposee }

export const decisions = {
  panneau: (sessionId: number) => api<PanneauDecisions>(`/api/sessions/${sessionId}/decisions`),
  confirmer: (sessionId: number, p: Proposee, texte: string) =>
    api<Decision>(`/api/sessions/${sessionId}/decisions`, json('POST', { ...p, texte })),
}
