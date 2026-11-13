import { api } from './client'

export type Status = { configure: boolean; message?: string; fournisseur?: string; model?: string }

export const status = () => api<Status>('/api/status')
