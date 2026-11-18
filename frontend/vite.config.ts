import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    host: '127.0.0.1',
    port: 5174,
    strictPort: true,
    proxy: {
      '/api': {
        target: process.env.VITE_PROXY_GATEWAY_URL ?? 'http://127.0.0.1:8080',
        changeOrigin: false,
      },
    },
  },
  test: {
    environment: 'jsdom',
    environmentOptions: { jsdom: { url: 'http://localhost/' } },
    include: ['src/**/*.spec.ts', 'tests/**/*.spec.ts'],
    setupFiles: ['tests/setup.ts'],
  },
})
