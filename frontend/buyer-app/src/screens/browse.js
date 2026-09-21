/*
 * The catalogue: browsing, searching and one department, which are the same
 * screen with a different starting filter.
 *
 * Facets come back counted against the current filter minus their own
 * dimension, so widening a search never needs it cleared first. Everything is
 * held in the URL fragment, which is what makes a filtered page shareable — a
 * buyer in Madrid sends their sister a link and she sees the same six shops.
 */

import { api } from '../api.js';
import { state, rememberSearch } from '../state.js';
import { withDelivery, shortPlaceLabel } from '../delivery.js';
import { productGrid, rankingNotice } from '../components/productCard.js';
import { openDeliveryModal } from '../components/deliveryModal.js';
import { renderChrome } from '../components/chrome.js';
import { go, query as buildQuery } from '../router.js';
import { esc, icon, modal, skeletonGrid, errorState, emptyState, titleCase } from '../ui.js';
import { money } from '../money.js';

const CONDITIONS = ['NEW', 'REFURBISHED', 'USED_LIKE_NEW', 'USED_GOOD', 'USED_FAIR'];

export async function browseView(context) {
  return render(context, {});
}

export async function searchView(context) {
  if (context.query.q) rememberSearch(context.query.q);
  return render(context, { searching: true });
}

export async function categoryView(context) {
  return render(context, { category: context.params.slug });
}

async function render({ query, outlet }, mode) {
  const filter = {
    q: query.q || '',
    category: mode.category || query.category || '',
    brand: query.brand || '',
    store: query.store || '',
    condition: query.condition || '',
    minPrice: query.minPrice || '',
    maxPrice: query.maxPrice || '',
    inStockOnly: query.inStockOnly === 'true',
    page: Number(query.page || 0)
  };

  const place = shortPlaceLabel();
  const heading = mode.searching && filter.q ? `“${filter.q}”`
    : filter.category ? titleCase(filter.category.replace(/-/g, ' '))
    : 'Everything in the shop';

  outlet.innerHTML = `
    <div class="wrap">
      <div class="section">
        <div class="section-head">
          <div>
            <h1 style="margin-bottom:2px">${esc(heading)}</h1>
            <p class="small muted" data-count>${place ? 'Nearest to ' + esc(place) + ' first' : ''}</p>
          </div>
          <button class="btn btn-ghost btn-sm" data-action="filters" type="button">
            ${icon('filter', 15)} Filter</button>
        </div>
        <div data-active-filters class="row" style="flex-wrap:wrap;margin-bottom:12px"></div>
        <div data-results>${skeletonGrid(8)}</div>
      </div>
    </div>`;

  outlet.querySelector('[data-action="filters"]')
    .addEventListener('click', () => openFilters(filter, mode));

  load(outlet, filter, mode);
}

function currentHash(filter, mode, overrides) {
  const merged = { ...filter, ...(overrides || {}) };
  const base = mode.category ? '/c/' + encodeURIComponent(mode.category)
    : mode.searching ? '/search' : '/browse';
  const params = {
    q: merged.q || '',
    brand: merged.brand,
    store: merged.store,
    condition: merged.condition,
    minPrice: merged.minPrice,
    maxPrice: merged.maxPrice,
    inStockOnly: merged.inStockOnly ? 'true' : '',
    page: merged.page ? String(merged.page) : ''
  };
  if (!mode.category && merged.category) params.category = merged.category;
  return base + buildQuery(params);
}

async function load(outlet, filter, mode) {
  const host = outlet.querySelector('[data-results]');
  const countLabel = outlet.querySelector('[data-count]');

  try {
    const call = params => {
      const request = {
        ...params,
        category: filter.category || undefined,
        brand: filter.brand || undefined,
        store: filter.store || undefined,
        condition: filter.condition || undefined,
        minPrice: filter.minPrice || undefined,
        maxPrice: filter.maxPrice || undefined,
        inStockOnly: filter.inStockOnly || undefined,
        page: filter.page,
        size: 20
      };
      // /search demands a term; /products takes one or none.
      return mode.searching && filter.q
        ? api.search({ ...request, q: filter.q })
        : api.products({ ...request, q: filter.q || undefined });
    };

    const page = await withDelivery(call);
    const items = page.items || [];

    const place = shortPlaceLabel();
    countLabel.textContent = `${page.totalElements} ${page.totalElements === 1 ? 'result' : 'results'}`
      + (page.delivery && page.delivery.rankedByProximity && place ? ` · nearest to ${place} first` : '');

    renderActiveFilters(outlet, filter, mode);

    host.innerHTML = items.length
      ? rankingNotice(page.delivery, shortPlaceLabel()) + productGrid(items) + pager(page)
      : emptyState('search', 'Nothing matched',
          filter.q ? `No products for “${filter.q}” right now.` : 'No products match these filters.',
          `<button class="btn btn-ghost" data-action="clear" type="button">Clear the filters</button>`);

    wire(outlet, host, filter, mode);
  } catch (error) {
    host.innerHTML = errorState(error);
    host.querySelector('[data-retry]').addEventListener('click', () => load(outlet, filter, mode));
  }
}

