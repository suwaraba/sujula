import type { Profile } from '@/api/types';

/**
 * Where the console keeps its credentials.
 *
 * The access token lives in a module variable and nowhere else. It is never
 * written to `localStorage`, because this surface can refund a payment,
 * impersonate a customer and reset somebody's second factor, and a token that
 * survives in storage is a token a single injected script can take away with
 * it and use from anywhere.
 *
 * The refresh token is in `sessionStorage`, which is the compromise that makes
 * the console usable: a reload keeps you signed in, closing the tab does not.
 * It is still reachable by script, so it is short-lived by rotation on the
 * server — `POST /auth/refresh` returns a new pair and invalidates the old one,
 * so a stolen refresh token is detectable the moment the real one is used.
 *
 * Nothing here talks to the network. `client.ts` owns that, and `AuthContext`
 * owns the lifecycle; this is only the box the values sit in.
 */

const REFRESH_KEY = 'sujula.admin.refresh';
const PROFILE_KEY = 'sujula.admin.profile';
/** Written on sign-out so other tabs of the same console follow. */
const LOGOUT_KEY = 'sujula.admin.logout';

let accessToken: string | null = null;
let accessTokenExpiresAt: number | null = null;

/** Set when the session was opened by impersonating somebody. */
let impersonating: { userId: number; actingAs: string; expiresAt: string } | null = null;

type Listener = () => void;
const listeners = new Set<Listener>();

function announce(): void {
  listeners.forEach((listener) => listener());
}

function safeSession(): Storage | null {
  try {
    return window.sessionStorage;
  } catch {
    // Storage can be blocked outright. The console still works; it just will
    // not survive a reload, which is a degradation rather than a failure.
    return null;
  }
}

export const tokenStore = {
  subscribe(listener: Listener): () => void {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },

  accessToken(): string | null {
    return accessToken;
  },

  /** Whether the access token is within `skewSeconds` of expiry. */
  accessTokenExpiring(skewSeconds = 30): boolean {
    if (accessTokenExpiresAt === null) return false;
    return Date.now() >= accessTokenExpiresAt - skewSeconds * 1000;
  },

  refreshToken(): string | null {
    return safeSession()?.getItem(REFRESH_KEY) ?? null;
  },

  profile(): Profile | null {
    const raw = safeSession()?.getItem(PROFILE_KEY);
    if (!raw) return null;
    try {
      return JSON.parse(raw) as Profile;
    } catch {
      return null;
    }
  },

  impersonation(): typeof impersonating {
    return impersonating;
  },

  setImpersonation(value: typeof impersonating): void {
    impersonating = value;
    announce();
  },

  /** Store a fresh token pair. `expiresIn` is seconds, as the server sends it. */
  set(tokens: { accessToken: string; expiresIn: number; refreshToken?: string | null }, profile?: Profile | null): void {
    accessToken = tokens.accessToken;
    accessTokenExpiresAt = Date.now() + tokens.expiresIn * 1000;
    const session = safeSession();
    if (session) {
      if (tokens.refreshToken) session.setItem(REFRESH_KEY, tokens.refreshToken);
      if (profile) session.setItem(PROFILE_KEY, JSON.stringify(profile));
    }
    announce();
  },

  clear(broadcast = true): void {
    accessToken = null;
    accessTokenExpiresAt = null;
    impersonating = null;
    const session = safeSession();
    session?.removeItem(REFRESH_KEY);
    session?.removeItem(PROFILE_KEY);
    if (broadcast) {
      try {
        // `localStorage` rather than `sessionStorage`: the storage event only
        // crosses tabs for localStorage, and signing out of one tab of a
        // money-moving console should sign out of all of them.
        window.localStorage.setItem(LOGOUT_KEY, String(Date.now()));
        window.localStorage.removeItem(LOGOUT_KEY);
      } catch {
        /* storage blocked; the other tabs will find out on their next 401 */
      }
    }
    announce();
  },

  /** Calls `onLogout` when another tab signs out. */
  onCrossTabLogout(onLogout: () => void): () => void {
    const handler = (event: StorageEvent) => {
      if (event.key === LOGOUT_KEY && event.newValue !== null) onLogout();
    };
    window.addEventListener('storage', handler);
    return () => window.removeEventListener('storage', handler);
  },
};
