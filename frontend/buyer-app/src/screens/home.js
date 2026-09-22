/*
 * The front page.
 *
 * Its whole job is to answer "what can I get to the person I am buying for?",
 * so the first heading names the destination and the first grid is the nearest
 * sellers to it. Until a destination is set the page says so rather than
 * quietly showing an arbitrary order and letting the buyer think it means
 * something.
 */

import { api } from '../api.js';
import { state } from '../state.js';
import { withDelivery, shortPlaceLabel, hasPlace } from '../delivery.js';
import { productGrid, rankingNotice } from '../components/productCard.js';
import { openDeliveryModal } from '../components/deliveryModal.js';
import { esc, icon, skeletonGrid, errorState, emptyState } from '../ui.js';
import { renderChrome } from '../components/chrome.js';

export async function homeView({ outlet }) {
  const place = shortPlaceLabel();

  outlet.innerHTML = `
    <div class="wrap">
      <section class="section" data-hero></section>
      <section class="section" data-categories></section>
      <section class="section">
        <div class="section-head">
          <h2 data-heading>${place ? 'Nearest to ' + esc(place) : 'In the shop'}</h2>
          <a class="link" href="#/browse">See all</a>
        </div>
        <div data-products>${skeletonGrid(8)}</div>
      </section>
    </div>`;

  renderHero(outlet.querySelector('[data-hero]'));
  loadCategories(outlet.querySelector('[data-categories]'));
  loadProducts(outlet);
}

function renderHero(host) {
  if (hasPlace()) {
    host.innerHTML = `<div class="card panel">
      <h1 style="margin-bottom:6px">Shop at home, from wherever you are</h1>
      <p class="muted" style="margin-bottom:12px">
        You are charged in ${esc(state.currencyCode || 'your currency')}. The seller is paid in theirs.
        Nobody is paid until the parcel is handed over.</p>
      <a class="btn btn-primary" href="#/browse">Start browsing</a>
    </div>`;
    return;
  }

  host.innerHTML = `<div class="card panel" style="background:var(--sun-100);border-color:transparent">
    <h1 style="margin-bottom:6px">Where is your parcel going?</h1>
    <p class="muted" style="margin-bottom:14px">
      Tell us the town and we will put the sellers closest to it first — it arrives
      sooner and costs less to send. You can be anywhere in the world yourself.</p>
    <button class="btn btn-accent" data-action="set-place" type="button">
      ${icon('pin', 18)} Set the delivery destination</button>
  </div>`;

  host.querySelector('[data-action="set-place"]').addEventListener('click', () => {
    openDeliveryModal({ onSaved: () => { renderChrome(); document.dispatchEvent(new CustomEvent('sujula:reload')); } });
  });
}

async function loadCategories(host) {
  try {
    const tree = await api.categories();
    const top = (tree.categories || []).slice(0, 10);
    if (!top.length) { host.remove(); return; }
    host.innerHTML = `
      <div class="section-head"><h2>Departments</h2></div>
      <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(130px,1fr));gap:10px">
        ${top.map(c => `
          <a class="card panel" style="text-decoration:none;color:inherit;padding:14px"
             href="#/c/${encodeURIComponent(c.slug)}">
            <strong class="small">${esc(c.name)}</strong>
            <span class="tiny muted" style="display:block">${c.productCount} item${c.productCount === 1 ? '' : 's'}</span>
          </a>`).join('')}
      </div>`;
  } catch {
    host.remove();     // a missing department list is not worth an error screen
  }
}

async function loadProducts(outlet) {
  const host = outlet.querySelector('[data-products]');
  try {
    const page = await withDelivery(params => api.products({ ...params, page: 0, size: 12 }));
    const items = page.items || [];
    host.innerHTML = items.length
      ? rankingNotice(page.delivery, shortPlaceLabel()) + productGrid(items)
      : emptyState('store', 'Nothing here yet',
          'No products are listed for this destination yet.',
          '<a class="btn btn-ghost" href="#/browse">Browse everything</a>');

    const change = host.querySelector('[data-action="change-place"]');
    if (change) change.addEventListener('click', () => openDeliveryModal({
      onSaved: () => { renderChrome(); document.dispatchEvent(new CustomEvent('sujula:reload')); }
    }));
  } catch (error) {
    host.innerHTML = errorState(error);
    host.querySelector('[data-retry]').addEventListener('click', () => loadProducts(outlet));
  }
}
