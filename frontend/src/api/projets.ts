import { api, json } from './client'

export type Projet = { id: number; nom: string; description: string; createdAt: string }
/** sessionTraceId : la 1re Session trace la Map (D-6). */
export type ProjetCree = Projet & { sessionTraceId: number }

export const projets = {
  lister: () => api<Projet[]>('/api/projets'),
  detail: (id: number) => api<Projet>(`/api/projets/${id}`),
  creer: (nom: string, description: string) => api<ProjetCree>('/api/projets', json('POST', { nom, description })),
}
