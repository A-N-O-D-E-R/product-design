export type Projet = { id: number; nom: string; description: string; createdAt: string }
export type QuestionMap = {
  id: number
  numero: number
  titre: string
  corps: string
  type: 'grilling' | 'research' | 'prototype' | 'tache'
  statut: 'open' | 'claimed' | 'resolved' | 'hors_perimetre'
  blockedBy: number[]
  ouvrable: boolean
}
export type Carte = {
  destination: string
  notes: string
  questions: QuestionMap[]
  brouillard: { id: number; texte: string }[]
  horsPerimetre: { id: number; texte: string }[]
}
export type Session = {
  id: number
  projetId: number
  methode: string
  statut: string
  createdAt: string
  questionId: number | null
  question: string | null
  continuedFrom: number | null
  tache: 'en_cours' | 'terminee' | 'echec' | null
  progression: string | null
}
export type Artefact = { id: number; type: 'research_md' | 'prototype_html'; contenu: string; createdAt: string }
export type QuestionRound = { id: string; titre: string; corps: string; recommandation: string }
export type Round = { questions: QuestionRound[] }
export type Affichage = { id: number; role: 'user' | 'assistant'; texte: string; round?: Round | null; incomplet?: boolean }

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
export async function postSse(path: string, body: unknown, onEvent: (name: string, data: any) => void) {
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
