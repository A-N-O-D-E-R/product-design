import { api } from './client'
import type { Total } from './types'

export type { Total }

export const consommation = {
  session: (id: number) => api<Total>(`/api/sessions/${id}/consommation`),
  projet: (id: number) => api<Total>(`/api/projets/${id}/consommation`),
}
