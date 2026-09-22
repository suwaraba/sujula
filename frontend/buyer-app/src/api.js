/*
 * The one place that talks to the server.
 *
 * Everything else in the application calls a named function here, so that
 * retries, token refresh, idempotency keys and error shapes are decided once
 * rather than in twenty views.
 */

import { config } from './config.js';
import { state, setAuth, signedIn } from './state.js';

export class ApiError extends Error {
  constructor(status, message, fieldErrors) {
    super(message || 'Something went wrong');
    this.status = status;
    this.fieldErrors = fieldErrors || null;
  }
  get isAuth() { return this.status === 401 || this.status === 403; }
  get isNotFound() { return this.status === 404; }
  /** A delivery context that has lapsed, told apart from a missing product. */
  get isStaleDeliveryContext() {
    return this.status === 404 && /delivery context/i.test(this.message);
  }
}

function cookie(name) {
  const match = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
  return match ? decodeURIComponent(match[1]) : null;
}

export function idempotencyKey() {
  if (crypto && crypto.randomUUID) return crypto.randomUUID();
  return 'k-' + Date.now() + '-' + Math.random().toString(36).slice(2);
}

function queryString(params) {
  if (!params) return '';
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value === null || value === undefined || value === '' || value === false) return;
    search.append(key, value);
  });
  const out = search.toString();
  return out ? '?' + out : '';
}

let refreshing = null;

async function refreshTokens() {
  if (!state.auth || !state.auth.refreshToken) return false;
  if (!refreshing) {
    refreshing = (async () => {
      try {
        const res = await fetch(config.apiBase + '/auth/refresh', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken: state.auth.refreshToken })
        });
        if (!res.ok) { setAuth(null); return false; }
        const tokens = await res.json();
        setAuth({
          accessToken: tokens.accessToken,
          refreshToken: tokens.refreshToken || state.auth.refreshToken,
          user: tokens.user || state.auth.user
        });
        return true;
      } catch {
        return false;      // offline: keep the tokens, the next call can retry
      } finally {
        refreshing = null;
      }
    })();
  }
  return refreshing;
}

async function request(method, path, { body, query, idempotent, retryOn401 = true } = {}) {
  const headers = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (signedIn()) headers.Authorization = 'Bearer ' + state.auth.accessToken;
  if (idempotent) headers['Idempotency-Key'] = idempotent;

  // Cookie-delivered CSRF token, echoed back in the header the server reads.
  // Harmless on the endpoints that do not require one.
  if (method !== 'GET' && method !== 'HEAD') {
    const token = cookie('XSRF-TOKEN');
    if (token) headers['X-XSRF-TOKEN'] = token;
  }

  let res;
  try {
    res = await fetch(config.apiBase + path + queryString(query), {
      method,
      headers,
      credentials: 'include',
      body: body === undefined ? undefined : JSON.stringify(body)
    });
  } catch {
    throw new ApiError(0, 'No connection. Check your network and try again.');
  }

  if (res.status === 401 && retryOn401 && state.auth && state.auth.refreshToken) {
    if (await refreshTokens()) {
      return request(method, path, { body, query, idempotent, retryOn401: false });
    }
  }

  if (res.status === 204) return null;

  const text = await res.text();
  let payload = null;
  if (text) {
    try { payload = JSON.parse(text); } catch { payload = { message: text }; }
  }

  if (!res.ok) {
    const message = (payload && (payload.message || payload.error)) || res.statusText;
    throw new ApiError(res.status, message, payload && payload.errors);
  }
  return payload;
}

const get = (path, query) => request('GET', path, { query });
const post = (path, body, opts) => request('POST', path, { body, ...(opts || {}) });
const put = (path, body) => request('PUT', path, { body });
const patch = (path, body) => request('PATCH', path, { body });
const del = path => request('DELETE', path);

