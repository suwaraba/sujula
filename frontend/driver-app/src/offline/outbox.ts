/**
 * What the driver did, waiting to be told to the server.
 *
 * This is the part of the app that has to be right. A driver works a round
 * through an area with no coverage; every collection, every doorstep, every
 * failed attempt is recorded on the phone and exists nowhere else until they
 * come back within range. Losing one is losing the evidence that a parcel
 * changed hands, which on this platform is the evidence that a seller gets
 * paid.
 *
 * Four rules, each of which the backend is built to meet halfway:
 *
 *  1. **Write first, send second.** Every action lands in IndexedDB before a
 *     request is attempted. The UI confirms from the write, not from the
 *     response, so a driver in a dead spot sees the same "recorded" they see in
 *     town and does not tap twice.
 *  2. **One id per event, for its whole life.** `clientEventId` is minted when
 *     the driver taps and never changes, across retries and across restarts.
 *     The server deduplicates on it, so a retry is a retry rather than a second
 *     collection of the same parcel.
 *  3. **One idempotency key per request.** Stored beside the event for the same
 *     reason: a key generated at send time is a new key every retry, which is
 *     the same as having none.
 *  4. **Oldest first, and stop on the first network failure.** A delivery
 *     applied before its collection is refused by the chain — correctly, and
 *     for entirely the wrong reason. And a phone with one bar should not fire
 *     twelve parallel requests at it.
 */

import { ApiError, OfflineError } from '../api/http';
import { driver } from '../api/endpoints';
import { localTimestamp, newId } from '../lib/ids';
import { db, type OutboxEvent } from './db';
import { compress, uploadEvidence } from '../media/photos';
import type { CustodyEventType, FailureReason, SyncEntry } from '../api/types';

/** Backoff between attempts, in milliseconds. Capped: a driver is waiting. */
const BACKOFF = [0, 5_000, 15_000, 60_000, 300_000] as const;

/** Beyond this many queued events, one batch round trip beats twelve. */
const BATCH_THRESHOLD = 3;

/**
 * Event kinds the batch endpoint can carry without losing evidence.
 *
 * A transfer is not among them: `/custody-events/sync` has no field for the
 * other driver or for the second code, and a transfer attested by one person is
 * exactly the link that would be forged. It always goes on its own endpoint.
 */
const BATCHABLE: ReadonlySet<CustodyEventType> = new Set<CustodyEventType>([
  'ARRIVED_AT_ORIGIN',
  'COLLECTED',
  'DEPOSITED',
  'RELEASED',
  'FAILED_ATTEMPT',
]);

export interface NewEvent {
  shipmentId: number;
  type: CustodyEventType;
  code?: string;
  qrToken?: string;
  note?: string;
  reason?: FailureReason;
  position?: { lat: number; lng: number; accuracy?: number } | null;
  photo?: Blob | null;
  toDriverId?: number;
  receivingDriverCode?: string;
  myCode?: string;
  /** A short, address-free line for the driver's own pending list. */
  label?: string;
}

type Listener = (pending: OutboxEvent[]) => void;
const listeners = new Set<Listener>();

export function subscribe(listener: Listener): () => void {
  listeners.add(listener);
  void list().then(listener);
  return () => listeners.delete(listener);
}

async function announce(): Promise<void> {
  const pending = await list();
  listeners.forEach((listener) => listener(pending));
}

export async function list(): Promise<OutboxEvent[]> {
  const rows = await (await db()).getAllFromIndex('outbox', 'by-captured');
  return rows;
}

export async function pendingCount(): Promise<number> {
  return (await list()).filter((event) => event.state !== 'rejected').length;
}

/**
 * Records what happened, on the phone, now.
 *
 * Returns as soon as it is written. The flush is fired off but not waited on,
 * because the driver is standing at a door and the answer they need — "it is
 * recorded" — is already true.
 */
export async function record(input: NewEvent): Promise<OutboxEvent> {
  const now = new Date();
  const event: OutboxEvent = {
    clientEventId: newId(),
    idempotencyKey: newId(),
    shipmentId: input.shipmentId,
    type: input.type,
    capturedAt: localTimestamp(now),
    capturedAtMs: now.getTime(),
    state: 'pending',
    attempts: 0,
    nextAttemptAt: 0,
    hasPhoto: Boolean(input.photo),
    ...(input.code ? { code: input.code } : {}),
    ...(input.qrToken ? { qrToken: input.qrToken } : {}),
    ...(input.note ? { note: input.note } : {}),
    ...(input.reason ? { reason: input.reason } : {}),
    ...(input.position
      ? {
          lat: input.position.lat,
          lng: input.position.lng,
          ...(input.position.accuracy !== undefined ? { accuracy: input.position.accuracy } : {}),
        }
      : {}),
    ...(input.toDriverId !== undefined ? { toDriverId: input.toDriverId } : {}),
    ...(input.receivingDriverCode ? { receivingDriverCode: input.receivingDriverCode } : {}),
    ...(input.myCode ? { myCode: input.myCode } : {}),
    ...(input.label ? { label: input.label } : {}),
  };

  const database = await db();
  const transaction = database.transaction(['outbox', 'blobs'], 'readwrite');
  await transaction.objectStore('outbox').put(event);
  if (input.photo) {
    const { blob, contentType } = await compress(input.photo);
    await transaction
      .objectStore('blobs')
      .put({ clientEventId: event.clientEventId, blob, contentType });
  }
  await transaction.done;

  void announce();
  void flush();
  return event;
}

