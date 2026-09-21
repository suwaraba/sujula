/*
 * Counters near the destination.
 *
 * Near the DESTINATION, not near the buyer: the buyer is in Madrid and there
 * is no counter there that helps. Open without an account because a shopper
 * usually chooses where to collect before they have one — and because the
 * person who will actually walk in has no account at all.
 */

import { api } from '../api.js';
import { state } from '../state.js';
import { shortPlaceLabel, hasPlace } from '../delivery.js';
import { openDeliveryModal } from '../components/deliveryModal.js';
import { renderChrome } from '../components/chrome.js';
import { esc, icon, emptyState, errorState } from '../ui.js';

const CAPACITY = {
  AVAILABLE: ['pill-near', 'Taking parcels'],
  LIMITED: ['pill-far', 'Filling up'],
  FULL: ['pill-no', 'Full'],
  CLOSED: ['pill-no', 'Closed']
};

export async function pickupPointsView({ outlet }) {
  const place = shortPlaceLabel();

  outlet.innerHTML = `<div class="wrap"><div class="section">
    <h1>Collection counters</h1>
    <p class="muted">${place ? 'Near ' + esc(place) + '.' : 'Set a destination and we will list the ones near it.'}
      A shop holds the parcel until it is collected — cheaper than the door, and nobody
      has to be at home.</p>
    <div data-list></div>
  </div></div>`;

  const host = outlet.querySelector('[data-list]');

  if (!hasPlace()) {
    host.innerHTML = emptyState('pin', 'Where is it going?',
      'Counters are listed against the delivery address, not against you.',
      '<button class="btn btn-primary" data-action="set" type="button">Set the destination</button>');
    host.querySelector('[data-action="set"]').addEventListener('click', () => openDeliveryModal({
      onSaved: () => { renderChrome(); document.dispatchEvent(new CustomEvent('sujula:reload')); }
    }));
    return;
  }

  host.innerHTML = '<div class="sk" style="height:140px"></div>';

  const p = state.place;
  const params = p.latitude != null
    ? { lat: p.latitude, lng: p.longitude, radius: 30 }
    : { city: p.city };

  try {
    const found = await api.pickupPoints(params);
    const points = found.points || [];
    host.innerHTML = points.length
      ? `<div class="card">${points.map(point).join('')}</div>
         ${found.note ? `<p class="small muted" style="margin-top:10px">${esc(found.note)}</p>` : ''}`
      : emptyState('store', 'No counters near there yet',
          'Have it brought to the door instead — that always works.',
          '<a class="btn btn-ghost" href="#/browse">Back to the shop</a>');
  } catch (error) {
    host.innerHTML = errorState(error);
    host.querySelector('[data-retry]').addEventListener('click', () => pickupPointsView({ outlet }));
  }
}

function point(p) {
  const [pill, label] = CAPACITY[p.capacity] || ['', ''];
  return `<div class="order-row" style="cursor:default">
    ${p.imageUrl ? `<img src="${esc(p.imageUrl)}" alt="">` : `<span style="color:var(--ink-3)">${icon('store', 26)}</span>`}
    <span class="grow">
      <strong>${esc(p.name)}</strong>
      <span class="small muted" style="display:block">
        ${esc([p.addressStreet, p.city].filter(Boolean).join(', '))}
        ${p.distanceKm != null ? ` · ${Number(p.distanceKm).toFixed(1)} km from the address` : ''}</span>
      ${p.openingHours ? `<span class="tiny muted" style="display:block">${esc(p.openingHours)}</span>` : ''}
      <span class="row" style="margin-top:6px">
        <span class="pill ${pill}">${esc(label)}</span>
        ${p.openNow ? '' : '<span class="pill pill-far">Shut right now</span>'}
      </span>
    </span>
  </div>`;
}