export const api = {
  /* ── What this deployment is ──────────────────────────────────────────── */
  publicConfig: () => get('/config/public'),
  currencies: () => get('/currencies'),
  countries: () => get('/countries'),

  /* ── The payer's side: who is paying, and from where ──────────────────── */
  resolvePayerContext: () => get('/geo/resolve-context'),

  /* ── The delivery side: where the goods go ────────────────────────────── */
  validateAddress: body => post('/geo/validate-address', body),
  reverseGeocode: body => post('/geo/reverse', body),
  createDeliveryContext: body => post('/delivery-contexts', body),
  readDeliveryContext: id => get('/delivery-contexts/' + encodeURIComponent(id)),
  pickupPoints: query => get('/pickup-points', query),
  pickupPoint: id => get('/pickup-points/' + id),

  /* ── Catalogue ────────────────────────────────────────────────────────── */
  categories: query => get('/categories', query),
  category: (slug, query) => get('/categories/' + encodeURIComponent(slug), query),
  products: query => get('/products', query),
  product: (slug, query) => get('/products/' + encodeURIComponent(slug), query),
  related: (productId, query) => get('/products/' + productId + '/related', query),
  reviews: (productId, query) => get('/products/' + productId + '/reviews', query),
  search: query => get('/search', query),
  suggest: query => get('/search/suggest', query),
  store: (slug, query) => get('/stores/' + encodeURIComponent(slug), query),
  storeProducts: (slug, query) => get('/stores/' + encodeURIComponent(slug) + '/products', query),

  /* ── Basket ───────────────────────────────────────────────────────────── */
  createCart: body => post('/carts', body || {}),
  readCart: token => get('/carts/' + encodeURIComponent(token)),
  addItem: (token, body, key) =>
    post('/carts/' + encodeURIComponent(token) + '/items', body, { idempotent: key }),
  setQuantity: (token, itemId, quantity) =>
    patch('/carts/' + encodeURIComponent(token) + '/items/' + itemId, { quantity }),
  removeItem: (token, itemId) =>
    del('/carts/' + encodeURIComponent(token) + '/items/' + itemId),
  mergeCart: (token, sourceCartToken) =>
    post('/carts/' + encodeURIComponent(token) + '/merge', { sourceCartToken }),
  setCartDeliveryContext: (token, deliveryContextId) =>
    put('/carts/' + encodeURIComponent(token) + '/delivery-context', { deliveryContextId }),
  setCartCurrency: (token, currency) =>
    put('/carts/' + encodeURIComponent(token) + '/currency', { currency }),
  applyCoupon: (token, code) => post('/carts/' + encodeURIComponent(token) + '/coupons', { code }),
  removeCoupon: (token, code) =>
    del('/carts/' + encodeURIComponent(token) + '/coupons/' + encodeURIComponent(code)),
  quoteCart: (token, body) => post('/carts/' + encodeURIComponent(token) + '/quote', body || {}),

  /* ── Paying ───────────────────────────────────────────────────────────── */
  checkout: (body, key) => post('/checkout', body, { idempotent: key }),
  checkoutStatus: orderId => get('/checkout/' + orderId + '/status'),
  retryPayment: (orderId, paymentMethod) =>
    post('/checkout/' + orderId + '/retry-payment', { paymentMethod }),

  /* ── Afterwards ───────────────────────────────────────────────────────── */
  orders: query => get('/orders', query),
  order: orderId => get('/orders/' + orderId),
  orderTracking: orderId => get('/orders/' + orderId + '/tracking'),
  cancelOrder: (orderId, body) => post('/orders/' + orderId + '/cancel', body || {}),
  cancelVendorOrder: (orderId, vendorOrderId, body) =>
    post('/orders/' + orderId + '/vendor-orders/' + vendorOrderId + '/cancel', body || {}),
  confirmReceipt: (orderId, vendorOrderId, body) =>
    post('/orders/' + orderId + '/vendor-orders/' + vendorOrderId + '/confirm-receipt', body || {}),
  track: code => get('/track/' + encodeURIComponent(code)),

  /* ── Identity ─────────────────────────────────────────────────────────── */
  register: body => post('/auth/register', body),
  login: body => post('/auth/login', body),
  logout: refreshToken => post('/auth/logout', { refreshToken }),
  me: () => get('/me'),
  addresses: () => get('/me/addresses'),
  createAddress: body => post('/me/addresses', body),
  confirmAddressPin: (addressId, body) => post('/me/addresses/' + addressId + '/confirm-pin', body),
  forgotPassword: email => post('/auth/password/forgot', { email })
};
