/*
 * Where the API is, and the handful of knobs a deployment may want to turn.
 *
 * On the web the answer is "this origin", which is what the supported
 * deployment gives it: the client served from the API's own host. In the
 * Android and iOS wrappers there is no such origin — the shell is loaded from
 * the local bundle — so the base URL is written into window.SUJULA_CONFIG
 * before this module is imported. See NATIVE.md.
 */

const injected = (typeof window !== 'undefined' && window.SUJULA_CONFIG) || {};

/**
 * The API is on this origin.
 *
 * That is the supported deployment for every client on this platform: the same
 * host, with the API's prefixes reverse-proxied to Spring and everything else
 * falling back to index.html. SecurityConfig publishes no CORS configuration at
 * all, so a separate front-end origin fails its first preflight.
 */
function sameOriginBase() {
  return window.location.origin;
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
