/**
 * Where the API is.
 *
 * Empty in development, where Vite proxies the API prefixes so the page and the
 * API share an origin — which is what makes the CSRF cookie readable.
 */
const configured = (import.meta.env.VITE_API_BASE_URL ?? '').trim();

export const API_BASE_URL = configured.replace(/\/+$/, '');

export function apiUrl(path: string): string {
  return `${API_BASE_URL}${path.startsWith('/') ? path : `/${path}`}`;
}

/**
 * The one thing written to the device.
 *
 * A counter tablet is shared and sits in a shop all day, so as little as
 * possible is left on it. Nothing about a parcel, a recipient or a code is
 * ever stored; see `api/tokens.ts` for what is and why.
 */
export const STORAGE_KEYS = {
  refreshToken: 'sujula.counter.refreshToken',
} as const;
