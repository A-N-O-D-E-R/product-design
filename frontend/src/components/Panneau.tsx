import { useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { api, type Carte } from '@/lib/api'

type Proposee = { messageId: number; indice: number; texte: string }
type Decision = { id: number; numero: number; texte: string; sessionId: number; confirmedAt: string }

/** Panneau latéral de la Session (D-15) : Décisions proposées, Décisions du Projet, Questions ouvertes. */
export function Panneau({ sessionId, projetId, version }: { sessionId: number; projetId: number; version: number }) {
  const [proposees, setProposees] = useState<Proposee[]>([])
  const [decisions, setDecisions] = useState<Decision[]>([])
  const [carte, setCarte] = useState<Carte>()
  const [erreur, setErreur] = useState<string>()

  function charger() {
    api<{ proposees: Proposee[]; decisions: Decision[] }>(`/api/sessions/${sessionId}/decisions`).then((p) => {
      setProposees(p.proposees)
      setDecisions(p.decisions)
    })
    api<Carte>(`/api/projets/${projetId}/map`).then(setCarte)
  }

  useEffect(charger, [sessionId, projetId, version])

  async function confirmer(p: Proposee, texte: string) {
    setErreur(undefined)
    try {
      await api(`/api/sessions/${sessionId}/decisions`, { method: 'POST', body: JSON.stringify({ ...p, texte }) })
    } catch (e) {
      setErreur((e as Error).message)
    }
    charger()
  }

  const ouvertes = carte?.questions.filter((q) => q.statut === 'open') ?? []

  return (
    <aside className="grid content-start gap-4 border-l bg-muted/30 p-4 text-sm">
      <Bloc titre="Décisions proposées">
        {proposees.length === 0 && <p className="text-muted-foreground">Aucune pour l'instant.</p>}
        {proposees.map((p) => (
          <Proposition key={`${p.messageId}:${p.indice}`} proposee={p} onConfirmer={(t) => confirmer(p, t)} />
        ))}
        {erreur && <p role="alert" className="text-red-700">{erreur}</p>}
      </Bloc>
      <Bloc titre="Décisions">
        {decisions.length === 0 && <p className="text-muted-foreground">Aucune.</p>}
        {decisions.map((d) => (
          <p key={d.id} className="border-b pb-1.5">
            <b>D-{d.numero}</b> · {d.texte}
          </p>
        ))}
      </Bloc>
      <Bloc titre="Questions ouvertes">
        {ouvertes.length === 0 && <p className="text-muted-foreground">Aucune.</p>}
        {ouvertes.map((q) => (
          <p key={q.id}>
            <b>{q.numero}</b> · {q.titre} <span className="text-xs text-muted-foreground">[{q.type}]</span>
          </p>
        ))}
      </Bloc>
    </aside>
  )
}

function Bloc({ titre, children }: { titre: string; children: React.ReactNode }) {
  return (
    <section className="grid gap-2">
      <h2 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">{titre}</h2>
      {children}
    </section>
  )
}

function Proposition({ proposee, onConfirmer }: { proposee: Proposee; onConfirmer: (texte: string) => void }) {
  const [edition, setEdition] = useState(false)
  const [texte, setTexte] = useState(proposee.texte)
  return (
    <div className="grid gap-2 rounded-md border border-amber-300 bg-amber-50 p-2">
      {edition ? (
        <Textarea aria-label="Texte de la Décision" value={texte} onChange={(e) => setTexte(e.target.value)} />
      ) : (
        <p>{texte}</p>
      )}
      <div className="flex gap-2">
        <Button size="sm" onClick={() => onConfirmer(texte)} disabled={!texte.trim()}>Confirmer</Button>
        {!edition && <Button size="sm" variant="outline" onClick={() => setEdition(true)}>Modifier</Button>}
      </div>
    </div>
  )
}
