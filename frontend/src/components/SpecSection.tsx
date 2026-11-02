import { useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Markdown } from '@/components/Markdown'
import { RoadmapSection } from '@/components/RoadmapSection'
import { api, postSse } from '@/lib/api'

type Version = { id: number; version: number; createdAt: string }
type Spec = Version & { contenu: string }

/** Spec du Projet : génération forcée streamée (E-19), versions consultables (E-22), avertissements de traçabilité (E-21). */
export function SpecSection({ projetId, mapVide }: { projetId: number; mapVide: boolean }) {
  const [versions, setVersions] = useState<Version[]>([])
  const [spec, setSpec] = useState<Spec>()
  const [enCours, setEnCours] = useState<string>()
  const [avertissements, setAvertissements] = useState<string[]>([])
  const [erreur, setErreur] = useState<string>()

  function charger(id?: number) {
    api<Version[]>(`/api/projets/${projetId}/specs`).then((v) => {
      setVersions(v)
      const cible = id ?? v[0]?.id
      if (cible) api<Spec>(`/api/specs/${cible}`).then(setSpec)
    })
  }

  useEffect(() => charger(), [projetId])

  async function generer() {
    setErreur(undefined)
    setAvertissements([])
    setEnCours('')
    let texte = ''
    let id: number | undefined
    try {
      await postSse(`/api/projets/${projetId}/specs`, undefined, (name, data) => {
        if (name === 'delta') setEnCours((texte += data.texte))
        if (name === 'fin') {
          id = data.id
          setAvertissements(data.avertissements)
        }
        if (name === 'erreur') setErreur(data.message)
      })
    } catch (e) {
      setErreur((e as Error).message)
    }
    setEnCours(undefined)
    if (id) charger(id)
  }

  return (
    <section className="grid gap-2 rounded-lg border p-3">
      <div className="flex items-center gap-2">
        <h2 className="flex-1 font-semibold">Spec</h2>
        {versions.length > 0 && (
          <select
            aria-label="Version de la Spec"
            className="rounded-md border px-2 py-1 text-sm"
            value={spec?.id}
            onChange={(e) => api<Spec>(`/api/specs/${e.target.value}`).then(setSpec)}
          >
            {versions.map((v) => <option key={v.id} value={v.id}>Version {v.version}</option>)}
          </select>
        )}
        <Button size="sm" onClick={generer} disabled={enCours !== undefined}>Générer la Spec</Button>
      </div>
      {mapVide && enCours === undefined && (
        <p role="status" className="rounded bg-emerald-50 px-2 py-1 text-sm text-emerald-950">
          La Map n'a plus de Question ouverte ni en cours : c'est le moment de générer la Spec.
        </p>
      )}
      {erreur && <p role="alert" className="text-sm text-red-700">{erreur}</p>}
      {avertissements.length > 0 && (
        <ul role="status" className="list-disc rounded bg-amber-50 py-1 pl-6 text-sm text-amber-950">
          {avertissements.map((a) => <li key={a}>{a}</li>)}
        </ul>
      )}
      {enCours !== undefined ? (
        <div className="whitespace-pre-wrap text-sm">{enCours || 'Génération…'}</div>
      ) : spec ? (
        <div className="max-h-[600px] overflow-auto text-sm"><Markdown>{spec.contenu}</Markdown></div>
      ) : (
        <p className="text-sm text-muted-foreground">Aucune Spec générée.</p>
      )}
      {spec && enCours === undefined && <RoadmapSection specId={spec.id} />}
    </section>
  )
}
