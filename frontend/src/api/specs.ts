import { api, json, postSse, type OnEvent } from './client'

export type Version = { id: number; version: number; createdAt: string }
export type Spec = Version & { contenu: string; confluenceVersion: number | null; confluenceEspace: string | null }
export type Publication = { espace: string; url: string; versionConfluence: number; creee: boolean }

export const specs = {
  versions: (projetId: number) => api<Version[]>(`/api/projets/${projetId}/specs`),
  detail: (id: number) => api<Spec>(`/api/specs/${id}`),
  /** Nouvelle version streamée en SSE : `delta`, puis `fin` {id, version, avertissements} ou `erreur`. */
  generer: (projetId: number, onEvent: OnEvent) => postSse(`/api/projets/${projetId}/specs`, undefined, onEvent),
  publier: (specId: number, espace: string) => api<Publication>(`/api/specs/${specId}/export-confluence`, json('POST', { espace })),
}
