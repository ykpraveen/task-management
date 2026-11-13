import { defineConfig } from '@playwright/test';

/**
 * Cross-service contract suites (HTTP and Realtime authorization) driven through the public
 * edge. They exercise protocols rather than rendering, so one browser engine is enough.
 */
export default defineConfig({
  testDir: '.',
  testMatch: '**/*.spec.ts',
  outputDir: '../../reports/contract/artifacts',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: [['list'], ['html', { outputFolder: '../../reports/contract/html', open: 'never' }]],
  use: {
    baseURL: process.env.BASE_URL ?? 'http://localhost:8080',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
});
