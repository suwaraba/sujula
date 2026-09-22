/**
 * The one place a request leaves this app.
 *
 * Everything that has to be true of every call is true here rather than at four
 * hundred call sites:
 *
 *  - the bearer token is attached, and a single in-flight refresh is shared by
 *    every request that discovers the token has lapsed;
 *  - the CSRF token is attached to writes that need one. `/driver/**` is *not*
 *    on the backend's CSRF exemption list — `/auth` and `/me` are — so every
 *    custody event needs the `X-XSRF-TOKEN` header, and the cookie it comes
 *    from is only issued on a GET. The bootstrap below is that GET;
 *  - nothing is cached. These responses carry recipients' addresses;
 *  - a failure arrives as a typed {@link ApiError} carrying the backend's own
 *    sentence, because the backend writes better messages for a driver than a
 *    generic client would.
 */

import type { Tokens } from './types';

/** Empty means same origin, which is how this should be deployed. */
const BASE = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '');

/**
 * Paths the backend exempts from CSRF, mirrored from `SecurityConfig`.
 *
 * Kept as a list rather than "send the header always" because the header is
 * read from a cookie that may not exist yet, and sending an empty one to
 * `/auth/login` would turn a working sign-in into a 403.
 */
const CSRF_EXEMPT = [
  /^\/auth(\/|$)/,
  /^\/me(\/|$)/,
  /^\/geo(\/|$)/,
  /^\/delivery(\/|$)/,
  /^\/currencies(\/|$)/,
];

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

export interface ApiErrorBody {
  timestamp?: string;
  status?: number;
  error?: string;
  message?: string;
  /** Field name → what is wrong with it, from bean validation. */
  errors?: Record<string, string>;
  /** Present on a 500: eight characters the driver can quote to support. */
  reference?: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly body: ApiErrorBody;
  readonly fieldErrors: Record<string, string>;

  constructor(status: number, body: ApiErrorBody) {
    super(body.message || `Request failed (${status})`);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
    this.fieldErrors = body.errors ?? {};
  }

  /** A 401 is "sign in again"; everything else is a message to show. */
  get isUnauthorised(): boolean {
    return this.status === 401;
  }

  /** The row is not this driver's, or does not exist. The backend does not say which. */
  get isNotFound(): boolean {
    return this.status === 404;
  }

  get isRateLimited(): boolean {
    return this.status === 429;
  }

  /**
   * Whether sending this again could ever produce a different answer.
   *
   * A wrong code and a lapsed offer will not change, and an app that keeps
   * retrying them burns a driver's battery and their data bundle to no end.
   */
  get isRetryable(): boolean {
    return this.status >= 500 || this.status === 429 || this.status === 408;
  }
}

/** No response at all: aeroplane mode, a dead cell, a captive portal. */
export class OfflineError extends Error {
  constructor(cause?: unknown) {
    super('No connection');
    this.name = 'OfflineError';
    this.cause = cause;
  }
}

// ── The access token ─────────────────────────────────────────────────────────

/**
 * Held in a module variable and nowhere else.
 *
 * Not in `localStorage`, not in a cookie, not in IndexedDB. An access token in
 * persistent storage on a shared phone survives the driver handing it over; one
 * in memory does not survive closing the app, which is the correct lifetime for
 * a credential that the refresh token can always re-mint.
 */
let accessToken: string | null = null;
let accessTokenExpiresAt = 0;

type RefreshFn = () => Promise<Tokens>;
let refreshFn: RefreshFn | null = null;
let refreshInFlight: Promise<Tokens> | null = null;
let onSessionLost: (() => void) | null = null;

export function setAccessToken(token: string | null, expiresInSeconds = 0): void {
  accessToken = token;
  // Sixty seconds of slack. A token that expires while the request is in the
  // air is a 401 the driver sees as "it did not save".
  accessTokenExpiresAt = token ? Date.now() + Math.max(0, expiresInSeconds - 60) * 1000 : 0;
}

export function currentAccessToken(): string | null {
  return accessToken;
}

/** Wired up by the auth provider, which owns the refresh token. */
export function configureAuth(options: { refresh: RefreshFn; onSessionLost: () => void }): void {
  refreshFn = options.refresh;
  onSessionLost = options.onSessionLost;
}

/**
 * One refresh at a time, whatever asks for it.
 *
 * Refresh tokens are single-use and every exchange invalidates the last: two
 * parallel refreshes mean the second presents a spent token, which the backend
 * treats as theft and answers by ending the session. On a screen that fires
 * three queries at once — and this app has one — that is a driver being signed
 * out mid-round for no reason.
 */
