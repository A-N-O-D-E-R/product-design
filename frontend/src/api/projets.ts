import { api, json } from './client'
import type { Projet, ProjetCree } from './types'

export type { Projet, ProjetCree }

export const projets = {
  lister: () => api<Projet[]>('/api/projets'),
  detail: (id: number) => api<Projet>(`/api/projets/${id}`),
  creer: (nom: string, description: string) => api<ProjetCree>('/api/projets', json('POST', { nom, description })),
}
