import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  build: {
    // CSP allows only same-origin scripts: no inline module preload polyfill.
    modulePreload: { polyfill: false },
    sourcemap: false,
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    // Only tokens.css is processed, so its contrast test can read it with ?raw.
    css: { include: [/tokens\.css/] },
  },
});
