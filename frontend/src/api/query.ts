import { QueryClient } from '@tanstack/react-query'
import { consommation } from './consommation'
import { decisions } from './decisions'
import { map } from './map'
import { projets } from './projets'
import { sessions } from './sessions'
import { status } from './status'

export const newQueryClient = () =>
  new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } })

/** Les clés et les lectures de l'API, au même endroit : une écriture invalide la clé de ce qu'elle change. */
export const queries = {
  status: () => ({ queryKey: ['status'] as const, queryFn: status }),
  projets: () => ({ queryKey: ['projets'] as const, queryFn: projets.lister }),
  projet: (id: number) => ({ queryKey: ['projets', id] as const, queryFn: () => projets.detail(id) }),
  sessions: (projetId: number) => ({ queryKey: ['projets', projetId, 'sessions'] as const, queryFn: () => sessions.lister(projetId) }),
  carte: (projetId: number) => ({ queryKey: ['projets', projetId, 'carte'] as const, queryFn: () => map.carte(projetId) }),
  panneau: (sessionId: number) => ({ queryKey: ['sessions', sessionId, 'decisions'] as const, queryFn: () => decisions.panneau(sessionId) }),
  consommationSession: (id: number) => ({ queryKey: ['sessions', id, 'consommation'] as const, queryFn: () => consommation.session(id) }),
  consommationProjet: (id: number) => ({ queryKey: ['projets', id, 'consommation'] as const, queryFn: () => consommation.projet(id) }),
}