async function refreshOnce(): Promise<Tokens> {
  if (!refreshFn) throw new ApiError(401, { message: 'Signed out' });
  if (!refreshInFlight) {
    refreshInFlight = refreshFn().finally(() => {
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
}

// ── CSRF ─────────────────────────────────────────────────────────────────────

function cookie(name: string): string | null {
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`));
  return match?.[1] ? decodeURIComponent(match[1]) : null;
}

let csrfBootstrapped: Promise<void> | null = null;

/**
 * Makes the backend issue its `XSRF-TOKEN` cookie.
 *
 * The repository is `CookieCsrfTokenRepository.withHttpOnlyFalse()`, so the
 * cookie is readable by script and echoed back in a header — but it is only
 * *issued* in response to a request, and the app's first act is a write. One
 * cheap public GET first is the whole of the fix.
 */
export function bootstrapCsrf(): Promise<void> {
  if (!csrfBootstrapped) {
    csrfBootstrapped = fetch(`${BASE}/config/public`, {
      method: 'GET',
      credentials: 'include',
    })
      .then(() => undefined)
      // Offline at startup is the normal case for this app. The token will be
      // fetched again on the first write that needs one.
      .catch(() => {
        csrfBootstrapped = null;
      });
  }
  return csrfBootstrapped;
}

function needsCsrf(method: string, path: string): boolean {
  return !SAFE_METHODS.has(method) && !CSRF_EXEMPT.some((pattern) => pattern.test(path));
}

// ── The request ──────────────────────────────────────────────────────────────

export interface RequestOptions {
  method?: string;
  body?: unknown;
  query?: Record<string, string | number | boolean | undefined | null>;
  /** Sent as `Idempotency-Key`. Reuse the same value for every retry. */
  idempotencyKey?: string;
  /** Skips the bearer token — for sign-in and refresh, which have none yet. */
  anonymous?: boolean;
  signal?: AbortSignal;
}

function url(path: string, query?: RequestOptions['query']): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(query ?? {})) {
    if (value !== undefined && value !== null && value !== '') search.set(key, String(value));
  }
  const qs = search.toString();
  return `${BASE}${path}${qs ? `?${qs}` : ''}`;
}

async function parse(response: Response): Promise<unknown> {
  if (response.status === 204) return null;
  const text = await response.text();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    // A proxy's HTML error page, a captive portal's login form. Not the
    // backend, so do not pretend its text is a message for the driver.
    return { message: 'The server answered with something this app cannot read.' };
  }
}

async function send(path: string, options: RequestOptions, retried: boolean): Promise<unknown> {
  const method = (options.method ?? 'GET').toUpperCase();

  if (!options.anonymous && accessToken && Date.now() > accessTokenExpiresAt) {
    // Refresh *before* the 401 rather than after it. On a slow connection the
    // round trip that discovers expiry is a round trip the driver waits through.
    try {
      await refreshOnce();
    } catch {
      /* Let the request itself fail with a 401 and take the usual path. */
    }
  }

  const headers: Record<string, string> = { Accept: 'application/json' };
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  if (!options.anonymous && accessToken) headers['Authorization'] = `Bearer ${accessToken}`;
  if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey;

  if (needsCsrf(method, path)) {
    let token = cookie('XSRF-TOKEN');
    if (!token) {
      await bootstrapCsrf();
      token = cookie('XSRF-TOKEN');
    }
    if (token) headers['X-XSRF-TOKEN'] = token;
  }

  let response: Response;
  try {
    response = await fetch(url(path, options.query), {
      method,
      headers,
      credentials: 'include',
      // Belt and braces with the backend's `no-store`: nothing on this surface
      // goes into the HTTP cache on a phone two drivers share.
      cache: 'no-store',
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      ...(options.signal ? { signal: options.signal } : {}),
    });
  } catch (cause) {
    if ((cause as Error)?.name === 'AbortError') throw cause;
    throw new OfflineError(cause);
  }

  if (response.ok) return parse(response);

  const body = ((await parse(response)) ?? {}) as ApiErrorBody;

  if (response.status === 401 && !options.anonymous && !retried) {
    try {
      await refreshOnce();
      return send(path, options, true);
    } catch {
      onSessionLost?.();
      throw new ApiError(401, body);
    }
  }

  // A 403 on a write, once, is very likely the CSRF cookie having been cleared
  // — by a browser sweeping storage, or by the app having been open since
  // before the backend restarted. Fetch a fresh one and try exactly once more.
  if (response.status === 403 && needsCsrf(method, path) && !retried) {
    csrfBootstrapped = null;
    await bootstrapCsrf();
    if (cookie('XSRF-TOKEN')) return send(path, options, true);
  }

  throw new ApiError(response.status, body);
}

export function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  return send(path, options, false) as Promise<T>;
}

export const api = {
  get: <T>(path: string, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    request<T>(path, { ...options, method: 'GET' }),
  post: <T>(path: string, body?: unknown, options: Omit<RequestOptions, 'method'> = {}) =>
    request<T>(path, { ...options, method: 'POST', body }),
  put: <T>(path: string, body?: unknown, options: Omit<RequestOptions, 'method'> = {}) =>
    request<T>(path, { ...options, method: 'PUT', body }),
  patch: <T>(path: string, body?: unknown, options: Omit<RequestOptions, 'method'> = {}) =>
    request<T>(path, { ...options, method: 'PATCH', body }),
  delete: <T>(path: string, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    request<T>(path, { ...options, method: 'DELETE' }),
};
