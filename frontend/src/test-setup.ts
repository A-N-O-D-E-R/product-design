import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

afterEach(cleanup) // pas de globals vitest : Testing Library ne peut pas l'enregistrer seule
