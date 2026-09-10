import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    host: '0.0.0.0',
    // Proxy API calls to the BFF so the browser sees a single origin in
    // development and CORS never enters the picture.
    proxy: {
      // A regex, not the plain '/api' prefix this used to be. A string key
      // matches any path *starting* with those characters, so the /api-explorer
      // page was handed to the BFF and the browser got the API's 404 instead of
      // the app. Every real call is '/api/<something>', so anchoring on the
      // trailing slash proxies exactly those and leaves client routes alone.
      '^/api/': {
        target: process.env.VITE_API_URL || 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
