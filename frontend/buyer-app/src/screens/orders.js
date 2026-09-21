/*
 * A buyer's orders, and one order in full.
 *
 * An order here is not one shipment. It is one payment split into a sub-order
 * per seller, each with its own status, its own parcel and its own money —
 * one may be delivered while another is still being packed, and cancelling
 * one must not touch the rest. So the detail screen is a list of sellers
 * rather than a single progress bar, and every action names the seller it
 * applies to.
 */

import { api } from '../api.js';
import { signedIn } from '../state.js';
import { money } from '../money.js';
import { esc, icon, toast, setBusy, modal, emptyState, errorState, titleCase, dateLabel } from '../ui.js';

function signInGate(what) {
  return `<div class="wrap"><div class="section"><div class="card panel center">
    ${icon('user', 40)}
    <h1>Sign in to see ${esc(what)}</h1>
    <p class="muted">Orders belong to the account that placed them.</p>
    <a class="btn btn-primary" href="#/signin?next=${encodeURIComponent('/orders')}">Sign in</a>
    <p class="small muted" style="margin-top:14px">Following a parcel for somebody else?
      <a href="#/track">Use the tracking code</a> — no account needed.</p>
  </div></div></div>`;
}

export async function ordersView({ outlet }) {
  if (!signedIn()) { outlet.innerHTML = signInGate('your orders'); return; }

  outlet.innerHTML = `<div class="wrap"><div class="section">
    <h1>Your orders</h1><div class="sk" style="height:140px"></div></div></div>`;

  try {
    const page = await api.orders({ page: 0, size: 20 });
    const items = page.items || [];

    outlet.innerHTML = `<div class="wrap"><div class="section">
      <h1>Your orders</h1>
      ${items.length ? `<div class="card">${items.map(orderRow).join('')}</div>`
        : emptyState('box', 'No orders yet',
            'When you buy something it appears here, seller by seller.',
            '<a class="btn btn-primary" href="#/browse">Browse the shop</a>')}
      <p class="small muted center" style="margin-top:16px">
        Following someone else’s parcel? <a href="#/track">Track it with the code</a>.</p>
    </div></div>`;
  } catch (error) {
    outlet.innerHTML = `<div class="wrap"><div class="section">${errorState(error)}</div></div>`;
    outlet.querySelector('[data-retry]').addEventListener('click', () => ordersView({ outlet }));
  }
}

function orderRow(order) {
  return `<a class="order-row" href="#/orders/${order.id}">
    <img src="${esc(order.leadImageUrl || '')}" alt="">
    <span class="grow">
      <strong>${esc(order.orderNumber)}</strong>
      <span class="small muted" style="display:block">
        ${esc(dateLabel(order.placedAt))} · ${order.itemCount} item${order.itemCount === 1 ? '' : 's'}
        from ${order.vendorCount} seller${order.vendorCount === 1 ? '' : 's'}</span>
      <span class="row" style="margin-top:6px">
        <span class="pill">${esc(titleCase(order.status))}</span>
        <span class="pill ${order.paymentStatus === 'PAID' ? 'pill-near' : 'pill-far'}">
          ${esc(titleCase(order.paymentStatus))}</span>
      </span>
    </span>
    <span class="nowrap"><strong>${esc(money(order.total, order.currency))}</strong></span>
  </a>`;
}

/* ── One order ────────────────────────────────────────────────────────────── */

