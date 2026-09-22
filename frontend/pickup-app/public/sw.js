/*
 * A deliberately small service worker.
 *
 * Its whole job is to make the counter installable and to survive the shop's
 * wifi dropping for a few seconds — the app shell is cached so the tablet does
 * not show a browser error page mid-handover.
 *
 * It caches nothing from the API, and that is not an oversight. Every operator
 * response is served `no-store` because it carries recipients' names and phone
 * hints, and a tablet on a shop counter is shared. A cached shelf list is also
 * a wrong shelf list: parcels move.
 */
const SHELL = 'sujula-counter-shell-v1';

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(SHELL).then((cache) => cache.addAll(['/', '/index.html', '/manifest.webmanifest'])),
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== SHELL).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;

  // Never the API. See the note above.
  if (/^\/(auth|me|pickup|pickup-points|currencies|countries|config)\b/.test(url.pathname)) return;

  // A navigation falls back to the cached shell, so a dropped connection shows
  // the app's own offline state rather than the browser's error page.
  if (request.mode === 'navigate') {
    event.respondWith(fetch(request).catch(() => caches.match('/index.html').then((r) => r ?? Response.error())));
    return;
  }

  event.respondWith(
    caches.match(request).then((cached) => cached ?? fetch(request).then((response) => {
      if (response.ok && response.type === 'basic') {
        const copy = response.clone();
        void caches.open(SHELL).then((cache) => cache.put(request, copy));
      }
      return response;
    })),
  );
});
