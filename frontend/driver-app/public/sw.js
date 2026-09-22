/*
 * The driver app's service worker.
 *
 * It exists for one reason: a driver opening the app in a village with no
 * coverage must get the app, not a dinosaur. It caches the shell — the HTML
 * document and the hashed build assets — and nothing else.
 *
 * WHAT IT MUST NEVER CACHE, and why this file is deliberately an allow-list
 * rather than a deny-list:
 *
 *   Every `/driver/**` response carries a recipient's street address and phone
 *   number, the driver's own position, or their earnings. The backend serves
 *   all of them `Cache-Control: no-store, private` precisely so they are not
 *   held anywhere. A service worker that cached them would put them in Cache
 *   Storage on a phone that is shared between drivers and left in a vehicle,
 *   which is the exact thing the header is there to prevent. So requests are
 *   cached only when they match the shell patterns below; anything else goes
 *   straight to the network and its response is never read by this worker.
 *
 * Offline custody events are NOT handled here. They live in IndexedDB and are
 * replayed by the app through /driver/custody-events/sync, which deduplicates
 * by the app's own event id. Background Sync would have to hold codes and
 * photographs in a second place, and the batch endpoint already solves the
 * problem the right way round.
 */

const VERSION = 'sujula-driver-v1';
const SHELL = `${VERSION}-shell`;
const DOCUMENT_URL = '/index.html';

// Only these are ever stored. Hashed assets are immutable, so cache-first is
// safe; a new build produces new filenames and the old ones are swept below.
const isImmutableAsset = (url) =>
  url.origin === self.location.origin &&
  (url.pathname.startsWith('/assets/') ||
    url.pathname.startsWith('/icons/') ||
    url.pathname === '/manifest.webmanifest');

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(SHELL)
      .then((cache) => cache.addAll([DOCUMENT_URL, '/manifest.webmanifest']))
      // A shell that could not be primed is not a reason to refuse to install:
      // the app still works online and will prime itself on first navigation.
      .catch(() => undefined)
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(keys.filter((key) => key !== SHELL).map((key) => caches.delete(key))),
      )
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('message', (event) => {
  if (event.data === 'SKIP_WAITING') self.skipWaiting();
  // Signing out wipes the shell too. Not because it is sensitive — it is not —
  // but because "log out and hand the phone over" should leave nothing behind
  // that could be mistaken for a session.
  if (event.data === 'PURGE') {
    event.waitUntil(caches.keys().then((keys) => Promise.all(keys.map((k) => caches.delete(k)))));
  }
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);

  // Navigations: network first so a deployed fix reaches a driver the next time
  // they have a bar of signal, falling back to the cached document so the app
  // opens when they do not.
  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request)
        .then((response) => {
          const copy = response.clone();
          caches.open(SHELL).then((cache) => cache.put(DOCUMENT_URL, copy));
          return response;
        })
        .catch(() =>
          caches
            .match(DOCUMENT_URL)
            .then((cached) => cached ?? new Response('', { status: 504 })),
        ),
    );
    return;
  }

  if (!isImmutableAsset(url)) return; // API and everything else: untouched.

  event.respondWith(
    caches.match(request).then(
      (cached) =>
        cached ??
        fetch(request).then((response) => {
          if (response.ok && response.type === 'basic') {
            const copy = response.clone();
            caches.open(SHELL).then((cache) => cache.put(request, copy));
          }
          return response;
        }),
    ),
  );
});
