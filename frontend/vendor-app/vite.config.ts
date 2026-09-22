import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';

/**
 * The dev server proxies the API rather than pointing the app at another
 * origin, for the reason the admin console gives: the backend publishes no CORS
 * configuration, and its CSRF protection is a double-submit cookie
 * (`XSRF-TOKEN`, readable by script) that a cross-origin page cannot read.
 * Same-origin is the only shape in which a `/vendor` write works at all.
 *
 * What differs here is that the list is *minimal*. Spring serves the API at the
 * root rather than behind one prefix, and two of this app's own routes collide
 * with endpoints: `/orders` and `/products` are screens here and endpoints
 * there. Proxying either sends a deep link to the backend, which answers 401
 * instead of serving the app — the page simply fails to open.
 *
 * So every prefix below appears in `src/api/endpoints/`. Adding one because the
 * backend happens to own it is how the collision comes back. The same applies
 * in production: see README.md, "Serving it".
 */
const API_PREFIXES = [
  '/auth',        // sign in, refresh, sign out, MFA, password
  '/me',          // the profile the vendor gate reads
  '/vendor',      // the whole seller surface
  '/api',         // /api/vendors, /api/products/images/presign
  '/currencies',  // the catalogue that decides how many decimals an amount has
  '/countries',
  '/categories',
  '/config',      // /config/public, which is also the CSRF handshake
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
      // 5173 is the admin console and 5175 the driver app.
      port: 5174,
      proxy: Object.fromEntries(
        API_PREFIXES.map((prefix) => [
          prefix,
          {
            target,
            // Rewriting Host would bind the CSRF cookie to :8080, and the
            // browser would stop sending it back.
            changeOrigin: false,
            secure: false,
          },
        ]),
      ),
    },
    build: {
      outDir: 'dist',
      assetsDir: 'assets',
      sourcemap: mode !== 'production',
      target: 'es2020',
    },
    // Relative, because Capacitor loads the bundle from a file-backed scheme
    // where an absolute asset path resolves against the scheme root and 404s.
    base: './',
  };
});