/** Stops retrying one the server has refused for good, at the driver's word. */
export async function discard(clientEventId: string): Promise<void> {
  const database = await db();
  const transaction = database.transaction(['outbox', 'blobs'], 'readwrite');
  await transaction.objectStore('outbox').delete(clientEventId);
  await transaction.objectStore('blobs').delete(clientEventId);
  await transaction.done;
  void announce();
}

/** Puts a rejected entry back in the queue, after the driver has fixed something. */
export async function retry(clientEventId: string): Promise<void> {
  const database = await db();
  const event = await database.get('outbox', clientEventId);
  if (!event) return;
  await database.put('outbox', {
    ...event,
    state: 'pending',
    attempts: 0,
    nextAttemptAt: 0,
    // A fresh key: the server may have stored the previous attempt's *failure*
    // against the old one, and replaying that would hand back the same refusal.
    idempotencyKey: newId(),
  });
  void announce();
  void flush();
}

async function settle(event: OutboxEvent): Promise<void> {
  const database = await db();
  const transaction = database.transaction(['outbox', 'blobs'], 'readwrite');
  await transaction.objectStore('outbox').delete(event.clientEventId);
  await transaction.objectStore('blobs').delete(event.clientEventId);
  await transaction.done;
}

async function defer(event: OutboxEvent, problem: string): Promise<void> {
  const attempts = event.attempts + 1;
  const wait = BACKOFF[Math.min(attempts, BACKOFF.length - 1)] ?? 300_000;
  await (await db()).put('outbox', {
    ...event,
    state: 'pending',
    attempts,
    nextAttemptAt: Date.now() + wait,
    lastProblem: problem,
  });
}

async function reject(event: OutboxEvent, problem: string): Promise<void> {
  await (await db()).put('outbox', {
    ...event,
    state: 'rejected',
    attempts: event.attempts + 1,
    lastProblem: problem,
  });
}

/** Uploads the photograph if one is waiting, and remembers where it landed. */
async function ensurePhoto(event: OutboxEvent): Promise<OutboxEvent> {
  if (!event.hasPhoto || event.photoUrl) return event;
  const database = await db();
  const stored = await database.get('blobs', event.clientEventId);
  if (!stored) return { ...event, hasPhoto: false };

  const photoUrl = await uploadEvidence(stored.blob, stored.contentType);
  const updated = { ...event, photoUrl };
  await database.put('outbox', updated);
  return updated;
}

function positionOf(event: OutboxEvent) {
  return {
    ...(event.lat !== undefined ? { lat: event.lat } : {}),
    ...(event.lng !== undefined ? { lng: event.lng } : {}),
    ...(event.accuracy !== undefined ? { accuracy: event.accuracy } : {}),
  };
}

/** One event, on the endpoint built for it — with its full evidence rules. */
async function sendOne(event: OutboxEvent): Promise<void> {
  const ready = await ensurePhoto(event);
  const common = {
    ...positionOf(ready),
    capturedAt: ready.capturedAt,
    clientEventId: ready.clientEventId,
    ...(ready.note ? { note: ready.note } : {}),
    ...(ready.photoUrl ? { photoUrl: ready.photoUrl } : {}),
  };
  const handover = {
    ...common,
    ...(ready.code ? { code: ready.code } : {}),
    ...(ready.qrToken ? { qrToken: ready.qrToken } : {}),
    ...(ready.signatureUrl ? { signatureUrl: ready.signatureUrl } : {}),
  };

  switch (ready.type) {
    case 'ARRIVED_AT_ORIGIN':
      await driver.arrived(ready.shipmentId, common, ready.idempotencyKey);
      return;
    case 'COLLECTED':
      await driver.collect(ready.shipmentId, handover, ready.idempotencyKey);
      return;
    case 'DEPOSITED':
      await driver.depositAtPickup(ready.shipmentId, handover, ready.idempotencyKey);
      return;
    case 'RELEASED':
      await driver.deliver(ready.shipmentId, handover, ready.idempotencyKey);
      return;
    case 'FAILED_ATTEMPT':
      await driver.deliveryFailed(
        ready.shipmentId,
        { ...common, reason: ready.reason ?? 'DRIVER_UNABLE' },
        ready.idempotencyKey,
      );
      return;
    case 'TRANSFERRED':
      await driver.transfer(
        ready.shipmentId,
        {
          ...common,
          toDriverId: ready.toDriverId!,
          receivingDriverCode: ready.receivingDriverCode!,
          myCode: ready.myCode!,
        },
        ready.idempotencyKey,
      );
      return;
    default:
      throw new ApiError(400, {
        message: `This app does not know how to send a ${ready.type} event.`,
      });
  }
}