export async function orderView({ params, outlet }) {
  if (!signedIn()) { outlet.innerHTML = signInGate('this order'); return; }

  outlet.innerHTML = `<div class="wrap"><div class="section">
    <div class="sk" style="height:220px"></div></div></div>`;

  let order;
  let tracking = null;
  try {
    order = await api.order(params.id);
    tracking = await api.orderTracking(params.id).catch(() => null);
  } catch (error) {
    outlet.innerHTML = `<div class="wrap"><div class="section">${errorState(error)}</div></div>`;
    outlet.querySelector('[data-retry]').addEventListener('click', () => orderView({ params, outlet }));
    return;
  }

  const timelines = (tracking && tracking.timelines) || [];
  const to = order.shippingTo || {};

  outlet.innerHTML = `
    <div class="wrap"><div class="section">
      <a class="link" href="#/orders">${icon('chevron', 14)} All orders</a>
      <div class="row-between" style="margin:10px 0 14px">
        <div>
          <h1 style="margin-bottom:2px">${esc(order.orderNumber)}</h1>
          <p class="small muted" style="margin:0">Placed ${esc(dateLabel(order.placedAt))}</p>
        </div>
        <span class="pill ${order.paymentStatus === 'PAID' ? 'pill-near' : 'pill-far'}">
          ${esc(titleCase(order.paymentStatus))}</span>
      </div>

      ${order.paymentStatus !== 'PAID' && order.status !== 'CANCELLED' ? `
        <div class="notice notice-warn" style="margin-bottom:14px">
          This order is not paid yet.
          <button class="link" data-action="pay" type="button">Pay for it now</button>
        </div>` : ''}

      <div class="card panel" style="margin-bottom:14px">
        <h2>Going to</h2>
        <p style="margin:0"><strong>${esc(to.fullName || '')}</strong>
          ${to.phone ? `<span class="muted"> · ${esc(to.phone)}</span>` : ''}</p>
        <p class="small muted" style="margin:4px 0 0">
          ${esc([to.street, to.apartment, to.city, to.state, to.country].filter(Boolean).join(', '))}</p>
        ${order.trackingCode ? `<p class="small" style="margin:10px 0 0">
          Tracking code <strong>${esc(order.trackingCode)}</strong> —
          <a href="#/track/${encodeURIComponent(order.trackingCode)}">the recipient can follow it here</a>
          without an account.</p>` : ''}
      </div>

      ${(order.vendorOrders || []).map(v => vendorPanel(order, v, timelines)).join('')}

      <div class="card panel" style="margin-top:14px">
        <h2>${order.paymentStatus === 'PAID' ? 'What you paid' : 'What it comes to'}</h2>
        <div class="totals">
          <div class="line"><span>Items</span><strong>${esc(money(order.subtotal, order.currency))}</strong></div>
          ${order.discount && Number(order.discount) > 0
            ? `<div class="line"><span>Discount</span><strong>−${esc(money(order.discount, order.currency))}</strong></div>` : ''}
          <div class="line"><span>Delivery</span><strong>${esc(money(order.shipping, order.currency))}</strong></div>
          ${order.tax && Number(order.tax) > 0
            ? `<div class="line"><span>Tax</span><strong>${esc(money(order.tax, order.currency))}</strong></div>` : ''}
          <div class="line grand"><span>Total</span><span>${esc(money(order.total, order.currency))}</span></div>
        </div>
        <p class="tiny muted" style="margin:10px 0 0">
          ${esc(titleCase(order.paymentMethod || ''))}${order.paidAt ? ' · paid ' + esc(dateLabel(order.paidAt)) : ''}.
          This total was fixed when the order was placed and has not been re-priced since.</p>
      </div>

      ${(order.refunds || []).length ? `<div class="card panel" style="margin-top:14px">
        <h2>Refunds</h2>
        ${order.refunds.map(r => `<div class="row-between small" style="padding:6px 0">
          <span>${esc(r.storeName || r.reference)} <span class="muted">${esc(titleCase(r.status))}</span></span>
          <strong>${esc(money(r.amount, r.currency))}</strong></div>`).join('')}
      </div>` : ''}

      ${order.cancellable ? `<button class="btn btn-danger btn-block" data-action="cancel-order"
          style="margin-top:14px">Cancel the whole order</button>`
        : order.cancellableBlockedBy ? `<p class="small muted center" style="margin-top:14px">
            ${esc(order.cancellableBlockedBy)}</p>` : ''}
    </div></div>`;

  wireOrder(outlet, order, params);
}

function vendorPanel(order, vendor, timelines) {
  const timeline = timelines.find(t => t.vendorOrderId === vendor.vendorOrderId);
  const events = (timeline && timeline.events) || [];

  return `<div class="card" style="margin-bottom:14px" data-vendor="${vendor.vendorOrderId}">
    <div class="vendor-head">
      ${icon('store', 18)}
      <strong class="grow truncate">${esc(vendor.storeName || 'Seller')}</strong>
      <span class="pill">${esc(titleCase(vendor.status))}</span>
    </div>

    ${(vendor.lines || []).map(l => `<div class="cart-line">
      <img src="${esc(l.imageUrl || '')}" alt="">
      <div class="grow">
        <a href="#/p/${encodeURIComponent(l.productSlug || '')}"
           style="font-weight:600;color:inherit;text-decoration:none">${esc(l.productName)}</a>
        <div class="small muted">${l.quantity} × ${esc(money(l.unitPrice, l.currency || order.currency))}</div>
      </div>
      <strong class="nowrap">${esc(money(l.lineTotal, l.currency || order.currency))}</strong>
    </div>`).join('')}

    <div class="panel" style="border-top:1px solid var(--line)">
      ${events.length ? `<ul class="timeline">
        ${events.map((e, i) => `<li data-done="${i < events.length}">
          <strong>${esc(titleCase(e.status))}</strong>
          <div class="small muted">${esc(e.description || '')}
            ${e.location ? ' · ' + esc(e.location) : ''}
            ${e.at ? ' · ' + esc(dateLabel(e.at)) : ''}</div>
          ${e.proof ? `<div class="tiny muted">${icon('shield', 11)} proof on file</div>` : ''}
        </li>`).join('')}
      </ul>` : `<p class="small muted" style="margin:0">Nothing has moved yet.</p>`}

      ${timeline && timeline.pickupPointName
        ? `<p class="small">${icon('pin', 13)} Collect from <strong>${esc(timeline.pickupPointName)}</strong></p>` : ''}

      <div class="row" style="flex-wrap:wrap;margin-top:10px">
        ${vendor.receiptConfirmable ? `<button class="btn btn-primary btn-sm"
          data-action="confirm" data-vo="${vendor.vendorOrderId}">Confirm it arrived</button>` : ''}
        ${vendor.receiptConfirmed ? `<span class="pill pill-near">${icon('check', 12)} You confirmed it arrived</span>` : ''}
        ${vendor.cancellable ? `<button class="btn btn-ghost btn-sm"
          data-action="cancel-vendor" data-vo="${vendor.vendorOrderId}">Cancel just this seller</button>` : ''}
      </div>
      <p class="tiny muted" style="margin:8px 0 0">
        ${esc(vendor.storeName || 'This seller')} is paid ${esc(money(vendor.total, vendor.currency || order.currency))}
        only once this parcel is handed over.</p>
    </div>
  </div>`;
}

