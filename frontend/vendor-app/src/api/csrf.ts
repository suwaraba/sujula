import { apiUrl } from '@/lib/config';
import { isNative } from '@/lib/platform';

/**
 * The CSRF token, and the handshake that gets one.
 *
 * `SecurityConfig` issues an `XSRF-TOKEN` cookie readable by JavaScript and
 * expects it echoed back in `X-XSRF-TOKEN`. It exempts the bearer surfaces —
 * `/auth/**` and `/me/**` — and a handful of public lookups, but *not*
 * `/vendor/**`, so every listing written, every order accepted and every stock
 * adjustment needs one.
 *
 * The exemption list below mirrors `SecurityConfig.ignoringRequestMatchers`.
 * Sending the header where it is not required is harmless; failing to send it
 * where it is required is a 403 on a seller's first save, so the list errs
 * towards sending.
 */
const CSRF_EXEMPT: RegExp[] = [
  /^\/auth(\/|$)/,
  /^\/me(\/|$)/,
  /^\/webhooks(\/|$)/,
  /^\/api\/payments\/callback$/,
  /^\/geo(\/|$)/,
  /^\/delivery(\/|$)/,
  /^\/delivery-contexts(\/|$)/,
  /^\/currencies(\/|$)/,
  /^\/carts(\/|$)/,
  /^\/checkout(\/|$)/,
];

export function needsCsrf(method: string, path: string): boolean {
  const verb = method.toUpperCase();
  if (verb === 'GET' || verb === 'HEAD' || verb === 'OPTIONS') return false;
  const bare = path.split('?')[0] ?? path;
  return !CSRF_EXEMPT.some((p) => p.test(bare));
}

function fromDocumentCookie(): string | null {
  const raw = globalThis.document?.cookie;
  if (!raw) return null;
  for (const part of raw.split(';')) {
    const [name, ...rest] = part.trim().split('=');
    if (name === 'XSRF-TOKEN') {
      return decodeURIComponent(rest.join('='));
    }
  }
  return null;
}

async function fromNativeJar(): Promise<string | null> {
  if (!isNative()) return null;
  try {
    const { CapacitorCookies } = await import('@capacitor/core');
    const jar = (await CapacitorCookies.getCookies({ url: apiUrl('/') })) as Record<string, string>;
    const value = jar['XSRF-TOKEN'];
    return value ? decodeURIComponent(value) : null;
  } catch {
    // The WebView keeps the jar itself on most configurations; a failure here
    // just means falling through to the handshake below.
    return null;
  }
}

let handshake: Promise<void> | null = null;

/**
 * Makes the server set a token, if it has not already.
 *
 * `/config/public` is the cheapest public GET the application serves, and any
 * GET is enough: the repository writes the cookie on the first request that
 * asks for the token. Single-flighted, because a screen that fires four
 * mutations at once should perform one handshake and not four.
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
  const existing = fromDocumentCookie() ?? (await fromNativeJar());
  if (existing) return existing;
  await performHandshake();
  return fromDocumentCookie() ?? (await fromNativeJar());
}
