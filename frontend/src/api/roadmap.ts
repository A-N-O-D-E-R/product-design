import { api, json, postSse, type OnEvent } from './client'
import type { ExportJira, Jalon, Roadmap, Ticket, TicketEdite } from './types'

export type { ExportJira, Jalon, Roadmap, Ticket, TicketEdite }
export type JiraStatus = { configure: boolean; message?: string }

/** Chaque écriture renvoie la Roadmap à jour. */
export const roadmap = {
  lire: (specId: number) => api<Roadmap>(`/api/specs/${specId}/roadmap`),
  /** SSE : `progression` {caracteres}, puis `fin` {avertissements} ou `erreur`. */
  generer: (specId: number, onEvent: OnEvent) => postSse(`/api/specs/${specId}/roadmap`, undefined, onEvent),
  renommerJalon: (id: number, titre: string) => api<Roadmap>(`/api/jalons/${id}`, json('PUT', { titre })),
  ajouterTicket: (jalonId: number, t: TicketEdite) => api<Roadmap>(`/api/jalons/${jalonId}/tickets`, json('POST', t)),
  modifierTicket: (id: number, t: TicketEdite) => api<Roadmap>(`/api/tickets/${id}`, json('PUT', t)),
  supprimerTicket: (id: number) => api<Roadmap>(`/api/tickets/${id}`, json('DELETE')),
  jiraStatus: () => api<JiraStatus>('/api/jira/status'),
  exporterJira: (specId: number, projet: string) => api<ExportJira>(`/api/specs/${specId}/export-jira`, json('POST', { projet })),
}