function wireOrder(outlet, order, params) {
  const reload = () => orderView({ params, outlet });

  outlet.querySelectorAll('[data-action="confirm"]').forEach(button => {
    button.addEventListener('click', async () => {
      setBusy(button, true, 'Confirming…');
      try {
        await api.confirmReceipt(order.id, Number(button.dataset.vo), {});
        toast('Thank you — the seller can be paid.', 'ok');
        reload();
      } catch (error) {
        toast(error.message, 'error');
        setBusy(button, false);
      }
    });
  });

  outlet.querySelectorAll('[data-action="cancel-vendor"]').forEach(button => {
    button.addEventListener('click', () => confirmCancel(
      'Cancel this seller’s part?',
      'The rest of your order is untouched — each seller ships and refunds on their own.',
      async reason => {
        await api.cancelVendorOrder(order.id, Number(button.dataset.vo), { reason });
        toast('Cancelled with that seller.', 'ok');
        reload();
      }));
  });

  const cancelAll = outlet.querySelector('[data-action="cancel-order"]');
  if (cancelAll) cancelAll.addEventListener('click', () => confirmCancel(
    'Cancel the whole order?',
    'Every seller in it is cancelled and anything already taken is refunded.',
    async reason => {
      await api.cancelOrder(order.id, { reason });
      toast('Order cancelled.', 'ok');
      reload();
    }));

  const pay = outlet.querySelector('[data-action="pay"]');
  if (pay) pay.addEventListener('click', () => retryPayment(order, reload));
}

function confirmCancel(title, subtitle, action) {
  const dialog = modal({
    title, subtitle,
    body: `<label class="field"><span class="label">Why? <span class="muted">(optional)</span></span>
      <textarea class="input" rows="3" data-reason placeholder="Changed my mind"></textarea></label>`,
    footer: `<button class="btn btn-danger" data-action="do">Yes, cancel it</button>
             <button class="btn btn-ghost" data-action="keep">Keep it</button>`
  });
  dialog.node.querySelector('[data-action="keep"]').addEventListener('click', dialog.close);
  dialog.node.querySelector('[data-action="do"]').addEventListener('click', async event => {
    setBusy(event.currentTarget, true, 'Cancelling…');
    try {
      await action(dialog.node.querySelector('[data-reason]').value.trim() || null);
      dialog.close();
    } catch (error) {
      toast(error.message, 'error');
      setBusy(event.currentTarget, false);
    }
  });
}

function retryPayment(order, reload) {
  const dialog = modal({
    title: 'Pay for this order',
    body: `<div class="choice-list">
      ${['CARD', 'PAYPAL', 'BANK_TRANSFER'].map(code => `
        <label class="choice"><input type="radio" name="retry" value="${code}">
          <span class="choice-title">${esc(titleCase(code))}</span></label>`).join('')}
    </div>`,
    footer: `<button class="btn btn-primary" data-action="go">Continue</button>`
  });

  dialog.node.querySelector('[data-action="go"]').addEventListener('click', async event => {
    const chosen = dialog.node.querySelector('input[name="retry"]:checked');
    if (!chosen) { toast('Pick a method.', 'error'); return; }
    setBusy(event.currentTarget, true, 'Starting…');
    try {
      const intent = await api.retryPayment(order.id, chosen.value);
      dialog.close();
      if (intent.checkoutUrl) location.href = intent.checkoutUrl;
      else { toast(intent.instructions || 'Payment started.', 'ok'); reload(); }
    } catch (error) {
      toast(error.message, 'error');
      setBusy(event.currentTarget, false);
    }
  });
}
