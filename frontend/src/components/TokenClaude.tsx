import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { queries } from '@/api/query'
import { token as api, type Token } from '@/api/status'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'

/** Token Claude Code (claude setup-token, ou clé API Console) : écrit côté serveur, jamais relu. */
export function TokenClaude({ token }: { token: Token }) {
  const client = useQueryClient()
  const [ouvert, setOuvert] = useState(false)
  const [valeur, setValeur] = useState('')
  const [erreur, setErreur] = useState<string>()
  const relire = () => client.invalidateQueries({ queryKey: queries.status().queryKey })

  async function enregistrer(e: React.FormEvent) {
    e.preventDefault()
    setErreur(undefined)
    try {
      await api.enregistrer(valeur)
      setValeur('')
      setOuvert(false)
      await relire()
    } catch (err) {
      setErreur((err as Error).message)
    }
  }

  async function effacer() {
    await api.effacer()
    await relire()
  }

  return (
    <div className="flex flex-wrap items-center gap-2 border-b px-4 py-2 text-xs">
      {token.configure ? (
        <span className="text-muted-foreground">
          Claude Code : {token.type === 'api_key' ? 'clé API' : 'token OAuth'} {token.indice}
        </span>
      ) : (
        <span>
          Aucun token saisi : les appels utilisent la connexion locale du CLI Claude Code (claude login). Pour un token dédié, lancer{' '}
          <code>claude setup-token</code> et le coller ici.
        </span>
      )}
      {ouvert ? (
        <form onSubmit={enregistrer} className="flex items-center gap-2">
          <Input
            type="password"
            autoComplete="off"
            aria-label="Token Claude"
            placeholder="sk-ant-oat01-…"
            value={valeur}
            onChange={(e) => setValeur(e.target.value)}
            className="h-7 w-64"
            autoFocus
          />
          <Button size="sm" type="submit" disabled={!valeur.trim()}>
            Enregistrer
          </Button>
          <Button size="sm" variant="ghost" type="button" onClick={() => setOuvert(false)}>
            Annuler
          </Button>
        </form>
      ) : (
        <>
          <Button size="sm" variant="outline" onClick={() => setOuvert(true)}>
            {token.configure ? 'Changer' : 'Saisir un token'}
          </Button>
          {token.configure && (
            <Button size="sm" variant="ghost" onClick={effacer}>
              Retirer
            </Button>
          )}
        </>
      )}
      {erreur && <span role="alert" className="text-destructive">{erreur}</span>}
    </div>
  )
}
