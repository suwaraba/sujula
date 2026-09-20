import { STORAGE_KEYS } from '@/lib/config';
import { clearStored, readStored, writeStored } from '@/lib/storage';

// Only the refresh token is written down. Everything else this app needs about
// the session it asks the server for, which is one fewer thing sitting on a
// device that might be shared, sold or taken.

/**
 * Where the two tokens live, and why they live in different places.
 *
 * The **access token** is held in this module and nowhere else. It is short
 * lived and re-obtainable, so writing it to storage would buy a slightly faster
 * reload in exchange for handing it to anything that manages to run script on
 * the page. It does not survive a reload, and does not need to: the refresh
 * token rebuilds the session before the first screen paints.
 *
 * The **refresh token** has to be written down, because "stay signed in" is
 * not optional for a seller who opens the app between customers. The backend
 * rotates it on every use and treats a second presentation of a spent token as
 * theft, ending the session — so a stolen copy is good for one use and then
 * announces itself. On a device it goes to the Keychain or SharedPreferences
 * rather than `localStorage`.
 */
export type Tokens = {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  refreshToken: string;
  sessionId: number | null;
};


let accessToken: string | null = null;
let accessTokenExpiresAt = 0;

export function getAccessToken(): string | null {
  return accessToken;
}

/** True when the access token is gone or close enough to expiry to be worth replacing. */
export function accessTokenIsStale(skewMs = 15_000): boolean {
  return !accessToken || Date.now() + skewMs >= accessTokenExpiresAt;
}

export function setAccessToken(token: string, expiresInSeconds: number): void {
  accessToken = token;
  accessTokenExpiresAt = Date.now() + Math.max(0, expiresInSeconds) * 1000;
}

export function clearAccessToken(): void {
  accessToken = null;
  accessTokenExpiresAt = 0;
}

export async function getRefreshToken(): Promise<string | null> {
  return readStored(STORAGE_KEYS.refreshToken);
}

export async function storeTokens(tokens: Tokens): Promise<void> {
  setAccessToken(tokens.accessToken, tokens.expiresIn);
  await writeStored(STORAGE_KEYS.refreshToken, tokens.refreshToken);
}

export async function clearTokens(): Promise<void> {
  clearAccessToken();
  await clearStored(STORAGE_KEYS.refreshToken);
}
