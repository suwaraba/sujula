import { isNative } from './platform';

/**
 * Small async key-value store over whatever the platform provides.
 *
 * Native goes to Capacitor Preferences, which is backed by the Keychain on iOS
 * and SharedPreferences on Android — not `localStorage`, which a WebView is
 * entitled to evict under storage pressure and which would sign a seller out
 * because their phone was low on space.
 *
 * Only the refresh token lives here. The access token is held in memory and
 * never written down; see `api/tokens.ts` for why.
 */
type Prefs = {
  get(options: { key: string }): Promise<{ value: string | null }>;
  set(options: { key: string; value: string }): Promise<void>;
  remove(options: { key: string }): Promise<void>;
};

let prefs: Prefs | null = null;
let prefsLoad: Promise<Prefs | null> | null = null;

async function nativePrefs(): Promise<Prefs | null> {
  if (!isNative()) return null;
  if (prefs) return prefs;
  prefsLoad ??= import('@capacitor/preferences')
    .then((m) => {
      prefs = m.Preferences as unknown as Prefs;
      return prefs;
    })
    .catch(() => null);
  return prefsLoad;
}

export async function readStored(key: string): Promise<string | null> {
  const native = await nativePrefs();
  if (native) return (await native.get({ key })).value;
  try {
    return globalThis.localStorage?.getItem(key) ?? null;
  } catch {
    // Private browsing, or a WebView with storage disabled. Signing in still
    // works; it just will not survive a reload.
    return null;
  }
}

export async function writeStored(key: string, value: string): Promise<void> {
  const native = await nativePrefs();
  if (native) return native.set({ key, value });
  try {
    globalThis.localStorage?.setItem(key, value);
  } catch {
    /* see readStored */
  }
}

export async function clearStored(key: string): Promise<void> {
  const native = await nativePrefs();
  if (native) return native.remove({ key });
  try {
    globalThis.localStorage?.removeItem(key);
  } catch {
    /* see readStored */
  }
}
