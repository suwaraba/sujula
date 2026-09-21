/*
 * Checkout, in four answers.
 *
 *   1. Who receives it — a name and a PHONE, because the person the parcel is
 *      for very often has no account, no email and no app. The phone is the
 *      only thing the handover can be built on.
 *   2. How it gets there — to the door, or to a counter they can walk to.
 *   3. What it comes to — a quote, taken once and held. The rate inside it is
 *      a snapshot: it does not move between this screen and the payment, and
 *      it does not move afterwards either.
 *   4. How it is paid — in the buyer's own currency, with a method that makes
 *      sense where the buyer is.
 *
 * The quote expires. That is not an inconvenience to route around: a total
 * assembled from an exchange rate nobody can point at is a total nobody can
 * explain, so when it lapses the screen takes a new one and says so.
 */

import { api, ApiError, idempotencyKey } from '../api.js';
import { state, signedIn } from '../state.js';
import { refreshCart, syncCartContext } from '../components/cart.js';
import { openDeliveryModal } from '../components/deliveryModal.js';
import { renderChrome } from '../components/chrome.js';
import { placeLabel, hasPlace, ensureContext } from '../delivery.js';
import { createMap } from '../map.js';
import { money } from '../money.js';
import { go, here } from '../router.js';
import { esc, icon, modal, toast, setBusy, errorState, titleCase } from '../ui.js';

const PAYMENT_METHODS = [
  { code: 'CARD', label: 'Card', note: 'Pay now with a debit or credit card.', modes: null },
  { code: 'PAYPAL', label: 'PayPal', note: 'Pay now with your PayPal account.', modes: null },
  { code: 'BANK_TRANSFER', label: 'Bank transfer', note: 'Transfer the total and quote the reference. The order is released when the transfer clears.', modes: null },
  { code: 'PAY_ON_DELIVERY', label: 'Pay on delivery', note: 'Cash to the driver at the door. Only for a home delivery.', modes: ['HOME_DELIVERY'] },
  { code: 'PAY_AT_PICKUP', label: 'Pay at the counter', note: 'Cash to the operator at collection.', modes: ['PICKUP_POINT'] },
  { code: 'CASH_IN_STORE', label: 'Pay at the shop', note: 'Cash to the seller when you collect from them.', modes: ['VENDOR_PICKUP'] }
];

export async function checkoutView({ outlet }) {
  if (!signedIn()) {
    outlet.innerHTML = `<div class="wrap"><div class="section">
      <div class="card panel center">
        ${icon('shield', 40)}
        <h1>Sign in to finish</h1>
        <p class="muted">An order needs an owner — it is who the refund goes to, and who can
          cancel it. Your basket is kept.</p>
        <a class="btn btn-primary" href="#/signin?next=${encodeURIComponent('/checkout')}">Sign in</a>
        <a class="btn btn-ghost" href="#/register?next=${encodeURIComponent('/checkout')}"
           style="margin-top:8px">Create an account</a>
      </div></div></div>`;
    return;
  }

  const view = {
    mode: (state.place && state.place.mode) || 'HOME_DELIVERY',
    pickupPointId: (state.place && state.place.pickupPointId) || null,
    addressId: null,
    paymentMethod: null,
    quote: null,
    addresses: [],
    cart: null
  };

  outlet.innerHTML = `<div class="wrap"><div class="section">
    <h1>Checkout</h1><div class="sk" style="height:260px"></div></div></div>`;

  try {
    await syncCartContext();
    view.cart = await refreshCart();
    view.addresses = await api.addresses();
  } catch (error) {
    outlet.innerHTML = `<div class="wrap"><div class="section">${errorState(error)}</div></div>`;
    outlet.querySelector('[data-retry]').addEventListener('click', () => checkoutView({ outlet }));
    return;
  }

  if (!view.cart || !view.cart.vendors || !view.cart.vendors.length) {
    go('/cart', { replace: true });
    return;
  }

  const preferred = view.addresses.find(a => a.isDefault) || view.addresses[0];
  view.addressId = preferred ? preferred.id : null;

  paint(outlet, view);
  if (view.addressId || view.mode === 'PICKUP_POINT') requote(outlet, view);
}

/* ── The screen ───────────────────────────────────────────────────────────── */

