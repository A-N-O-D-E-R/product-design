/** Requête JSON : corps sérialisé seulement s'il y en a un. */
export const json = (method: string, body?: unknown): RequestInit => ({
  method,
  body: body === undefined ? undefined : JSON.stringify(body),
})

/** Jeton CSRF du cookie XSRF-TOKEN, à renvoyer dans X-XSRF-TOKEN sur les écritures (Spring Security, mode SPA). */
function jetonCsrf(): Record<string, string> {
  const jeton = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.slice('XSRF-TOKEN='.length)
  return jeton ? { 'X-XSRF-TOKEN': decodeURIComponent(jeton) } : {}
}

/** Pas connecté : retour à la page de login (le front statique est public, l'API répond 401). */
function verifierConnexion(r: Response) {
  if (r.status === 401) {
    window.location.assign('/login')
    throw new Error('Connexion requise.')
  }
}

/** Message d'une réponse d'erreur : `detail` (RFC 9457), à défaut `message`. */
async function messageErreur(r: Response): Promise<string> {
  const corps = await r.json().catch(() => undefined)
  return corps?.detail ?? corps?.message ?? `Erreur ${r.status}`
}

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const r = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...jetonCsrf(), ...init?.headers },
  })
  verifierConnexion(r)
  if (!r.ok) throw new Error(await messageErreur(r))
  return r.json()
}

/** POST qui répond en SSE ; appelle `onEvent` pour chaque événement nommé. */
export type OnEvent = (name: string, data: any) => void

export async function postSse(path: string, body: unknown, onEvent: OnEvent) {
  const r = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', ...jetonCsrf() },
    body: JSON.stringify(body),
  })
  verifierConnexion(r)
  if (!r.ok || !r.body) throw new Error(await messageErreur(r))
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
