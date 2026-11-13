import { useEffect, useState } from 'react'
import { createRootRoute, createRoute, createRouter, Outlet } from '@tanstack/react-router'
import { status as chargerStatus, type Status } from '@/api/status'
import { AppSidebar } from '@/components/layout/AppSidebar'
import { SidebarInset, SidebarProvider } from '@/components/ui/sidebar'
import { Projets } from '@/pages/Projets'
import { ProjetPage } from '@/pages/ProjetPage'
import { SessionPage } from '@/pages/SessionPage'

function Layout() {
  const [status, setStatus] = useState<Status>()
  const [open, setOpen] = useState(false)
  useEffect(() => {
    chargerStatus().then(setStatus)
  }, [])
  return (
    <SidebarProvider style={{ '--sidebar-width': '13rem' } as React.CSSProperties} open={open} onOpenChange={setOpen}>
      <AppSidebar status={status} onMouseEnter={() => setOpen(true)} onMouseLeave={() => setOpen(false)} />
      <SidebarInset className="min-w-0">
        {status && !status.configure && (
          <div role="alert" className="border-b bg-warning/15 px-4 py-2 text-sm text-foreground">
            {status.message}
          </div>
        )}
        <main className="p-4">
          <Outlet />
        </main>
      </SidebarInset>
    </SidebarProvider>
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
