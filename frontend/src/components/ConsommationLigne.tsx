import { useQuery } from '@tanstack/react-query'
import { queries } from '@/api/query'

const nombre = new Intl.NumberFormat('fr-FR')
const dollars = new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'USD', maximumFractionDigits: 4 })

/** Tokens et coût estimé (D-29) ; `version` force le rechargement après un échange. */
export function ConsommationLigne({ sessionId, projetId, version = 0 }: { sessionId?: number; projetId?: number; version?: number }) {
  const requete = sessionId !== undefined ? queries.consommationSession(sessionId) : queries.consommationProjet(projetId!)
  // `version` change après un échange : la clé aussi, donc la consommation est relue
  const { data: total } = useQuery({ ...requete, queryKey: [...requete.queryKey, version] })
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
