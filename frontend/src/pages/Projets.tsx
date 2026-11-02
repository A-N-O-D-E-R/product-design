import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate } from '@tanstack/react-router'
import { Button } from '@/components/ui/button'
import { Card, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import type { Projet } from '@/lib/api'


export function Projets() {
  const [projets, setProjets] = useState<Projet[]>([])
  const [nom, setNom] = useState('')
  const [description, setDescription] = useState('')
  const [erreur, setErreur] = useState<string>()
  const navigate = useNavigate()

  useEffect(() => {
    fetch('/api/projets').then((r) => r.json()).then(setProjets)
  }, [])

  async function creer(e: FormEvent) {
    e.preventDefault()
    const r = await fetch('/api/projets', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ nom, description }),
    })
    if (!r.ok) {
      setErreur('Création impossible : le nom est obligatoire.')
      return
    }
    // La 1re Session trace la Map (D-6).
    const projet: Projet & { sessionTraceId: number } = await r.json()
    navigate({ to: '/sessions/$id', params: { id: String(projet.sessionTraceId) } })
  }

  return (
    <div className="mx-auto grid max-w-2xl gap-6">
      <form onSubmit={creer} className="grid gap-3">
        <h1 className="text-lg font-semibold">Nouveau Projet</h1>
        <div className="grid gap-1.5">
          <Label htmlFor="nom">Nom</Label>
          <Input id="nom" value={nom} onChange={(e) => setNom(e.target.value)} required />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="description">Ton idée</Label>
          <Textarea
            id="description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="Décris en quelques lignes ce que tu veux préciser."
          />
        </div>
        {erreur && <p role="alert" className="text-sm text-red-700">{erreur}</p>}
        <Button type="submit" className="justify-self-start">Créer le Projet</Button>
      </form>

      <section className="grid gap-3">
        <h2 className="text-lg font-semibold">Projets</h2>
        {projets.length === 0 && <p className="text-muted-foreground">Aucun Projet pour l'instant.</p>}
        {projets.map((p) => (
          <Card key={p.id}>
            <CardHeader>
              <CardTitle>
                <Link to="/projets/$id" params={{ id: String(p.id) }} className="hover:underline">{p.nom}</Link>
              </CardTitle>
              {p.description && <CardDescription className="whitespace-pre-wrap">{p.description}</CardDescription>}
            </CardHeader>
          </Card>
        ))}
      </section>
    </div>
  )
}