function paint(outlet, view) {
  const address = view.addresses.find(a => a.id === view.addressId);
  const methods = PAYMENT_METHODS.filter(m => !m.modes || m.modes.includes(view.mode));

  // A quote the server itself calls incomplete is not a total. It happens for
  // a real reason — no rate for a seller's currency today, or a seller who
  // cannot reach the address — and the honest thing is to refuse the order
  // rather than take money against a figure that is missing a part.
  const usableQuote = Boolean(view.quote && view.quote.complete && view.quote.deliverable);
  const placeable = Boolean(usableQuote && view.addressId && view.paymentMethod);
  if (view.paymentMethod && !methods.some(m => m.code === view.paymentMethod)) {
    view.paymentMethod = null;
  }

  outlet.innerHTML = `
    <div class="wrap">
      <div class="section">
        <h1>Checkout</h1>
        <div class="steps">
          <span class="step" data-done="true"></span>
          <span class="step" data-done="${Boolean(view.addressId)}"></span>
          <span class="step" data-done="${Boolean(view.quote)}"></span>
          <span class="step" data-done="${Boolean(view.paymentMethod)}"></span>
        </div>

        ${!hasPlace() ? `<div class="notice notice-warn" style="margin-bottom:14px">
          ${icon('pin', 15)} No destination set, so delivery cannot be priced.
          <button class="link" data-action="change-place" type="button">Set it</button>
        </div>` : ''}

        <!-- 1 ─ who receives it -->
        <section class="card panel" style="margin-bottom:14px">
          <div class="row-between" style="margin-bottom:8px">
            <h2 style="margin:0">Who receives it</h2>
            <button class="link" data-action="new-address" type="button">Add someone</button>
          </div>
          <p class="small muted" style="margin-top:0">
            The name and phone of the person the parcel is handed to. They get an SMS code
            at the door — they do not need an account here.
          </p>
          ${view.addresses.length ? `<div class="choice-list">
            ${view.addresses.map(a => `
              <label class="choice" data-selected="${a.id === view.addressId}">
                <input type="radio" name="address" value="${a.id}"${a.id === view.addressId ? ' checked' : ''}>
                <span class="grow">
                  <span class="choice-title">${esc(a.fullName)} <span class="muted">· ${esc(a.phone || '')}</span></span>
                  <span class="small muted" style="display:block">${esc(addressLine(a))}</span>
                  ${a.needsPinConfirmation ? `<span class="pill pill-far" style="margin-top:6px">
                    ${icon('pin', 11)} Pin not confirmed</span>` : ''}
                </span>
              </label>`).join('')}
          </div>` : `<div class="notice notice-warn">
            Nobody to deliver to yet.
            <button class="link" data-action="new-address" type="button">Add the recipient</button>
          </div>`}
        </section>

        <!-- 2 ─ how it gets there -->
        <section class="card panel" style="margin-bottom:14px">
          <h2>How it gets there</h2>
          <div class="choice-list">
            <label class="choice" data-selected="${view.mode === 'HOME_DELIVERY'}">
              <input type="radio" name="mode" value="HOME_DELIVERY"${view.mode === 'HOME_DELIVERY' ? ' checked' : ''}>
              <span class="grow"><span class="choice-title">To the door</span>
                <span class="small muted" style="display:block">A driver brings it to the address above.</span></span>
            </label>
            <label class="choice" data-selected="${view.mode === 'PICKUP_POINT'}">
              <input type="radio" name="mode" value="PICKUP_POINT"${view.mode === 'PICKUP_POINT' ? ' checked' : ''}>
              <span class="grow"><span class="choice-title">To a collection counter</span>
                <span class="small muted" style="display:block">A shop near them holds it. Often cheaper, and it
                  does not need anyone to be at home.</span></span>
            </label>
          </div>
          <div data-pickup style="margin-top:10px"></div>
        </section>

        <!-- 3 ─ the total -->
        <section class="card panel" style="margin-bottom:14px">
          <h2>What it comes to</h2>
          <div data-quote>${view.quote ? '' : '<div class="sk" style="height:120px"></div>'}</div>
        </section>

        <!-- 4 ─ paying -->
        <section class="card panel" style="margin-bottom:14px">
          <h2>How you pay</h2>
          <p class="small muted" style="margin-top:0">
            You are charged in ${esc(state.currencyCode || '')}. Each seller is paid in their own
            currency, at the rate fixed the moment you order.
          </p>
          <div class="choice-list">
            ${methods.map(m => `
              <label class="choice" data-selected="${view.paymentMethod === m.code}">
                <input type="radio" name="method" value="${m.code}"${view.paymentMethod === m.code ? ' checked' : ''}>
                <span class="grow"><span class="choice-title">${esc(m.label)}</span>
                  <span class="small muted" style="display:block">${esc(m.note)}</span></span>
              </label>`).join('')}
          </div>
        </section>

        <button class="btn btn-primary btn-block" data-action="place"
                ${placeable ? '' : 'aria-disabled="true"'}>
          ${icon('shield', 18)} Place the order${placeable
            ? ' — ' + esc(money(view.quote.total, view.quote.displayCurrency)) : ''}
        </button>
        <p class="tiny muted center" style="margin-top:10px">
          The seller is not paid until the parcel is handed over and the code checks out.
        </p>
      </div>
    </div>`;

  wire(outlet, view);
  if (view.quote) renderQuote(outlet, view);
  if (view.mode === 'PICKUP_POINT') loadPickupPoints(outlet, view);
}

