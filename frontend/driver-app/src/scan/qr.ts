/**
 * Reading a parcel label.
 *
 * The QR on a label carries a URL whose last path segment is a signed token
 * naming the vendor order — `https://…/parcels/<token>` by default, and a
 * relative `/parcels/<token>` where the deployment has no absolute base
 * configured. The driver app lifts that last segment and sends it as
 * `qrToken` alongside the handover, where the backend verifies the signature.
 *
 * The token is **not** a substitute for the code. A label can be photographed
 * through a shop window; the code has to be read out by the person handing the
 * parcel over, which is the only thing that proves two people were in the same
 * place. Scanning saves the driver typing a reference, and that is all it is
 * for — so a scan that does not match the job in hand is a warning, not a
 * shortcut past anything.
 */

/** The signed token from a scanned label, or null when this is not one of ours. */
export function tokenFromScan(raw: string): string | null {
  const text = raw.trim();
  if (!text) return null;

  const segment = (path: string): string | null => {
    const parts = path.split('/').filter(Boolean);
    const last = parts[parts.length - 1];
    // Signed tokens are long and base64url-ish. A short segment is a stray URL
    // — a shop's own poster, a payment QR — not a parcel label.
    return last && last.length >= 16 && /^[A-Za-z0-9_.\-+=]+$/.test(last) ? last : null;
  };

  try {
    // Absolute URL.
    return segment(new URL(text).pathname);
  } catch {
    // Relative path, or a bare token printed by an older label run.
    if (text.startsWith('/')) return segment(text);
    return /^[A-Za-z0-9_.\-+=]{16,400}$/.test(text) ? text : null;
  }
}

/**
 * A parcel reference a driver can read back, where one is in the scan.
 *
 * Only ever used to say "this is not the parcel on your screen". It never
 * unlocks anything.
 */
export function referenceFromScan(raw: string): string | null {
  const match = raw.match(/\b([A-Z]{2,4}-[A-Z0-9]{4,12})\b/);
  return match?.[1] ?? null;
}
