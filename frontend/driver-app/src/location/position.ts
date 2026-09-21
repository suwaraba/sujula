/**
 * Where the phone says it is.
 *
 * Position is evidence here, not a convenience. A handover carries the
 * coordinates it happened at, the backend measures them against where the
 * parcel was expected, and a delivery two kilometres from the address is
 * flagged as the shape of a round being closed from home. An authorised safe
 * drop is *refused* outright without a position, because there is no code in
 * that case and the position is the only thing corroborating that the driver
 * was where the recipient said to leave it.
 *
 * So this module does not hand back a stale fix and call it good. It keeps the
 * last one with its age and its accuracy, and the screens that record evidence
 * say plainly when the fix is old or vague rather than silently attaching it.
 */

import { useEffect, useRef, useState } from 'react';

export interface Fix {
  lat: number;
  lng: number;
  /** Metres of uncertainty the device reported. */
  accuracy?: number;
  heading?: number | null;
  speed?: number | null;
  /** Unix millis. */
  at: number;
}

export type PositionProblem =
  | 'denied'
  | 'unavailable'
  | 'timeout'
  | 'unsupported'
  | 'insecure'
  | null;

/** A fix older than this is stale enough to say so out loud. */
export const STALE_AFTER_MS = 60_000;
/** Beyond this, the fix is a neighbourhood rather than a doorway. */
export const VAGUE_ABOVE_M = 100;

export function isUsable(fix: Fix | null): boolean {
  return Boolean(fix && Date.now() - fix.at < STALE_AFTER_MS);
}

export function isVague(fix: Fix | null): boolean {
  return Boolean(fix?.accuracy && fix.accuracy > VAGUE_ABOVE_M);
}

function problemFor(error: GeolocationPositionError): Exclude<PositionProblem, null> {
  if (error.code === error.PERMISSION_DENIED) return 'denied';
  if (error.code === error.TIMEOUT) return 'timeout';
  return 'unavailable';
}

/**
 * Follows the phone for as long as `active` is true.
 *
 * `watchPosition` rather than repeated `getCurrentPosition`: the platform
 * coalesces one watch across the whole app, and polling wakes the GPS from
 * cold every time — which on the phones this runs on is the single biggest
 * thing an app can do to a battery that may not be charged again until night.
 */
export function useLivePosition(active: boolean): {
  fix: Fix | null;
  problem: PositionProblem;
  /** Asks the platform for one fresh fix, for the moment a driver taps record. */
  refresh: () => Promise<Fix | null>;
} {
  const [fix, setFix] = useState<Fix | null>(null);
  const [problem, setProblem] = useState<PositionProblem>(null);
  const latest = useRef<Fix | null>(null);

  useEffect(() => {
    if (!active) return undefined;
    if (!('geolocation' in navigator)) {
      setProblem('unsupported');
      return undefined;
    }
    if (!window.isSecureContext) {
      // Geolocation is refused outside a secure context, and the browser's own
      // error for it is indistinguishable from the driver having said no.
      setProblem('insecure');
      return undefined;
    }

    const id = navigator.geolocation.watchPosition(
      (position) => {
        const next: Fix = {
          lat: position.coords.latitude,
          lng: position.coords.longitude,
          ...(position.coords.accuracy !== null
            ? { accuracy: Math.round(position.coords.accuracy) }
            : {}),
          heading: position.coords.heading,
          speed: position.coords.speed,
          at: position.timestamp || Date.now(),
        };
        latest.current = next;
        setFix(next);
        setProblem(null);
      },
      (error) => setProblem(problemFor(error)),
      { enableHighAccuracy: true, maximumAge: 15_000, timeout: 20_000 },
    );

    return () => navigator.geolocation.clearWatch(id);
  }, [active]);

  const refresh = async (): Promise<Fix | null> => {
    if (!('geolocation' in navigator) || !window.isSecureContext) return latest.current;
    return new Promise<Fix | null>((resolve) => {
      navigator.geolocation.getCurrentPosition(
        (position) => {
          const next: Fix = {
            lat: position.coords.latitude,
            lng: position.coords.longitude,
            ...(position.coords.accuracy !== null
              ? { accuracy: Math.round(position.coords.accuracy) }
              : {}),
            at: position.timestamp || Date.now(),
          };
          latest.current = next;
          setFix(next);
          setProblem(null);
          resolve(next);
        },
        (error) => {
          setProblem(problemFor(error));
          // The last known fix is better than nothing for an event that is
          // about to be written; the screen shows its age beside it.
          resolve(latest.current);
        },
        { enableHighAccuracy: true, maximumAge: 10_000, timeout: 12_000 },
      );
    });
  };

  return { fix, problem, refresh };
}

/** Metres between two points. The same haversine the backend geofences with. */
export function metresBetween(
  a: { lat: number; lng: number },
  b: { lat: number; lng: number },
): number {
  const R = 6_371_000;
  const toRad = (deg: number) => (deg * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat);
  const dLng = toRad(b.lng - a.lng);
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLng / 2) ** 2;
  return Math.round(R * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h)));
}
