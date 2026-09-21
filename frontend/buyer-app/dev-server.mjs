/*
 * The dev server, in place of Vite.
 *
 * This client has no build step, so there is nothing to bundle and nothing to
 * transform — but it still needs the one thing Vite was giving the others: the
 * API on the same origin, so the bearer token, the CSRF cookie and the session
 * all behave in development exactly as they do in production.
 *
 *     node dev-server.mjs           →  http://localhost:5177
 *
 * The proxy list below is the list of prefixes THIS client calls, not a list of
 * the API's prefixes. That distinction is the one the clients index warns about:
 * proxy something this client has a screen at and the deep link goes to Spring,
 * which answers 401 instead of opening the app. It is safe here because every
 * screen is in the fragment — but the list stays narrow anyway, because the
 * next person to add a path route should find it already correct.
 */

import { createServer } from 'node:http';
import { request as httpRequest } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize, resolve } from 'node:path';

const PORT = Number(process.env.PORT || 5177);
const API = process.env.VITE_DEV_API_TARGET || process.env.API_TARGET || 'http://localhost:8080';
const ROOT = resolve(import.meta.dirname);

/** Every prefix this client calls. Kept in the same order as src/api.js. */
const API_PREFIXES = [
  '/config', '/currencies', '/countries',
  '/geo', '/delivery-contexts', '/delivery', '/pickup-points',
  '/categories', '/products', '/stores', '/brands', '/search',
  '/carts', '/checkout',
  '/orders', '/track', '/invoices',
  '/auth', '/me'
];

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon'
};

const target = new URL(API);

function isApi(pathname) {
  return API_PREFIXES.some(prefix => pathname === prefix || pathname.startsWith(prefix + '/'));
}

function proxy(req, res) {
  const upstream = httpRequest({
    host: target.hostname,
    port: target.port || 80,
    path: req.url,
    method: req.method,
    headers: { ...req.headers, host: target.host }
  }, answer => {
    res.writeHead(answer.statusCode, answer.headers);
    answer.pipe(res);
  });

  upstream.on('error', error => {
    res.writeHead(502, { 'content-type': 'application/json' });
    res.end(JSON.stringify({
      message: `The API at ${API} did not answer (${error.code}). Is it running?`
    }));
  });

  req.pipe(upstream);
}

async function serveFile(res, pathname) {
  // normalize() before join() so ../ in a request cannot walk out of ROOT.
  const file = join(ROOT, normalize(pathname).replace(/^(\.\.[/\\])+/, ''));
  if (!file.startsWith(ROOT)) { res.writeHead(403).end(); return; }

  try {
    const body = await readFile(file);
    res.writeHead(200, {
      'content-type': TYPES[extname(file)] || 'application/octet-stream',
      'cache-control': 'no-store'
    });
    res.end(body);
  } catch {
    // Everything unknown is the shell. Routes are in the fragment, so this
    // only catches a mistyped asset — but it is also what the production
    // reverse proxy does, and development should not differ.
    const shell = await readFile(join(ROOT, 'index.html'));
    res.writeHead(pathname === '/' ? 200 : 404, { 'content-type': TYPES['.html'] });
    res.end(shell);
  }
}

createServer((req, res) => {
  const pathname = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
  if (isApi(pathname)) proxy(req, res);
  else serveFile(res, pathname === '/' ? '/index.html' : pathname);
}).listen(PORT, () => {
  console.log(`Sujula buyer  →  http://localhost:${PORT}`);
  console.log(`API           →  ${API}`);
});
