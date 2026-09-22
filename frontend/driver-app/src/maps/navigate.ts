/**
 * Handing the driver over to the navigation app they already know.
 *
 * This is the "very easy connection to Google Maps" that matters most on the
 * road, and it is deliberately a URL rather than an embedded turn-by-turn view:
 *
 *  - it opens the Google Maps app itself on Android and iOS, with voice
 *    guidance in the driver's own language, traffic, and the offline maps they
 *    may already have downloaded for their area;
 *  - it needs no API key, no script and no data beyond what Maps uses anyway;
 *  - it leaves this app in the background, where it keeps recording position,
 *    so the driver comes back to the same screen with the same buttons.
 *
 * A destination is given by coordinates where the platform has them and by text
 * where it does not. Coordinates first, always: a street name in Serrekunda
 * that geocodes to the wrong compound is worse than no address at all, and the
 * platform's own pin was confirmed by the person who lives there.
 */

export interface NavTarget {
  lat?: number | undefined;
  lng?: number | undefined;
  label?: string | undefined;
}

export function hasTarget(target: NavTarget | null | undefined): boolean {
  return Boolean(
    target && ((target.lat !== undefined && target.lng !== undefined) || target.label),
  );
}

/** A turn-by-turn route from where the driver is now. */
export function directionsUrl(target: NavTarget): string {
  const params = new URLSearchParams({ api: '1', travelmode: 'driving', dir_action: 'navigate' });
  params.set(
    'destination',
    target.lat !== undefined && target.lng !== undefined
      ? `${target.lat},${target.lng}`
      : (target.label ?? ''),
  );
  return `https://www.google.com/maps/dir/?${params.toString()}`;
}

/** The pin on a map, without starting navigation. For "where is this, roughly". */
export function placeUrl(target: NavTarget): string {
  const params = new URLSearchParams({ api: '1' });
  params.set(
    'query',
    target.lat !== undefined && target.lng !== undefined
      ? `${target.lat},${target.lng}`
      : (target.label ?? ''),
  );
  return `https://www.google.com/maps/search/?${params.toString()}`;
}

/**
 * Opens the maps app.
 *
 * `_blank` with `noopener` so the maps tab cannot reach back into this one —
 * this window holds a recipient's address and a live session.
 */
export function navigateTo(target: NavTarget): void {
  window.open(directionsUrl(target), '_blank', 'noopener,noreferrer');
}

/** Dials a number. The one action a driver takes with a phone that is not a map. */
export function callNumber(phone: string): void {
  window.location.href = `tel:${phone.replace(/[^+0-9]/g, '')}`;
}

/** Opens WhatsApp, which is how most people on this platform actually answer. */
export function whatsAppUrl(phone: string, text?: string): string {
  const number = phone.replace(/[^0-9]/g, '');
  const query = text ? `?text=${encodeURIComponent(text)}` : '';
  return `https://wa.me/${number}${query}`;
}
