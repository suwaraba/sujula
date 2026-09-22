/*
 * A seller's shopfront.
 *
 * Still ranked and priced against the buyer's destination and the buyer's
 * currency: walking into one shop does not change where the parcel is going.
 */

import { api } from '../api.js';
import { withDelivery, shortPlaceLabel } from '../delivery.js';
import { productGrid } from '../components/productCard.js';
import { esc, icon, stars, skeletonGrid, errorState, emptyState } from '../ui.js';

export async function storeView({ params, outlet }) {
  outlet.innerHTML = `<div class="wrap"><div class="section">
    <div class="sk" style="height:120px;margin-bottom:16px"></div>${skeletonGrid(4)}</div></div>`;

  let store;
  try {
    store = await withDelivery(p => api.store(params.slug, p));
  } catch (error) {
    outlet.innerHTML = `<div class="wrap"><div class="section">${errorState(error)}</div></div>`;
    outlet.querySelector('[data-retry]').addEventListener('click', () => storeView({ params, outlet }));
    return;
  }

  const place = shortPlaceLabel();

  outlet.innerHTML = `
    <div class="wrap"><div class="section">
      <div class="card panel row" style="margin-bottom:16px">
        ${store.logoUrl
          ? `<img src="${esc(store.logoUrl)}" alt="" style="width:60px;height:60px;border-radius:14px;object-fit:cover">`
          : `<span style="color:var(--ink-3)">${icon('store', 34)}</span>`}
        <div class="grow">
          <h1 style="margin-bottom:2px">${esc(store.name)}</h1>
          <div class="row small muted">
            ${esc([store.city, store.countryCode].filter(Boolean).join(', '))}
            ${store.rating ? stars(store.rating) : ''}
            ${store.acceptingOrders === false ? '<span class="pill pill-no">Not taking orders</span>' : ''}
          </div>
        </div>
      </div>
      ${store.description ? `<p class="muted">${esc(store.description)}</p>` : ''}
      <div class="section-head"><h2>What they have${place ? ` for ${esc(place)}` : ''}</h2></div>
      <div data-products>${skeletonGrid(8)}</div>
    </div></div>`;

  const host = outlet.querySelector('[data-products]');
  try {
    const page = await withDelivery(p => api.storeProducts(params.slug, { ...p, page: 0, size: 24 }));
    host.innerHTML = (page.items || []).length
      ? productGrid(page.items)
      : emptyState('store', 'Nothing listed', 'This shop has nothing available right now.');
  } catch (error) {
    host.innerHTML = errorState(error);
  }
}
