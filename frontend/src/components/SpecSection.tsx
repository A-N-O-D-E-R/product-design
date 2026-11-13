import { useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Markdown } from '@/components/Markdown'
import { RoadmapSection } from '@/components/RoadmapSection'
import { specs as api, type Publication, type Spec, type Version } from '@/api/specs'

/** Spec du Projet : génération forcée streamée (E-19), versions consultables (E-22), avertissements de traçabilité (E-21). */
export function SpecSection({ projetId, mapVide }: { projetId: number; mapVide: boolean }) {
  const [versions, setVersions] = useState<Version[]>([])
  const [spec, setSpec] = useState<Spec>()
  const [enCours, setEnCours] = useState<string>()
  const [avertissements, setAvertissements] = useState<string[]>([])
  const [erreur, setErreur] = useState<string>()
  const [espace, setEspace] = useState('')
  const [publication, setPublication] = useState<Publication>()
  const [publiant, setPubliant] = useState(false)

  function charger(id?: number) {
    api.versions(projetId).then((v) => {
      setVersions(v)
      const cible = id ?? v[0]?.id
      if (cible) api.detail(cible).then(setSpec)
    })
  }

  useEffect(() => charger(), [projetId])
  useEffect(() => {
    setPublication(undefined)
    if (spec?.confluenceEspace) setEspace(spec.confluenceEspace)
  }, [spec?.id])

  /** Publie la version affichée : crée la page du Projet, ou en ajoute une version Confluence. */
  async function publier() {
    if (!spec) return
    setErreur(undefined)
    setPubliant(true)
    try {
      const p = await api.publier(spec.id, espace)
      setPublication(p)
      api.detail(spec.id).then(setSpec)
    } catch (e) {
      setErreur((e as Error).message)
    }
    setPubliant(false)
  }

  async function generer() {
    setErreur(undefined)
    setAvertissements([])
    setEnCours('')
    let texte = ''
    let id: number | undefined
    try {
      await api.generer(projetId, (name, data) => {
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
            onChange={(e) => api.detail(Number(e.target.value)).then(setSpec)}
          >
            {versions.map((v) => <option key={v.id} value={v.id}>Version {v.version}</option>)}
          </select>
        )}
        <Button size="sm" onClick={generer} disabled={enCours !== undefined}>Générer la Spec</Button>
      </div>
      {mapVide && enCours === undefined && (
        <p role="status" className="rounded bg-success/15 px-2 py-1 text-sm text-foreground">
          La Map n'a plus de Question ouverte ni en cours : c'est le moment de générer la Spec.
        </p>
      )}
      {erreur && <p role="alert" className="text-sm text-destructive">{erreur}</p>}
      {avertissements.length > 0 && (
        <ul role="status" className="list-disc rounded bg-warning/15 py-1 pl-6 text-sm text-foreground">
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
      {spec && enCours === undefined && (
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <Input
            aria-label="Clé de l'espace Confluence"
            placeholder="Espace Confluence (ex. PD)"
            className="max-w-56"
            value={espace}
            disabled={!!spec.confluenceEspace}
            onChange={(e) => setEspace(e.target.value)}
          />
          <Button size="sm" variant="outline" onClick={publier} disabled={publiant || !espace.trim()}>
            Publier sur Confluence
          </Button>
          {spec.confluenceVersion && !publication && (
            <span className="text-muted-foreground">Publiée (version Confluence {spec.confluenceVersion}).</span>
          )}
          {publication && (
            <a href={publication.url} target="_blank" rel="noreferrer" className="underline">
              {publication.creee ? 'Page créée' : `Version ${publication.versionConfluence} publiée`} dans {publication.espace}
            </a>
          )}
        </div>
      )}
      {spec && enCours === undefined && <RoadmapSection specId={spec.id} />}
    </section>
  )
}
