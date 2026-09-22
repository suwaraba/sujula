import { tokenStore } from '@/auth/tokenStore';
import type { ApiErrorBody } from './types';

/**
 * The one place this console talks to the server.
 *
 * Three things live here that every call needs and no call should re-implement:
 * the bearer token, the CSRF token, and what to do about a 401.
 */

const BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '');

/** Raised for every non-2xx answer. Carries the server's own message. */
export class ApiError extends Error {
  readonly status: number;
  readonly body: ApiErrorBody | null;
  readonly fieldErrors: Record<string, string>;

  constructor(status: number, body: ApiErrorBody | null, fallback: string) {
    super(body?.message || fallback);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
    this.fieldErrors = body?.errors ?? {};
  }

  /** A 404 on somebody else's row is the right answer, not a bug to report. */
  get isNotFound(): boolean {
    return this.status === 404;
  }

  /** Support reading a surface it cannot decide on. */
  get isForbidden(): boolean {
    return this.status === 403;
  }

  /** Two agents acting on the same row at the same moment. */
  get isConflict(): boolean {
    return this.status === 409;
  }

  /**
   * The step-up endpoints answer 401 when the password or authenticator code
   * is wrong. That is not a dead session, so it must not trigger a refresh.
   */
  get isCredentialChallenge(): boolean {
    return this.status === 401 && /password|code|confirm/i.test(this.message);
  }
}

export class NetworkError extends Error {
  constructor(cause: unknown) {
    super('Could not reach the server. Check your connection and try again.');
    this.name = 'NetworkError';
    this.cause = cause;
  }
}

// ── CSRF ─────────────────────────────────────────────────────────────────────

/**
 * `/admin` is not in the server's CSRF exemption list, and the repository is a
 * `CookieCsrfTokenRepository.withHttpOnlyFalse()` — a double-submit cookie. So
 * every write has to echo the `XSRF-TOKEN` cookie back in a header.
 *
 * This only works same-origin: a cross-origin page cannot read the cookie.
 * That is why the dev server proxies the API rather than pointing at it.
 */
function csrfToken(): string | null {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
  return match ? decodeURIComponent(match[1]) : null;
}

let csrfPrimed = false;

/**
 * Makes the server mint an `XSRF-TOKEN` cookie before the first write.
 *
 * Any request through the filter chain does it, so a cheap public GET is
 * enough. Called once on start-up and again if a write ever finds no cookie.
 */
export async function primeCsrf(): Promise<void> {
  if (csrfToken()) {
    csrfPrimed = true;
    return;
  }
  try {
    await fetch(`${BASE_URL}/config/public`, { credentials: 'include' });
  } catch {
    /* offline; the write will fail with its own message */
  }
  csrfPrimed = true;
}

const MUTATING = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

// ── Refresh, single-flight ───────────────────────────────────────────────────

let refreshInFlight: Promise<boolean> | null = null;
let onSessionLost: (() => void) | null = null;

/** Lets `AuthContext` be told when the session could not be renewed. */
export function setSessionLostHandler(handler: (() => void) | null): void {
  onSessionLost = handler;
}

/**
 * Exchanges the refresh token for a new pair.
 *
 * Collapsed into one in-flight promise on purpose: the server rotates refresh
 * tokens, so two concurrent refreshes would race, and the second would present
 * a token the first had already spent — which the server correctly treats as a
 * replay and answers by killing the session.
 */
async function refreshSession(): Promise<boolean> {
  if (refreshInFlight) return refreshInFlight;

  const refreshToken = tokenStore.refreshToken();
  if (!refreshToken) return false;

  refreshInFlight = (async () => {
    try {
      const response = await fetch(`${BASE_URL}/auth/refresh`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken }),
      });
      if (!response.ok) return false;
      const tokens = (await response.json()) as {
        accessToken: string;
        expiresIn: number;
        refreshToken: string;
        user?: unknown;
      };
      tokenStore.set(tokens, (tokens.user as never) ?? null);
      return true;
    } catch {
      return false;
    } finally {
      // Cleared on the next tick so callers awaiting this promise all see the
      // same result before a new attempt can start.
      setTimeout(() => {
        refreshInFlight = null;
      }, 0);
    }
  })();

  return refreshInFlight;
}

// ── Request ──────────────────────────────────────────────────────────────────

/**
 * Query-string values. Written as an index signature so the typed search
 * interfaces in `endpoints.ts` can be passed straight through without each of
 * them having to declare one.
 */
export type QueryParams = Record<string, unknown>;

export interface RequestOptions {
  method?: string;
  query?: QueryParams | undefined;
  body?: unknown;
  /**
   * Sent as `Idempotency-Key`. Every write that can be replayed safely takes
   * one; `endpoints.ts` generates it per attempt so a retry after a timeout
   * cannot place a second order or release a second payout batch.
   */
  idempotencyKey?: string;
  signal?: AbortSignal;
  /** Set for `/auth` calls, which must not recurse into a refresh. */
  skipAuthRetry?: boolean;
}

