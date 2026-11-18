import path from 'node:path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@': path.resolve(__dirname, './src') } },
  // /api et la connexion (page de login, OIDC, déconnexion) sont servis par Spring Security
  server: { proxy: Object.fromEntries(['/api', '/login', '/logout', '/oauth2'].map((p) => [p, 'http://localhost:8080'])) },
  // Le jar sert le build depuis classpath:/static.
  build: { outDir: '../target/classes/static', emptyOutDir: true },
})