function pager(page) {
  if (page.totalPages <= 1) return '';
  const back = page.page > 0;
  const forward = page.page + 1 < page.totalPages;
  return `<div class="row" style="justify-content:center;gap:12px;margin-top:20px">
    <button class="btn btn-ghost btn-sm" data-page="${page.page - 1}" ${back ? '' : 'disabled'}>Previous</button>
    <span class="small muted">Page ${page.page + 1} of ${page.totalPages}</span>
    <button class="btn btn-ghost btn-sm" data-page="${page.page + 1}" ${forward ? '' : 'disabled'}>Next</button>
  </div>`;
}

function renderActiveFilters(outlet, filter, mode) {
  const host = outlet.querySelector('[data-active-filters]');
  const chips = [];
  if (filter.brand) chips.push(['brand', 'Brand: ' + filter.brand]);
  if (filter.store) chips.push(['store', 'Shop: ' + filter.store]);
  if (filter.condition) chips.push(['condition', titleCase(filter.condition)]);
  if (filter.minPrice) chips.push(['minPrice', 'From ' + money(filter.minPrice)]);
  if (filter.maxPrice) chips.push(['maxPrice', 'Up to ' + money(filter.maxPrice)]);
  if (filter.inStockOnly) chips.push(['inStockOnly', 'In stock only']);

  host.innerHTML = chips.map(([key, label]) =>
    `<button class="pill pill-sun" data-drop="${key}" type="button" style="border:none">
      ${esc(label)} ${icon('close', 11)}</button>`).join('');

  host.querySelectorAll('[data-drop]').forEach(button => {
    button.addEventListener('click', () => {
      const key = button.dataset.drop;
      go(currentHash(filter, mode, { [key]: key === 'inStockOnly' ? false : '', page: 0 }));
    });
  });
}

function wire(outlet, host, filter, mode) {
  host.querySelectorAll('[data-page]').forEach(button => {
    button.addEventListener('click', () => go(currentHash(filter, mode, { page: Number(button.dataset.page) })));
  });
  const clear = host.querySelector('[data-action="clear"]');
  if (clear) clear.addEventListener('click', () => go(currentHash(
    { q: filter.q, category: filter.category }, mode, { page: 0 })));
  const change = host.querySelector('[data-action="change-place"]');
  if (change) change.addEventListener('click', () => openDeliveryModal({
    onSaved: () => { renderChrome(); document.dispatchEvent(new CustomEvent('sujula:reload')); }
  }));
}

function openFilters(filter, mode) {
  const dialog = modal({
    title: 'Narrow it down',
    body: `
      <label class="field">
        <span class="label">Condition</span>
        <select class="input" data-f="condition">
          <option value="">Any condition</option>
          ${CONDITIONS.map(c => `<option value="${c}"${filter.condition === c ? ' selected' : ''}>${esc(titleCase(c))}</option>`).join('')}
        </select>
      </label>
      <div class="row" style="gap:10px">
        <label class="field grow">
          <span class="label">Least (${esc(state.currencyCode || '')})</span>
          <input class="input" type="number" inputmode="decimal" min="0" data-f="minPrice" value="${esc(filter.minPrice)}">
        </label>
        <label class="field grow">
          <span class="label">Most (${esc(state.currencyCode || '')})</span>
          <input class="input" type="number" inputmode="decimal" min="0" data-f="maxPrice" value="${esc(filter.maxPrice)}">
        </label>
      </div>
      <label class="choice" data-selected="${filter.inStockOnly}">
        <input type="checkbox" data-f="inStockOnly"${filter.inStockOnly ? ' checked' : ''}>
        <span class="grow"><span class="choice-title">In stock only</span>
          <span class="small muted" style="display:block">Hide anything the seller has run out of.</span></span>
      </label>`,
    footer: `<button class="btn btn-primary" data-action="apply">Show results</button>
             <button class="btn btn-ghost" data-action="reset">Clear</button>`
  });

  const value = name => dialog.node.querySelector(`[data-f="${name}"]`);

  dialog.node.querySelector('[data-action="apply"]').addEventListener('click', () => {
    go(currentHash(filter, mode, {
      condition: value('condition').value,
      minPrice: value('minPrice').value,
      maxPrice: value('maxPrice').value,
      inStockOnly: value('inStockOnly').checked,
      page: 0
    }));
    dialog.close();
  });

  dialog.node.querySelector('[data-action="reset"]').addEventListener('click', () => {
    go(currentHash({ q: filter.q, category: filter.category }, mode, { page: 0 }));
    dialog.close();
  });
}
