import { useEffect, useState, type FormEvent } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Markdown } from '@/components/Markdown'
import { roadmap as api, type ExportJira, type JiraStatus, type Jalon, type Roadmap, type Ticket } from '@/api/roadmap'

type Edition = { jalonId: number; titre: string; corps: string; exigences: string; blockedBy: number[] }

/** Roadmap d'une version de Spec : génération (E-23, E-24) et édition avant export (E-25). */
export function RoadmapSection({ specId }: { specId: number }) {
  const [roadmap, setRoadmap] = useState<Roadmap>()
  const [caracteres, setCaracteres] = useState<number>()
  const [avertissements, setAvertissements] = useState<string[]>([])
  const [erreur, setErreur] = useState<string>()
  const [jira, setJira] = useState<JiraStatus>()
  const [projetJira, setProjetJira] = useState('')
  const [exportEnCours, setExportEnCours] = useState(false)
  const [resultat, setResultat] = useState<ExportJira>()

  useEffect(() => {
    setAvertissements([])
    setResultat(undefined)
    api.lire(specId).then((r) => {
      setRoadmap(r)
      setProjetJira(r.jiraProjet ?? '')
    })
  }, [specId])

  useEffect(() => {
    api.jiraStatus().then(setJira)
  }, [])

  /** Export (E-26 à E-30) : crée ce qui manque, met à jour le reste, ne supprime rien dans Jira. */
  async function exporter() {
    setErreur(undefined)
    setResultat(undefined)
    setExportEnCours(true)
    try {
      setResultat(await api.exporterJira(specId, projetJira))
    } catch (e) {
      setErreur((e as Error).message)
    }
    setExportEnCours(false)
    api.lire(specId).then(setRoadmap)
  }

  const tickets = roadmap?.jalons.flatMap((j) => j.tickets) ?? []
  const titre = (id: number) => tickets.find((t) => t.id === id)?.titre ?? `#${id}`

  async function generer() {
    if (tickets.length > 0 && !confirm('Régénérer remplace tous les Jalons et Tickets de cette version, éditions comprises. Continuer ?')) return
    setErreur(undefined)
    setAvertissements([])
    setCaracteres(0)
    try {
      await api.generer(specId, (name, data) => {
        if (name === 'progression') setCaracteres(data.caracteres)
        if (name === 'fin') setAvertissements(data.avertissements)
        if (name === 'erreur') setErreur(data.message)
      })
    } catch (e) {
      setErreur((e as Error).message)
    }
    setCaracteres(undefined)
    api.lire(specId).then(setRoadmap)
  }

  async function ecrire(requete: Promise<Roadmap>) {
    setErreur(undefined)
    try {
      setRoadmap(await requete)
      return true
    } catch (e) {
      setErreur((e as Error).message)
      return false
    }
  }

  const corps = (e: Edition) => ({ ...e, exigences: e.exigences.split(/[\s,]+/).filter(Boolean) })

  return (
    <section className="grid gap-3 border-t pt-3">
      <div className="flex items-center gap-2">
        <h3 className="flex-1 font-semibold">Roadmap {roadmap && `· Spec version ${roadmap.version}`}</h3>
        <Button size="sm" variant="outline" onClick={generer} disabled={caracteres !== undefined}>
          {tickets.length > 0 ? 'Régénérer la Roadmap' : 'Générer la Roadmap'}
        </Button>
      </div>
      {caracteres !== undefined && <p role="status" className="text-sm text-muted-foreground">Génération… {caracteres} caractères reçus.</p>}
      {erreur && <p role="alert" className="text-sm text-destructive">{erreur}</p>}
      {avertissements.length > 0 && (
        <ul role="status" className="list-disc rounded bg-warning/15 py-1 pl-6 text-sm text-foreground">
          {avertissements.map((a) => <li key={a}>{a}</li>)}
        </ul>
      )}
      {tickets.length > 0 && (
        <div className="grid gap-2 rounded-md border bg-muted/30 p-2 text-sm">
          {jira && !jira.configure ? (
            <p role="alert" className="text-foreground">{jira.message}</p>
          ) : (
            <div className="flex items-center gap-2">
              <Input
                aria-label="Clé du projet Jira"
                placeholder="Clé du projet Jira (ex. PD)"
                className="max-w-56"
                value={projetJira}
                disabled={!!roadmap?.jiraProjet}
                onChange={(e) => setProjetJira(e.target.value)}
              />
              <Button size="sm" onClick={exporter} disabled={exportEnCours || !projetJira.trim()}>
                {roadmap?.jiraProjet ? 'Ré-exporter vers Jira' : 'Exporter vers Jira'}
              </Button>
              {exportEnCours && <span className="text-muted-foreground">Export en cours…</span>}
            </div>
          )}
          {resultat && (
            <div role="status" className="grid gap-1">
              <p>
                {resultat.projet} : {resultat.epicsCrees} Epic(s) et {resultat.storiesCreees} Story(s) créés, {resultat.liensCrees} lien(s) « Blocks »,{' '}
                {resultat.misesAJour} mise(s) à jour.
              </p>
              {resultat.echecs.length > 0 && (
                <ul className="list-disc pl-5 text-destructive">
                  {resultat.echecs.map((e) => <li key={e.titre + e.erreur}>{e.titre} : {e.erreur}</li>)}
                  <li className="list-none text-muted-foreground">Relancer l'export ne rejoue que ces éléments.</li>
                </ul>
              )}
              {resultat.orphelines.length > 0 && (
                <p className="text-foreground">
                  Supprimés localement mais toujours dans Jira (non supprimés) : {resultat.orphelines.join(', ')}.
                </p>
              )}
            </div>
          )}
        </div>
      )}
      {roadmap?.jalons.map((j) => (
        <div key={j.id} className="grid gap-2 rounded-md border p-2">
          <TitreJalon jalon={j} onSave={(t) => ecrire(api.renommerJalon(j.id, t))} />
          {j.tickets.map((t) => (
            <TicketLigne
              key={t.id}
              ticket={t}
              jalons={roadmap.jalons}
              autres={tickets.filter((x) => x.id !== t.id)}
              titre={titre}
              onSave={(e) => ecrire(api.modifierTicket(t.id, corps(e)))}
              onSupprimer={() => confirm(`Supprimer « ${t.titre} » ?`) && ecrire(api.supprimerTicket(t.id))}
            />
          ))}
          <NouveauTicket
            jalonId={j.id}
            autres={tickets}
            onSave={(e) => ecrire(api.ajouterTicket(j.id, corps(e)))}
          />
        </div>
      ))}
    </section>
  )
}

