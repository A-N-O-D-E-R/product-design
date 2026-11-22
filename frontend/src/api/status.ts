import { api, http, json } from './client'

/** Token du CLI Claude Code (fournisseur claude-code) : type et 10 premiers caractères, jamais la valeur. */
export type Token = { configure: boolean; type?: 'oauth_token' | 'api_key' | null; indice?: string | null }

export type Status = { configure: boolean; message?: string; fournisseur?: string; model?: string; token?: Token }

export const status = () => api<Status>('/api/status')

export const token = {
  enregistrer: (valeur: string) => api<Token>('/api/config/token', json('PUT', { token: valeur })),
  effacer: () => http.delete('/api/config/token'),
}
