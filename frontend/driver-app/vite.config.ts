import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * The driver app is a mobile web app, installed to the home screen.
 *
 * Two things here are not cosmetic:
 *
 *  - `server.proxy` exists so the dev build talks to the API on the same
 *    origin. The backend publishes no CORS configuration, and same-origin is
 *    also how this ships in production (one host, `/driver/**` reverse-proxied
 *    to Spring), so development and production make the same requests.
 *  - `server.https` is off, but geolocation, the camera and service workers all
 *    require a secure context. `localhost` counts as one; a phone on the same
 *    wifi pointed at a laptop's LAN address does not, which is why the README
 *    tells you to tunnel rather than to use the IP.
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const target = env.VITE_DEV_API_TARGET || 'http://localhost:8080';

  // Every backend prefix the driver app is allowed to touch. Listed rather
  // than globbed: a proxy that forwards everything would happily forward the
  // admin surface too, and a typo in a path would then reach it.
  const apiPrefixes = [
    '/auth',
    '/me',
    '/driver',
    '/notifications',
    '/config',
    '/countries',
    '/currencies',
  ];

  return {
    plugins: [react()],
    server: {
      port: 5175,
      proxy: Object.fromEntries(
        apiPrefixes.map((prefix) => [
          prefix,
          { target, changeOrigin: true, secure: false },
        ]),
      ),
    },
    build: {
      target: 'es2022',
      sourcemap: mode !== 'production',
      rollupOptions: {
        output: {
          manualChunks: {
            // The scanner is ~200kB and only loads on the two screens that
            // scan. Splitting it keeps first paint cheap on a 3G connection,
            // which is the connection this app actually runs on.
            scanner: ['@zxing/browser', '@zxing/library'],
          },
        },
      },
    },
  };
});
