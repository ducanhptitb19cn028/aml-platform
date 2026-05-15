import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3001,
    proxy: {
      '/api/v1': {
        target:       'http://localhost:9005',
        changeOrigin: true,
      },
      '/api/llm': {
        target:       'http://localhost:8001',
        changeOrigin: true,
        rewrite:      (path) => path.replace(/^\/api\/llm/, ''),
      },
      '/prom': {
        target:       'http://localhost:9090',
        changeOrigin: true,
        rewrite:      (path) => path.replace(/^\/prom/, ''),
      },
      '/lokiapi': {
        target:       'http://localhost:3100',
        changeOrigin: true,
        rewrite:      (path) => path.replace(/^\/lokiapi/, ''),
      },
      '/tempo': {
        target:       'http://localhost:3200',
        changeOrigin: true,
        rewrite:      (path) => path.replace(/^\/tempo/, ''),
      },
    },
  },
})
