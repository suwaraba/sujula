import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';

/**
 * The dev server proxies the API rather than pointing the app at another
 * origin: the backend publishes no CORS configuration, and its CSRF protection
 * is a double-submit cookie a cross-origin page cannot read.
 *
 * The list is only what this client calls. Spring serves the API at the root,
 * so an over-broad prefix swallows the app's own routes — the vendor app lost
 * `/orders` and `/products` that way. Nothing here is speculative.
 */
const API_PREFIXES = [
  '/auth',           // sign in, refresh, sign out
  '/me',             // the profile behind the operator gate
  '/pickup',         // everything the operator does
  '/pickup-points',  // the public lookup, for the application form
  '/currencies',     // minor units, so commission is never rounded to two
  '/countries',
  '/config',         // /config/public, which is also the CSRF handshake
];

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  const target = env.VITE_DEV_API_TARGET || 'http://localhost:8080';

  return {
    plugins: [react()],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    server: {
      // 5173 admin, 5174 vendor, 5175 driver, 5176 here.
      port: 5176,
      proxy: Object.fromEntries(
        API_PREFIXES.map((prefix) => [
          prefix,
          // Host is not rewritten: doing so binds the CSRF cookie to :8080 and
          // the browser stops sending it back.
          { target, changeOrigin: false, secure: false },
        ]),
      ),
    },
    build: {
      outDir: 'dist',
      sourcemap: mode !== 'production',
      target: 'es2020',
    },
  };
});
