import ky from 'ky'

/** Requête JSON : corps sérialisé seulement s'il y en a un. */
export const json = (method: string, body?: unknown): { method: string; body?: string } => ({
  method,
  body: body === undefined ? undefined : JSON.stringify(body),
})

/** Jeton CSRF du cookie XSRF-TOKEN, à renvoyer dans X-XSRF-TOKEN sur les écritures (Spring Security, mode SPA). */
export function jetonCsrf(): string | undefined {
  const jeton = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.slice('XSRF-TOKEN='.length)
  return jeton ? decodeURIComponent(jeton) : undefined
}

/**
 * Le client HTTP : cookies de session, jeton CSRF sur les écritures. Pas de délai ni de nouvel essai (une génération LLM dure des minutes,
 * et une écriture rejouée ferait deux fois l'effet) ; les statuts d'erreur sont traités ici, pas levés par ky.
 */
export const http = ky.create({
  baseUrl: window.location.origin, // les chemins sont absolus (/api/...) : relatifs à l'origine de la page
  credentials: 'include',
  timeout: false,
  retry: 0,
  throwHttpErrors: false,
  hooks: {
    beforeRequest: [
      ({ request }) => {
        if (request.method === 'GET' || request.method === 'HEAD') return
        const jeton = jetonCsrf()
        if (jeton) request.headers.set('X-XSRF-TOKEN', jeton)
      },
    ],
  },
})

/** Erreur d'API : le message est celui du serveur. */
export class ApiError extends Error {
  readonly status: number
  constructor(message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

/** Pas connecté : retour à la page de login (le front statique est public, l'API répond 401). */
function verifierConnexion(r: Response) {
  if (r.status === 401) {
    window.location.assign('/login')
    throw new ApiError('Connexion requise.', 401)
  }
}

/** Message d'une réponse d'erreur : `detail` (RFC 9457), à défaut `message`. */
async function messageErreur(r: Response): Promise<string> {
  const corps = await r.json().catch(() => undefined)
  return corps?.detail ?? corps?.message ?? `Erreur ${r.status}`
}

export async function api<T>(path: string, init?: { method?: string; body?: string }): Promise<T> {
  const r = await http(path, { ...init, headers: { 'Content-Type': 'application/json' } })
  verifierConnexion(r)
  if (!r.ok) throw new ApiError(await messageErreur(r), r.status)
  return (await r.json()) as T
}

/** POST qui répond en SSE ; appelle `onEvent` pour chaque événement nommé. */
export type OnEvent = (name: string, data: any) => void

export async function postSse(path: string, body: unknown, onEvent: OnEvent) {
  const r = await http.post(path, {
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify(body),
  })
  verifierConnexion(r)
  if (!r.ok || !r.body) throw new ApiError(await messageErreur(r), r.status)
  const reader = r.body.pipeThrough(new TextDecoderStream()).getReader()
  let tampon = ''
  for (;;) {
    const { value, done } = await reader.read()
    if (done) return
    tampon += value
    let fin
    while ((fin = tampon.indexOf('\n\n')) >= 0) {
      const bloc = tampon.slice(0, fin)
      tampon = tampon.slice(fin + 2)
      let name = 'message'
      const data: string[] = []
      for (const ligne of bloc.split('\n')) {
        if (ligne.startsWith('event:')) name = ligne.slice(6).trim()
        else if (ligne.startsWith('data:')) data.push(ligne.slice(5))
      }
      onEvent(name, data.length ? JSON.parse(data.join('\n')) : undefined)
    }
  }
}
