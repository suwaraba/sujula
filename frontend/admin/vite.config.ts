import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';

/**
 * The dev server proxies the API rather than pointing the app at another
 * origin. That is not a convenience: the backend publishes no CORS
 * configuration, and its CSRF protection is a double-submit cookie
 * (`XSRF-TOKEN`, readable by script) that a cross-origin page cannot read.
 * Same-origin is therefore the only shape in which `/admin` writes work at all,
 * in development and in production alike.
 */
const API_PREFIXES = [
  '/admin',
  '/auth',
  '/me',
  '/api',
  '/currencies',
  '/countries',
  '/locales',
  '/config',
  '/health',
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
      port: 5173,
      proxy: Object.fromEntries(
        API_PREFIXES.map((prefix) => [
          prefix,
          { target, changeOrigin: false, secure: false },
        ]),
      ),
    },
    build: {
      outDir: 'dist',
      sourcemap: true,
    },
  };
});
