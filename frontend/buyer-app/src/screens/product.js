/*
 * One product.
 *
 * The serviceability panel is the part that matters. A diaspora buyer needs to
 * know whether this seller can reach the address, what the leg costs and how
 * long it takes BEFORE choosing — finding out at checkout that the shop three
 * towns over cannot deliver is how a basket is abandoned. The server answers
 * it here for that reason, and this page puts it beside the price rather than
 * below the fold.
 */

import { api } from '../api.js';
import { state } from '../state.js';
import { withDelivery, placeLabel, hasPlace } from '../delivery.js';
import { addToCart } from '../components/cart.js';
import { productGrid } from '../components/productCard.js';
import { openDeliveryModal } from '../components/deliveryModal.js';
import { renderChrome } from '../components/chrome.js';
import { money, distance, priceLines } from '../money.js';
import { esc, icon, stars, toast, setBusy, errorState, titleCase, dateLabel } from '../ui.js';

export async function productView({ params, outlet }) {
  outlet.innerHTML = `<div class="wrap"><div class="section">
    <div class="sk" style="height:300px;margin-bottom:16px"></div>
    <div class="sk sk-line" style="width:60%"></div>
    <div class="sk sk-line" style="width:35%"></div></div></div>`;

  let product;
  try {
    product = await withDelivery(p => api.product(params.slug, p));
  } catch (error) {
    outlet.innerHTML = `<div class="wrap"><div class="section">${errorState(error)}</div></div>`;
    outlet.querySelector('[data-retry]').addEventListener('click', () => productView({ params, outlet }));
    return;
  }

  const images = product.images && product.images.length
    ? product.images
    : [{ url: '', altText: product.name }];
  const price = priceLines(product);
  const variants = (product.variants || []).filter(v => v.active !== false);

  outlet.innerHTML = `
    <div class="wrap">
      <nav class="small muted" style="padding:12px 0">
        <a href="#/browse">Shop</a>
        ${product.category ? ` / <a href="#/c/${encodeURIComponent(product.category.slug)}">${esc(product.category.name)}</a>` : ''}
      </nav>

      <div class="product-layout">
        <div class="gallery">
          <img class="main-image" data-main src="${esc(images[0].url)}" alt="${esc(product.name)}">
          ${images.length > 1 ? `<div class="strip">${images.map((image, i) =>
            `<img src="${esc(image.url)}" alt="${esc(image.altText || product.name)}"
                  data-pick="${i}"${i === 0 ? ' aria-current="true"' : ''}>`).join('')}</div>` : ''}
        </div>

        <div>
          <h1>${esc(product.name)}</h1>
          <div class="row" style="flex-wrap:wrap;margin-bottom:10px">
            ${product.rating ? stars(product.rating) : ''}
            ${product.totalReviews ? `<span class="small muted">${product.totalReviews} review${product.totalReviews === 1 ? '' : 's'}</span>` : ''}
            <span class="pill">${esc(titleCase(product.condition || ''))}</span>
            ${product.inStock ? '' : '<span class="pill pill-no">Sold out</span>'}
          </div>

          <div class="price-block card panel" style="margin-bottom:14px">
            <div>
              <span class="now">${esc(price.main)}</span>
              ${product.compareAtPrice != null && !price.unconverted
                ? `<span class="was">${esc(money(product.compareAtPrice, product.currency))}</span>` : ''}
            </div>
            ${price.unconverted
              ? `<div class="native">${esc(price.note || '')}. You will be charged in
                   ${esc(product.listingCurrency)} at the rate fixed when you order.</div>`
              : price.note ? `<div class="native">
                  The seller lists this at ${esc(money(product.listingPrice, product.listingCurrency))}.
                  You are charged in ${esc(product.currency)} and they are paid in ${esc(product.listingCurrency)},
                  at the rate fixed when you order.</div>` : ''}
          </div>

          <div data-serviceability></div>

          ${variants.length ? `<label class="field">
            <span class="label">Choose one</span>
            <select class="input" data-variant>
              ${variants.map(v => `<option value="${v.id}" ${v.inStock ? '' : 'disabled'}>
                ${esc(Object.values(v.options || {}).join(' · ') || v.sku)}
                — ${esc(money(v.price, v.currency || product.currency))}${v.inStock ? '' : ' (sold out)'}
              </option>`).join('')}
            </select>
          </label>` : ''}

          <div class="sticky-buy">
            <div class="qty">
              <button type="button" data-step="-1" aria-label="Fewer">−</button>
              <span data-qty>1</span>
              <button type="button" data-step="1" aria-label="More">+</button>
            </div>
            <button class="btn btn-primary grow" data-action="add"
                    ${product.inStock ? '' : 'aria-disabled="true"'}>
              ${icon('cart', 18)} ${product.inStock ? 'Add to basket' : 'Sold out'}
            </button>
          </div>

          ${product.store ? storePanel(product.store) : ''}
        </div>
      </div>

      ${product.description ? `<section class="section">
        <h2>About this</h2>
        <div class="card panel"><p style="white-space:pre-wrap;margin:0">${esc(product.description)}</p></div>
      </section>` : ''}

      ${(product.attributes || []).length ? `<section class="section">
        <h2>Details</h2>
        <div class="card">
          ${product.attributes.map(a => `<div class="row-between" style="padding:10px 16px;border-bottom:1px solid var(--line)">
            <span class="muted">${esc(a.name)}</span><strong>${esc(a.value)}</strong></div>`).join('')}
        </div>
      </section>` : ''}

      <section class="section" data-reviews></section>
      <section class="section" data-related></section>
    </div>`;

  renderServiceability(outlet, product);
  wireGallery(outlet, images);
  wireBuy(outlet, product, variants);
  loadReviews(outlet, product);
  loadRelated(outlet, product);
}

