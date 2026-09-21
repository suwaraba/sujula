/**
 * Small synchronous key-value store over `localStorage`.
 *
 * Every accessor is guarded: a WebView with site data blocked, a private
 * window, or a tablet that has run out of space all throw here rather than
 * returning null. Signing in still works in that case — it just will not
 * survive a reload, which is a far better outcome than a blank screen.
 */
export function readStored(key: string): string | null {
  try {
    return globalThis.localStorage?.getItem(key) ?? null;
  } catch {
    return null;
  }
}

export function writeStored(key: string, value: string): void {
  try {
    globalThis.localStorage?.setItem(key, value);
  } catch {
    /* see readStored */
  }
}

export function clearStored(key: string): void {
  try {
    globalThis.localStorage?.removeItem(key);
  } catch {
    /* see readStored */
  }
}
