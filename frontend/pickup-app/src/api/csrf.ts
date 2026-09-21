import { apiUrl } from '@/lib/config';

/**
 * The CSRF token, and the handshake that gets one.
 *
 * `SecurityConfig` issues an `XSRF-TOKEN` cookie readable by script and expects
 * it echoed in `X-XSRF-TOKEN`. It exempts the bearer surfaces — `/auth` and
 * `/me` — but *not* `/pickup`, so every parcel accepted, released or returned
 * needs one. Without it the first handover of the day is a 403.
 *
 * The list below mirrors the server's. Sending the header where it is not
 * needed is harmless; omitting it where it is needed is a refused handover with
 * a driver waiting, so it errs towards sending.
 */
const CSRF_EXEMPT: RegExp[] = [/^\/auth(\/|$)/, /^\/me(\/|$)/, /^\/currencies(\/|$)/];

export function needsCsrf(method: string, path: string): boolean {
  const verb = method.toUpperCase();
  if (verb === 'GET' || verb === 'HEAD' || verb === 'OPTIONS') return false;
  const bare = path.split('?')[0] ?? path;
  return !CSRF_EXEMPT.some((pattern) => pattern.test(bare));
}

function fromCookie(): string | null {
  const raw = globalThis.document?.cookie;
  if (!raw) return null;
  for (const part of raw.split(';')) {
    const [name, ...rest] = part.trim().split('=');
    if (name === 'XSRF-TOKEN') return decodeURIComponent(rest.join('='));
  }
  return null;
}

let handshake: Promise<void> | null = null;

/**
 * Makes the server set a token. Single-flighted, because a counter screen that
 * fires several calls at once should shake hands once and not several times.
 */
async function performHandshake(): Promise<void> {
  handshake ??= fetch(apiUrl('/config/public'), {
    method: 'GET',
    credentials: 'include',
    headers: { Accept: 'application/json' },
  })
    .then(() => undefined)
    .catch(() => undefined)
    .finally(() => {
      handshake = null;
    });
  return handshake;
}

export async function csrfToken(): Promise<string | null> {
  const existing = fromCookie();
  if (existing) return existing;
  await performHandshake();
  return fromCookie();
}