function TitreJalon({ jalon, onSave }: { jalon: Jalon; onSave: (titre: string) => Promise<boolean> }) {
  const [edition, setEdition] = useState(false)
  const [titre, setTitre] = useState(jalon.titre)
  if (edition) {
    return (
      <div className="flex gap-2">
        <Input aria-label="Titre du Jalon" value={titre} onChange={(e) => setTitre(e.target.value)} />
        <Button size="sm" onClick={async () => (await onSave(titre)) && setEdition(false)}>Enregistrer</Button>
      </div>
    )
  }
  return (
    <div className="flex items-center gap-2">
      <h4 className="flex-1 font-semibold">
        Jalon {jalon.ordre} · {jalon.titre} {jalon.jiraKey && <span className="text-xs font-normal text-muted-foreground">{jalon.jiraKey}</span>}
      </h4>
      <Button size="sm" variant="ghost" onClick={() => setEdition(true)}>Renommer</Button>
    </div>
  )
}

function TicketLigne(props: {
  ticket: Ticket
  jalons: Jalon[]
  autres: Ticket[]
  titre: (id: number) => string
  onSave: (e: Edition) => Promise<boolean>
  onSupprimer: () => void
}) {
  const { ticket: t } = props
  const [edition, setEdition] = useState(false)
  const jalonId = props.jalons.find((j) => j.tickets.some((x) => x.id === t.id))!.id
  if (edition) {
    return (
      <FormTicket
        initial={{ jalonId, titre: t.titre, corps: t.corps, exigences: t.exigences.join(', '), blockedBy: t.blockedBy }}
        jalons={props.jalons}
        autres={props.autres}
        onAnnuler={() => setEdition(false)}
        onSave={async (e) => (await props.onSave(e)) && setEdition(false)}
      />
    )
  }
  return (
    <details className="rounded border px-2 py-1 text-sm">
      <summary className="cursor-pointer">
        {t.titre}{' '}
        <span className="text-xs text-muted-foreground">
          {t.jiraKey && `${t.jiraKey} · `}
          {t.exigences.join(', ')}
          {t.blockedBy.length > 0 && ` · bloqué par ${t.blockedBy.map(props.titre).join(', ')}`}
        </span>
      </summary>
      <div className="mt-1 text-muted-foreground"><Markdown>{t.corps}</Markdown></div>
      <div className="mt-2 flex gap-2">
        <Button size="sm" variant="outline" onClick={() => setEdition(true)}>Modifier</Button>
        <Button size="sm" variant="ghost" onClick={props.onSupprimer}>Supprimer</Button>
      </div>
    </details>
  )
}

