/*
 * "Where is this going?" — the first question the shop asks, and the only one
 * it insists on.
 *
 * It is asked before anything else because everything else depends on the
 * answer: which sellers are near enough to be worth showing, what delivery
 * will cost, whether it can be delivered at all. And it is asked as a
 * destination rather than as "your address", because on this marketplace the
 * buyer is usually not the person the parcel is for.
 *
 * Three ways to answer, in the order they work in the delivery area:
 *
 *   1. Country and town — always available, enough to rank a catalogue.
 *   2. Search for the address — a geocoder, where there is one to find.
 *   3. Drag the pin — the answer for the many places with no street address
 *      at all, and the one the driver actually uses.
 */

import { api } from '../api.js';
import { state, setPlace } from '../state.js';
import { createMap, currentPosition } from '../map.js';
import { ensureContext, shippableCountries, hasPlace } from '../delivery.js';
import { modal, esc, icon, toast, setBusy } from '../ui.js';

/** Dismissed for this visit only — the next launch asks again. */
let waivedForSession = false;

/**
 * The one that is open, if any.
 *
 * Two routes can reach for this question in the same tick — the boot sequence
 * asks, and the first route render asks again as it finishes — and two stacked
 * copies of a modal is a screen the buyer cannot get out of: they close one and
 * are still looking at the other.
 */
let openDialog = null;

export function waiveForSession() { waivedForSession = true; }
export function waived() { return waivedForSession; }

/**
 * The screens the question belongs on.
 *
 * It is asked because nothing on a shopping screen can be ranked, priced or
 * quoted without it. On the others it is an interruption with no payoff, and a
 * harmful one: somebody who followed a link straight to sign-in, to their
 * orders or to a tracking page is not shopping, and a full-screen question
 * about a parcel destination sits on top of what they actually came for.
 *
 * So the shopper looking at products is asked, and the person signing in or
 * chasing a parcel is not. Either way the strip under the navbar still carries
 * the prompt, and the next shopping screen asks again.
 */
