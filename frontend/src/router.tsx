import { useEffect, useState } from 'react'
import { createRootRoute, createRoute, createRouter, Outlet } from '@tanstack/react-router'
import { Projets } from '@/pages/Projets'
import { ProjetPage } from '@/pages/ProjetPage'
import { SessionPage } from '@/pages/SessionPage'

type Status = { anthropic: boolean; message?: string; fournisseur?: string; model?: string }

function Layout() {
  const [status, setStatus] = useState<Status>()
  useEffect(() => {
    fetch('/api/status').then((r) => r.json()).then(setStatus)
  }, [])
  return (
    <div className="min-h-screen">
      <header className="flex items-baseline gap-3 border-b px-4 py-2">
        <span className="font-semibold">productDesigner</span>
        {status?.model && (
          <span className="text-xs text-muted-foreground">
            {status.fournisseur === 'bedrock' ? 'Amazon Bedrock' : 'API Anthropic'} · {status.model}
          </span>
        )}
      </header>
      {status && !status.anthropic && (
        <div role="alert" className="border-b bg-amber-50 px-4 py-2 text-sm text-amber-900">
          {status.message}
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
