import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Icons come from the shared Feather SVG folder at the repository root (smart-library/feather).
const featherDir = fileURLToPath(new URL('../feather', import.meta.url))

// In development the API runs separately on :8080; proxy /api so the browser sees one origin.
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@feather': featherDir },
  },
  server: {
    port: 5173,
    fs: { allow: ['.', featherDir] },
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true, ws: true },
    },
  },
})