const SHOPPING_ROUTES = [/^\/$/, /^\/browse/, /^\/search/, /^\/c\//, /^\/p\//,
                         /^\/store\//, /^\/cart$/, /^\/pickup-points/];

export function isShoppingRoute(path) {
  return SHOPPING_ROUTES.some(pattern => pattern.test(path || '/'));
}

export function shouldAskOnEntry(path) {
  return !hasPlace() && !waivedForSession && isShoppingRoute(path);
}

export function openDeliveryModal(options = {}) {
  if (openDialog) return openDialog;
  const place = state.place || {};
  const countries = shippableCountries();
  const fallbackCountry = place.countryCode
    || (state.config && state.config.baseCountry)
    || (countries[0] && countries[0].code)
    || '';

  const draft = {
    countryCode: fallbackCountry,
    city: place.city || '',
    state: place.state || '',
    addressLine: place.addressLine || '',
    postalCode: place.postalCode || '',
    latitude: place.latitude ?? null,
    longitude: place.longitude ?? null,
    confidence: place.confidence || null,
    needsPinConfirmation: place.needsPinConfirmation || false,
    mode: place.mode || 'HOME_DELIVERY'
  };

  const firstTime = !hasPlace();

  const body = `
    <div class="notice" style="margin-bottom:14px">
      ${icon('truck', 18)} Products are ranked by how close the seller is to
      <strong>this</strong> address — not to where you are. You can be anywhere.
    </div>

    <label class="field">
      <span class="label">Country the parcel is delivered in</span>
      <select class="input" data-field="countryCode">
        ${countries.map(c => `<option value="${esc(c.code)}"${c.code === draft.countryCode ? ' selected' : ''}>${esc(c.name)}</option>`).join('')}
      </select>
    </label>

    <label class="field">
      <span class="label">Town or city</span>
      <input class="input" data-field="city" autocomplete="address-level2"
             placeholder="Serrekunda" value="${esc(draft.city)}">
    </label>

    <label class="field">
      <span class="label">Area, street or landmark <span class="muted">(optional)</span></span>
      <input class="input" data-field="addressLine" autocomplete="address-line1"
             placeholder="Behind the mosque, Latrikunda" value="${esc(draft.addressLine)}">
      <span class="hint">Many places here have no street address. That is fine —
        put the pin on the compound below and the driver will have what they need.</span>
    </label>

    <div class="row" style="flex-wrap:wrap;gap:8px;margin-bottom:12px">
      <button type="button" class="btn btn-ghost btn-sm" data-action="find">
        ${icon('search', 16)} Find this address
      </button>
      <button type="button" class="btn btn-ghost btn-sm" data-action="locate">
        ${icon('pin', 16)} Use my current position
      </button>
    </div>

    <div class="field">
      <span class="label">Drop the pin where it is delivered</span>
      <div data-map style="height:260px"></div>
      <span class="hint" data-pin-readout></span>
    </div>`;

  const footer = `
    <button class="btn btn-primary" data-action="save">
      ${firstTime ? 'Show me what is near' : 'Save this destination'}
    </button>
    ${options.dismissible === false ? '' :
      `<button class="btn btn-ghost" data-action="skip">
        ${firstTime ? 'Just browse for now' : 'Cancel'}
      </button>`}`;

  // Held in a box because the map is built after the dialog exists, and
  // closing the dialog by any route has to take the map with it.
  const live = { map: null, saved: false };

  const dialog = modal({
    title: firstTime ? 'Where is this going?' : 'Change the delivery destination',
    subtitle: firstTime
      ? 'The address the parcel arrives at, which may be someone else’s.'
      : null,
    body,
    footer,
    dismissible: options.dismissible !== false,
    onClose: () => {
      openDialog = null;
      if (live.map) live.map.destroy();
      // Closing with the X is the same answer as "not now". Without this the
      // question would come back on the next shopping screen, which is the
      // behaviour that makes people hate a popup.
      if (!live.saved) waivedForSession = true;
    }
  });

  openDialog = dialog;

  const node = dialog.node;
  const field = name => node.querySelector(`[data-field="${name}"]`);
  const readout = node.querySelector('[data-pin-readout]');

  /* ── The map ────────────────────────────────────────────────────────────── */

  const mapHost = node.querySelector('[data-map]');
  const start = draft.latitude != null
    ? { lat: draft.latitude, lng: draft.longitude, zoom: 16 }
    : undefined;

  let pinMoved = draft.latitude != null;
  const map = createMap(mapHost, {
    ...(start || {}),
    onMove: point => {
      draft.latitude = round(point.latitude);
      draft.longitude = round(point.longitude);
      // A pin the buyer placed by hand is the strongest answer there is: it is
      // the only one that came from someone who has actually been there.
      draft.needsPinConfirmation = false;
      draft.confidence = 'EXACT';
      pinMoved = true;
      paintReadout();
    }
  });

  live.map = map;

  function round(value) { return Math.round(value * 1e6) / 1e6; }

  function paintReadout() {
    readout.innerHTML = pinMoved
      ? `Pin at ${draft.latitude.toFixed(5)}, ${draft.longitude.toFixed(5)}`
      : 'Move the map to place the pin, or fill the town in above and we will find it.';
  }
  paintReadout();

  /* ── Look the address up ────────────────────────────────────────────────── */

  async function find(button) {
    const country = field('countryCode').value;
    const city = field('city').value.trim();
    const line = field('addressLine').value.trim();
    if (!city && !line) {
      toast('Type a town or an area first.', 'error');
      field('city').focus();
      return;
    }
    setBusy(button, true, 'Looking…');
    try {
      const found = await api.validateAddress({
        street: line || null, city: city || null, countryCode: country || null
      });
      if (!found.available) {
        toast(found.message || 'Address lookup is off here — place the pin yourself.');
      } else if (!found.resolved) {
        toast(found.message || 'That address was not found. Place the pin instead.');
      } else {
        if (found.city && !city) field('city').value = found.city;
        draft.needsPinConfirmation = Boolean(found.needsPinConfirmation);
        draft.confidence = found.confidence || null;
        if (found.latitude != null) {
          pinMoved = true;
          map.setCentre(found.latitude, found.longitude, 16);
          toast(found.needsPinConfirmation
            ? 'Found it roughly — drag the map so the pin is exact.'
            : 'Found it. Check the pin is right.', found.needsPinConfirmation ? '' : 'ok');
        }
      }
    } catch (error) {
      toast(error.message, 'error');
    } finally {
      setBusy(button, false);
    }
  }

  /* ── Use the phone's position ───────────────────────────────────────────── */

  async function locate(button) {
    setBusy(button, true, 'Locating…');
    try {
      const position = await currentPosition();
      map.setCentre(position.latitude, position.longitude, 17);
      pinMoved = true;
      const found = await api.reverseGeocode({
        lat: position.latitude, lng: position.longitude
      }).catch(() => null);
      if (found && found.resolved) {
        if (found.city) field('city').value = found.city;
        if (found.countryCode && field('countryCode').querySelector(`option[value="${found.countryCode}"]`)) {
          field('countryCode').value = found.countryCode;
        }
        if (found.formattedAddress && !field('addressLine').value) {
          field('addressLine').value = found.formattedAddress;
        }
      }
      // Said plainly, because it is the mistake this screen exists to prevent.
      toast('That is where YOU are. If the parcel goes somewhere else, change it.');
    } catch (error) {
      toast(error.message, 'error');
    } finally {
      setBusy(button, false);
    }
  }

  /* ── Save ───────────────────────────────────────────────────────────────── */

  async function save(button) {
    const country = field('countryCode').value;
    const city = field('city').value.trim();
    if (!country) { toast('Choose the country first.', 'error'); return; }
    if (!city && !pinMoved) {
      toast('Give a town, or put the pin on the map.', 'error');
      field('city').focus();
      return;
    }

    setBusy(button, true, 'Saving…');

    // Nobody moved the pin, so try to find the town ourselves before giving
    // up on distance. A destination with coordinates is ranked by how far each
    // seller actually is; one with only a country is not ranked at all, and
    // the difference is the whole proposition. Best effort: where there is no
    // geocoder, or the town is not on any map, the country still filters.
    if (!pinMoved) {
      try {
        const found = await api.validateAddress({
          street: field('addressLine').value.trim() || null,
          city,
          countryCode: country
        });
        if (found.resolved && found.latitude != null) {
          draft.latitude = round(found.latitude);
          draft.longitude = round(found.longitude);
          draft.confidence = found.confidence || null;
          // A town-level hit is a town-level hit: good enough to sort by, not
          // good enough to drive to, and the shopper is told which it is.
          draft.needsPinConfirmation = true;
          pinMoved = true;
        }
      } catch {
        /* Not worth a message: the save below still works without it. */
      }
    }

    const saved = {
      countryCode: country,
      city,
      state: draft.state || null,
      addressLine: field('addressLine').value.trim() || null,
      postalCode: draft.postalCode || null,
      latitude: pinMoved ? draft.latitude : null,
      longitude: pinMoved ? draft.longitude : null,
      confidence: draft.confidence,
      needsPinConfirmation: draft.needsPinConfirmation,
      mode: draft.mode
    };

    setPlace(saved);
    try {
      await ensureContext();
    } catch {
      /* Ranking falls back to the coordinates; nothing to tell the buyer. */
    }
    live.saved = true;
    dialog.close();
    if (options.onSaved) options.onSaved(saved);
  }

  node.querySelector('[data-action="find"]').addEventListener('click', e => find(e.currentTarget));
  node.querySelector('[data-action="locate"]').addEventListener('click', e => locate(e.currentTarget));
  node.querySelector('[data-action="save"]').addEventListener('click', e => save(e.currentTarget));

  const skip = node.querySelector('[data-action="skip"]');
  if (skip) {
    skip.addEventListener('click', () => {
      waivedForSession = true;
      dialog.close();
      if (options.onSkipped) options.onSkipped();
    });
  }

  return dialog;
}