function NouveauTicket({ jalonId, autres, onSave }: { jalonId: number; autres: Ticket[]; onSave: (e: Edition) => Promise<boolean> }) {
  const [ouvert, setOuvert] = useState(false)
  if (!ouvert) {
    return <Button size="sm" variant="ghost" className="justify-self-start" onClick={() => setOuvert(true)}>+ Ajouter un Ticket</Button>
  }
  return (
    <FormTicket
      initial={{ jalonId, titre: '', corps: '', exigences: '', blockedBy: [] }}
      autres={autres}
      onAnnuler={() => setOuvert(false)}
      onSave={async (e) => (await onSave(e)) && setOuvert(false)}
    />
  )
}

function FormTicket(props: { initial: Edition; jalons?: Jalon[]; autres: Ticket[]; onSave: (e: Edition) => void; onAnnuler: () => void }) {
  const [e, setE] = useState(props.initial)
  function soumettre(ev: FormEvent) {
    ev.preventDefault()
    props.onSave(e)
  }
  return (
    <form onSubmit={soumettre} className="grid gap-2 rounded border p-2 text-sm">
      <Input aria-label="Titre du Ticket" placeholder="Titre" value={e.titre} onChange={(x) => setE({ ...e, titre: x.target.value })} required />
      <Textarea aria-label="Corps du Ticket" className="min-h-32" value={e.corps} onChange={(x) => setE({ ...e, corps: x.target.value })} />
      <Input aria-label="Exigences" placeholder="Exigences (E-1, E-3)" value={e.exigences} onChange={(x) => setE({ ...e, exigences: x.target.value })} />
      <label className="grid gap-1">
        Bloqué par
        <select
          multiple
          aria-label="Bloqué par"
          className="min-h-20 rounded-md border px-2"
          value={e.blockedBy.map(String)}
          onChange={(x) => setE({ ...e, blockedBy: [...x.target.selectedOptions].map((o) => Number(o.value)) })}
        >
          {props.autres.map((t) => <option key={t.id} value={t.id}>{t.titre}</option>)}
        </select>
      </label>
      {props.jalons && (
        <label className="flex items-center gap-2">
          Jalon
          <select aria-label="Jalon" className="rounded-md border px-2" value={e.jalonId} onChange={(x) => setE({ ...e, jalonId: Number(x.target.value) })}>
            {props.jalons.map((j) => <option key={j.id} value={j.id}>{j.ordre} · {j.titre}</option>)}
          </select>
        </label>
      )}
      <div className="flex gap-2">
        <Button size="sm" type="submit">Enregistrer</Button>
        <Button size="sm" type="button" variant="outline" onClick={props.onAnnuler}>Annuler</Button>
      </div>
    </form>
  )
}
