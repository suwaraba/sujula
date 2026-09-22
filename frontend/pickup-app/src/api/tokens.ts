import { STORAGE_KEYS } from '@/lib/config';
import { clearStored, readStored, writeStored } from '@/lib/storage';

/**
 * Two tokens, in two different places.
 *
 * The **access token** lives in this module and nowhere else. A counter tablet
 * is shared and left on a shop counter; a short-lived token that is never
 * written down is one fewer thing to find on it.
 *
 * The **refresh token** has to be persisted, because an operator should not be
 * signing in again every time the tablet sleeps. The backend rotates it on
 * every use and treats a replay as theft by ending the session, so a copy taken
 * off the device is good once and then announces itself.
 */
export type Tokens = {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  refreshToken: string;
  sessionId: number | null;
};

let accessToken: string | null = null;
let expiresAt = 0;

export function getAccessToken(): string | null {
  return accessToken;
}

/** True when the token is gone or close enough to expiry to be worth replacing. */
export function accessTokenIsStale(skewMs = 15_000): boolean {
  return !accessToken || Date.now() + skewMs >= expiresAt;
}

export function setAccessToken(token: string, expiresInSeconds: number): void {
  accessToken = token;
  expiresAt = Date.now() + Math.max(0, expiresInSeconds) * 1000;
}

export function getRefreshToken(): string | null {
  return readStored(STORAGE_KEYS.refreshToken);
}

export function storeTokens(tokens: Tokens): void {
  setAccessToken(tokens.accessToken, tokens.expiresIn);
  writeStored(STORAGE_KEYS.refreshToken, tokens.refreshToken);
}

export function clearTokens(): void {
  accessToken = null;
  expiresAt = 0;
  clearStored(STORAGE_KEYS.refreshToken);
}
