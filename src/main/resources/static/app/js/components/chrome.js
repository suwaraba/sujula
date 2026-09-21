/*
 * The two bars, the destination strip, the phone's tab bar and the footer.
 *
 * Bar one is contact: WhatsApp first, because on this marketplace the buyer is
 * abroad, the recipient has no account, and the thing that rescues a delivery
 * is someone answering a message. Bar two is the shop: the search field and
 * the icons. Between them sits the destination, which belongs to neither and
 * is too important to bury in either.
 *
 * On a phone the icon row is duplicated as a fixed tab bar at the bottom —
 * which is also what makes the wrapped Android and iOS builds feel like
 * applications rather than like a website in a box.
 */

import { api } from '../api.js';
import { state, on, setCurrency, signedIn } from '../state.js';
import { here } from '../router.js';
import { placeLabel, shortPlaceLabel } from '../delivery.js';
import { esc, icon, modal, toast } from '../ui.js';
import { openDeliveryModal } from './deliveryModal.js';

function whatsappHref(number) {
  const digits = String(number || '').replace(/[^0-9]/g, '');
  return digits ? 'https://wa.me/' + digits : null;
}

/* ── Bar one ──────────────────────────────────────────────────────────────── */

function renderContactBar() {
  const host = document.getElementById('contact-bar');
  const support = (state.config && state.config.support) || {};
  const whatsapp = whatsappHref(support.whatsapp);

  host.innerHTML = `<div class="wrap">
    ${whatsapp ? `<a class="contact-link whatsapp" href="${esc(whatsapp)}" target="_blank" rel="noopener">
        ${icon('whatsapp', 16)}<span>WhatsApp ${esc(support.whatsapp)}</span></a>` : ''}
    ${support.phone ? `<a class="contact-link" href="tel:${esc(support.phone.replace(/\s/g, ''))}">
        ${icon('phone', 15)}<span class="nowrap">${esc(support.phone)}</span></a>` : ''}
    ${support.email ? `<a class="contact-link" href="mailto:${esc(support.email)}">
        ${icon('mail', 15)}<span>${esc(support.email)}</span></a>` : ''}
    <span class="contact-sep"></span>
    <button class="chip-button" data-action="currency" type="button"
            title="The currency you are charged in">
      ${icon('globe', 14)}<span class="chip-label">${esc(state.currencyCode || '—')}</span>
    </button>
  </div>`;

  host.querySelector('[data-action="currency"]').addEventListener('click', openCurrencyPicker);
}

/* ── Bar two ──────────────────────────────────────────────────────────────── */

function renderMainBar() {
  const host = document.getElementById('main-bar');
  const count = state.cart && state.cart.itemCount ? state.cart.itemCount : 0;

  host.innerHTML = `<div class="wrap">
    <a class="brand" href="#/"><span class="mark">S</span>Sujula</a>

    <div class="searchbar">
      <form role="search" autocomplete="off">
        <input type="search" name="q" enterkeyhint="search"
               placeholder="Search phones, cloth, anything…"
               aria-label="Search the shop">
        <button class="search-go" type="submit" aria-label="Search">${icon('search', 18)}</button>
      </form>
      <div class="suggestions hidden" role="listbox"></div>
    </div>

    <div class="iconbar">
      <a class="icon-button" href="#/orders">${icon('box')}<span class="icon-label">Orders</span></a>
      <a class="icon-button" href="#/account">${icon('user')}<span class="icon-label">${signedIn() ? 'Account' : 'Sign in'}</span></a>
      <a class="icon-button" href="#/cart">
        ${icon('cart')}<span class="icon-label">Basket</span>
        ${count ? `<span class="badge">${count > 99 ? '99+' : count}</span>` : ''}
      </a>
    </div>
  </div>`;

  wireSearch(host);
}

