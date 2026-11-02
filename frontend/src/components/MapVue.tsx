import { useState, type FormEvent } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { api, type Carte, type QuestionMap } from '@/lib/api'

const STATUTS = { open: 'ouverte', claimed: 'en cours', resolved: 'résolue', hors_perimetre: 'hors périmètre' }
const TYPES = ['grilling', 'research', 'prototype', 'tache'] as const

type Edition = { titre: string; corps: string; type: string; blockedBy: string }

/** Map d'un Projet, éditable à la main (E-3, E-5). Chaque écriture renvoie la Carte à jour. */
export function MapVue({ projetId, carte, onChange }: { projetId: number; carte: Carte; onChange: (c: Carte) => void }) {
  const [erreur, setErreur] = useState<string>()
  const base = `/api/projets/${projetId}`

  async function ecrire(path: string, method: string, body?: unknown) {
    setErreur(undefined)
    try {
      onChange(await api<Carte>(base + path, { method, body: body === undefined ? undefined : JSON.stringify(body) }))
      return true
    } catch (e) {
      setErreur((e as Error).message)
      return false
    }
  }

  const question = (e: Edition) => ({
    titre: e.titre,
    corps: e.corps,
    type: e.type,
    blockedBy: e.blockedBy.split(/[\s,]+/).filter(Boolean).map(Number),
  })

  return (
    <div className="grid gap-4">
      {erreur && <p role="alert" className="text-sm text-red-700">{erreur}</p>}
      <EnTete carte={carte} onSave={(destination, notes) => ecrire('/map', 'PUT', { destination, notes })} />

      <section className="grid gap-2">
        <h2 className="font-semibold">Questions</h2>
        {carte.questions.length === 0 && <p className="text-muted-foreground">Aucune.</p>}
        {carte.questions.map((q) => (
          <QuestionLigne
            key={q.id}
            q={q}
            onSave={(e) => ecrire(`/questions/${q.id}`, 'PUT', question(e))}
            onHorsPerimetre={() => ecrire(`/questions/${q.id}/hors-perimetre`, 'POST')}
          />
        ))}
        <NouvelleQuestion onSave={(e) => ecrire('/questions', 'POST', question(e))} />
      </section>

      <Liste
        titre="Brouillard"
        lignes={carte.brouillard}
        onAjouter={(texte) => ecrire('/brouillard', 'POST', { texte })}
        onModifier={(id, texte) => ecrire(`/brouillard/${id}`, 'PUT', { texte })}
        onSupprimer={(id) => ecrire(`/brouillard/${id}`, 'DELETE')}
        onPromouvoir={(id) => ecrire(`/brouillard/${id}/promouvoir`, 'POST', { type: 'grilling' })}
      />
      <Liste
        titre="Hors périmètre"
        lignes={carte.horsPerimetre}
        onAjouter={(texte) => ecrire('/hors-perimetre', 'POST', { texte })}
        onModifier={(id, texte) => ecrire(`/hors-perimetre/${id}`, 'PUT', { texte })}
        onSupprimer={(id) => ecrire(`/hors-perimetre/${id}`, 'DELETE')}
      />
    </div>
  )
}

function EnTete({ carte, onSave }: { carte: Carte; onSave: (destination: string, notes: string) => void }) {
  const [edition, setEdition] = useState(false)
  const [destination, setDestination] = useState(carte.destination)
  const [notes, setNotes] = useState(carte.notes)
  if (!edition) {
    return (
      <section className="grid gap-1">
        <h2 className="font-semibold">Destination</h2>
        <p className={carte.destination ? '' : 'text-muted-foreground'}>{carte.destination || 'Pas encore fixée.'}</p>
        {carte.notes && <p className="whitespace-pre-wrap text-sm text-muted-foreground">{carte.notes}</p>}
        <Button size="sm" variant="outline" className="justify-self-start" onClick={() => setEdition(true)}>
          Modifier la Destination et les notes
        </Button>
      </section>
    )
  }
  return (
    <section className="grid gap-2">
      <h2 className="font-semibold">Destination</h2>
      <Textarea aria-label="Destination" value={destination} onChange={(e) => setDestination(e.target.value)} />
      <Textarea aria-label="Notes" placeholder="Notes" value={notes} onChange={(e) => setNotes(e.target.value)} />
      <Button size="sm" className="justify-self-start" onClick={() => { onSave(destination, notes); setEdition(false) }}>
        Enregistrer
      </Button>
    </section>
  )
}

