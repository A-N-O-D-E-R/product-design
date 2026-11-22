import { api, json } from './client'
import type { Carte, Ligne, QuestionEditee, QuestionMap } from './types'

export type { Carte, Ligne, QuestionEditee, QuestionMap }
export type Liste = 'brouillard' | 'hors-perimetre'

const base = (projetId: number) => `/api/projets/${projetId}`

/** Chaque écriture renvoie la Carte à jour. */
export const map = {
  carte: (projetId: number) => api<Carte>(`${base(projetId)}/map`),
  modifierEnTete: (projetId: number, destination: string, notes: string) =>
    api<Carte>(`${base(projetId)}/map`, json('PUT', { destination, notes })),
  creerQuestion: (projetId: number, q: QuestionEditee) => api<Carte>(`${base(projetId)}/questions`, json('POST', q)),
  modifierQuestion: (projetId: number, id: number, q: QuestionEditee) =>
    api<Carte>(`${base(projetId)}/questions/${id}`, json('PUT', q)),
  horsPerimetre: (projetId: number, id: number) => api<Carte>(`${base(projetId)}/questions/${id}/hors-perimetre`, json('POST')),
  ajouterLigne: (projetId: number, liste: Liste, texte: string) => api<Carte>(`${base(projetId)}/${liste}`, json('POST', { texte })),
  modifierLigne: (projetId: number, liste: Liste, id: number, texte: string) =>
    api<Carte>(`${base(projetId)}/${liste}/${id}`, json('PUT', { texte })),
  supprimerLigne: (projetId: number, liste: Liste, id: number) => api<Carte>(`${base(projetId)}/${liste}/${id}`, json('DELETE')),
  promouvoir: (projetId: number, id: number, type = 'grilling') =>
    api<Carte>(`${base(projetId)}/brouillard/${id}/promouvoir`, json('POST', { type })),
}
