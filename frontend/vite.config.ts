import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// 개발 서버는 /api를 gateway로 넘긴다
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': process.env.GATEWAY_URL ?? 'http://localhost:8080' },
  },
  test: { environment: 'node' },
})
