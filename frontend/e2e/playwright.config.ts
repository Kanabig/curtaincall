import { defineConfig } from '@playwright/test'

// BASE_URL: compose의 frontend(nginx). /api는 같은 origin으로 gateway에 프록시된다.
export default defineConfig({
  testDir: './tests',
  timeout: 180_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : [['list']],
  use: {
    baseURL: process.env.BASE_URL ?? 'http://localhost:5173',
    locale: 'ko-KR',
    timezoneId: 'Asia/Seoul',
    trace: 'retain-on-failure',
  },
})
