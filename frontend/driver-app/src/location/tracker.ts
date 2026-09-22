/**
 * Telling the platform where the driver is — live, and only while it is theirs
 * to know.
 *
 * Two constraints come straight from the backend and are not the app's to
 * soften:
 *
 *  - **Refused while offline.** `POST /driver/location` is rejected unless the
 *    driver has said they are working. A platform that tracked drivers who had
 *    finished for the day would be tracking people rather than parcels, so this
 *    hook does not even start the GPS watch until availability is ONLINE.
 *  - **Throttled to one every twenty seconds.** The response says how long to
 *    wait — `nextPingInSeconds` — and that number is obeyed rather than
 *    guessed. A phone pinging every second is a flat battery by eleven o'clock,
 *    on a platform where the driver may have nowhere to charge it.
 *
 * On top of those the app adds one rule of its own: a fix that has not moved
 * meaningfully is not worth a request. A driver waiting twenty minutes at a
 * shop does not need sixty pings saying the same thing.
 */

import { useEffect, useRef } from 'react';
import { driver as driverApi } from '../api/endpoints';
import { ApiError, OfflineError } from '../api/http';
import { localTimestamp } from '../lib/ids';
import { metresBetween, useLivePosition, type Fix } from './position';

/** Below this, the phone has not really gone anywhere. */
const MOVED_ENOUGH_M = 30;
/** Even standing still, say so occasionally: dispatch reads silence as trouble. */
const HEARTBEAT_MS = 120_000;

export interface TrackerState {
  fix: Fix | null;
  problem: ReturnType<typeof useLivePosition>['problem'];
}

/**
 * Runs the live position feed and the throttled upload behind it.
 *
 * @param online whether the driver has gone on duty. Nothing starts until it
 *               is true, and everything stops the moment it is false.
 */
export function useLocationTracker(online: boolean): TrackerState {
  const { fix, problem } = useLivePosition(online);

  const lastSent = useRef<{ fix: Fix; at: number } | null>(null);
  const notBefore = useRef(0);
  const inFlight = useRef(false);

  useEffect(() => {
    if (!online) {
      lastSent.current = null;
      notBefore.current = 0;
      return;
    }
    if (!fix || inFlight.current) return;

    const now = Date.now();
    if (now < notBefore.current) return;

    const previous = lastSent.current;
    if (previous) {
      const moved = metresBetween(previous.fix, fix);
      const quiet = now - previous.at;
      if (moved < MOVED_ENOUGH_M && quiet < HEARTBEAT_MS) return;
    }

    inFlight.current = true;
    void driverApi
      .ping({
        lat: fix.lat,
        lng: fix.lng,
        ...(fix.accuracy !== undefined ? { accuracy: fix.accuracy } : {}),
        // The device's own clock. A ping buffered through a dead spot should
        // say when it was taken, not when it happened to get through.
        capturedAt: localTimestamp(new Date(fix.at)),
      })
      .then((accepted) => {
        lastSent.current = { fix, at: Date.now() };
        // Obey what the server asked for, with twenty seconds as the floor it
        // enforces anyway.
        const wait = Math.max(accepted.nextPingInSeconds ?? 20, 20) * 1000;
        notBefore.current = Date.now() + wait;
      })
      .catch((failure: unknown) => {
        if (failure instanceof OfflineError) {
          // A position is only interesting live. There is no point queueing
          // pings from a dead spot to upload in a batch an hour later, and
          // every one of them would be a row saying where a driver was.
          notBefore.current = Date.now() + 30_000;
          return;
        }
        if (failure instanceof ApiError) {
          // 400 here is the backend refusing a ping from a driver it does not
          // consider online — the availability toggle and this hook have got
          // out of step. Back off hard rather than hammering it.
          notBefore.current = Date.now() + (failure.isRateLimited ? 60_000 : 30_000);
        }
      })
      .finally(() => {
        inFlight.current = false;
      });
  }, [online, fix]);

  return { fix, problem };
}
