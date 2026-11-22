import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate } from '@tanstack/react-router'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { ConsommationLigne } from '@/components/ConsommationLigne'
import { Markdown } from '@/components/Markdown'
import { Panneau } from '@/components/Panneau'
import { PrototypeFrame } from '@/components/PrototypeFrame'
import { RoundCard } from '@/components/RoundCard'
import { useSession } from '@/hooks/useSession'
import type { Affichage } from '@/api/sessions'

export function SessionPage({ id }: { id: number }) {
  const { detail, messages, enCours, erreur, avertissement, version, reessayable, contexte, artefacts, envoyer, reessayer, clore, continuer, relancer } =
    useSession(id)
  const [texte, setTexte] = useState('')
  const bas = useRef<HTMLDivElement>(null)
  const navigate = useNavigate()

  useEffect(() => bas.current?.scrollIntoView({ block: 'end' }), [messages, enCours])

  function soumettre(e: FormEvent) {
    e.preventDefault()
    if (envoyer(texte.trim())) setTexte('')
  }

  async function cloreEtRetour() {
    if (!detail || !confirm('Clore la Session ? Sans Décision confirmée, la Question redevient ouverte.')) return
    if (await clore()) navigate({ to: '/projets/$id', params: { id: String(detail.session.projetId) } })
  }

  async function continuerEtAller() {
    const s = await continuer()
    if (s) navigate({ to: '/sessions/$id', params: { id: String(s.id) } })
  }

  if (!detail) return erreur ? <p role="alert">{erreur}</p> : null
  const active = detail.session.statut === 'active' && detail.session.tache !== 'en_cours'
  const prototypes = artefacts.filter((a) => a.type === 'prototype_html')
  return (
    <div className="-m-4 grid min-h-svh grid-cols-[1fr_320px]">
      <div className="mx-auto grid w-full max-w-3xl content-start gap-4 p-4">
        <Link to="/projets/$id" params={{ id: String(detail.session.projetId) }} className="text-sm text-muted-foreground">
          ← {detail.projet}
        </Link>
        <div>
          <h1 className="text-lg font-semibold">
            {detail.session.question ?? 'Tracé de la Map'} <span className="font-normal text-muted-foreground">· {detail.session.methode}</span>
          </h1>
          <ConsommationLigne sessionId={id} version={version} />
          {active ? (
            <Button size="sm" variant="outline" className="mt-2" onClick={cloreEtRetour} disabled={enCours !== undefined}>
              Clore la Session
            </Button>
          ) : (
            <p className="mt-1 text-sm text-muted-foreground">Session close.</p>
          )}
        </div>
        {active && contexte?.seuilAtteint && (
          <div role="status" className="flex items-center gap-3 rounded-md border border-warning/50 bg-warning/15 p-2 text-sm text-foreground">
            <span className="flex-1">
              Contexte rempli à {Math.round(contexte.remplissage * 100)} %. Continue dans une nouvelle Session, qui reprendra la Map et
              les Décisions sans l'historique.
            </span>
            <Button size="sm" onClick={continuerEtAller} disabled={enCours !== undefined}>Continuer dans une nouvelle Session</Button>
          </div>
        )}
        {detail.session.tache && (
          <p
            role="status"
            className={detail.session.tache === 'echec' ? 'rounded bg-destructive/10 px-2 py-1 text-sm text-destructive' : 'rounded bg-muted px-2 py-1 text-sm'}
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
        {avertissement && <p role="status" className="rounded bg-warning/15 px-2 py-1 text-sm text-foreground">{avertissement}</p>}
        {erreur && <p role="alert" className="text-sm text-destructive">{erreur}</p>}
        {active && reessayable && enCours === undefined && (
          <Button variant="outline" className="justify-self-start" onClick={reessayer}>
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
