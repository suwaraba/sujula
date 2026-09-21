/*
 * What the application knows, and what survives being closed.
 *
 * Two of these fields are the whole point of the marketplace and are kept
 * deliberately apart:
 *
 *   payer  — where the buyer is. Decides the currency they are charged in.
 *   place  — where the parcel is going. Decides what they are shown, what it
 *            costs to send, and whether it can be sent at all.
 *
 * A buyer in Madrid sending a phone to Serrekunda has both, and they are
 * different. Nothing in here may derive one from the other.
 */

const KEYS = {
  place: 'sujula.place',
  currency: 'sujula.currency',
  cart: 'sujula.cart',
  auth: 'sujula.auth',
  recent: 'sujula.recentSearches'
};

function read(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : fallback;
  } catch {
    return fallback;      // private mode, or a half-written value
  }
}

function write(key, value) {
  try {
    if (value === null || value === undefined) localStorage.removeItem(key);
    else localStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* Storage full or blocked. The session still works; it just forgets. */
  }
}

export const state = {
  booted: false,
  config: null,
  currencies: [],
  countries: [],

  /** The PAYER context: resolved from the browser and IP by /geo/resolve-context. */
  payer: { countryCode: null, currency: null, language: null, timezone: null, source: null },

  /** What the buyer is charged and quoted in. Theirs, not the seller's. */
  currencyCode: null,
  /** True once they have chosen it by hand, which stops us overriding it. */
  currencyPinned: false,

  /**
   * The DELIVERY context, kept as the inputs rather than as the server's id.
   *
   * The server's context expires in twelve hours. Storing the id alone would
   * mean asking the buyer where their parcel is going every single day, which
   * is exactly the interruption this screen exists to avoid — so the answer
   * lives here and the id is re-minted from it, silently, whenever it lapses.
   */
  place: read(KEYS.place, null),
  deliveryContextId: null,
  deliveryContextExpiresAt: null,

  cartToken: read(KEYS.cart, null),
  cart: null,

  auth: read(KEYS.auth, { accessToken: null, refreshToken: null, user: null }),

  recentSearches: read(KEYS.recent, [])
};

/* ── A very small event bus ───────────────────────────────────────────────── */

const listeners = new Map();

export function on(event, fn) {
  if (!listeners.has(event)) listeners.set(event, new Set());
  listeners.get(event).add(fn);
  return () => listeners.get(event).delete(fn);
}

export function emit(event, payload) {
  const set = listeners.get(event);
  if (set) set.forEach(fn => {
    try { fn(payload); } catch (e) { console.error('[sujula] listener for', event, e); }
  });
}

/* ── Writers ──────────────────────────────────────────────────────────────── */

export function setPlace(place) {
  state.place = place;
  // The id belongs to the old answer; a new destination needs a new context.
  state.deliveryContextId = null;
  state.deliveryContextExpiresAt = null;
  write(KEYS.place, place);
  emit('place', place);
}

export function setDeliveryContext(response) {
  state.deliveryContextId = response ? response.id : null;
  state.deliveryContextExpiresAt = response && response.expiresAt ? Date.parse(response.expiresAt + 'Z') : null;
}

export function setCurrency(code, { pinned = true } = {}) {
  if (!code || code === state.currencyCode) {
    if (pinned && code) state.currencyPinned = true;
    return;
  }
  state.currencyCode = code;
  if (pinned) state.currencyPinned = true;
  write(KEYS.currency, { code, pinned: state.currencyPinned });
  emit('currency', code);
}

export function restoreCurrency() {
  return read(KEYS.currency, null);
}

export function setCartToken(token) {
  state.cartToken = token;
  write(KEYS.cart, token);
}

export function setCart(cart) {
  state.cart = cart;
  if (cart && cart.token) setCartToken(cart.token);
  emit('cart', cart);
}

export function setAuth(auth) {
  state.auth = auth || { accessToken: null, refreshToken: null, user: null };
  write(KEYS.auth, state.auth.accessToken ? state.auth : null);
  emit('auth', state.auth);
}

export function signedIn() {
  return Boolean(state.auth && state.auth.accessToken);
}

export function rememberSearch(term) {
  const q = (term || '').trim();
  if (!q) return;
  state.recentSearches = [q, ...state.recentSearches.filter(s => s !== q)].slice(0, 8);
  write(KEYS.recent, state.recentSearches);
}
