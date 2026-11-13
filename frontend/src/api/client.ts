/** Requête JSON : corps sérialisé seulement s'il y en a un. */
export const json = (method: string, body?: unknown): RequestInit => ({
  method,
  body: body === undefined ? undefined : JSON.stringify(body),
})

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const r = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
  if (!r.ok) {
    const corps = await r.json().catch(() => undefined)
    throw new Error(corps?.message ?? `Erreur ${r.status}`)
  }
  return r.json()
}

/** POST qui répond en SSE ; appelle `onEvent` pour chaque événement nommé. */
export type OnEvent = (name: string, data: any) => void

export async function postSse(path: string, body: unknown, onEvent: OnEvent) {
  const r = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify(body),
  })
  if (!r.ok || !r.body) {
    const corps = await r.json().catch(() => undefined)
    throw new Error(corps?.message ?? `Erreur ${r.status}`)
  }
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