function storePanel(store) {
  return `<a class="card panel row" href="#/store/${encodeURIComponent(store.slug)}"
     style="text-decoration:none;color:inherit;margin-top:14px">
    ${store.logoUrl ? `<img src="${esc(store.logoUrl)}" alt="" style="width:44px;height:44px;border-radius:10px;object-fit:cover">` : icon('store', 26)}
    <span class="grow">
      <strong style="display:block">${esc(store.name)}</strong>
      <span class="small muted">${esc([store.city, store.countryCode].filter(Boolean).join(', '))}
        ${store.rating ? ' · ' + Number(store.rating).toFixed(1) + '★' : ''}</span>
    </span>
    ${icon('chevron', 18)}
  </a>`;
}

/* ── Can it get there, what does the leg cost, how long ───────────────────── */

function renderServiceability(outlet, product) {
  const host = outlet.querySelector('[data-serviceability]');
  const service = product.serviceability;

  if (!hasPlace() || !service) {
    host.innerHTML = `<div class="notice notice-warn" style="margin-bottom:14px">
      ${icon('pin', 16)} <strong>Where is this going?</strong>
      We can only tell you whether this seller delivers, what it costs and how long it takes
      once we know the destination.
      <button class="link" data-action="ask" type="button">Tell us</button>
    </div>`;
    host.querySelector('[data-action="ask"]').addEventListener('click', () => openDeliveryModal({
      onSaved: () => { renderChrome(); document.dispatchEvent(new CustomEvent('sujula:reload')); }
    }));
    return;
  }

  const options = (service.options || []).filter(o => o.available);

  host.innerHTML = `<div class="card panel" style="margin-bottom:14px">
    <div class="row-between" style="margin-bottom:8px">
      <strong>${icon('truck', 16)} To ${esc(placeLabel())}</strong>
      <button class="link btn-sm" data-action="ask" type="button">Change</button>
    </div>
    ${service.deliverable
      ? `<p class="small" style="margin:0 0 8px">
           ${icon('check', 14)} This seller delivers there${service.distanceKm != null
             ? ' — about ' + esc(distance(service.distanceKm)) + ' away' : ''}.
           ${service.distanceEstimated ? '<span class="muted">(distance estimated)</span>' : ''}
         </p>`
      : `<p class="small" style="margin:0 0 8px;color:var(--danger)">
           ${icon('close', 14)} ${esc(service.message || 'This seller cannot deliver to that address.')}
         </p>`}
    ${options.length ? `<div class="totals">
      ${options.map(o => `<div class="line">
        <span>${esc(titleCase(o.mode))}${o.etaMinDays != null
          ? ` <span class="muted">· ${o.etaMinDays}–${o.etaMaxDays} days</span>` : ''}</span>
        <strong>${o.cost != null ? esc(money(o.cost, o.currency)) : '—'}</strong>
      </div>`).join('')}
    </div>` : ''}
  </div>`;

  host.querySelector('[data-action="ask"]').addEventListener('click', () => openDeliveryModal({
    onSaved: () => { renderChrome(); document.dispatchEvent(new CustomEvent('sujula:reload')); }
  }));
}

