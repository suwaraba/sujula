/*
 * Following a parcel with a code and nothing else.
 *
 * This is the screen for the person the parcel is actually for. She did not
 * sign up for anything, she may not have an email address, and the phone in
 * her hand may not be a smartphone. So: a code in a box, no account, and a
 * page that is worth nothing to a stranger who guesses one — a town, a count
 * of parcels and a fixed phrase, never a name, a street or a price.
 */

import { api } from '../api.js';
import { esc, icon, titleCase, dateLabel, errorState } from '../ui.js';

export async function trackView({ params, outlet }) {
  const code = params.code || '';

  outlet.innerHTML = `
    <div class="wrap"><div class="section">
      <h1>Where is my parcel?</h1>
      <p class="muted">Type the tracking code from your message. No account needed.</p>
      <form class="card panel" data-form>
        <label class="field">
          <span class="label">Tracking code</span>
          <input class="input" name="code" value="${esc(code)}" autocomplete="off"
                 autocapitalize="characters" placeholder="SJL-XXXXXX">
        </label>
        <button class="btn btn-primary btn-block" type="submit">${icon('truck', 18)} Follow it</button>
      </form>
      <div data-result style="margin-top:16px"></div>
    </div></div>`;

  const form = outlet.querySelector('[data-form]');
  form.addEventListener('submit', event => {
    event.preventDefault();
    const value = form.code.value.trim();
    if (value) location.hash = '#/track/' + encodeURIComponent(value);
  });

  if (code) load(outlet, code);
}

async function load(outlet, code) {
  const host = outlet.querySelector('[data-result]');
  host.innerHTML = '<div class="sk" style="height:160px"></div>';

  let parcel;
  try {
    parcel = await api.track(code);
  } catch (error) {
    host.innerHTML = error.status === 404
      ? `<div class="notice notice-warn">No parcel with that code. Check the message again —
         the letters and numbers have to match exactly.</div>`
      : errorState(error);
    const retry = host.querySelector('[data-retry]');
    if (retry) retry.addEventListener('click', () => load(outlet, code));
    return;
  }

  host.innerHTML = `
    <div class="card panel">
      <div class="row" style="margin-bottom:10px">
        <span style="color:var(--accent)">${icon('truck', 28)}</span>
        <span class="grow">
          <strong style="display:block">${esc(titleCase(parcel.stage || ''))}</strong>
          <span class="small muted">${esc(parcel.description || '')}</span>
        </span>
      </div>

      <div class="totals">
        <div class="line"><span>Parcels</span>
          <strong>${parcel.parcelsDelivered} of ${parcel.parcels} delivered</strong></div>
        ${parcel.destinationCity ? `<div class="line"><span>Going to</span>
          <strong>${esc([parcel.destinationCity, parcel.destinationCountry].filter(Boolean).join(', '))}</strong></div>` : ''}
        ${parcel.estimatedDeliveryAt ? `<div class="line"><span>Expected</span>
          <strong>${esc(dateLabel(parcel.estimatedDeliveryAt))}</strong></div>` : ''}
      </div>

      ${parcel.pickupPointName ? `<div class="notice" style="margin-top:12px">
        ${icon('pin', 15)} Collect from <strong>${esc(parcel.pickupPointName)}</strong>
        ${parcel.pickupPointAddress ? `<span class="muted"> — ${esc(parcel.pickupPointAddress)}</span>` : ''}
      </div>` : ''}
    </div>

    ${(parcel.events || []).length ? `<div class="card panel" style="margin-top:14px">
      <h2>What has happened</h2>
      <ul class="timeline">
        ${parcel.events.map(e => `<li data-done="true">
          <strong>${esc(titleCase(e.stage || ''))}</strong>
          <div class="small muted">${esc(e.description || '')}${e.at ? ' · ' + esc(dateLabel(e.at)) : ''}</div>
        </li>`).join('')}
      </ul>
    </div>` : ''}

    <p class="small muted center" style="margin-top:14px">
      When the driver arrives you will be sent a code by SMS. Read it out to them —
      that code is what proves the parcel reached you, and it is what releases the
      seller's money.</p>`;
}
