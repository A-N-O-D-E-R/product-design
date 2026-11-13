import { useEffect, useState } from 'react'
import { consommation, type Total } from '@/api/consommation'

const nombre = new Intl.NumberFormat('fr-FR')
const dollars = new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'USD', maximumFractionDigits: 4 })

/** Tokens et coût estimé (D-29) ; `version` force le rechargement après un échange. */
export function ConsommationLigne({ sessionId, projetId, version = 0 }: { sessionId?: number; projetId?: number; version?: number }) {
  const [total, setTotal] = useState<Total>()
  useEffect(() => {
    const requete = sessionId !== undefined ? consommation.session(sessionId) : consommation.projet(projetId!)
    requete.then(setTotal)
  }, [sessionId, projetId, version])
  if (!total) return null
  const tokens = total.inputTokens + total.outputTokens + total.cacheReadTokens + total.cacheWriteTokens
  return (
    <p
      className="text-xs text-muted-foreground"
      title={`Entrée ${nombre.format(total.inputTokens)} · sortie ${nombre.format(total.outputTokens)} · cache lu ${nombre.format(total.cacheReadTokens)} · cache écrit ${nombre.format(total.cacheWriteTokens)}`}
    >
      {nombre.format(tokens)} tokens · {total.cout === null ? 'coût inconnu (tarif absent)' : `≈ ${dollars.format(total.cout)}`}
    </p>
  )
}