function addressLine(a) {
  return [a.street, a.apartmentSuite, a.city, a.state, a.postalCode, a.countryCode]
    .filter(Boolean).join(', ');
}

/* ── Wiring ───────────────────────────────────────────────────────────────── */

function wire(outlet, view) {
  outlet.querySelectorAll('input[name="address"]').forEach(input => {
    input.addEventListener('change', () => {
      view.addressId = Number(input.value);
      paint(outlet, view);
      requote(outlet, view);
    });
  });

  outlet.querySelectorAll('input[name="mode"]').forEach(input => {
    input.addEventListener('change', () => {
      view.mode = input.value;
      view.pickupPointId = null;
      view.quote = null;
      paint(outlet, view);
      if (view.mode !== 'PICKUP_POINT') requote(outlet, view);
    });
  });

  outlet.querySelectorAll('input[name="method"]').forEach(input => {
    input.addEventListener('change', () => {
      view.paymentMethod = input.value;
      paint(outlet, view);
    });
  });

  outlet.querySelectorAll('[data-action="new-address"]').forEach(button => {
    button.addEventListener('click', () => openAddressForm(outlet, view));
  });

  const changePlace = outlet.querySelector('[data-action="change-place"]');
  if (changePlace) changePlace.addEventListener('click', () => openDeliveryModal({
    onSaved: () => { renderChrome(); document.dispatchEvent(new CustomEvent('sujula:reload')); }
  }));

  outlet.querySelector('[data-action="place"]')
    .addEventListener('click', event => place(outlet, view, event.currentTarget));
}

/* ── The quote ────────────────────────────────────────────────────────────── */

async function requote(outlet, view) {
  const host = outlet.querySelector('[data-quote]');
  if (!host) return;
  host.innerHTML = '<div class="sk" style="height:120px"></div>';

  try {
    await ensureContext();
    view.quote = await api.quoteCart(state.cartToken, {
      deliveryMode: view.mode,
      pickupPointId: view.pickupPointId || null
    });
    paint(outlet, view);
  } catch (error) {
    view.quote = null;
    host.innerHTML = `<div class="notice notice-danger">${esc(error.message)}</div>`;
  }
}

