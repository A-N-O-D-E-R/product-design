import { api } from './client'

export type Total = { inputTokens: number; outputTokens: number; cacheReadTokens: number; cacheWriteTokens: number; cout: number | null }

export const consommation = {
  session: (id: number) => api<Total>(`/api/sessions/${id}/consommation`),
  projet: (id: number) => api<Total>(`/api/projets/${id}/consommation`),
}
