import { useEffect } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { Proposee } from '@/api/decisions'
import { decisions as api } from '@/api/decisions'
import { queries } from '@/api/query'

/** Décisions proposées et confirmées d'une Session, et Map du Projet (panneau latéral). `version` : un échange vient d'avoir lieu. */
export function useDecisions(sessionId: number, projetId: number, version: number) {
  const client = useQueryClient()
  const panneau = useQuery(queries.panneau(sessionId))
  const carte = useQuery(queries.carte(projetId))

  useEffect(() => {
    if (version === 0) return
    void client.invalidateQueries({ queryKey: queries.panneau(sessionId).queryKey })
    void client.invalidateQueries({ queryKey: queries.carte(projetId).queryKey })
  }, [client, sessionId, projetId, version])

  const confirmation = useMutation({
    mutationFn: ({ p, texte }: { p: Proposee; texte: string }) => api.confirmer(sessionId, p, texte),
    // la Décision confirmée change le panneau et peut résoudre une Question de la Map
    onSettled: () => {
      void client.invalidateQueries({ queryKey: queries.panneau(sessionId).queryKey })
      void client.invalidateQueries({ queryKey: queries.carte(projetId).queryKey })
    },
  })

  return {
    proposees: panneau.data?.proposees ?? [],
    decisions: panneau.data?.decisions ?? [],
    carte: carte.data,
    erreur: confirmation.error?.message,
    confirmer: (p: Proposee, texte: string) => confirmation.mutateAsync({ p, texte }).catch(() => undefined),
  }
}