function renderQuote(outlet, view) {
  const host = outlet.querySelector('[data-quote]');
  const quote = view.quote;
  if (!host || !quote) return;

  const blockers = quote.blockers || [];
  const usable = quote.complete && quote.deliverable;

  host.innerHTML = `
    ${blockers.length ? `<div class="notice notice-danger" style="margin-bottom:10px">
      ${blockers.map(esc).join('<br>')}</div>` : ''}

    ${!usable && !blockers.length ? `<div class="notice notice-danger" style="margin-bottom:10px">
      This basket cannot be totalled for that destination yet.</div>` : ''}

    ${(quote.vendors || []).map(v => `
      <div class="row-between small" style="padding:6px 0">
        <span class="truncate">${icon('store', 13)} ${esc(v.storeName || 'Seller')}</span>
        <span>${esc(money(v.total, quote.displayCurrency))}
          ${v.listingCurrency && v.listingCurrency !== quote.displayCurrency
            ? `<span class="muted tiny">(paid out in ${esc(v.listingCurrency)})</span>` : ''}</span>
      </div>`).join('')}

    <div class="totals" style="margin-top:10px">
      <div class="line"><span>Items</span><strong>${esc(money(quote.subtotal, quote.displayCurrency))}</strong></div>
      ${quote.discount && Number(quote.discount) > 0
        ? `<div class="line"><span>Discount</span><strong>−${esc(money(quote.discount, quote.displayCurrency))}</strong></div>` : ''}
      <div class="line"><span>Delivery</span><strong>${esc(money(quote.shipping, quote.displayCurrency))}</strong></div>
      ${quote.tax && Number(quote.tax) > 0
        ? `<div class="line"><span>Tax</span><strong>${esc(money(quote.tax, quote.displayCurrency))}</strong></div>` : ''}
      <div class="line grand"><span>Total</span><span>${esc(money(quote.total, quote.displayCurrency))}</span></div>
    </div>

    ${usable
      ? '<p class="tiny muted" style="margin:10px 0 0" data-expiry></p>'
      : `<p class="small" style="margin:10px 0 0;color:var(--danger)">
           Nothing can be ordered until that is resolved — remove what cannot be sent,
           or change the currency or the destination.</p>`}`;

  // Only a usable quote has a rate worth holding, and only a held rate is
  // worth counting down.
  if (usable) {
    startExpiryClock(host.querySelector('[data-expiry]'), quote, () => requote(outlet, view));
  }
}

/**
 * The countdown on a held quote.
 *
 * Shown rather than hidden: the buyer is about to be charged a converted
 * figure, and the honest thing is to say how long the number they are looking
 * at is the number. When it runs out we take a fresh one rather than quietly
 * charging against a stale rate.
 */
function startExpiryClock(node, quote, onExpired) {
  if (!node) return;
  let left = quote.expiresInSeconds || 0;

  const tick = () => {
    if (!document.body.contains(node)) { clearInterval(timer); return; }
    if (left <= 0) {
      clearInterval(timer);
      node.innerHTML = 'This total has expired — taking a fresh one…';
      onExpired();
      return;
    }
    const minutes = Math.floor(left / 60);
    const seconds = String(left % 60).padStart(2, '0');
    node.innerHTML = `Total and exchange rate held for ${minutes}:${seconds}. `
      + 'Nothing about this order is re-priced afterwards.';
    left--;
  };

  const timer = setInterval(tick, 1000);
  tick();
}

/* ── Collection counters ──────────────────────────────────────────────────── */

async function loadPickupPoints(outlet, view) {
  const host = outlet.querySelector('[data-pickup]');
  if (!host) return;
  host.innerHTML = '<div class="sk sk-line" style="width:60%"></div>';

  const place = state.place || {};
  const params = {};
  if (place.latitude != null) { params.lat = place.latitude; params.lng = place.longitude; params.radius = 25; }
  else if (place.city) { params.city = place.city; }

  if (!params.lat && !params.city) {
    host.innerHTML = `<div class="notice notice-warn">Set the destination first and we will
      list the counters near it.</div>`;
    return;
  }

  try {
    const found = await api.pickupPoints(params);
    const points = (found.points || []).filter(p => p.capacity !== 'CLOSED');
    if (!points.length) {
      host.innerHTML = `<div class="notice notice-warn">No open counter near that address.
        Have it brought to the door instead.</div>`;
      return;
    }
    host.innerHTML = `<div class="choice-list">
      ${points.map(p => `
        <label class="choice" data-selected="${view.pickupPointId === p.id}">
          <input type="radio" name="point" value="${p.id}"${view.pickupPointId === p.id ? ' checked' : ''}>
          <span class="grow">
            <span class="choice-title">${esc(p.name)}</span>
            <span class="small muted" style="display:block">${esc([p.addressStreet, p.city].filter(Boolean).join(', '))}
              ${p.distanceKm != null ? ` · ${Number(p.distanceKm).toFixed(1)} km away` : ''}</span>
            ${p.openingHours ? `<span class="tiny muted" style="display:block">${esc(p.openingHours)}</span>` : ''}
            ${p.capacity === 'LIMITED' ? '<span class="pill pill-far">Filling up</span>' : ''}
            ${p.capacity === 'FULL' ? '<span class="pill pill-no">Full</span>' : ''}
          </span>
        </label>`).join('')}
    </div>`;

    host.querySelectorAll('input[name="point"]').forEach(input => {
      input.addEventListener('change', () => {
        view.pickupPointId = Number(input.value);
        requote(outlet, view);
      });
    });
  } catch (error) {
    host.innerHTML = `<div class="notice notice-warn">${esc(error.message)}</div>`;
  }
}

