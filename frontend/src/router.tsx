import { useEffect, useState } from 'react'
import { createRootRoute, createRoute, createRouter, Outlet } from '@tanstack/react-router'
import { Projets } from '@/pages/Projets'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { api } from '@/lib/api'
import { ProjetPage } from '@/pages/ProjetPage'
import { SessionPage } from '@/pages/SessionPage'

type Token = { configure: boolean; type?: 'oauth_token' | 'api_key' | null; indice?: string | null }
type Status = { model: string; token: Token }

/** Token Claude Code (claude setup-token, ou clé API Console) : écrit côté serveur, jamais relu. */
function TokenClaude({ token, onChange }: { token: Token; onChange: (t: Token) => void }) {
  const [ouvert, setOuvert] = useState(false)
  const [valeur, setValeur] = useState('')
  const [erreur, setErreur] = useState<string>()

  async function enregistrer(e: React.FormEvent) {
    e.preventDefault()
    setErreur(undefined)
    try {
      onChange(await api<Token>('/api/config/token', { method: 'PUT', body: JSON.stringify({ token: valeur }) }))
      setValeur('')
      setOuvert(false)
    } catch (err) {
      setErreur((err as Error).message)
    }
  }

  async function effacer() {
    await fetch('/api/config/token', { method: 'DELETE' })
    onChange({ configure: false })
  }

  return (
    <div className="ml-auto flex items-center gap-2 text-xs">
      {token.configure ? (
        <span className="text-muted-foreground">
          {token.type === 'api_key' ? 'Clé API' : 'Token OAuth'} {token.indice}
        </span>
      ) : (
        <span className="text-amber-800">Connexion locale de Claude Code</span>
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

function Layout() {
  const [status, setStatus] = useState<Status>()
  useEffect(() => {
    fetch('/api/status').then((r) => r.json()).then(setStatus)
  }, [])
  return (
    <div className="min-h-screen">
      <header className="flex items-center gap-3 border-b px-4 py-2">
        <span className="font-semibold">productDesigner</span>
        {status && <span className="text-xs text-muted-foreground">Claude Code · {status.model}</span>}
        {status && <TokenClaude token={status.token} onChange={(token) => setStatus({ ...status, token })} />}
      </header>
      {status && !status.token.configure && (
        <div role="note" className="border-b bg-amber-50 px-4 py-2 text-sm text-amber-900">
          Aucun token saisi : les appels utilisent la connexion locale du CLI Claude Code (claude login). Pour un token
          dédié, lancer <code>claude setup-token</code> et le coller via « Saisir un token ».
        </div>
      )}
      <main className="p-4">
        <Outlet />
      </main>
    </div>
  )
}

const rootRoute = createRootRoute({
  component: Layout,
  notFoundComponent: () => <p>Page introuvable.</p>,
})

const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  component: Projets,
})

const projetRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/projets/$id',
  component: function Page() {
    const { id } = projetRoute.useParams()
    return <ProjetPage id={Number(id)} />
  },
})

const sessionRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/sessions/$id',
  component: function Page() {
    const { id } = sessionRoute.useParams()
    return <SessionPage key={id} id={Number(id)} />
  },
})

export const router = createRouter({ routeTree: rootRoute.addChildren([indexRoute, projetRoute, sessionRoute]) })

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}
