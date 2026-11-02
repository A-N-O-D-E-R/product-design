import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate } from '@tanstack/react-router'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { ConsommationLigne } from '@/components/ConsommationLigne'
import { Markdown } from '@/components/Markdown'
import { Panneau } from '@/components/Panneau'
import { PrototypeFrame } from '@/components/PrototypeFrame'
import { RoundCard } from '@/components/RoundCard'
import { api, postSse, type Affichage, type Artefact, type Round, type Session } from '@/lib/api'

type Detail = { session: Session; projet: string; messages: Affichage[] }
type Contexte = { tokens: number; fenetre: number; remplissage: number; seuilAtteint: boolean }

export function SessionPage({ id }: { id: number }) {
  const [detail, setDetail] = useState<Detail>()
  const [messages, setMessages] = useState<Affichage[]>([])
  const [enCours, setEnCours] = useState<string>()
  const [texte, setTexte] = useState('')
  const [erreur, setErreur] = useState<string>()
  const [avertissement, setAvertissement] = useState<string>()
  const [version, setVersion] = useState(0)
  const [reessayable, setReessayable] = useState(false)
  const [contexte, setContexte] = useState<Contexte>()
  const [artefacts, setArtefacts] = useState<Artefact[]>([])
  const bas = useRef<HTMLDivElement>(null)
  const navigate = useNavigate()

  useEffect(() => {
    api<Detail>(`/api/sessions/${id}`).then((d) => {
      setDetail(d)
      setMessages(d.messages)
      const dernier = d.messages.at(-1)
      setReessayable(dernier !== undefined && (dernier.role === 'user' || !!dernier.incomplet))
    }, (e) => setErreur(e.message))
  }, [id])

  useEffect(() => {
    api<Contexte>(`/api/sessions/${id}/contexte`).then(setContexte)
    api<Artefact[]>(`/api/sessions/${id}/artefacts`).then(setArtefacts)
  }, [id, version])

  // Research en tâche de fond (D-26) : polling de la progression jusqu'à la fin.
  const tache = detail?.session.tache
  useEffect(() => {
    if (tache !== 'en_cours') return
    const t = setInterval(() => {
      api<Detail>(`/api/sessions/${id}`).then((d) => {
        if (d.session.tache === 'en_cours') {
          setDetail(d)
          return
        }
        setDetail(d)
        setMessages(d.messages)
        setVersion((v) => v + 1)
      })
    }, 2000)
    return () => clearInterval(t)
  }, [id, tache])

  useEffect(() => bas.current?.scrollIntoView({ block: 'end' }), [messages, enCours])

  function soumettre(e: FormEvent) {
    e.preventDefault()
    if (envoyer(texte.trim())) setTexte('')
  }

  function envoyer(contenu: string) {
    if (!contenu || enCours !== undefined) return false
    setMessages((m) => [...m, { id: -Date.now(), role: 'user', texte: contenu }])
    void echanger(`/api/sessions/${id}/messages`, { texte: contenu })
    return true
  }

  async function echanger(path: string, body?: unknown) {
    setErreur(undefined)
    setAvertissement(undefined)
    setReessayable(false)
    setEnCours('')
    let reponse = ''
    let round: Round | null = null
    let incomplet = false
    try {
      await postSse(path, body, (name, data) => {
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
      setErreur((err as Error).message)
    }
    if (reponse || round) setMessages((m) => [...m, { id: -Date.now(), role: 'assistant', texte: reponse, round, incomplet }])
    setEnCours(undefined)
    setVersion((v) => v + 1)
  }

  /** Clôture : la Question est résolue s'il y a au moins une Décision confirmée, sinon rouverte (E-13). */
  async function clore() {
    if (!detail || !confirm('Clore la Session ? Sans Décision confirmée, la Question redevient ouverte.')) return
    try {
      await api(`/api/sessions/${id}/clore`, { method: 'POST' })
      navigate({ to: '/projets/$id', params: { id: String(detail.session.projetId) } })
    } catch (e) {
      setErreur((e as Error).message)
    }
  }

  /** Contexte presque plein : nouvelle Session amorcée avec la Map et les Décisions, même Question (D-28). */
  async function continuer() {
    try {
      const s = await api<Session>(`/api/sessions/${id}/continuer`, { method: 'POST' })
      navigate({ to: '/sessions/$id', params: { id: String(s.id) } })
    } catch (e) {
      setErreur((e as Error).message)
    }
  }

  /** Research en échec (ex. redémarrage) : relance depuis la consigne de départ (E-16). */
  async function relancer() {
    try {
      const s = await api<Session>(`/api/sessions/${id}/relancer`, { method: 'POST' })
      setDetail((d) => d && { ...d, session: s, messages: [] })
      setMessages([])
    } catch (e) {
      setErreur((e as Error).message)
    }
  }

  if (!detail) return erreur ? <p role="alert">{erreur}</p> : null
  const active = detail.session.statut === 'active' && detail.session.tache !== 'en_cours'
  const prototypes = artefacts.filter((a) => a.type === 'prototype_html')
  return (
    <div className="-m-4 grid min-h-[calc(100vh-41px)] grid-cols-[1fr_320px]">
      <div className="mx-auto grid w-full max-w-3xl content-start gap-4 p-4">
        <Link to="/projets/$id" params={{ id: String(detail.session.projetId) }} className="text-sm text-muted-foreground">
          ← {detail.projet}
        </Link>
        <div>
          <h1 className="text-lg font-semibold">
            {detail.session.question ?? 'Tracé de la Map'} <span className="font-normal text-muted-foreground">· {detail.session.methode}</span>
          </h1>
          <ConsommationLigne url={`/api/sessions/${id}/consommation`} version={version} />
          {active ? (
            <Button size="sm" variant="outline" className="mt-2" onClick={clore} disabled={enCours !== undefined}>
              Clore la Session
            </Button>
          ) : (
            <p className="mt-1 text-sm text-muted-foreground">Session close.</p>
          )}
        </div>
        {active && contexte?.seuilAtteint && (
          <div role="status" className="flex items-center gap-3 rounded-md border border-amber-300 bg-amber-50 p-2 text-sm text-amber-950">
            <span className="flex-1">
              Contexte rempli à {Math.round(contexte.remplissage * 100)} %. Continue dans une nouvelle Session, qui reprendra la Map et
              les Décisions sans l'historique.
            </span>
            <Button size="sm" onClick={continuer} disabled={enCours !== undefined}>Continuer dans une nouvelle Session</Button>
          </div>
        )}
        {detail.session.tache && (
          <p
            role="status"
            className={detail.session.tache === 'echec' ? 'rounded bg-red-50 px-2 py-1 text-sm text-red-800' : 'rounded bg-muted px-2 py-1 text-sm'}
          >
            {detail.session.tache === 'en_cours' ? '⏳ ' : ''}
            {detail.session.progression}
            {detail.session.tache === 'echec' && detail.session.statut === 'active' && (
              <Button size="sm" variant="outline" className="ml-3" onClick={relancer}>Relancer</Button>
            )}
          </p>
        )}
        {artefacts.filter((a) => a.type === 'research_md').map((a) => (
          <article key={a.id} className="rounded-lg border p-4">
            <p className="mb-2 text-xs font-semibold tracking-wide text-muted-foreground uppercase">Rapport de recherche</p>
            <Markdown>{a.contenu}</Markdown>
          </article>
        ))}
        {prototypes.map((a, i) => (
          <details key={a.id} open={i === prototypes.length - 1} className="rounded-lg border p-3">
            <summary className="cursor-pointer text-xs font-semibold tracking-wide text-muted-foreground uppercase">
              Prototype v{i + 1}
            </summary>
            <div className="mt-2">
              <PrototypeFrame html={a.contenu} titre={`Prototype v${i + 1}`} />
            </div>
          </details>
        ))}
        <div className="grid gap-3">
          {messages.map((m, i) => (
            <div key={m.id} className="grid gap-3">
              {m.texte && !artefacts.some((a) => a.contenu === m.texte.trim()) && <Bulle {...m} />}
              {m.round && (
                <RoundCard
                  round={m.round}
                  actif={active && i === messages.length - 1 && enCours === undefined}
                  onEnvoyer={envoyer}
                />
              )}
            </div>
          ))}
          {enCours !== undefined && <Bulle id={0} role="assistant" texte={enCours || '…'} />}
          <div ref={bas} />
        </div>
        {avertissement && <p role="status" className="rounded bg-amber-50 px-2 py-1 text-sm text-amber-900">{avertissement}</p>}
        {erreur && <p role="alert" className="text-sm text-red-700">{erreur}</p>}
        {active && reessayable && enCours === undefined && (
          <Button variant="outline" className="justify-self-start" onClick={() => echanger(`/api/sessions/${id}/reessayer`)}>
            Réessayer
          </Button>
        )}
        {active && <form onSubmit={soumettre} className="sticky bottom-0 flex gap-2 bg-background py-2">
          <Textarea
            value={texte}
            onChange={(e) => setTexte(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) soumettre(e)
            }}
            placeholder="Message libre, hors round (Entrée pour envoyer, Maj+Entrée pour une nouvelle ligne)"
          />
          <Button type="submit" disabled={enCours !== undefined}>Envoyer</Button>
        </form>}
      </div>
      <Panneau sessionId={id} projetId={detail.session.projetId} version={version} />
    </div>
  )
}

function Bulle({ role, texte, incomplet }: Affichage) {
  if (role === 'assistant') {
    return (
      <div>
        <Markdown>{texte}</Markdown>
        {incomplet && <span className="text-xs text-muted-foreground">(réponse interrompue)</span>}
      </div>
    )
  }
  return (
    <div className="ml-auto max-w-[80%] rounded-lg bg-muted px-3 py-2 whitespace-pre-wrap">
      {texte}
    </div>
  )
}
