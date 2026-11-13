import { useEffect, useState } from 'react'
import { sessions, type Affichage, type Artefact, type Contexte, type Round, type SessionDetail } from '@/api/sessions'

/** État et actions d'une Session : fil de messages, réponse streamée, research en tâche de fond (D-26). */
export function useSession(id: number) {
  const [detail, setDetail] = useState<SessionDetail>()
  const [messages, setMessages] = useState<Affichage[]>([])
  const [enCours, setEnCours] = useState<string>()
  const [erreur, setErreur] = useState<string>()
  const [avertissement, setAvertissement] = useState<string>()
  const [version, setVersion] = useState(0)
  const [reessayable, setReessayable] = useState(false)
  const [contexte, setContexte] = useState<Contexte>()
  const [artefacts, setArtefacts] = useState<Artefact[]>([])

  function recaler(d: SessionDetail) {
    setDetail(d)
    setMessages(d.messages)
    setReessayable(d.reessayable)
  }

  useEffect(() => {
    sessions.detail(id).then(recaler, (e) => setErreur(e.message))
  }, [id])

  useEffect(() => {
    sessions.contexte(id).then(setContexte)
    sessions.artefacts(id).then(setArtefacts)
  }, [id, version])

  // Research en tâche de fond : polling de la progression jusqu'à la fin.
  const tache = detail?.session.tache
  useEffect(() => {
    if (tache !== 'en_cours') return
    const t = setInterval(() => {
      sessions.detail(id).then((d) => {
        setDetail(d)
        if (d.session.tache !== 'en_cours') {
          setMessages(d.messages)
          setVersion((v) => v + 1)
        }
      })
    }, 2000)
    return () => clearInterval(t)
  }, [id, tache])

  /** Lance un échange streamé (message ou réessai) et range sa réponse dans le fil. */
  async function echanger(lancer: (onEvent: (name: string, data: any) => void) => Promise<void>) {
    setErreur(undefined)
    setAvertissement(undefined)
    setReessayable(false)
    setEnCours('')
    let reponse = ''
    let round: Round | null = null
    let incomplet = false
    try {
      await lancer((name, data) => {
        if (name === 'delta') setEnCours((reponse += data.texte))
        if (name === 'record' && data.round?.questions?.length) round = data.round
        if (name === 'avertissement') setAvertissement(data.message)
        if (name === 'erreur') {
          setErreur(data.message)
          if (data.reessayable) {
            setReessayable(true)
            incomplet = !round
          }
        }
      })
    } catch (err) {
      // Refus avant tout stream (Session close, réponse déjà en cours…) : on recale le fil sur le serveur,
      // ce qui retire le message affiché par anticipation.
      setErreur((err as Error).message)
      recaler(await sessions.detail(id))
      setEnCours(undefined)
      setVersion((v) => v + 1)
      return
    }
    if (reponse || round) setMessages((m) => [...m, { id: -Date.now(), role: 'assistant', texte: reponse, round, incomplet }])
    setEnCours(undefined)
    setVersion((v) => v + 1)
  }

  /** Renvoie false si le message n'est pas parti (vide, ou réponse déjà en cours). */
  function envoyer(contenu: string) {
    if (!contenu || enCours !== undefined) return false
    setMessages((m) => [...m, { id: -Date.now(), role: 'user', texte: contenu }])
    void echanger((onEvent) => sessions.envoyer(id, contenu, onEvent))
    return true
  }

  const reessayer = () => echanger((onEvent) => sessions.reessayer(id, onEvent))

  /** Exécute une action serveur ; renvoie son résultat, ou undefined (erreur affichée). */
  async function agir<T>(action: () => Promise<T>) {
    try {
      return await action()
    } catch (e) {
      setErreur((e as Error).message)
    }
  }

  /** Clôture : la Question est résolue s'il y a au moins une Décision confirmée, sinon rouverte (E-13). */
  const clore = () => agir(() => sessions.clore(id).then(() => true))

  /** Contexte presque plein : nouvelle Session amorcée avec la Map et les Décisions, même Question (D-28). */
  const continuer = () => agir(() => sessions.continuer(id))

  /** Research en échec (ex. redémarrage) : relance depuis la consigne de départ (E-16). */
  async function relancer() {
    const s = await agir(() => sessions.relancer(id))
    if (s) {
      setDetail((d) => d && { ...d, session: s, messages: [] })
      setMessages([])
    }
  }

  return {
    detail, messages, enCours, erreur, avertissement, version, reessayable, contexte, artefacts,
    envoyer, reessayer, clore, continuer, relancer,
  }
}
