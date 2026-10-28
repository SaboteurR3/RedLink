import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

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
        target: 'http://localhost:8080',
        changeOrigin: false,
      },
    },
  },
  test: {
    environment: 'node',
  },
});
