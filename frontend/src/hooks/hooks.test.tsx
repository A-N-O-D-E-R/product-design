import { QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { newQueryClient } from '@/api/query'
import { useDecisions } from './useDecisions'
import { useProjet } from './useProjet'

const carte = { destination: 'D', notes: '', questions: [], brouillard: [], horsPerimetre: [] }
let panneau: { proposees: unknown[]; decisions: unknown[] }
let appels: string[]

const reponse = (status: number, corps: unknown) =>
  new Response(JSON.stringify(corps), { status, headers: { 'Content-Type': 'application/json' } })

beforeEach(() => {
  appels = []
  panneau = { proposees: [{ messageId: 1, indice: 0, texte: 'Choisir X' }], decisions: [] }
  vi.stubGlobal('fetch', async (r: Request) => {
    const { pathname } = new URL(r.url)
    appels.push(`${r.method} ${pathname}`)
    if (pathname === '/api/projets/7') return reponse(200, { id: 7, nom: 'P', description: '', createdAt: '', sourceIdeaUrl: null })
    if (pathname === '/api/projets/404') return reponse(404, { detail: 'Projet inconnu' })
    if (pathname.endsWith('/sessions')) return reponse(200, [])
    if (pathname.endsWith('/map')) return reponse(200, carte)
    if (r.method === 'POST' && pathname === '/api/sessions/3/decisions') {
      panneau = { proposees: [], decisions: [{ id: 1, numero: 1, texte: 'Choisir X', sessionId: 3, confirmedAt: '' }] }
      return reponse(200, panneau.decisions[0])
    }
    if (pathname === '/api/sessions/3/decisions') return reponse(200, panneau)
    return reponse(404, {})
  })
})
afterEach(() => vi.unstubAllGlobals())

/** Un client par test : le cache doit survivre aux rendus. */
const enveloppe = () => {
  const client = newQueryClient()
  return ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

describe('useProjet', () => {
  it('charge le Projet, ses Sessions et sa Map', async () => {
    const { result } = renderHook(() => useProjet(7), { wrapper: enveloppe() })
    await waitFor(() => expect(result.current.carte).toEqual(carte))
    expect(result.current.projet?.nom).toBe('P')
    expect(result.current.sessions).toEqual([])
  })

  it("remplace la Map du cache par celle qu'une écriture renvoie", async () => {
    const { result } = renderHook(() => useProjet(7), { wrapper: enveloppe() })
    await waitFor(() => expect(result.current.carte).toBeDefined())
    act(() => result.current.setCarte({ ...carte, destination: 'Autre' }))
    await waitFor(() => expect(result.current.carte?.destination).toBe('Autre')) // React Query notifie en différé
  })

  it("expose le message d'une erreur du serveur", async () => {
    const { result } = renderHook(() => useProjet(404), { wrapper: enveloppe() })
    await waitFor(() => expect(result.current.erreur).toBe('Projet inconnu'))
  })
})

describe('useDecisions', () => {
  it('confirme une Décision puis relit le panneau', async () => {
    const { result } = renderHook(() => useDecisions(3, 7, 0), { wrapper: enveloppe() })
    await waitFor(() => expect(result.current.proposees).toHaveLength(1))
    await act(() => result.current.confirmer(result.current.proposees[0], 'Choisir X'))
    await waitFor(() => expect(result.current.decisions).toHaveLength(1))
    expect(result.current.proposees).toHaveLength(0)
    expect(appels.filter((a) => a === 'GET /api/sessions/3/decisions').length).toBeGreaterThanOrEqual(2)
  })
})