function wireSearch(host) {
  const form = host.querySelector('form[role="search"]');
  const input = form.querySelector('input[type="search"]');
  const panel = host.querySelector('.suggestions');
  let timer = null;
  let sequence = 0;

  form.addEventListener('submit', event => {
    event.preventDefault();
    const q = input.value.trim();
    if (!q) return;
    panel.classList.add('hidden');
    input.blur();
    location.hash = '#/search?q=' + encodeURIComponent(q);
  });

  input.addEventListener('input', () => {
    const q = input.value.trim();
    clearTimeout(timer);
    if (q.length < 2) { panel.classList.add('hidden'); return; }
    timer = setTimeout(async () => {
      const mine = ++sequence;
      try {
        const params = { q, limit: 8 };
        if (state.deliveryContextId) params.deliverableTo = state.deliveryContextId;
        else if (state.place && state.place.countryCode) params.deliveryCountry = state.place.countryCode;
        const found = await api.suggest(params);
        if (mine !== sequence) return;             // a later keystroke won
        const items = (found && found.suggestions) || [];
        if (!items.length) { panel.classList.add('hidden'); return; }
        panel.innerHTML = items
          .map(s => `<button type="button" role="option">${esc(s)}</button>`).join('');
        panel.querySelectorAll('button').forEach(button => {
          button.addEventListener('click', () => {
            input.value = button.textContent;
            panel.classList.add('hidden');
            location.hash = '#/search?q=' + encodeURIComponent(button.textContent);
          });
        });
        panel.classList.remove('hidden');
      } catch {
        panel.classList.add('hidden');            // typeahead never interrupts
      }
    }, 220);
  });

  input.addEventListener('blur', () => setTimeout(() => panel.classList.add('hidden'), 160));
  input.addEventListener('keydown', event => {
    if (event.key === 'Escape') panel.classList.add('hidden');
  });
}

/* ── The destination ──────────────────────────────────────────────────────── */

function renderDeliveryStrip() {
  const host = document.getElementById('delivery-strip');
  const label = placeLabel();

  host.innerHTML = label
    ? `<div class="delivery-strip"><div class="wrap">
         ${icon('pin', 16)}
         <span class="truncate"><span class="muted">Delivering to</span> <strong>${esc(label)}</strong></span>
         <button class="link" data-action="change" type="button">Change</button>
       </div></div>`
    : `<div class="delivery-strip unset"><div class="wrap">
         ${icon('pin', 16)}
         <span class="truncate"><strong>Where is it going?</strong>
           <span class="muted">Sellers are ranked by distance to the delivery address.</span></span>
         <button class="link" data-action="change" type="button">Set destination</button>
       </div></div>`;

  host.querySelector('[data-action="change"]').addEventListener('click', () => {
    openDeliveryModal({ onSaved: () => { renderChrome(); reloadRoute(); } });
  });
}

function reloadRoute() {
  document.dispatchEvent(new CustomEvent('sujula:reload'));
}

/* ── The phone's tab bar ──────────────────────────────────────────────────── */

const TABS = [
  { href: '#/', label: 'Home', icon: 'home', match: p => p === '/' },
  { href: '#/browse', label: 'Browse', icon: 'grid', match: p => p.startsWith('/browse') || p.startsWith('/search') || p.startsWith('/c/') },
  { href: '#/cart', label: 'Basket', icon: 'cart', match: p => p.startsWith('/cart') || p.startsWith('/checkout'), badge: true },
  { href: '#/orders', label: 'Orders', icon: 'box', match: p => p.startsWith('/orders') || p.startsWith('/track') },
  { href: '#/account', label: 'Account', icon: 'user', match: p => p.startsWith('/account') || p.startsWith('/signin') || p.startsWith('/register') }
];

