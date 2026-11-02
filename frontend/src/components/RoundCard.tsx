import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Textarea } from '@/components/ui/textarea'
import type { Round } from '@/lib/api'

/** Carte d'un round (D-15) : reco acceptée par défaut, réponse libre prioritaire, envoi groupé `Q1: …`. */
export function RoundCard({ round, actif, onEnvoyer }: { round: Round; actif: boolean; onEnvoyer: (texte: string) => void }) {
  const [reponses, setReponses] = useState<Record<string, { accepte: boolean; texte: string }>>({})
  const etat = (id: string) => reponses[id] ?? { accepte: true, texte: '' }
  const maj = (id: string, patch: Partial<{ accepte: boolean; texte: string }>) =>
    setReponses((r) => ({ ...r, [id]: { ...etat(id), ...patch } }))

  function envoyer() {
    onEnvoyer(
      round.questions
        .map((q) => {
          const { accepte, texte } = etat(q.id)
          return `${q.id}: ${texte.trim() || (accepte ? q.recommandation : '(sans réponse)')}`
        })
        .join('\n'),
    )
  }

  return (
    <div className="grid gap-3 rounded-lg border p-3">
      {round.questions.map((q) => (
        <div key={q.id} className="grid gap-1.5 border-t border-dashed pt-3 first:border-t-0 first:pt-0">
          <p>
            <b>{q.id}.</b> <b>{q.titre}</b>
          </p>
          <p className="whitespace-pre-wrap">{q.corps}</p>
          <p className="rounded bg-amber-50 px-2 py-1 text-sm text-amber-950">Reco : {q.recommandation}</p>
          {actif && (
            <>
              <label className="flex items-center gap-2 text-sm">
                <Checkbox checked={etat(q.id).accepte} onCheckedChange={(c) => maj(q.id, { accepte: c })} />
                Accepter la reco
              </label>
              <Textarea
                aria-label={`Réponse à ${q.id}`}
                placeholder="…ou ta réponse"
                value={etat(q.id).texte}
                onChange={(e) => maj(q.id, { texte: e.target.value })}
              />
            </>
          )}
        </div>
      ))}
      {actif && <Button onClick={envoyer} className="justify-self-start">Envoyer les réponses</Button>}
    </div>
  )
}
