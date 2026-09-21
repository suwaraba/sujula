/*
 * The basket, grouped by seller.
 *
 * Grouped rather than listed flat because that is what an order on this
 * marketplace actually is: one payment that splits into a sub-order per
 * seller, each of which ships, cancels, refunds and is paid out on its own.
 * Showing one merged list would be a lie about what happens next — and the
 * moment one seller cannot reach the address, it is the lie that costs the
 * buyer the whole basket instead of one line.
 */

import { api } from '../api.js';
import { state } from '../state.js';
import { ensureCart, refreshCart, setQuantity, removeLine } from '../components/cart.js';
import { openDeliveryModal } from '../components/deliveryModal.js';
import { renderChrome } from '../components/chrome.js';
import { placeLabel, hasPlace } from '../delivery.js';
import { money } from '../money.js';
import { esc, icon, toast, setBusy, emptyState, errorState } from '../ui.js';

export async function cartView({ outlet }) {
  outlet.innerHTML = `<div class="wrap"><div class="section">
    <h1>Your basket</h1><div class="sk" style="height:180px"></div></div></div>`;

  let cart;
  try {
    cart = state.cartToken ? await refreshCart() : null;
  } catch (error) {
    outlet.innerHTML = `<div class="wrap"><div class="section">${errorState(error)}</div></div>`;
    outlet.querySelector('[data-retry]').addEventListener('click', () => cartView({ outlet }));
    return;
  }

  if (!cart || !cart.vendors || !cart.vendors.length) {
    outlet.innerHTML = `<div class="wrap"><div class="section">
      ${emptyState('cart', 'Nothing in the basket yet',
        'Find something for whoever you are sending it to.',
        '<a class="btn btn-primary" href="#/browse">Browse the shop</a>')}
    </div></div>`;
    return;
  }

  paint(outlet, cart);
}

function paint(outlet, cart) {
  const undeliverable = (cart.vendors || []).filter(v => v.deliverable === false);

  outlet.innerHTML = `
    <div class="wrap">
      <div class="section">
        <h1>Your basket</h1>

        ${hasPlace()
          ? `<div class="notice" style="margin-bottom:14px">
               ${icon('pin', 15)} Going to <strong>${esc(placeLabel())}</strong>.
               <button class="link" data-action="change-place" type="button">Change</button>
             </div>`
          : `<div class="notice notice-warn" style="margin-bottom:14px">
               ${icon('pin', 15)} We still do not know where this is going, so shipping is not
               priced yet. <button class="link" data-action="change-place" type="button">Tell us</button>
             </div>`}

        ${undeliverable.length ? `<div class="notice notice-danger" style="margin-bottom:14px">
          ${icon('truck', 15)} ${undeliverable.length === 1 ? 'One seller cannot' : `${undeliverable.length} sellers cannot`}
          deliver to that address. You can still order from the rest — each seller is a separate
          delivery and a separate payment to them.
        </div>` : ''}

        ${(cart.issues || []).map(issue => `<div class="notice notice-warn" style="margin-bottom:10px">
          ${esc(issue.message || issue.type || '')}</div>`).join('')}

        ${(cart.vendors || []).map(v => vendorGroup(v, cart.displayCurrency)).join('')}

        <div class="card panel" style="margin-top:16px">
          <div class="totals">
            <div class="line"><span>Items</span><strong>${esc(money(cart.subtotal, cart.displayCurrency))}</strong></div>
            ${cart.discount && Number(cart.discount) > 0
              ? `<div class="line"><span>Discount</span><strong>−${esc(money(cart.discount, cart.displayCurrency))}</strong></div>` : ''}
            <div class="line"><span>Delivery</span><strong>${
              cart.shipping == null ? '<span class="muted">once we know where</span>'
                                    : esc(money(cart.shipping, cart.displayCurrency))}</strong></div>
            <div class="line grand"><span>Total</span><span>${esc(money(cart.total, cart.displayCurrency))}</span></div>
          </div>
          ${cart.totalsComplete ? '' : `<p class="tiny muted" style="margin:10px 0 0">
            This is not the final total — delivery is priced at checkout, against the destination.</p>`}
          <a class="btn btn-primary btn-block" href="#/checkout" style="margin-top:14px">
            Continue to checkout ${icon('chevron', 16)}</a>
          <a class="btn btn-ghost btn-block" href="#/browse" style="margin-top:8px">Keep shopping</a>
        </div>
      </div>
    </div>`;

  wire(outlet, cart);
}

