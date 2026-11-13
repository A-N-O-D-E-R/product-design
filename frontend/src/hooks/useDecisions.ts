import { useEffect, useState } from 'react'
import { decisions as api, type Decision, type Proposee } from '@/api/decisions'
import { map, type Carte } from '@/api/map'

/** Décisions proposées et confirmées d'une Session, et Map du Projet (panneau latéral). */
export function useDecisions(sessionId: number, projetId: number, version: number) {
  const [proposees, setProposees] = useState<Proposee[]>([])
  const [decisions, setDecisions] = useState<Decision[]>([])
  const [carte, setCarte] = useState<Carte>()
  const [erreur, setErreur] = useState<string>()

  function charger() {
    api.panneau(sessionId).then((p) => {
      setProposees(p.proposees)
      setDecisions(p.decisions)
    })
    map.carte(projetId).then(setCarte)
  }

  useEffect(charger, [sessionId, projetId, version])

  async function confirmer(p: Proposee, texte: string) {
    setErreur(undefined)
    try {
      await api.confirmer(sessionId, p, texte)
    } catch (e) {
      setErreur((e as Error).message)
    }
    charger()
  }

  return { proposees, decisions, carte, erreur, confirmer }
}
