import { useState } from 'react'
import { Link, useNavigate } from '@tanstack/react-router'
import { ConsommationLigne } from '@/components/ConsommationLigne'
import { MapVue } from '@/components/MapVue'
import { SpecSection } from '@/components/SpecSection'
import { Button } from '@/components/ui/button'
import { useProjet } from '@/hooks/useProjet'
import { sessions as api } from '@/api/sessions'

export function ProjetPage({ id }: { id: number }) {
  const { projet, sessions, carte, setCarte, erreur } = useProjet(id)
  const [erreurAction, setErreurAction] = useState<string>()
  const navigate = useNavigate()

  /** Ouvre une Question de la frontière dans une Session à la Méthode de son type (E-6). */
  async function ouvrir(questionId: number) {
    try {
      const s = await api.ouvrir(id, questionId)
      navigate({ to: '/sessions/$id', params: { id: String(s.id) } })
    } catch (e) {
      setErreurAction((e as Error).message)
    }
  }

  const frontiere = carte?.questions.filter((q) => q.ouvrable) ?? []
  // E-19 : proposer la Spec quand plus aucune Question n'est open ou claimed.
  const mapVide = !!carte && carte.questions.length > 0 && carte.questions.every((q) => q.statut === 'resolved' || q.statut === 'hors_perimetre')

  if (erreur) return <p role="alert">{erreur}</p>
  if (!projet) return null
  return (
    <div className="mx-auto grid max-w-2xl gap-4">
      <Link to="/" className="text-sm text-muted-foreground">← Projets</Link>
      <div>
        <h1 className="text-xl font-semibold">{projet.nom}</h1>
        <ConsommationLigne projetId={id} />
      </div>
      {projet.description && <p className="whitespace-pre-wrap text-muted-foreground">{projet.description}</p>}
      <section className="grid gap-2 rounded-lg border p-3">
        <h2 className="font-semibold">Frontière</h2>
        {frontiere.length === 0 && <p className="text-sm text-muted-foreground">Aucune Question ouvrable pour l'instant.</p>}
        {frontiere.map((q) => (
          <div key={q.id} className="flex items-center gap-2 text-sm">
            <span className="flex-1">
              <b>{q.numero}</b> · {q.titre} <span className="text-xs text-muted-foreground">[{q.type}]</span>
            </span>
            <Button size="sm" onClick={() => ouvrir(q.id)}>Ouvrir</Button>
          </div>
        ))}
        {erreurAction && <p role="alert" className="text-sm text-destructive">{erreurAction}</p>}
      </section>
      {carte && <MapVue projetId={id} carte={carte} onChange={setCarte} />}
      <SpecSection projetId={id} mapVide={mapVide} />
      <h2 className="font-semibold">Sessions</h2>
      {sessions.length === 0 && <p className="text-muted-foreground">Aucune Session.</p>}
      <ul className="grid gap-1">
        {sessions.map((s) => (
          <li key={s.id}>
            <Link to="/sessions/$id" params={{ id: String(s.id) }} className="underline">
              Session {s.id} · {s.question ?? 'Tracé de la Map'} · {s.methode} · {s.statut}
            </Link>
          </li>
        ))}
      </ul>
    </div>
  )
}
