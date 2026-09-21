/*
 * Starting the shop.
 *
 * The order of the first few milliseconds matters. Reference data first,
 * because nothing can be priced or labelled without it; then the payer's
 * currency, resolved from where the buyer is; then the chrome; then the route.
 * The destination question comes last and on top, because it is a question
 * about the parcel rather than about the page behind it, and the page behind
 * it should already be there when it is asked.
 */

import { api } from './api.js';
import { config } from './config.js';
import { state, setCurrency, restoreCurrency, on, signedIn } from './state.js';
import { route, start, refresh, parse } from './router.js';
import { startChrome, renderChrome } from './components/chrome.js';
import { openDeliveryModal, shouldAskOnEntry } from './components/deliveryModal.js';
import { syncCartContext, refreshCart } from './components/cart.js';
import { toast } from './ui.js';

import { homeView } from './views/home.js';
import { browseView, searchView, categoryView } from './views/browse.js';
import { productView } from './views/product.js';
import { storeView } from './views/store.js';
import { cartView } from './views/cartView.js';
import { checkoutView } from './views/checkout.js';
import { ordersView, orderView } from './views/orders.js';
import { trackView } from './views/track.js';
import { pickupPointsView } from './views/pickupPoints.js';
import { signInView, registerView, accountView } from './views/account.js';

/* ── Reference data and the payer's currency ─────────────────────────────── */

async function loadReferenceData() {
  const [publicConfig, currencies, countries] = await Promise.all([
    api.publicConfig().catch(() => null),
    api.currencies().catch(() => ({ currencies: [] })),
    api.countries().catch(() => ({ countries: [] }))
  ]);

  state.config = publicConfig;
  state.currencies = currencies.currencies || [];
  state.countries = countries.countries || [];
}

/**
 * Which currency the buyer sees, decided in this order:
 *
 *   1. what they chose by hand — never overridden;
 *   2. what their account says they prefer;
 *   3. where they are browsing from, by IP and browser;
 *   4. the platform's base currency.
 *
 * Note what is NOT in that list: the delivery country. A buyer in Madrid
 * sending to Serrekunda pays in euro. Pricing them in dalasi because the
 * parcel lands there would charge the wrong person in the wrong money.
 */
async function resolveCurrency() {
  const saved = restoreCurrency();
  if (saved && saved.pinned && supported(saved.code)) {
    state.currencyPinned = true;
    setCurrency(saved.code, { pinned: true });
    return;
  }

  const profile = state.auth && state.auth.user;
  if (profile && supported(profile.preferredCurrency)) {
    setCurrency(profile.preferredCurrency, { pinned: false });
    return;
  }

  try {
    const payer = await api.resolvePayerContext();
    state.payer = {
      countryCode: payer.countryCode || null,
      currency: payer.currency || null,
      language: payer.language || null,
      timezone: payer.timezone || null,
      source: payer.source || null
    };
    if (supported(payer.currency)) {
      setCurrency(payer.currency, { pinned: false });
      return;
    }
  } catch {
    /* No lookup available: the base currency below is a correct answer. */
  }

  const base = (state.config && state.config.baseCurrency)
    || (state.currencies[0] && state.currencies[0].code);
  if (base) setCurrency(base, { pinned: false });
}

function supported(code) {
  return Boolean(code) && state.currencies.some(c => c.code === code && c.buyerFacing);
}

/* ── Routes ───────────────────────────────────────────────────────────────── */

function registerRoutes() {
  route('/', homeView);
  route('/browse', browseView);
  route('/search', searchView);
  route('/c/:slug', categoryView);
  route('/p/:slug', productView);
  route('/store/:slug', storeView);
  route('/cart', cartView);
  route('/checkout', checkoutView);
  route('/orders', ordersView);
  route('/orders/:id', orderView);
  route('/track', trackView);
  route('/track/:code', trackView);
  route('/pickup-points', pickupPointsView);
  route('/signin', signInView);
  route('/register', registerView);
  route('/account', accountView);
}

/* ── Version gate ─────────────────────────────────────────────────────────── */

function checkMinimumVersion() {
  const minimums = state.config && state.config.minimumAppVersions;
  if (!minimums) return;
  const required = minimums[config.platform];
  if (!required) return;
  if (compareVersions(config.appVersion, required) < 0) {
    toast('There is a newer version of this app. Some things may not work until you update.', 'error');
  }
}

function compareVersions(a, b) {
  const left = String(a).split('.').map(Number);
  const right = String(b).split('.').map(Number);
  for (let i = 0; i < Math.max(left.length, right.length); i++) {
    const diff = (left[i] || 0) - (right[i] || 0);
    if (diff !== 0) return diff < 0 ? -1 : 1;
  }
  return 0;
}

/* ── Go ───────────────────────────────────────────────────────────────────── */

function askForDestination(path) {
  if (!shouldAskOnEntry(path)) return;
  openDeliveryModal({
    onSaved: () => { renderChrome(); refresh(); },
    onSkipped: () => renderChrome()
  });
}

async function boot() {
  await loadReferenceData();
  await resolveCurrency();

  startChrome();
  registerRoutes();
  start(document.getElementById('main'));
  checkMinimumVersion();

  // Either of these changes what the basket is priced against, so the basket
  // is told. Pricing is the server's, not ours — we only keep it pointed at
  // the right destination and the right currency.
  on('currency', () => { syncCartContext(); });
  on('place', () => { syncCartContext(); });

  document.addEventListener('sujula:reload', () => refresh());

  if (state.cartToken) refreshCart().catch(() => {});
  if (signedIn()) {
    api.me()
      .then(me => {
        if (me && me.profile) {
          state.auth.user = me.profile;
          if (!state.currencyPinned && supported(me.resolvedCurrency)) {
            setCurrency(me.resolvedCurrency, { pinned: false });
          }
          renderChrome();
        }
      })
      .catch(() => { /* an expired session is handled on the next call */ });
  }

  // Last, and over the top of a page that already works: the one question the
  // shop cannot rank anything without. Escapable — a shopper who wants to look
  // first may — but asked again on the next visit, because an unranked
  // catalogue is barely a catalogue here.
  //
  // Only on a shopping screen. Someone who followed a link to sign in, to
  // their orders or to a tracking page came for that, and covering it with a
  // question about a destination is how a popup stops being help.
  askForDestination(parse().path);

  // And again the first time they reach a shopping screen, if they arrived on
  // something else. Once they close or skip it, it stops for the visit — the
  // strip under the navbar carries the prompt from then on.
  document.addEventListener('sujula:navigated', event => {
    askForDestination(event.detail && event.detail.path);
  });

  state.booted = true;
}

boot().catch(error => {
  console.error('[sujula] boot', error);
  document.getElementById('main').innerHTML = `<div class="wrap"><div class="empty">
    <h2>The shop could not start</h2>
    <p class="muted">${error && error.message ? error.message : ''}</p>
    <button class="btn btn-primary" onclick="location.reload()">Try again</button>
  </div></div>`;
});
