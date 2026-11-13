import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { useDecisions } from '@/hooks/useDecisions'
import type { Proposee } from '@/api/decisions'

/** Panneau latéral de la Session (D-15) : Décisions proposées, Décisions du Projet, Questions ouvertes. */
export function Panneau({ sessionId, projetId, version }: { sessionId: number; projetId: number; version: number }) {
  const { proposees, decisions, carte, erreur, confirmer } = useDecisions(sessionId, projetId, version)

  const ouvertes = carte?.questions.filter((q) => q.statut === 'open') ?? []

  return (
    <aside className="grid content-start gap-4 border-l bg-muted/30 p-4 text-sm">
      <Bloc titre="Décisions proposées">
        {proposees.length === 0 && <p className="text-muted-foreground">Aucune pour l'instant.</p>}
        {proposees.map((p) => (
          <Proposition key={`${p.messageId}:${p.indice}`} proposee={p} onConfirmer={(t) => confirmer(p, t)} />
        ))}
        {erreur && <p role="alert" className="text-destructive">{erreur}</p>}
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
    <div className="grid gap-2 rounded-md border border-warning/50 bg-warning/15 p-2">
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
