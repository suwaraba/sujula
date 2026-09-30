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
  recent: 'sujula.recentSearches',
  checkoutAttempt: 'sujula.checkoutAttempt'
};

// Cart quotes are held for fifteen minutes. The quote countdown itself is not
// persisted, so this matching fixed TTL is the conservative recovery window
// available after a reload. Stale attempts are discarded before they can be
// replayed.
const CHECKOUT_ATTEMPT_TTL_MS = 15 * 60 * 1000;

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
    return true;
  } catch {
    /* Storage full or blocked. The session still works; it just forgets. */
    return false;
  }
}

function validCheckoutAttempt(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  if (typeof value.quoteId !== 'string' || !value.quoteId || value.quoteId.length > 200) return false;
  if (typeof value.idempotencyKey !== 'string' || !value.idempotencyKey
      || value.idempotencyKey.length > 200) return false;
  if ((!Number.isSafeInteger(value.addressId) && typeof value.addressId !== 'string')
      || String(value.addressId).length > 40) return false;
  if (typeof value.paymentMethod !== 'string' || !value.paymentMethod
      || value.paymentMethod.length > 64) return false;
  if (value.notes !== null && (typeof value.notes !== 'string' || value.notes.length > 2000)) return false;
  if (typeof value.createdAt !== 'string' || value.createdAt.length > 40) return false;

  const createdAt = Date.parse(value.createdAt);
  const age = Date.now() - createdAt;
  return Number.isFinite(createdAt) && age >= 0 && age <= CHECKOUT_ATTEMPT_TTL_MS;
}

function checkoutAttemptRecord(value) {
  return {
    quoteId: value.quoteId,
    idempotencyKey: value.idempotencyKey,
    addressId: value.addressId,
    paymentMethod: value.paymentMethod,
    notes: value.notes,
    createdAt: value.createdAt
  };
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

/** The one replayable order-creation attempt, if it is well-formed and fresh. */
export function getCheckoutAttempt() {
  const attempt = read(KEYS.checkoutAttempt, null);
  if (!validCheckoutAttempt(attempt)) {
    write(KEYS.checkoutAttempt, null);
    return null;
  }
  return checkoutAttemptRecord(attempt);
}

/** Persist the exact checkout inputs before the request is allowed to leave. */
export function saveCheckoutAttempt(attempt) {
  if (!validCheckoutAttempt(attempt)) return null;
  const record = checkoutAttemptRecord(attempt);
  return write(KEYS.checkoutAttempt, record) ? record : null;
}

/** Avoid letting an old response clear a newer quote's attempt. */
export function clearCheckoutAttempt(expectedQuoteId) {
  const current = read(KEYS.checkoutAttempt, null);
  if (expectedQuoteId && current && current.quoteId !== expectedQuoteId) return false;
  return write(KEYS.checkoutAttempt, null);
}

export function rememberSearch(term) {
  const q = (term || '').trim();
  if (!q) return;
  state.recentSearches = [q, ...state.recentSearches.filter(s => s !== q)].slice(0, 8);
  write(KEYS.recent, state.recentSearches);
}
