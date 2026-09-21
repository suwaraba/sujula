/*
 * Signing in, signing up, and the account screen.
 *
 * Signing in is deliberately late in the journey: a shopper browses, fills a
 * basket and only needs an account when an order needs an owner. What they
 * filled before signing in is merged into the account basket rather than
 * thrown away.
 */

import { api } from '../api.js';
import { state, setAuth, setCurrency, signedIn, setCartToken, setCart } from '../state.js';
import { mergeGuestCart, syncCartContext } from '../components/cart.js';
import { openDeliveryModal, } from '../components/deliveryModal.js';
import { openCurrencyPicker, renderChrome } from '../components/chrome.js';
import { placeLabel } from '../delivery.js';
import { go } from '../router.js';
import { esc, icon, toast, setBusy, modal, errorState } from '../ui.js';

/* ── Sign in ──────────────────────────────────────────────────────────────── */

export async function signInView({ query, outlet }) {
  if (signedIn()) { go(query.next || '/account', { replace: true }); return; }
  const next = query.next || '/account';

  outlet.innerHTML = `
    <div class="wrap"><div class="section" style="max-width:440px;margin:0 auto">
      <h1>Sign in</h1>
      <form class="card panel" data-form>
        <label class="field"><span class="label">Email</span>
          <input class="input" type="email" name="email" autocomplete="email" required></label>
        <label class="field"><span class="label">Password</span>
          <input class="input" type="password" name="password" autocomplete="current-password" required></label>
        <div class="field hidden" data-totp>
          <span class="label">Authenticator code</span>
          <input class="input" name="totpCode" inputmode="numeric" autocomplete="one-time-code"
                 placeholder="123456">
          <span class="hint">Your password was right — this is the second step.</span>
        </div>
        <button class="btn btn-primary btn-block" type="submit">Sign in</button>
        <button class="btn btn-ghost btn-block" type="button" data-action="forgot"
                style="margin-top:8px">I forgot my password</button>
      </form>
      <p class="small center" style="margin-top:14px">
        New here? <a href="#/register${next ? '?next=' + encodeURIComponent(next) : ''}">Create an account</a></p>
    </div></div>`;

  const form = outlet.querySelector('[data-form]');

  form.addEventListener('submit', async event => {
    event.preventDefault();
    const button = form.querySelector('button[type="submit"]');
    setBusy(button, true, 'Signing in…');
    try {
      const result = await api.login({
        email: form.email.value.trim(),
        password: form.password.value,
        totpCode: form.totpCode ? form.totpCode.value.trim() : '',
        deviceLabel: deviceLabel()
      });

      if (result.mfaRequired) {
        outlet.querySelector('[data-totp]').classList.remove('hidden');
        form.totpCode.focus();
        toast('Enter the code from your authenticator.');
        setBusy(button, false);
        return;
      }

      await adopt(result.tokens);
      go(next, { replace: true });
    } catch (error) {
      toast(error.message, 'error');
      setBusy(button, false);
    }
  });

  outlet.querySelector('[data-action="forgot"]').addEventListener('click', () => openForgot(form.email.value));
}

function openForgot(email) {
  const dialog = modal({
    title: 'Reset your password',
    subtitle: 'We send a link to the address on the account.',
    body: `<label class="field"><span class="label">Email</span>
      <input class="input" type="email" data-email value="${esc(email || '')}"></label>`,
    footer: `<button class="btn btn-primary" data-action="send">Send the link</button>`
  });
  dialog.node.querySelector('[data-action="send"]').addEventListener('click', async event => {
    const value = dialog.node.querySelector('[data-email]').value.trim();
    if (!value) return;
    setBusy(event.currentTarget, true, 'Sending…');
    try {
      await api.forgotPassword(value);
    } catch {
      /* Answered the same either way: whether an address has an account here
         is not something this screen should confirm to whoever is typing. */
    }
    dialog.close();
    toast('If that address has an account, the link is on its way.', 'ok');
  });
}

/* ── Register ─────────────────────────────────────────────────────────────── */

export async function registerView({ query, outlet }) {
  if (signedIn()) { go(query.next || '/account', { replace: true }); return; }
  const next = query.next || '/account';

  outlet.innerHTML = `
    <div class="wrap"><div class="section" style="max-width:440px;margin:0 auto">
      <h1>Create an account</h1>
      <p class="muted small">You need one to place an order — it is who the refund goes back to.
        The person receiving the parcel does not need one.</p>
      <form class="card panel" data-form>
        <div class="row" style="gap:10px">
          <label class="field grow"><span class="label">First name</span>
            <input class="input" name="firstName" autocomplete="given-name" required></label>
          <label class="field grow"><span class="label">Last name</span>
            <input class="input" name="lastName" autocomplete="family-name" required></label>
        </div>
        <label class="field"><span class="label">Email</span>
          <input class="input" type="email" name="email" autocomplete="email" required></label>
        <label class="field"><span class="label">Your phone <span class="muted">(optional)</span></span>
          <input class="input" type="tel" name="phone" autocomplete="tel" placeholder="+34 …">
          <span class="hint">Yours, for order updates — not the recipient's.</span></label>
        <label class="field"><span class="label">Password</span>
          <input class="input" type="password" name="password" autocomplete="new-password"
                 minlength="10" required>
          <span class="hint">At least ten characters.</span></label>
        <button class="btn btn-primary btn-block" type="submit">Create the account</button>
      </form>
      <p class="small center" style="margin-top:14px">
        Already have one? <a href="#/signin${next ? '?next=' + encodeURIComponent(next) : ''}">Sign in</a></p>
    </div></div>`;

  const form = outlet.querySelector('[data-form]');
  form.addEventListener('submit', async event => {
    event.preventDefault();
    const button = form.querySelector('button[type="submit"]');
    setBusy(button, true, 'Creating…');
    try {
      const tokens = await api.register({
        email: form.email.value.trim(),
        password: form.password.value,
        firstName: form.firstName.value.trim(),
        lastName: form.lastName.value.trim(),
        phone: form.phone.value.trim(),
        // The buyer's own currency travels with the account. The destination
        // does not: it belongs to the parcel, not to the person.
        preferredCurrency: state.currencyCode || null,
        deviceLabel: deviceLabel()
      });
      await adopt(tokens);
      go(next, { replace: true });
    } catch (error) {
      toast(error.message, 'error');
      if (error.fieldErrors) {
        Object.entries(error.fieldErrors).forEach(([field, message]) => {
          const input = form.querySelector(`[name="${field}"]`);
          if (input) { input.setAttribute('aria-invalid', 'true'); input.title = message; }
        });
      }
      setBusy(button, false);
    }
  });
}

