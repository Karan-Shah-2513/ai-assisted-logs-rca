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
          if (id.includes('/node_modules/ag-grid-community/')) return 'ag-grid'
          if (id.includes('/node_modules/ag-grid-react/')) return 'ag-grid-react'
          if (id.includes('/node_modules/react-dom/') || id.includes('/node_modules/react/')) return 'react-vendor'
        },
      },
    },
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': {
        target: 'http://localhost:8080',
        ws: true,
      },
    },
  },
})
