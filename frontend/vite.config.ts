import { defineConfig } from 'vitest/config'
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
    // 这些用例只做 DOM 挂载与事件断言，没有网络等待；默认 5s 在本地同时跑
    // mvn verify（四个 Java 服务 + 引擎占满 CPU）时会偶发超时，放宽到不受机器负载影响。
    testTimeout: 15_000,
    hookTimeout: 30_000,
    // jsdom 环境每个 worker 都要初始化，本机满载时并发建环境会把 worker 拖死
    // （表现为 vitest 无输出地挂住）。限制并发换取确定性，总耗时影响很小。
    maxWorkers: 2,
  },
})