function deviceLabel() {
  const ua = navigator.userAgent || '';
  if (/android/i.test(ua)) return 'Sujula on Android';
  if (/iphone|ipad/i.test(ua)) return 'Sujula on iPhone';
  return 'Sujula in a browser';
}

/** Take the tokens, carry the guest basket over, and keep the buyer's currency. */
async function adopt(tokens) {
  const guestCart = state.cartToken;
  setAuth({
    accessToken: tokens.accessToken,
    refreshToken: tokens.refreshToken,
    user: tokens.user || null
  });

  const profile = tokens.user;
  if (profile && profile.preferredCurrency && !state.currencyPinned) {
    setCurrency(profile.preferredCurrency, { pinned: false });
  }

  setCartToken(null);
  setCart(null);
  await mergeGuestCart(guestCart);
  await syncCartContext();
  renderChrome();
  toast('Signed in.', 'ok');
}

/* ── The account ──────────────────────────────────────────────────────────── */

export async function accountView({ outlet }) {
  if (!signedIn()) { go('/signin', { replace: true }); return; }

  outlet.innerHTML = `<div class="wrap"><div class="section">
    <h1>Your account</h1><div class="sk" style="height:200px"></div></div></div>`;

  let me;
  try {
    me = await api.me();
  } catch (error) {
    outlet.innerHTML = `<div class="wrap"><div class="section">${errorState(error)}</div></div>`;
    outlet.querySelector('[data-retry]').addEventListener('click', () => accountView({ outlet }));
    return;
  }

  const profile = me.profile || {};
  const addresses = await api.addresses().catch(() => []);

  outlet.innerHTML = `
    <div class="wrap"><div class="section">
      <h1>Your account</h1>

      <div class="card panel" style="margin-bottom:14px">
        <strong>${esc(profile.fullName || [profile.firstName, profile.lastName].filter(Boolean).join(' '))}</strong>
        <div class="small muted">${esc(profile.email || '')}</div>
        ${profile.emailVerified ? '' : `<div class="notice notice-warn" style="margin-top:10px">
          Your email is not verified yet. Check your inbox for the link.</div>`}
      </div>

      <div class="card panel" style="margin-bottom:14px">
        <div class="row-between">
          <div><strong>Delivering to</strong>
            <div class="small muted">${esc(placeLabel() || 'Not set yet')}</div></div>
          <button class="btn btn-ghost btn-sm" data-action="place" type="button">Change</button>
        </div>
        <hr style="border:none;border-top:1px solid var(--line);margin:12px 0">
        <div class="row-between">
          <div><strong>You are charged in</strong>
            <div class="small muted">${esc(state.currencyCode || '')}${
              state.payer.countryCode ? ' · browsing from ' + esc(state.payer.countryCode) : ''}</div></div>
          <button class="btn btn-ghost btn-sm" data-action="currency" type="button">Change</button>
        </div>
      </div>

      <div class="card" style="margin-bottom:14px">
        <div class="vendor-head"><strong class="grow">Recipients you have saved</strong></div>
        ${addresses.length ? addresses.map(a => `<div class="order-row" style="cursor:default">
          <span style="color:var(--ink-3)">${icon('pin', 22)}</span>
          <span class="grow">
            <strong>${esc(a.fullName)}</strong>
            <span class="small muted" style="display:block">${esc(a.phone || '')} ·
              ${esc([a.street, a.city, a.countryCode].filter(Boolean).join(', '))}</span>
            ${a.isDefault ? '<span class="pill pill-near">Default</span>' : ''}
            ${a.needsPinConfirmation ? '<span class="pill pill-far">Pin not confirmed</span>' : ''}
          </span>
        </div>`).join('') : '<div class="panel small muted">Nobody saved yet.</div>'}
      </div>

      <a class="btn btn-ghost btn-block" href="#/orders" style="margin-bottom:8px">
        ${icon('box', 18)} Your orders</a>
      <button class="btn btn-danger btn-block" data-action="signout" type="button">
        ${icon('logout', 18)} Sign out</button>
    </div></div>`;

  outlet.querySelector('[data-action="place"]').addEventListener('click', () => openDeliveryModal({
    onSaved: () => { renderChrome(); accountView({ outlet }); }
  }));
  outlet.querySelector('[data-action="currency"]').addEventListener('click', openCurrencyPicker);

  outlet.querySelector('[data-action="signout"]').addEventListener('click', async event => {
    setBusy(event.currentTarget, true, 'Signing out…');
    const refreshToken = state.auth.refreshToken;
    try { await api.logout(refreshToken); } catch { /* the local tokens go either way */ }
    setAuth(null);
    setCartToken(null);
    setCart(null);
    renderChrome();
    toast('Signed out.');
    go('/', { replace: true });
  });
}