/* ── Adding the recipient ─────────────────────────────────────────────────── */

function openAddressForm(outlet, view) {
  const place = state.place || {};
  const countries = state.countries.filter(c => c.ship).length
    ? state.countries.filter(c => c.ship) : state.countries;

  const dialog = modal({
    title: 'Who is receiving this?',
    subtitle: 'Their name, their phone, and where they are.',
    body: `
      <div class="notice" style="margin-bottom:12px">
        ${icon('phone', 15)} The phone matters more than the address here: the driver reads a
        code to them, or they read one back. It does not need to be a smartphone.
      </div>
      <label class="field"><span class="label">Their full name</span>
        <input class="input" data-a="fullName" autocomplete="name" placeholder="Fatou Jallow"></label>
      <label class="field"><span class="label">Their phone</span>
        <input class="input" data-a="phone" type="tel" inputmode="tel" autocomplete="tel"
               placeholder="+220 …"></label>
      <label class="field"><span class="label">Street, or the nearest landmark</span>
        <input class="input" data-a="street" placeholder="Behind the mosque, Latrikunda"
               value="${esc(place.addressLine || '')}"></label>
      <label class="field"><span class="label">Flat, compound or gate <span class="muted">(optional)</span></span>
        <input class="input" data-a="apartmentSuite"></label>
      <div class="row" style="gap:10px">
        <label class="field grow"><span class="label">Town or city</span>
          <input class="input" data-a="city" value="${esc(place.city || '')}"></label>
        <label class="field grow"><span class="label">Region <span class="muted">(optional)</span></span>
          <input class="input" data-a="state" value="${esc(place.state || '')}"></label>
      </div>
      <label class="field"><span class="label">Country</span>
        <select class="input" data-a="countryCode">
          ${countries.map(c => `<option value="${esc(c.code)}"${c.code === place.countryCode ? ' selected' : ''}>${esc(c.name)}</option>`).join('')}
        </select></label>
      <label class="field"><span class="label">Label this <span class="muted">(so you know it next time)</span></span>
        <input class="input" data-a="label" placeholder="My sister"></label>
      <div class="field">
        <span class="label">Exactly where the driver goes</span>
        <div data-map style="height:230px"></div>
        <span class="hint" data-readout></span>
      </div>`,
    footer: `<button class="btn btn-primary" data-action="save">Save this recipient</button>`
  });

  const node = dialog.node;
  const value = name => node.querySelector(`[data-a="${name}"]`).value.trim();
  const readout = node.querySelector('[data-readout]');

  let pin = place.latitude != null
    ? { latitude: place.latitude, longitude: place.longitude } : null;

  const map = createMap(node.querySelector('[data-map]'), {
    ...(pin ? { lat: pin.latitude, lng: pin.longitude, zoom: 16 } : {}),
    onMove: point => {
      pin = { latitude: Math.round(point.latitude * 1e6) / 1e6,
              longitude: Math.round(point.longitude * 1e6) / 1e6 };
      readout.textContent = `Pin at ${pin.latitude.toFixed(5)}, ${pin.longitude.toFixed(5)}`;
    }
  });
  readout.textContent = pin
    ? `Pin at ${pin.latitude.toFixed(5)}, ${pin.longitude.toFixed(5)}`
    : 'Drag the map so the pin is on the right compound.';

  node.querySelector('[data-action="save"]').addEventListener('click', async event => {
    const button = event.currentTarget;
    const body = {
      label: value('label') || value('fullName') || 'Delivery',
      fullName: value('fullName'),
      phone: value('phone'),
      street: value('street'),
      apartmentSuite: value('apartmentSuite') || null,
      city: value('city'),
      state: value('state') || null,
      postalCode: null,
      countryCode: value('countryCode'),
      latitude: pin ? pin.latitude : null,
      longitude: pin ? pin.longitude : null,
      makeDefault: view.addresses.length === 0
    };

    if (!body.fullName || !body.phone || !body.street || !body.city) {
      toast('Name, phone, street and town are all needed.', 'error');
      return;
    }

    setBusy(button, true, 'Saving…');
    try {
      const created = await api.createAddress(body);
      view.addresses = await api.addresses();
      view.addressId = created.id;
      map.destroy();
      dialog.close();
      paint(outlet, view);
      requote(outlet, view);
    } catch (error) {
      toast(error.message, 'error');
      setBusy(button, false);
    }
  });
}

