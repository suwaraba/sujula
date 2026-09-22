/** Whether this bundle is running inside the Capacitor shell rather than a browser tab. */
export function isNative(): boolean {
  const cap = (globalThis as { Capacitor?: { isNativePlatform?: () => boolean } }).Capacitor;
  return typeof cap?.isNativePlatform === 'function' && cap.isNativePlatform();
}

/** 'android' | 'ios' | 'web'. */
export function platformName(): string {
  const cap = (globalThis as { Capacitor?: { getPlatform?: () => string } }).Capacitor;
  return cap?.getPlatform?.() ?? 'web';
}