function renderTabBar() {
  const host = document.getElementById('tab-bar');
  const path = here().replace(/^#/, '').split('?')[0] || '/';
  const count = state.cart && state.cart.itemCount ? state.cart.itemCount : 0;

  host.innerHTML = TABS.map(tab => `
    <a href="${tab.href}"${tab.match(path) ? ' aria-current="page"' : ''}>
      <span style="position:relative;display:inline-flex">
        ${icon(tab.icon)}
        ${tab.badge && count ? `<span class="badge" style="top:-4px;right:-10px">${count > 99 ? '99+' : count}</span>` : ''}
      </span>
      <span>${esc(tab.label)}</span>
    </a>`).join('');
}

/* ── Footer ───────────────────────────────────────────────────────────────── */

function renderFooter() {
  const host = document.getElementById('site-footer');
  const support = (state.config && state.config.support) || {};
  const whatsapp = whatsappHref(support.whatsapp);
  const place = shortPlaceLabel();

  host.innerHTML = `<div class="wrap">
    <div class="cols">
      <div>
        <h3>Sujula</h3>
        <p class="small muted">Buy from sellers at home. Pay from where you are.
          Delivered to whoever it is for — and the seller is not paid until it arrives.</p>
      </div>
      <div>
        <h3>Help</h3>
        ${whatsapp ? `<a href="${esc(whatsapp)}" target="_blank" rel="noopener">WhatsApp us</a>` : ''}
        ${support.phone ? `<a href="tel:${esc(support.phone.replace(/\s/g, ''))}">Call ${esc(support.phone)}</a>` : ''}
        ${support.email ? `<a href="mailto:${esc(support.email)}">${esc(support.email)}</a>` : ''}
        <a href="#/track">Track a parcel</a>
      </div>
      <div>
        <h3>Shopping</h3>
        <a href="#/browse">All products</a>
        <a href="#/pickup-points">Collection points</a>
        <a href="#/orders">My orders</a>
      </div>
      <div>
        <h3>Terms</h3>
        ${support.termsUrl ? `<a href="${esc(support.termsUrl)}" target="_blank" rel="noopener">Terms of sale</a>` : ''}
        ${support.privacyUrl ? `<a href="${esc(support.privacyUrl)}" target="_blank" rel="noopener">Privacy</a>` : ''}
        <p class="tiny muted" style="margin-top:8px">
          Prices in ${esc(state.currencyCode || '')}${place ? ', delivering to ' + esc(place) : ''}.
        </p>
      </div>
    </div>
  </div>`;
}

/* ── The currency picker ──────────────────────────────────────────────────── */

export function openCurrencyPicker() {
  const offered = state.currencies.filter(c => c.buyerFacing);
  const detected = state.payer.currency;

  const dialog = modal({
    title: 'What you pay in',
    subtitle: 'Your currency, not the seller’s. Sellers are always paid in theirs.',
    body: `
      <div class="notice" style="margin-bottom:12px">
        ${detected
          ? `We picked <strong>${esc(detected)}</strong> from where you are browsing${state.payer.countryCode ? ' (' + esc(state.payer.countryCode) + ')' : ''}. Change it if that is wrong.`
          : 'Pick the currency you want to be charged in.'}
      </div>
      <div class="choice-list">
        ${offered.map(c => `
          <label class="choice" data-selected="${c.code === state.currencyCode}">
            <input type="radio" name="currency" value="${esc(c.code)}"${c.code === state.currencyCode ? ' checked' : ''}>
            <span class="grow">
              <span class="choice-title">${esc(c.code)} — ${esc(c.name)}</span>
              <span class="small muted" style="display:block">
                ${esc(c.symbol || c.code)}${c.minorUnits === 0 ? ' · no decimals' : ''}
                ${c.code === detected ? ' · where you are' : ''}
              </span>
            </span>
          </label>`).join('')}
      </div>`,
    footer: `<button class="btn btn-primary" data-action="use">Use this currency</button>`
  });

  dialog.node.querySelector('[data-action="use"]').addEventListener('click', () => {
    const chosen = dialog.node.querySelector('input[name="currency"]:checked');
    if (!chosen) { dialog.close(); return; }
    setCurrency(chosen.value, { pinned: true });
    dialog.close();
    toast('Prices now shown in ' + chosen.value, 'ok');
    renderChrome();
    reloadRoute();
  });

  return dialog;
}

/* ── Wiring ───────────────────────────────────────────────────────────────── */

export function renderChrome() {
  renderContactBar();
  renderMainBar();
  renderDeliveryStrip();
  renderTabBar();
  renderFooter();
}

export function startChrome() {
  renderChrome();
  on('cart', () => { renderMainBar(); renderTabBar(); });
  on('auth', () => { renderMainBar(); });
  on('place', () => { renderDeliveryStrip(); renderFooter(); });
  on('currency', () => { renderContactBar(); renderFooter(); });
  document.addEventListener('sujula:navigated', renderTabBar);
}
