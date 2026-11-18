import { useState, type FormEvent } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { queries } from '@/api/query'
import { Link, useNavigate } from '@tanstack/react-router'
import { Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { projets as api } from '@/api/projets'

/** Accueil : les Projets en cartes, et une carte en pointillés qui ouvre le formulaire de création. */
export function Projets() {
  const { data: projets = [] } = useQuery(queries.projets())
  const [ouvert, setOuvert] = useState(false)

  return (
    <div className="mx-auto grid max-w-5xl gap-4">
      <h1 className="text-lg font-semibold">Projets</h1>
      <div className="grid grid-cols-[repeat(auto-fill,minmax(16rem,1fr))] gap-4">
        {projets.map((p) => (
          <Link key={p.id} to="/projets/$id" params={{ id: String(p.id) }} className="block">
            <Card className="h-full transition-shadow hover:shadow-md">
              <CardHeader>
                <CardTitle>{p.nom}</CardTitle>
                {p.description && <CardDescription className="line-clamp-4 whitespace-pre-wrap">{p.description}</CardDescription>}
              </CardHeader>
            </Card>
          </Link>
        ))}
        <button
          type="button"
          onClick={() => setOuvert(true)}
          aria-label="Nouveau Projet"
          className="flex min-h-32 flex-col items-center justify-center gap-1 rounded-xl border-2 border-dashed border-primary/40 text-primary transition-colors hover:border-primary hover:bg-primary/5"
        >
          <Plus className="size-8" />
          <span className="text-sm">Nouveau Projet</span>
        </button>
      </div>
      <NouveauProjet ouvert={ouvert} onOuvert={setOuvert} />
    </div>
  )
}

function NouveauProjet({ ouvert, onOuvert }: { ouvert: boolean; onOuvert: (o: boolean) => void }) {
  const [nom, setNom] = useState('')
  const [description, setDescription] = useState('')
  const [erreur, setErreur] = useState<string>()
  const navigate = useNavigate()
  const client = useQueryClient()

  async function creer(e: FormEvent) {
    e.preventDefault()
    try {
      // La 1re Session trace la Map (D-6).
      const projet = await api.creer(nom, description)
      await client.invalidateQueries({ queryKey: queries.projets().queryKey })
      navigate({ to: '/sessions/$id', params: { id: String(projet.sessionTraceId) } })
    } catch {
      setErreur('Création impossible : le nom est obligatoire.')
    }
  }

  return (
    <Dialog open={ouvert} onOpenChange={onOuvert}>
      <DialogContent>
        <form onSubmit={creer} className="grid gap-4">
          <DialogHeader>
            <DialogTitle>Nouveau Projet</DialogTitle>
            <DialogDescription>Décris en quelques lignes ce que tu veux préciser.</DialogDescription>
          </DialogHeader>
          <div className="grid gap-1.5">
            <Label htmlFor="nom">Nom</Label>
            <Input id="nom" value={nom} onChange={(e) => setNom(e.target.value)} required />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="description">Ton idée</Label>
            <Textarea id="description" value={description} onChange={(e) => setDescription(e.target.value)} />
          </div>
          {erreur && <p role="alert" className="text-sm text-destructive">{erreur}</p>}
          <DialogFooter>
            <Button type="submit">Créer le Projet</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
