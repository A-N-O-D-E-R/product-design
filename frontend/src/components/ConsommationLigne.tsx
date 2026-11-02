import { useEffect, useState } from 'react'
import { api } from '@/lib/api'

type Total = { inputTokens: number; outputTokens: number; cacheReadTokens: number; cacheWriteTokens: number; cout: number | null }

const nombre = new Intl.NumberFormat('fr-FR')
const dollars = new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'USD', maximumFractionDigits: 4 })

/** Tokens et coût estimé (D-29) ; `version` force le rechargement après un échange. */
export function ConsommationLigne({ url, version = 0 }: { url: string; version?: number }) {
  const [total, setTotal] = useState<Total>()
  useEffect(() => {
    api<Total>(url).then(setTotal)
  }, [url, version])
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
