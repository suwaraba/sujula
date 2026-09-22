/**
 * The phone's own storage.
 *
 * Four stores, and the split is deliberate rather than tidy:
 *
 *  - `outbox`   — custody events the driver has recorded that the server has
 *                 not acknowledged. This is the one that matters. A collection
 *                 recorded in a shop doorway with no signal lives here until it
 *                 is uploaded, and it is the only record that it happened.
 *  - `blobs`    — photographs waiting to be uploaded, keyed by the event that
 *                 needs them. Separate from the outbox because an event row is
 *                 read on every flush and a two-megabyte JPEG is not something
 *                 to drag through memory each time.
 *  - `vault`    — the encrypted refresh token. See `auth/vault.ts`.
 *  - `meta`     — small scalars: the last ping, the cached job list, the
 *                 driver's language.
 *
 * Note what is NOT here: recipients' names, phone numbers and addresses are
 * never written to disk. They are held in memory for as long as the screen
 * showing them is open and go with it. A parcel's address on a shared phone is
 * an address the next driver to hold that phone can read, and the backend's
 * `no-store` header says the same thing about the network.
 */

import { openDB, type DBSchema, type IDBPDatabase } from 'idb';
import type { CustodyEventType, FailureReason } from '../api/types';

export const DB_NAME = 'sujula-driver';
const DB_VERSION = 1;

/** Where an outbox entry has got to. */
export type OutboxState =
  /** Waiting for a connection, or for its turn. */
  | 'pending'
  /** Being sent right now. Reset to pending if the app restarts mid-flight. */
  | 'sending'
  /** The server refused it in a way that will not change. Shown to the driver. */
  | 'rejected';

/**
 * One thing the driver did to a parcel.
 *
 * `clientEventId` is minted when the driver taps the button and never changes,
 * including across a restart. It is what lets the server tell this event from a
 * second one, and a retry of it from a second collection of the same parcel.
 */
export interface OutboxEvent {
  clientEventId: string;
  /** Reused by every retry of this request. A fresh key each time is no key. */
  idempotencyKey: string;
  shipmentId: number;
  type: CustodyEventType;
  /** The device's clock at the moment it happened. */
  capturedAt: string;
  /** Milliseconds, for ordering and for showing the driver how long ago. */
  capturedAtMs: number;
  state: OutboxState;
  attempts: number;
  /** Unix millis; nothing is retried before this. Backs off on failure. */
  nextAttemptAt: number;
  lastProblem?: string;

  code?: string;
  qrToken?: string;
  lat?: number;
  lng?: number;
  accuracy?: number;
  note?: string;
  reason?: FailureReason;
  /** Whether a photograph is waiting in `blobs` under this event's id. */
  hasPhoto: boolean;
  /** Set once the photograph has been uploaded and has a URL. */
  photoUrl?: string;
  signatureUrl?: string;
  /** Transfers only. */
  toDriverId?: number;
  receivingDriverCode?: string;
  myCode?: string;
  /** For the driver's own list: "Collected · MOB-4471". Never an address. */
  label?: string;
}

interface DriverDb extends DBSchema {
  outbox: {
    key: string;
    value: OutboxEvent;
    indexes: { 'by-captured': number; 'by-state': OutboxState };
  };
  blobs: {
    key: string;
    value: { clientEventId: string; blob: Blob; contentType: string };
  };
  vault: {
    key: string;
    value: unknown;
  };
  meta: {
    key: string;
    value: unknown;
  };
}

let handle: Promise<IDBPDatabase<DriverDb>> | null = null;

export function db(): Promise<IDBPDatabase<DriverDb>> {
  if (!handle) {
    handle = openDB<DriverDb>(DB_NAME, DB_VERSION, {
      upgrade(database) {
        const outbox = database.createObjectStore('outbox', { keyPath: 'clientEventId' });
        outbox.createIndex('by-captured', 'capturedAtMs');
        outbox.createIndex('by-state', 'state');
        database.createObjectStore('blobs', { keyPath: 'clientEventId' });
        database.createObjectStore('vault');
        database.createObjectStore('meta');
      },
      blocked() {
        // Another tab is holding an old version open. Rare on a phone, and not
        // worth a dialogue: the app keeps working against the old schema.
      },
    });
  }
  return handle;
}

export async function meta<T>(key: string): Promise<T | undefined> {
  return (await db()).get('meta', key) as Promise<T | undefined>;
}

export async function setMeta(key: string, value: unknown): Promise<void> {
  await (await db()).put('meta', value, key);
}

/**
 * Everything this app has written, gone.
 *
 * Called on sign-out and after too many wrong PINs. The outbox goes with it,
 * which is a real cost — so the caller warns first when anything is still
 * waiting to be uploaded, and this function does not decide that.
 */
export async function wipe(): Promise<void> {
  const database = await db();
  await Promise.all([
    database.clear('outbox'),
    database.clear('blobs'),
    database.clear('vault'),
    database.clear('meta'),
  ]);
}
