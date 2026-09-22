import { apiUrl } from '@/lib/config';
import { ApiError, parseErrorBody } from './errors';
import { csrfToken, needsCsrf } from './csrf';
import {
  accessTokenIsStale, clearTokens, getAccessToken, getRefreshToken, storeTokens, type Tokens,
} from './tokens';

export type RequestOptions = {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  query?: Record<string, string | number | boolean | null | undefined>;
  body?: unknown;
  /**
   * Sends an `Idempotency-Key`. Pass a string to hold one steady across
   * retries of the same action — `true` mints a fresh one, which is right for
   * a first press and wrong for a retry.
   */
  idempotent?: boolean | string;
  anonymous?: boolean;
  noRetry?: boolean;
  signal?: AbortSignal;
  headers?: Record<string, string>;
};

type SessionEndedListener = (reason: 'expired' | 'revoked') => void;
const listeners = new Set<SessionEndedListener>();

export function onSessionEnded(listener: SessionEndedListener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function endSession(reason: 'expired' | 'revoked'): void {
  clearTokens();
  for (const listener of listeners) listener(reason);
}

export function newEventId(): string {
  const c = globalThis.crypto;
  if (c && 'randomUUID' in c) return c.randomUUID();
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 14)}`;
}

function buildQuery(query: RequestOptions['query']): string {
  if (!query) return '';
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === null || value === undefined || value === '') continue;
    params.append(key, String(value));
  }
  const s = params.toString();
  return s ? `?${s}` : '';
}

/**
 * Exchanging the refresh token, exactly once at a time.
 *
 * The backend rotates it on use and treats a second presentation as theft by
 * ending every session on the account. The counter screen loads several things
 * at once, so without this a tablet waking up would spend the same refresh
 * token four times and sign the operator out mid-shift.
 */
let refreshInFlight: Promise<boolean> | null = null;

async function refreshAccessToken(): Promise<boolean> {
  refreshInFlight ??= (async () => {
    try {
      const refreshToken = getRefreshToken();
      if (!refreshToken) return false;

      const response = await fetch(apiUrl('/auth/refresh'), {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ refreshToken }),
      });

      if (!response.ok) {
        // Terminal: spent, revoked, or signed out everywhere. Retrying would be
        // the replay the backend is watching for.
        endSession(response.status === 401 ? 'expired' : 'revoked');
        return false;
      }

      storeTokens((await response.json()) as Tokens);
      return true;
    } catch {
      // A dropped connection is not proof the token is dead, so the session
      // stays and the caller reports being offline.
      return false;
    } finally {
      refreshInFlight = null;
    }
  })();

  return refreshInFlight;
}

async function readBody(response: Response) {
  if (response.status === 204 || response.headers.get('Content-Length') === '0') return null;
  const contentType = response.headers.get('Content-Type') ?? '';
  if (contentType.includes('application/json')) {
    try {
      return await response.json();
    } catch {
      return null;
    }
  }
  const text = await response.text();
  return text || null;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const {
    method = 'GET', query, body, idempotent, anonymous = false,
    noRetry = false, signal, headers: extraHeaders,
  } = options;

  const url = `${path}${buildQuery(query)}`;

  // Minted once here rather than per attempt, so the retry below presents the
  // same key and the server recognises the same action.
  const idempotencyKey =
    typeof idempotent === 'string' ? idempotent : idempotent ? newEventId() : null;

  const send = async (): Promise<Response> => {
    const headers: Record<string, string> = { Accept: 'application/json', ...extraHeaders };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;

    if (!anonymous) {
      const token = getAccessToken();
      if (token) headers['Authorization'] = `Bearer ${token}`;
    }

    if (needsCsrf(method, path)) {
      const token = await csrfToken();
      if (token) headers['X-XSRF-TOKEN'] = token;
    }

    return fetch(apiUrl(url), {
      method,
      headers,
      credentials: 'include',
      body: body === undefined ? undefined : JSON.stringify(body),
      ...(signal ? { signal } : {}),
    });
  };

  // Refreshed before the call rather than after a 401: it saves a round trip
  // and, more to the point, avoids a failed handover the operator then has to
  // decide whether to repeat with a driver standing there.
  if (!anonymous && !noRetry && accessTokenIsStale() && getRefreshToken()) {
    await refreshAccessToken();
  }

  let response: Response;
  try {
    response = await send();
  } catch (cause) {
    if (signal?.aborted) throw cause;
    throw new ApiError({
      status: 0,
      path,
      message: 'No connection to Sujula. Check the shop’s network and try again.',
    });
  }

  if (response.status === 401 && !anonymous && !noRetry) {
    if (await refreshAccessToken()) {
      try {
        response = await send();
      } catch {
        throw new ApiError({
          status: 0,
          path,
          message: 'No connection to Sujula. Check the shop’s network and try again.',
        });
      }
    }
  }

  if (!response.ok) {
    if (response.status === 401 && !anonymous) endSession('expired');
    throw parseErrorBody(response.status, path, await readBody(response), defaultMessage(response.status));
  }

  return (await readBody(response)) as T;
}

function defaultMessage(status: number): string {
  switch (status) {
    case 400:
      return 'That was not accepted. Check what was entered.';
    case 401:
      return 'Your session has ended. Sign in again.';
    case 403:
      return 'This account is not allowed to do that.';
    case 404:
      return 'That is not here.';
    case 409:
      return 'That conflicts with the current state. Refresh and try again.';
    case 422:
      return 'That cannot be done yet.';
    case 429:
      return 'Too many attempts. Wait a moment and try again.';
    default:
      return status >= 500 ? 'Something went wrong on our side.' : 'That did not work.';
  }
}

export const api = {
  get: <T>(path: string, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    request<T>(path, { ...options, method: 'GET' }),
  post: <T>(path: string, body?: unknown, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    request<T>(path, { ...options, method: 'POST', body }),
  patch: <T>(path: string, body?: unknown, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    request<T>(path, { ...options, method: 'PATCH', body }),
  delete: <T>(path: string, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    request<T>(path, { ...options, method: 'DELETE' }),
};

export { refreshAccessToken };
