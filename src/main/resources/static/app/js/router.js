/*
 * Routing in the fragment, not the path.
 *
 * Three reasons, in order of how much they matter:
 *
 *  1. The Android and iOS wrappers load this shell from the local bundle.
 *     There is no server there to answer a deep link with index.html, so a
 *     path-routed application shows a blank screen on every route but the
 *     first. A fragment never reaches a server at all.
 *  2. The API owns the bare paths on this origin (/products, /orders, /search).
 *     A catch-all at the root would sit in front of them.
 *  3. Back and forward keep working, for free, on both.
 */

const routes = [];
let outlet = null;
let current = null;

export function route(pattern, handler) {
  const names = [];
  const regex = new RegExp('^' + pattern
    .replace(/\//g, '\\/')
    .replace(/:(\w+)/g, (_, name) => { names.push(name); return '([^\\/]+)'; }) + '$');
  routes.push({ regex, names, handler });
}

export function parse(hash) {
  const raw = (hash || location.hash || '#/').replace(/^#/, '') || '/';
  const cut = raw.indexOf('?');
  const path = cut === -1 ? raw : raw.slice(0, cut);
  const query = {};
  if (cut !== -1) {
    new URLSearchParams(raw.slice(cut + 1)).forEach((value, key) => { query[key] = value; });
  }
  return { path: path || '/', query };
}

export function go(to, { replace = false } = {}) {
  const target = to.startsWith('#') ? to : '#' + to;
  if (replace) location.replace(target);
  else location.hash = target;
}

export function href(to) {
  return to.startsWith('#') ? to : '#' + to;
}

/** Where we are now, for the tab bar and the "sign in and come back" flow. */
export function here() {
  return location.hash || '#/';
}

export function query(params) {
  const search = new URLSearchParams();
  Object.entries(params || {}).forEach(([key, value]) => {
    if (value === null || value === undefined || value === '') return;
    search.append(key, value);
  });
  const out = search.toString();
  return out ? '?' + out : '';
}

export function start(outletElement) {
  outlet = outletElement;
  window.addEventListener('hashchange', render);
  render();
}

export function refresh() {
  render();
}

async function render() {
  const { path, query: q } = parse();
  const match = routes
    .map(r => ({ r, m: r.regex.exec(path) }))
    .find(x => x.m);

  // A route change is a new page: start at the top, as a page load would.
  if (current !== path) {
    window.scrollTo({ top: 0, behavior: 'instant' in document.documentElement.style ? 'instant' : 'auto' });
  }
  current = path;

  if (!match) {
    outlet.innerHTML = `<div class="wrap"><div class="empty">
      <h2>That page is not here</h2>
      <p class="muted">The link may be old.</p>
      <a class="btn btn-primary" href="#/">Back to the shop</a></div></div>`;
    return;
  }

  const params = {};
  match.r.names.forEach((name, i) => { params[name] = decodeURIComponent(match.m[i + 1]); });

  try {
    await match.r.handler({ params, query: q, outlet });
  } catch (error) {
    console.error('[sujula] route', path, error);
    outlet.innerHTML = `<div class="wrap"><div class="empty">
      <h2>That did not load</h2>
      <p class="muted">${(error && error.message) || ''}</p>
      <button class="btn btn-ghost" onclick="location.reload()">Reload</button></div></div>`;
  }

  document.dispatchEvent(new CustomEvent('sujula:navigated', { detail: { path } }));
}
