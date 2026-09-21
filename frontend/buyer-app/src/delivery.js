/*
 * Where the parcel is going — the one answer the whole storefront hangs on.
 *
 * The catalogue is ranked against the DELIVERY location, never the buyer's,
 * and serviceability and shipping are quoted against it too. So the shop asks
 * for it once, keeps it, and re-mints the server-side context behind the
 * buyer's back when it lapses. It never asks twice for an answer it already
 * has, and it never guesses a destination from the buyer's own whereabouts:
 * the buyer is in Madrid and the parcel is going to Serrekunda, and reading
 * one off the other would be wrong every time.
 */

import { api, ApiError } from './api.js';
import { state, setDeliveryContext } from './state.js';

/** Enough of an answer to rank a catalogue against? */
export function hasPlace() {
  return Boolean(state.place && (state.place.countryCode || hasPin()));
}

export function hasPin() {
  return Boolean(state.place && state.place.latitude != null && state.place.longitude != null);
}

/** One line a buyer recognises as their own destination. */
export function placeLabel(place) {
  const p = place || state.place;
  if (!p) return null;
  const parts = [p.addressLine, p.city, p.state].filter(Boolean);
  const country = countryName(p.countryCode);
  if (!parts.length) return country || p.countryCode || 'Somewhere';
  return parts.slice(0, 2).join(', ') + (country ? ', ' + country : '');
}

export function shortPlaceLabel(place) {
  const p = place || state.place;
  if (!p) return null;
  return p.city || p.addressLine || countryName(p.countryCode) || p.countryCode;
}

export function countryName(code) {
  if (!code) return null;
  const found = state.countries.find(c => c.code === code);
  return found ? found.name : code;
}

/** Countries this deployment actually ships to, for the destination picker. */
export function shippableCountries() {
  const shipping = state.countries.filter(c => c.ship);
  return shipping.length ? shipping : state.countries;
}

function fresh() {
  if (!state.deliveryContextId) return false;
  if (!state.deliveryContextExpiresAt) return true;
  // Re-mint a few minutes early rather than let a request fall off the edge.
  return state.deliveryContextExpiresAt - Date.now() > 5 * 60 * 1000;
}

let minting = null;

/**
 * A live delivery-context id, minted if need be.
 *
 * Returns null when the buyer has not told us where the parcel goes yet, which
 * is a normal state on a first visit and not an error: the catalogue still
 * renders, unranked, behind the question.
 */
export async function ensureContext() {
  if (!hasPlace()) return null;
  if (fresh()) return state.deliveryContextId;
  if (minting) return minting;

  const p = state.place;
  minting = api.createDeliveryContext({
    addressId: p.addressId || null,
    latitude: p.latitude ?? null,
    longitude: p.longitude ?? null,
    addressLine: p.addressLine || null,
    city: p.city || null,
    state: p.state || null,
    postalCode: p.postalCode || null,
    countryCode: p.countryCode || null,
    mode: p.mode || 'HOME_DELIVERY',
    pickupPointId: p.pickupPointId || null,
    // The context carries the buyer's display currency, which is the payer's
    // and has nothing to do with the destination. Both travel together; they
    // are not derived from each other.
    currency: state.currencyCode || null
  }).then(response => {
    setDeliveryContext(response);
    return response.id;
  }).catch(error => {
    // A destination we cannot mint a context for still ranks the catalogue by
    // its coordinates, so this is a degradation rather than a failure.
    console.warn('[sujula] delivery context', error);
    setDeliveryContext(null);
    return null;
  }).finally(() => { minting = null; });

  return minting;
}

/**
 * The location and currency parameters every catalogue call carries.
 *
 * Note that currency is the buyer's and the rest is the destination's. The
 * server takes them as separate arguments for exactly that reason.
 */
export function deliveryParams(contextId) {
  const params = {};
  const id = contextId === undefined ? state.deliveryContextId : contextId;
  const p = state.place;

  if (id) {
    params.deliverableTo = id;
  } else if (p) {
    // No context yet — the coordinates still rank the page.
    if (p.latitude != null && p.longitude != null) {
      params.deliveryLat = p.latitude;
      params.deliveryLng = p.longitude;
    }
    if (p.countryCode) params.deliveryCountry = p.countryCode;
  }

  if (state.currencyCode) params.currency = state.currencyCode;
  return params;
}

/**
 * Run a catalogue call with the delivery parameters attached, re-minting once
 * if the context has expired between the page loading and the call landing.
 */
export async function withDelivery(call, extra) {
  await ensureContext();
  try {
    return await call({ ...deliveryParams(), ...(extra || {}) });
  } catch (error) {
    if (error instanceof ApiError && error.isStaleDeliveryContext) {
      setDeliveryContext(null);
      const id = await ensureContext();
      return call({ ...deliveryParams(id), ...(extra || {}) });
    }
    throw error;
  }
}

/** True when the pin is a guess and the buyer should be asked to move it. */
export function pinNeedsConfirming() {
  return Boolean(state.place && state.place.needsPinConfirmation);
}
