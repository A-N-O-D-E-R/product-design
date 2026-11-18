import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, json, postSse } from './client'

const reponse = (status: number, corps: unknown, type = 'application/json') =>
  new Response(typeof corps === 'string' ? corps : JSON.stringify(corps), { status, headers: { 'Content-Type': type } })

let fetchMock: ReturnType<typeof vi.fn>
const demande = (i = 0) => fetchMock.mock.calls[i][0] as Request

beforeEach(() => {
  fetchMock = vi.fn()
  vi.stubGlobal('fetch', fetchMock)
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
})
afterEach(() => vi.unstubAllGlobals())

describe('api', () => {
  it('lit le JSON avec les cookies de session, sans jeton CSRF sur un GET', async () => {
    document.cookie = 'XSRF-TOKEN=abc'
    fetchMock.mockResolvedValue(reponse(200, [{ id: 1 }]))
    expect(await api('/api/projets')).toEqual([{ id: 1 }])
    expect(demande().credentials).toBe('include')
    expect(demande().headers.get('X-XSRF-TOKEN')).toBeNull()
  })

  it("renvoie le jeton CSRF du cookie sur les écritures", async () => {
    document.cookie = 'XSRF-TOKEN=a%2Fb%3D'
    fetchMock.mockResolvedValue(reponse(200, { id: 2 }))
    await api('/api/projets', json('POST', { nom: 'N' }))
    const r = demande()
    expect(r.method).toBe('POST')
    expect(r.headers.get('X-XSRF-TOKEN')).toBe('a/b=')
    expect(r.headers.get('Content-Type')).toBe('application/json')
  })

  it('sérialise le corps seulement quand il y en a un', () => {
    expect(json('POST', { nom: 'N' })).toEqual({ method: 'POST', body: '{"nom":"N"}' })
    expect(json('POST').body).toBeUndefined()
  })

  it('lève le `detail` de la réponse RFC 9457, sinon `message`, sinon le statut', async () => {
    fetchMock.mockResolvedValueOnce(reponse(409, { detail: 'Déjà publié', title: 'Conflit' }, 'application/problem+json'))
    await expect(api('/x')).rejects.toMatchObject({ message: 'Déjà publié', status: 409 })
    fetchMock.mockResolvedValueOnce(reponse(400, { message: 'Texte vide' }))
    await expect(api('/x')).rejects.toThrow('Texte vide')
    fetchMock.mockResolvedValueOnce(reponse(500, 'oups', 'text/plain'))
    const e = (await api('/x').catch((e: unknown) => e)) as ApiError
    expect(e).toBeInstanceOf(ApiError)
    expect(e.message).toBe('Erreur 500')
  })

  it("ne rejoue pas une requête qui échoue", async () => {
    fetchMock.mockResolvedValue(reponse(503, { detail: 'indisponible' }))
    await expect(api('/x', json('POST'))).rejects.toThrow('indisponible')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('renvoie à /login sur un 401', async () => {
    const assign = vi.fn()
    vi.stubGlobal('location', { ...window.location, assign })
    fetchMock.mockResolvedValue(reponse(401, {}))
    await expect(api('/api/projets')).rejects.toThrow('Connexion requise.')
    expect(assign).toHaveBeenCalledWith('/login')
  })
})

describe('postSse', () => {
  it('découpe le flux en événements nommés, même coupés au milieu', async () => {
    document.cookie = 'XSRF-TOKEN=tok'
    const flux = new ReadableStream({
      start(c) {
        const e = new TextEncoder()
        c.enqueue(e.encode('event: delta\ndata: {"t":"Bon'))
        c.enqueue(e.encode('jour"}\n\nevent: fin\ndata: {"id":3}\n\nevent: vide\n\n'))
        c.close()
      },
    })
    fetchMock.mockResolvedValue(new Response(flux, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }))
    const evenements: [string, unknown][] = []
    await postSse('/api/sessions/1/messages', { texte: 'salut' }, (n, d) => evenements.push([n, d]))
    expect(evenements).toEqual([['delta', { t: 'Bonjour' }], ['fin', { id: 3 }], ['vide', undefined]])
    expect(demande().headers.get('Accept')).toBe('text/event-stream')
    expect(demande().headers.get('X-XSRF-TOKEN')).toBe('tok')
  })

  it("lève l'erreur du serveur avant tout flux", async () => {
    fetchMock.mockResolvedValue(reponse(400, { detail: 'Message vide' }))
    await expect(postSse('/x', {}, () => {})).rejects.toThrow('Message vide')
  })
})