function QuestionLigne({ q, onSave, onHorsPerimetre }: { q: QuestionMap; onSave: (e: Edition) => Promise<boolean>; onHorsPerimetre: () => void }) {
  const [edition, setEdition] = useState(false)
  if (edition) {
    const initial = { titre: q.titre, corps: q.corps, type: q.type, blockedBy: q.blockedBy.join(', ') }
    return <FormQuestion initial={initial} onAnnuler={() => setEdition(false)} onSave={async (e) => (await onSave(e)) && setEdition(false)} />
  }
  return (
    <div className="rounded-md border p-2 text-sm">
      <p>
        <b>{q.numero}</b> · {q.titre}{' '}
        <span className="text-xs text-muted-foreground">
          [{q.type}] {STATUTS[q.statut]}
          {q.blockedBy.length > 0 && ` · bloquée par ${q.blockedBy.join(', ')}`}
        </span>
      </p>
      {q.corps && <p className="mt-1 whitespace-pre-wrap text-muted-foreground">{q.corps}</p>}
      <div className="mt-2 flex gap-2">
        <Button size="sm" variant="outline" onClick={() => setEdition(true)}>Modifier</Button>
        {q.statut !== 'hors_perimetre' && <Button size="sm" variant="outline" onClick={onHorsPerimetre}>Hors périmètre</Button>}
      </div>
    </div>
  )
}

function NouvelleQuestion({ onSave }: { onSave: (e: Edition) => Promise<boolean> }) {
  const [ouvert, setOuvert] = useState(false)
  if (!ouvert) {
    return <Button size="sm" variant="outline" className="justify-self-start" onClick={() => setOuvert(true)}>Ajouter une Question</Button>
  }
  return (
    <FormQuestion
      initial={{ titre: '', corps: '', type: 'grilling', blockedBy: '' }}
      onAnnuler={() => setOuvert(false)}
      onSave={async (e) => (await onSave(e)) && setOuvert(false)}
    />
  )
}

function FormQuestion({ initial, onSave, onAnnuler }: { initial: Edition; onSave: (e: Edition) => void; onAnnuler: () => void }) {
  const [e, setE] = useState(initial)
  function soumettre(ev: FormEvent) {
    ev.preventDefault()
    onSave(e)
  }
  return (
    <form onSubmit={soumettre} className="grid gap-2 rounded-md border p-2 text-sm">
      <Input aria-label="Titre" placeholder="Titre" value={e.titre} onChange={(x) => setE({ ...e, titre: x.target.value })} required />
      <Textarea aria-label="Question" placeholder="La question précise" value={e.corps} onChange={(x) => setE({ ...e, corps: x.target.value })} />
      <div className="flex gap-2">
        <select aria-label="Type" className="rounded-md border px-2" value={e.type} onChange={(x) => setE({ ...e, type: x.target.value })}>
          {TYPES.map((t) => <option key={t}>{t}</option>)}
        </select>
        <Input aria-label="Bloquée par" placeholder="Bloquée par (numéros : 1, 3)" value={e.blockedBy} onChange={(x) => setE({ ...e, blockedBy: x.target.value })} />
      </div>
      <div className="flex gap-2">
        <Button size="sm" type="submit">Enregistrer</Button>
        <Button size="sm" type="button" variant="outline" onClick={onAnnuler}>Annuler</Button>
      </div>
    </form>
  )
}

type Ligne = { id: number; texte: string }

function Liste(props: {
  titre: string
  lignes: Ligne[]
  onAjouter: (texte: string) => Promise<boolean>
  onModifier: (id: number, texte: string) => Promise<boolean>
  onSupprimer: (id: number) => void
  onPromouvoir?: (id: number) => void
}) {
  const [nouveau, setNouveau] = useState('')
  async function ajouter(e: FormEvent) {
    e.preventDefault()
    if (nouveau.trim() && (await props.onAjouter(nouveau))) setNouveau('')
  }
  return (
    <section className="grid gap-1.5">
      <h2 className="font-semibold">{props.titre}</h2>
      {props.lignes.length === 0 && <p className="text-muted-foreground">Rien.</p>}
      {props.lignes.map((l) => (
        <LigneEditable key={l.id} ligne={l} {...props} />
      ))}
      <form onSubmit={ajouter} className="flex gap-2">
        <Input aria-label={`Ajouter à ${props.titre}`} placeholder="Ajouter…" value={nouveau} onChange={(e) => setNouveau(e.target.value)} />
        <Button size="sm" type="submit" variant="outline">Ajouter</Button>
      </form>
    </section>
  )
}

function LigneEditable({ ligne, onModifier, onSupprimer, onPromouvoir }: {
  ligne: Ligne
  onModifier: (id: number, texte: string) => Promise<boolean>
  onSupprimer: (id: number) => void
  onPromouvoir?: (id: number) => void
}) {
  const [edition, setEdition] = useState(false)
  const [texte, setTexte] = useState(ligne.texte)
  if (edition) {
    return (
      <div className="flex gap-2">
        <Input aria-label="Texte" value={texte} onChange={(e) => setTexte(e.target.value)} />
        <Button size="sm" onClick={async () => (await onModifier(ligne.id, texte)) && setEdition(false)}>Enregistrer</Button>
      </div>
    )
  }
  return (
    <div className="flex items-center gap-2 text-sm">
      <span className="flex-1">• {ligne.texte}</span>
      {onPromouvoir && <Button size="sm" variant="outline" onClick={() => onPromouvoir(ligne.id)}>→ Question</Button>}
      <Button size="sm" variant="ghost" onClick={() => setEdition(true)}>Modifier</Button>
      <Button size="sm" variant="ghost" onClick={() => onSupprimer(ligne.id)}>Supprimer</Button>
    </div>
  )
}
