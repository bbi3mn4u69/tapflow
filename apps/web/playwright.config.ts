import { defineConfig } from '@playwright/test'
if (!process.env.TAPFLOW_E2E_BASE_URL?.startsWith('http://127.0.0.1:'))
  throw new Error(
    'Run infra/scripts/test-web.py to provision an isolated backend and web origin.',
  )
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 45000,
  use: {
    baseURL: process.env.TAPFLOW_E2E_BASE_URL,
    viewport: { width: 1440, height: 1000 },
    reducedMotion: 'reduce',
    locale: 'en-AU',
    timezoneId: 'Australia/Melbourne',
    trace: 'off',
  },
  reporter: 'list',
})
