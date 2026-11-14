import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  define: {
    global: 'window',
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('/node_modules/react-dom/') || id.includes('/node_modules/react/')) return 'react-vendor'
        },
      },
    },
  },
  server: {
    proxy: {
      '/api/v1/orders': 'http://localhost:8081',
      '/api/orders': 'http://localhost:8081',
      '/api/v1/ledger': 'http://localhost:8082',
      '/api/ledger': 'http://localhost:8082',
      '/api/v1/auth': 'http://localhost:8083',
      '/api/auth': 'http://localhost:8083',
      '/api/v1/wallet': 'http://localhost:8084',
      '/api/wallet': 'http://localhost:8084',
      '/api': 'http://localhost:8080',
      '/ws': {
        target: 'http://localhost:8080',
        ws: true,
      },
    },
  },
})