/**
 * A backlog, in one request.
 *
 * `/driver/custody-events/sync` applies oldest first, deduplicates on the app's
 * own event ids, and reports entry by entry — so one bad record does not throw
 * away the rest of a day's work. On the connection a driver comes back into
 * range on, one round trip for twelve events is the difference between
 * uploading the day and giving up on it.
 */
async function sendBatch(events: OutboxEvent[]): Promise<void> {
  const ready: OutboxEvent[] = [];
  for (const event of events) {
    ready.push(await ensurePhoto(event));
  }

  const entries: SyncEntry[] = ready.map((event) => ({
    shipmentId: event.shipmentId,
    type: event.type,
    clientEventId: event.clientEventId,
    capturedAt: event.capturedAt,
    ...(event.code ? { code: event.code } : {}),
    ...positionOf(event),
    ...(event.photoUrl ? { photoUrl: event.photoUrl } : {}),
    ...(event.reason ? { reasonCode: event.reason } : {}),
    ...(event.note ? { note: event.note } : {}),
  }));

  const result = await driver.sync({ events: entries }, newId());
  const byId = new Map(result.outcomes.map((outcome) => [outcome.clientEventId, outcome]));

  for (const event of ready) {
    const outcome = byId.get(event.clientEventId);
    if (!outcome || outcome.accepted) {
      await settle(event);
    } else {
      // The batch endpoint reports refusals that will not change on a retry,
      // and says so. Keep it, show the driver, stop sending it.
      await reject(event, outcome.problem ?? 'The server would not record this one.');
    }
  }
}

let flushing: Promise<void> | null = null;

/**
 * Sends whatever is waiting, oldest first.
 *
 * Single-flight, because two flushes racing would present the same idempotency
 * key twice — safe on the server, wasteful on a phone — and would interleave
 * events out of order.
 */
export function flush(): Promise<void> {
  if (!flushing) {
    flushing = run().finally(() => {
      flushing = null;
    });
  }
  return flushing;
}

async function run(): Promise<void> {
  if (typeof navigator !== 'undefined' && navigator.onLine === false) return;

  const now = Date.now();
  const due = (await list()).filter(
    (event) => event.state === 'pending' && event.nextAttemptAt <= now,
  );
  if (due.length === 0) return;

  const batchable = due.filter((event) => BATCHABLE.has(event.type));
  if (batchable.length >= BATCH_THRESHOLD) {
    try {
      await sendBatch(batchable);
    } catch (failure) {
      if (failure instanceof OfflineError) return;
      // A batch that failed as a whole — a 500, a refused upload — is retried
      // entry by entry below, where each one gets its own verdict.
      for (const event of batchable) {
        await defer(event, describe(failure));
      }
    }
    void announce();
  }

  const remaining = (await list()).filter(
    (event) => event.state === 'pending' && event.nextAttemptAt <= Date.now(),
  );

  for (const event of remaining) {
    try {
      await sendOne(event);
      await settle(event);
    } catch (failure) {
      if (failure instanceof OfflineError) {
        // No point walking the rest of the queue: the connection is gone.
        await defer(event, 'Waiting for a connection.');
        break;
      }
      if (failure instanceof ApiError && !failure.isRetryable) {
        await reject(event, failure.message);
      } else {
        await defer(event, describe(failure));
      }
    }
    void announce();
  }
  void announce();
}

function describe(failure: unknown): string {
  if (failure instanceof ApiError) return failure.message;
  if (failure instanceof OfflineError) return 'Waiting for a connection.';
  return failure instanceof Error ? failure.message : 'Could not send this one yet.';
}

/**
 * Keeps the queue moving.
 *
 * Three triggers, because a phone gives three different signals that a dead
 * spot has ended: the browser's own online event, the app coming back to the
 * foreground, and a timer for the cases where neither fires — which on Android
 * is more often than it should be.
 */
export function startFlushLoop(): () => void {
  const onOnline = () => void flush();
  const onVisible = () => {
    if (!document.hidden) void flush();
  };
  const timer = window.setInterval(() => void flush(), 30_000);

  window.addEventListener('online', onOnline);
  document.addEventListener('visibilitychange', onVisible);
  void flush();

  return () => {
    window.clearInterval(timer);
    window.removeEventListener('online', onOnline);
    document.removeEventListener('visibilitychange', onVisible);
  };
}