/* ── Placing it ───────────────────────────────────────────────────────────── */

async function place(outlet, view, button) {
  if (!view.quote || !view.quote.complete || !view.quote.deliverable) return;
  if (!view.addressId || !view.paymentMethod) return;

  setBusy(button, true, 'Placing the order…');
  try {
    const placed = await api.checkout({
      quoteId: view.quote.id,
      addressId: view.addressId,
      paymentMethod: view.paymentMethod,
      notes: null
    }, idempotencyKey());

    await refreshCart();
    showPlaced(outlet, placed);
  } catch (error) {
    setBusy(button, false);
    if (error instanceof ApiError && /expired|price changed/i.test(error.message)) {
      toast(error.message, 'error');
      requote(outlet, view);
      return;
    }
    toast(error.message, 'error');
  }
}

const PAY_LATER = new Set(['PAY_ON_DELIVERY', 'PAY_AT_PICKUP', 'CASH_IN_STORE']);

function showPlaced(outlet, placed) {
  const payment = placed.payment || {};
  const sellers = placed.vendorOrders ? placed.vendorOrders.length : 1;
  // Saying "you paid" to somebody who chose to pay the driver is not a small
  // slip: it is the screen telling them the money has moved when it has not.
  const payLater = PAY_LATER.has(payment.method);

  outlet.innerHTML = `
    <div class="wrap"><div class="section">
      <div class="card panel center">
        <div style="color:var(--ok)">${icon('check', 44)}</div>
        <h1>Order ${esc(placed.orderNumber)} is placed</h1>
        <p class="muted">${esc(money(placed.total, placed.currency))} ·
          ${sellers} ${sellers === 1 ? 'seller' : 'sellers'}</p>

        ${payment.checkoutUrl ? `<a class="btn btn-primary btn-block" href="${esc(payment.checkoutUrl)}">
          Pay ${esc(money(payment.amount, payment.currency))} now</a>` : ''}

        ${payment.instructions ? `<div class="notice" style="text-align:left;margin-top:12px">
          <strong>How to pay</strong>
          <p class="small" style="white-space:pre-wrap;margin:6px 0 0">${esc(payment.instructions)}</p>
          ${payment.reference ? `<p class="small" style="margin:6px 0 0">
            Reference <strong>${esc(payment.reference)}</strong></p>` : ''}
        </div>` : ''}

        <a class="btn btn-ghost btn-block" href="#/orders/${placed.orderId}" style="margin-top:10px">
          See the order</a>
      </div>

      <div class="card panel" style="margin-top:14px">
        <h2>What happens now</h2>
        <ul class="timeline">
          ${payLater
            ? `<li data-done="true"><strong>You pay when it reaches you</strong>
                 <div class="small muted">Nothing has been charged yet.</div></li>`
            : `<li data-done="true"><strong>You paid</strong>
                 <div class="small muted">Held by the platform, not sent on yet.</div></li>`}
          <li><strong>Each seller packs their own part</strong>
            <div class="small muted">${sellers === 1
              ? 'One seller, so one delivery.'
              : 'Your order is ' + sellers + ' separate deliveries; they move independently.'}</div></li>
          <li><strong>A driver collects it with a code</strong>
            <div class="small muted">Nobody can mark a parcel handed over without one.</div></li>
          <li><strong>The recipient reads back their own code</strong>
            <div class="small muted">By SMS, to whatever phone they have.</div></li>
          <li><strong>Only then is the seller paid</strong>
            <div class="small muted">Not when it is dispatched, and not when it is scanned —
              when it is handed over.</div></li>
        </ul>
      </div>
    </div></div>`;
}
