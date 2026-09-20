/**
 * Where the API is.
 *
 * Empty in web development, where Vite proxies the API roots to the backend so
 * the page and the API share an origin — which is what makes the CSRF cookie
 * readable. A native build has no proxy and no same-origin story, so it is
 * given an absolute origin at build time.
 */
const configured = (import.meta.env.VITE_API_BASE_URL ?? '').trim();

export const API_BASE_URL = configured.replace(/\/+$/, '');

/** Joins the base to a path without producing a double slash or dropping one. */
export function apiUrl(path: string): string {
  const suffix = path.startsWith('/') ? path : `/${path}`;
  return `${API_BASE_URL}${suffix}`;
}

/**
 * Storage keys, named once so a rename cannot orphan a value on a device.
 *
 * There is one. Everything else about the session is asked of the server,
 * which is one fewer thing left behind on a phone that gets shared or sold.
 */
export const STORAGE_KEYS = {
  refreshToken: 'sujula.vendor.refreshToken',
} as const;
