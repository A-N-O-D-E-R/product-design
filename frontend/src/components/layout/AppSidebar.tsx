import { useEffect } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useLocation } from '@tanstack/react-router'
import { FolderKanban, LayoutGrid } from 'lucide-react'
import { queries } from '@/api/query'
import type { Status } from '@/api/status'
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarRail,
  SidebarSeparator,
} from '@/components/ui/sidebar'

const FOURNISSEURS: Record<string, string> = {
  'claude-code': 'Claude Code',
  anthropic: 'API Anthropic',
  openai: 'OpenAI',
  'bedrock-converse': 'Amazon Bedrock',
  ollama: 'Ollama',
}

/** Menu latéral sur dégradé saphir : accueil, puis les Projets ; le fournisseur LLM en pied. */
export function AppSidebar({ status, onMouseEnter, onMouseLeave }: {
  status?: Status
  onMouseEnter?: React.MouseEventHandler<HTMLDivElement>
  onMouseLeave?: React.MouseEventHandler<HTMLDivElement>
}) {
  const { pathname } = useLocation()
  const client = useQueryClient()
  const { data: projets = [] } = useQuery(queries.projets())
  // Relue à chaque navigation, comme avant : un Projet créé (ici ou ailleurs) apparaît tout de suite.
  useEffect(() => {
    void client.invalidateQueries({ queryKey: queries.projets().queryKey })
  }, [client, pathname])

  const lien = 'flex w-full items-center gap-2 text-white hover:text-white data-active:text-white'
  const bouton = 'hover:bg-white/10 data-active:bg-white/30 data-active:font-semibold'

  return (
    <Sidebar
      collapsible="icon"
      className="group/sidebar [&_[data-slot='sidebar-inner']]:bg-transparent"
      style={{ background: 'radial-gradient(circle at 15% 15%, #0a1640 0%, #0f3a94 40%, #0f52ba 65%, #4f83e0 88%, #8fb2f0 100%)' }}
      onMouseEnter={onMouseEnter}
      onMouseLeave={onMouseLeave}
    >
      <SidebarHeader className="p-2">
        <Link to="/" className="flex cursor-pointer flex-col items-center group-data-[collapsible=icon]:w-full">
          <div className="flex items-center justify-center gap-2">
            <div className="flex size-8 shrink-0 items-center justify-center rounded-full border-2 border-white text-sm font-bold text-white">I</div>
            <div className="flex size-8 shrink-0 items-center justify-center rounded-full border-2 border-white text-sm font-bold text-white group-data-[collapsible=icon]:hidden">P</div>
            <div className="flex size-8 shrink-0 items-center justify-center rounded-full border-2 border-white text-sm font-bold text-white group-data-[collapsible=icon]:hidden">A</div>
          </div>
          <p className="mt-1 text-[0.6rem] tracking-wider text-white/80 group-data-[collapsible=icon]:hidden">Idea Project Architect</p>
        </Link>
      </SidebarHeader>
      <SidebarContent className="gap-2 p-2">
        <SidebarGroup className="p-0">
          <SidebarSeparator className="hidden bg-white/20 group-data-[collapsible=icon]:mx-2 group-data-[collapsible=icon]:my-6 group-data-[collapsible=icon]:block group-data-[collapsible=icon]:w-auto" />
          <div className="relative flex items-center py-2 group-data-[collapsible=icon]:hidden">
            <div className="flex-1 border-t border-white/20" />
            <SidebarGroupLabel className="mx-2 text-xs text-white/80">PROJETS</SidebarGroupLabel>
            <div className="flex-1 border-t border-white/20" />
          </div>
          <SidebarGroupContent className="px-0">
            <SidebarMenu>
              <SidebarMenuItem>
                <SidebarMenuButton
                  isActive={pathname === '/'}
                  tooltip="Tous les Projets"
                  render={<Link to="/" className={lien}><LayoutGrid /><span>Tous les Projets</span></Link>}
                  className={bouton}
                />
              </SidebarMenuItem>
              {projets.map((p) => (
                <SidebarMenuItem key={p.id}>
                  <SidebarMenuButton
                    isActive={pathname === `/projets/${p.id}`}
                    tooltip={p.nom}
                    render={
                      <Link to="/projets/$id" params={{ id: String(p.id) }} className={lien}>
                        <FolderKanban />
                        <span className="truncate">{p.nom}</span>
                      </Link>
                    }
                    className={bouton}
                  />
                </SidebarMenuItem>
              ))}
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>
      </SidebarContent>
      <SidebarFooter className="p-2">
        {status?.model && (
          <p className="text-center text-[0.65rem] leading-tight text-white/80 group-data-[collapsible=icon]:hidden">
            {FOURNISSEURS[status.fournisseur ?? ''] ?? status.fournisseur}
            <br />
            {status.model}
          </p>
        )}
      </SidebarFooter>
      <SidebarRail />
    </Sidebar>
  )
}