/* ── Gallery, quantity, basket ────────────────────────────────────────────── */

function wireGallery(outlet, images) {
  const main = outlet.querySelector('[data-main]');
  outlet.querySelectorAll('[data-pick]').forEach(thumb => {
    thumb.addEventListener('click', () => {
      main.src = images[Number(thumb.dataset.pick)].url;
      outlet.querySelectorAll('[data-pick]').forEach(t => t.removeAttribute('aria-current'));
      thumb.setAttribute('aria-current', 'true');
    });
  });
}

function wireBuy(outlet, product, variants) {
  let quantity = 1;
  const readout = outlet.querySelector('[data-qty]');

  outlet.querySelectorAll('[data-step]').forEach(button => {
    button.addEventListener('click', () => {
      quantity = Math.min(99, Math.max(1, quantity + Number(button.dataset.step)));
      readout.textContent = quantity;
    });
  });

  const add = outlet.querySelector('[data-action="add"]');
  if (!product.inStock) return;

  add.addEventListener('click', async () => {
    const select = outlet.querySelector('[data-variant]');
    const variantId = select ? Number(select.value) : null;
    setBusy(add, true, 'Adding…');
    try {
      await addToCart(product.id, variantId, quantity);
      toast('In your basket.', 'ok');
    } catch (error) {
      toast(error.message, 'error');
    } finally {
      setBusy(add, false);
    }
  });
}

async function loadReviews(outlet, product) {
  const host = outlet.querySelector('[data-reviews]');
  try {
    const page = await api.reviews(product.id, { page: 0, size: 5 });
    if (!page.items || !page.items.length) { host.remove(); return; }
    host.innerHTML = `
      <div class="section-head"><h2>What buyers said</h2>
        <span class="small muted">${page.totalElements} in total</span></div>
      <div class="card">
        ${page.items.map(review => `<div style="padding:14px 16px;border-bottom:1px solid var(--line)">
          <div class="row" style="margin-bottom:4px">
            <strong>${esc(review.authorName || 'A buyer')}</strong>
            ${stars(review.rating)}
            ${review.verifiedPurchase ? '<span class="pill pill-near">Bought it</span>' : ''}
            <span class="tiny muted" style="margin-left:auto">${esc(dateLabel(review.createdAt))}</span>
          </div>
          ${review.title ? `<strong class="small">${esc(review.title)}</strong>` : ''}
          <p class="small" style="margin:4px 0 0">${esc(review.comment || '')}</p>
          ${review.vendorReply ? `<p class="small muted" style="margin:8px 0 0;padding-left:12px;border-left:2px solid var(--line)">
            <strong>Seller:</strong> ${esc(review.vendorReply)}</p>` : ''}
        </div>`).join('')}
      </div>`;
  } catch {
    host.remove();
  }
}

async function loadRelated(outlet, product) {
  const host = outlet.querySelector('[data-related]');
  try {
    const page = await withDelivery(p => api.related(product.id, { ...p, size: 8 }));
    if (!page.items || !page.items.length) { host.remove(); return; }
    host.innerHTML = `<div class="section-head"><h2>Also near that address</h2></div>`
      + productGrid(page.items);
  } catch {
    host.remove();
  }
}
