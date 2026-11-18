import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { Carte } from '@/api/map'
import { queries } from '@/api/query'

/** Projet, sa Map éditable et ses Sessions. */
export function useProjet(id: number) {
  const client = useQueryClient()
  const projet = useQuery(queries.projet(id))
  const sessions = useQuery(queries.sessions(id))
  const carte = useQuery(queries.carte(id))
  // Chaque écriture de la Map renvoie la Carte à jour : elle remplace celle du cache.
  const setCarte = (c: Carte) => client.setQueryData(queries.carte(id).queryKey, c)
  return { projet: projet.data, sessions: sessions.data ?? [], carte: carte.data, setCarte, erreur: projet.error?.message }
}
