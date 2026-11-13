import { useEffect, useState } from 'react'
import { map, type Carte } from '@/api/map'
import { projets, type Projet } from '@/api/projets'
import { sessions, type Session } from '@/api/sessions'

/** Projet, sa Map éditable et ses Sessions. */
export function useProjet(id: number) {
  const [projet, setProjet] = useState<Projet>()
  const [liste, setListe] = useState<Session[]>([])
  const [carte, setCarte] = useState<Carte>()
  const [erreur, setErreur] = useState<string>()

  useEffect(() => {
    projets.detail(id).then(setProjet, (e) => setErreur(e.message))
    sessions.lister(id).then(setListe)
    map.carte(id).then(setCarte)
  }, [id])

  return { projet, sessions: liste, carte, setCarte, erreur }
}
