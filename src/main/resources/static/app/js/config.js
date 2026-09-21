/*
 * Where the API is, and the handful of knobs a deployment may want to turn.
 *
 * On the web the answer is "the same origin this file came from", which is how
 * the Spring application serves it. In the Android and iOS wrappers there is no
 * such origin — the shell is loaded from the local bundle — so the base URL is
 * baked in at build time by writing window.SUJULA_CONFIG before this module is
 * imported. See NATIVE.md.
 */

const injected = (typeof window !== 'undefined' && window.SUJULA_CONFIG) || {};

function sameOriginBase() {
  // The shell lives at /app/; the API sits at the root beside it.
  const { origin, pathname } = window.location;
  const cut = pathname.indexOf('/app/');
  return cut === -1 ? origin : origin + pathname.slice(0, cut);
}

export const config = {
  /** Root of the API. No trailing slash. */
  apiBase: (injected.apiBase || sameOriginBase()).replace(/\/+$/, ''),

  /**
   * Raster tiles for the pin picker.
   *
   * A pin matters more here than anywhere else in the product: most of the
   * delivery area has no street addresses to geocode, so "drag the pin onto the
   * compound" is the address. Swap this for a paid tile provider before any
   * real traffic — the OSM community tile servers ask you not to point an
   * application at them.
   */
  tileUrl: injected.tileUrl || 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
  tileAttribution: injected.tileAttribution || '© OpenStreetMap contributors',

  /** Where the map opens when there is nothing better to centre on. */
  defaultMapCentre: injected.defaultMapCentre || { lat: 13.4549, lng: -16.5790, zoom: 12 },

  /** How long a page of the catalogue is. */
  pageSize: injected.pageSize || 20,

  /** Which client this is, for the minimum-version check in /config/public. */
  platform: injected.platform || 'web',
  appVersion: injected.appVersion || '1.0.0'
};
