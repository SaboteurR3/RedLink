import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Read without @types/node: the config runs in Node, but the project only types browser code.
const env = (globalThis as { process?: { env: Record<string, string | undefined> } }).process?.env ?? {};
// REDLINK_API_URL points a second dev server at another mission-control (e.g. one on 8095).
const API_URL = env.REDLINK_API_URL ?? 'http://localhost:8080';

// In development the browser talks only to Vite (http://localhost:5173), which forwards API calls
// to mission-control: one origin, so no CORS round-trips and cookies/headers behave as in production
// behind a reverse proxy.
export default defineConfig({
  plugins: [react()],
  build: {
    // The 3D scene chunk (three.js + post-processing, ~280 kB gzipped) is lazy-loaded on purpose;
    // the main bundle stays small.
    chunkSizeWarningLimit: 1200,
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: API_URL,
        changeOrigin: false,
      },
    },
  },
  test: {
    environment: 'node',
  },
});