function buildQuery(query: QueryParams | undefined): string {
  if (!query) return '';
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    if (Array.isArray(value)) {
      value.forEach((item) => {
        if (item !== undefined && item !== null && item !== '') params.append(key, String(item));
      });
    } else {
      params.set(key, String(value));
    }
  }
  const qs = params.toString();
  return qs ? `?${qs}` : '';
}

async function parseError(response: Response): Promise<ApiError> {
  let body: ApiErrorBody | null = null;
  try {
    const text = await response.text();
    if (text) body = JSON.parse(text) as ApiErrorBody;
  } catch {
    /* not JSON — a proxy error page, say */
  }
  return new ApiError(response.status, body, `${response.status} ${response.statusText}`);
}

/**
 * Whether this refusal is worth exchanging the refresh token over.
 *
 * 401 is the ordinary case, and since `FilterChainRefusals` it is the one the
 * server actually sends: a caller who has not identified themselves — no
 * header, a forged token, an expired one — is told to say who they are, while
 * 403 is reserved for a credential that was read and found to lack permission.
 *
 * The 403 arm is kept deliberately, and it is not dead weight. Before that
 * split the chain answered 403 to both, and a client refreshing only on 401
 * signed its user out every time an access token aged out and on every reload —
 * which is precisely what this console did until it was caught. Against a
 * deployment whose filter chain predates the fix, the arm costs one refresh
 * attempt and keeps people signed in.
 *
 * What it must never do is swallow a real refusal: 403 is the honest answer for
 * support reaching a decision endpoint. The two are told apart by what we are
 * holding. A live access token in memory means the server saw a credential and
 * refused on the merits, so the answer stands. No token, or one already aged
 * out, means the refusal was about identification, and a refresh is the reply.
 */
function shouldTryRefresh(status: number): boolean {
  if (status === 401) return true;
  if (status !== 403) return false;
  return tokenStore.accessToken() === null || tokenStore.accessTokenExpiring(0);
}

async function send(path: string, options: RequestOptions, attempt: number): Promise<Response> {
  const method = (options.method ?? 'GET').toUpperCase();
  const headers = new Headers({ Accept: 'application/json' });

  const token = tokenStore.accessToken();
  if (token) headers.set('Authorization', `Bearer ${token}`);

  if (options.body !== undefined) headers.set('Content-Type', 'application/json');
  if (options.idempotencyKey) headers.set('Idempotency-Key', options.idempotencyKey);

  if (MUTATING.has(method)) {
    if (!csrfPrimed) await primeCsrf();
    let xsrf = csrfToken();
    if (!xsrf) {
      // The cookie can be missing on the very first write of a fresh browser
      // profile. One prime, then give up and let the server answer.
      await primeCsrf();
      xsrf = csrfToken();
    }
    if (xsrf) headers.set('X-XSRF-TOKEN', xsrf);
  }

  let response: Response;
  try {
    response = await fetch(`${BASE_URL}${path}${buildQuery(options.query)}`, {
      method,
      headers,
      // Needed for the XSRF cookie to be sent back at all.
      credentials: 'include',
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
    });
  } catch (cause) {
    if ((cause as Error)?.name === 'AbortError') throw cause;
    throw new NetworkError(cause);
  }

  if (options.skipAuthRetry || attempt > 0) return response;
  if (!shouldTryRefresh(response.status)) return response;

  // A 401 with a message about a password or a code is a step-up refusal, not
  // an expired session. Refreshing would hide the real answer from the form
  // that asked for it.
  const peeked = await parseError(response.clone());
  if (peeked.isCredentialChallenge) return response;

  const renewed = await refreshSession();
  if (!renewed) {
    tokenStore.clear();
    onSessionLost?.();
    return response;
  }
  return send(path, options, attempt + 1);
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const response = await send(path, options, 0);

  if (!response.ok) throw await parseError(response);

  if (response.status === 204) return undefined as T;
  const text = await response.text();
  if (!text) return undefined as T;
  return JSON.parse(text) as T;
}

/** A per-attempt idempotency key. */
export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID();
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

export const http = {
  get: <T>(path: string, query?: object, signal?: AbortSignal) =>
    request<T>(path, { method: 'GET', query: query as QueryParams | undefined, signal }),
  post: <T>(path: string, body?: unknown, extra?: Partial<RequestOptions>) =>
    request<T>(path, { method: 'POST', body, ...extra }),
  patch: <T>(path: string, body?: unknown, extra?: Partial<RequestOptions>) =>
    request<T>(path, { method: 'PATCH', body, ...extra }),
  put: <T>(path: string, body?: unknown, extra?: Partial<RequestOptions>) =>
    request<T>(path, { method: 'PUT', body, ...extra }),
  del: <T>(path: string, extra?: Partial<RequestOptions>) =>
    request<T>(path, { method: 'DELETE', ...extra }),
};
