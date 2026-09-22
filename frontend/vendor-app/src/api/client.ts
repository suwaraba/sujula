import { apiUrl } from '@/lib/config';
import { ApiError, parseErrorBody } from './errors';
import { csrfToken, needsCsrf } from './csrf';
import {
  accessTokenIsStale,
  clearTokens,
  getAccessToken,
  getRefreshToken,
  storeTokens,
  type Tokens,
} from './tokens';

export type RequestOptions = {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  query?: Record<string, string | number | boolean | null | undefined>;
  body?: unknown;
  /**
   * Sends an `Idempotency-Key`. Pass a string to reuse one across retries of
   * the same logical action — `true` mints a fresh key, which is right for a
   * button press and wrong for a retry loop.
   */
  idempotent?: boolean | string;
  /** Skips the bearer header. Only the token endpoints want this. */
  anonymous?: boolean;
  /** Skips the refresh-and-retry dance. The refresh call itself sets this. */
  noRetry?: boolean;
  signal?: AbortSignal;
  /** Expect bytes rather than JSON — the parcel label, a statement PDF. */
  responseType?: 'json' | 'blob' | 'text';
  headers?: Record<string, string>;
};

/** Notified when the session ends for a reason the user did not choose. */
type SessionEndedListener = (reason: 'expired' | 'revoked') => void;
const sessionEndedListeners = new Set<SessionEndedListener>();

export function onSessionEnded(listener: SessionEndedListener): () => void {
  sessionEndedListeners.add(listener);
  return () => sessionEndedListeners.delete(listener);
}

async function endSession(reason: 'expired' | 'revoked'): Promise<void> {
  await clearTokens();
  for (const listener of sessionEndedListeners) listener(reason);
}

function newIdempotencyKey(): string {
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
 * The single-flight promise is not a micro-optimisation. The backend rotates
 * the refresh token on use and treats a replay as theft by ending every session
 * on the account. A dashboard that fires six queries which all 401 together
 * would, without this, spend the same refresh token six times and sign the
 * seller out of their own shop.
 */
let refreshInFlight: Promise<boolean> | null = null;

async function refreshAccessToken(): Promise<boolean> {
  refreshInFlight ??= (async () => {
    try {
      const refreshToken = await getRefreshToken();
      if (!refreshToken) return false;

      const response = await fetch(apiUrl('/auth/refresh'), {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ refreshToken }),
      });

      if (!response.ok) {
        // A refused refresh is terminal: the token was spent, revoked, or the
        // account was signed out everywhere. Retrying would be the replay the
        // backend is watching for.
        await endSession(response.status === 401 ? 'expired' : 'revoked');
        return false;
      }

      const tokens = (await response.json()) as Tokens;
      await storeTokens(tokens);
      return true;
    } catch {
      // A network failure is not proof the token is dead, so the session is
      // left intact and the caller surfaces an offline error.
      return false;
    } finally {
      refreshInFlight = null;
    }
  })();

  return refreshInFlight;
}

async function readBody(response: Response, responseType: RequestOptions['responseType']) {
  if (response.status === 204 || response.headers.get('Content-Length') === '0') return null;
  if (responseType === 'blob') return response.blob();
  if (responseType === 'text') return response.text();

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
    method = 'GET',
    query,
    body,
    idempotent,
    anonymous = false,
    noRetry = false,
    signal,
    responseType = 'json',
    headers: extraHeaders,
  } = options;

  const url = `${path}${buildQuery(query)}`;

  // A key minted here rather than per attempt, so the retry below presents the
  // same one and the server recognises it as the same action.
  const idempotencyKey =
    typeof idempotent === 'string' ? idempotent : idempotent ? newIdempotencyKey() : null;

  const send = async (): Promise<Response> => {
    const headers: Record<string, string> = {
      Accept: responseType === 'blob' ? '*/*' : 'application/json',
      ...extraHeaders,
    };

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

  // A stale access token is refreshed before the call rather than after a 401,
  // which saves a round trip and, more importantly, avoids a failed mutation
  // that the caller would then have to decide whether to repeat.
  if (!anonymous && !noRetry && accessTokenIsStale() && (await getRefreshToken())) {
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
      message: 'Could not reach Sujula. Check your connection and try again.',
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
          message: 'Could not reach Sujula. Check your connection and try again.',
        });
      }
    }
  }

  if (!response.ok) {
    if (response.status === 401 && !anonymous) await endSession('expired');
    const errorBody = await readBody(response, 'json');
    throw parseErrorBody(response.status, path, errorBody, defaultMessageFor(response.status));
  }

  return (await readBody(response, responseType)) as T;
}

function defaultMessageFor(status: number): string {
  switch (status) {
    case 400:
      return 'Some of that was not accepted. Check the highlighted fields.';
    case 401:
      return 'Your session has ended. Sign in again.';
    case 403:
      return 'Your account is not allowed to do that.';
    case 404:
      return 'That is not here.';
    case 409:
      return 'That conflicts with the current state. Reload and try again.';
    case 413:
      return 'That file is too large.';
    case 422:
      return 'That cannot be done in the current state.';
    case 429:
      return 'Too many attempts. Wait a moment and try again.';
    case 503:
      return 'Sujula is briefly unavailable. Try again shortly.';
    default:
      return status >= 500 ? 'Something went wrong on our side.' : 'That did not work.';
  }
}

export const api = {
  get: <T>(path: string, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    request<T>(path, { ...options, method: 'GET' }),
  post: <T>(path: string, body?: unknown, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    request<T>(path, { ...options, method: 'POST', body }),
  put: <T>(path: string, body?: unknown, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    request<T>(path, { ...options, method: 'PUT', body }),
  patch: <T>(path: string, body?: unknown, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    request<T>(path, { ...options, method: 'PATCH', body }),
  delete: <T>(path: string, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    request<T>(path, { ...options, method: 'DELETE' }),
};

export { refreshAccessToken };
