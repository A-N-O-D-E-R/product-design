import { QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { newQueryClient } from '@/api/query'
import { SpecSection } from './SpecSection'

const reponse = (corps: unknown) => new Response(JSON.stringify(corps), { status: 200, headers: { 'Content-Type': 'application/json' } })
const spec = (id: number, version: number, contenu: string) => ({ id, version, contenu, createdAt: '', confluenceVersion: null, confluenceEspace: null })

beforeEach(() => {
  vi.stubGlobal('fetch', async (r: Request) => {
    const { pathname } = new URL(r.url)
    if (pathname === '/api/projets/7/specs') return reponse([{ id: 12, version: 2, createdAt: '' }, { id: 11, version: 1, createdAt: '' }])
    if (pathname === '/api/specs/12') return reponse(spec(12, 2, 'Contenu de la version deux'))
    if (pathname === '/api/specs/11') return reponse(spec(11, 1, 'Contenu de la version un'))
    if (pathname.endsWith('/roadmap')) return reponse({ specId: 12, version: 2, jiraProjet: null, jalons: [] })
    if (pathname === '/api/jira/status') return reponse({ configure: false })
    return new Response('{}', { status: 404 })
  })
})
afterEach(() => vi.unstubAllGlobals())

it('affiche la dernière version de la Spec, et celle qu\'on choisit dans la liste', async () => {
  render(<QueryClientProvider client={newQueryClient()}><SpecSection projetId={7} mapVide={false} /></QueryClientProvider>)
  expect(await screen.findByText('Contenu de la version deux')).toBeInTheDocument()
  fireEvent.change(screen.getByRole('combobox'), { target: { value: '11' } })
  await waitFor(() => expect(screen.getByText('Contenu de la version un')).toBeInTheDocument())
})
