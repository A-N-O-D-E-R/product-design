import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { expect, it } from 'vitest'

/** schema.d.ts est généré de src/test/resources/openapi.json (`pnpm api:types`) : un contrat modifié sans régénération échoue ici. */
it("schema.d.ts correspond à l'instantané OpenAPI", () => {
  const dossier = mkdtempSync(path.join(tmpdir(), 'schema-'))
  try {
    const sortie = path.join(dossier, 'schema.d.ts')
    execFileSync(
      path.resolve(import.meta.dirname, '../../node_modules/.bin/openapi-typescript'),
      [path.resolve(import.meta.dirname, '../../../src/test/resources/openapi.json'), '--properties-required-by-default', '--empty-objects-unknown', '-o', sortie],
      { stdio: 'pipe' },
    )
    expect(readFileSync(sortie, 'utf8')).toBe(readFileSync(path.resolve(import.meta.dirname, 'schema.d.ts'), 'utf8'))
  } finally {
    rmSync(dossier, { recursive: true, force: true })
  }
}, 30_000)
