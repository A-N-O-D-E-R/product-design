import { api, json, postSse, type OnEvent } from './client'
import type { Publication, Spec, Version } from './types'

export type { Publication, Spec, Version }

export const specs = {
  versions: (projetId: number) => api<Version[]>(`/api/projets/${projetId}/specs`),
  detail: (id: number) => api<Spec>(`/api/specs/${id}`),
  /** Nouvelle version streamée en SSE : `delta`, puis `fin` {id, version, avertissements} ou `erreur`. */
  generer: (projetId: number, onEvent: OnEvent) => postSse(`/api/projets/${projetId}/specs`, undefined, onEvent),
  publier: (specId: number, espace: string) => api<Publication>(`/api/specs/${specId}/export-confluence`, json('POST', { espace })),
}
