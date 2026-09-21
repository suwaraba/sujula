import type { CapacitorConfig } from '@capacitor/cli';

/**
 * The same build, wrapped.
 *
 * `server.url` is deliberately absent: a shipped app that loads its UI from a
 * URL is an app that shows a white screen when that host is down, and one whose
 * contents can be changed after review. The bundle is packaged, and only the
 * API is remote.
 *
 * `androidScheme: 'https'` matters more than it looks. Under the default
 * `http` scheme Android treats the WebView origin as insecure, which blocks
 * `Secure` cookies — and the CSRF token this application issues is one.
 */
const config: CapacitorConfig = {
  appId: 'gm.sujula.vendor',
  appName: 'Sujula Seller',
  webDir: 'dist',
  android: { allowMixedContent: false },
  ios: { contentInset: 'always', limitsNavigationsToAppBoundDomains: true },
  server: {
    androidScheme: 'https',
    iosScheme: 'https',
    // Populated at build time for a device pointed at a LAN dev server.
    ...(process.env.SUJULA_NATIVE_DEV_URL
      ? { url: process.env.SUJULA_NATIVE_DEV_URL, cleartext: true }
      : {}),
  },
  plugins: {
    CapacitorHttp: {
      // Native HTTP keeps a cookie jar the WebView cannot lose on restart,
      // which is what the CSRF handshake depends on.
      enabled: true,
    },
  },
};

export default config;