function vendorGroup(vendor, displayCurrency) {
  return `<div class="card vendor-group">
    <div class="vendor-head">
      ${icon('store', 18)}
      <strong class="grow truncate">${esc(vendor.storeName || 'Seller')}</strong>
      ${vendor.deliverable === false
        ? '<span class="pill pill-no">Cannot deliver there</span>'
        : vendor.checkoutable === false ? '<span class="pill pill-far">Needs attention</span>' : ''}
    </div>

    ${(vendor.items || []).map(item => line(item, displayCurrency)).join('')}

    <div class="panel" style="border-top:1px solid var(--line)">
      <div class="totals">
        <div class="line"><span>${esc(vendor.storeName || 'Seller')} subtotal</span>
          <strong>${esc(money(vendor.subtotal))}</strong></div>
        ${vendor.shipping != null
          ? `<div class="line"><span>Their delivery</span><strong>${esc(money(vendor.shipping))}</strong></div>` : ''}
      </div>
      ${vendor.nativeCurrency && vendor.subtotalNative != null && vendor.nativeCurrency !== displayCurrency
        ? `<p class="tiny muted" style="margin:8px 0 0">
             Paid out to them as ${esc(money(vendor.subtotalNative, vendor.nativeCurrency))}
             ${vendor.exchangeRate ? `at ${Number(vendor.exchangeRate).toPrecision(6)} per ${esc(vendor.nativeCurrency)}` : ''},
             at the rate fixed when you order.</p>` : ''}
      ${(vendor.issues || []).map(issue => `<p class="small" style="color:var(--danger);margin:6px 0 0">
        ${esc(issue.message || '')}</p>`).join('')}
    </div>
  </div>`;
}

function line(item, displayCurrency) {
  // Shown only when there is genuinely a second currency to show. A seller
  // listing in the same currency the buyer is charged in has nothing to
  // convert, and printing "8,500 GMD each from the seller" beside 8,500 GMD
  // is noise dressed up as transparency.
  const converted = item.nativeCurrency && item.nativeCurrency !== displayCurrency
    && item.unitPriceNative != null;

  return `<div class="cart-line" data-item="${item.itemId}">
    <img src="${esc(item.imageUrl || '')}" alt="">
    <div class="grow">
      <a href="#/p/${encodeURIComponent(item.productSlug || '')}"
         style="font-weight:600;color:inherit;text-decoration:none">${esc(item.productName)}</a>
      ${item.variantLabel ? `<div class="tiny muted">${esc(item.variantLabel)}</div>` : ''}
      <div class="small" style="margin-top:4px">
        <strong>${esc(money(item.lineTotal))}</strong>
        ${converted ? `<span class="muted"> · ${esc(money(item.unitPriceNative, item.nativeCurrency))} each from the seller</span>` : ''}
      </div>
      ${item.deliverable === false
        ? '<div class="tiny" style="color:var(--danger)">Not deliverable to that address</div>' : ''}
      ${item.purchasable === false && item.deliverable !== false
        ? '<div class="tiny" style="color:var(--danger)">Unavailable right now</div>' : ''}
      <div class="row" style="margin-top:8px">
        <div class="qty">
          <button type="button" data-q="-1" aria-label="Fewer">−</button>
          <span>${item.quantity}</span>
          <button type="button" data-q="1" aria-label="More">+</button>
        </div>
        <button class="link" data-remove type="button">Remove</button>
      </div>
    </div>
  </div>`;
}

function wire(outlet, cart) {
  outlet.querySelectorAll('[data-action="change-place"]').forEach(button => {
    button.addEventListener('click', () => openDeliveryModal({
      onSaved: () => { renderChrome(); document.dispatchEvent(new CustomEvent('sujula:reload')); }
    }));
  });

  outlet.querySelectorAll('[data-item]').forEach(row => {
    const itemId = Number(row.dataset.item);
    const current = findItem(cart, itemId);

    row.querySelectorAll('[data-q]').forEach(button => {
      button.addEventListener('click', async () => {
        const next = (current ? current.quantity : 1) + Number(button.dataset.q);
        if (next < 1) return;
        setBusy(button, true, '…');
        try {
          const updated = await setQuantity(itemId, Math.min(99, next));
          paint(outlet, updated);
        } catch (error) {
          toast(error.message, 'error');
          setBusy(button, false);
        }
      });
    });

    row.querySelector('[data-remove]').addEventListener('click', async event => {
      setBusy(event.currentTarget, true, 'Removing…');
      try {
        const updated = await removeLine(itemId);
        if (!updated || !updated.vendors || !updated.vendors.length) cartView({ outlet });
        else paint(outlet, updated);
      } catch (error) {
        toast(error.message, 'error');
        setBusy(event.currentTarget, false);
      }
    });
  });
}

function findItem(cart, itemId) {
  for (const vendor of cart.vendors || []) {
    const found = (vendor.items || []).find(i => i.itemId === itemId);
    if (found) return found;
  }
  return null;
}
