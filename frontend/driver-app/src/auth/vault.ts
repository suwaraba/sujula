/**
 * Where the refresh token sleeps.
 *
 * The problem this solves is specific to the job. A driver's phone is shared
 * between shifts, left in a vehicle, and sometimes stolen with the parcels. A
 * refresh token sitting in `localStorage` on such a phone is a working session
 * for whoever picks it up — and the session it opens can read every recipient
 * address on that driver's round.
 *
 * So the token is encrypted at rest under a key derived from a PIN the driver
 * chooses, with PBKDF2-SHA256 and AES-GCM. The PIN never leaves the device and
 * is never stored, not even hashed: the only test of whether it is right is
 * whether the token decrypts. Five wrong attempts and the ciphertext is
 * destroyed, which mirrors what the backend does to a handover code after five
 * wrong guesses — a six-digit secret is a hundred thousand tries to somebody
 * determined and three to somebody who mistyped, and the count is what tells
 * them apart.
 *
 * What this does and does not buy:
 *
 *  - it does buy: a stolen or handed-on phone is useless without the PIN, and
 *    stays useless — there is no offline dictionary attack worth running
 *    against 310,000 PBKDF2 iterations for a token that the server will rotate
 *    out from under the attacker anyway.
 *  - it does not buy: protection from malicious code running in this origin.
 *    Nothing in a browser does. The mitigations for that are same-origin
 *    deployment, no third-party scripts, and the access token living only in
 *    memory.
 */

import { db } from '../offline/db';

const RECORD_KEY = 'session';
const ITERATIONS = 310_000;
const MAX_ATTEMPTS = 5;

interface VaultRecord {
  /** AES-GCM ciphertext of the refresh token. */
  ciphertext: ArrayBuffer;
  iv: Uint8Array;
  salt: Uint8Array;
  iterations: number;
  /** Shown on the lock screen so the driver knows whose session this is. */
  emailHint: string;
  /** Wrong PINs since the last success. */
  failures: number;
  savedAt: number;
}

export class WrongPinError extends Error {
  readonly attemptsRemaining: number;
  constructor(attemptsRemaining: number) {
    super('Wrong PIN');
    this.name = 'WrongPinError';
    this.attemptsRemaining = attemptsRemaining;
  }
}

export class VaultEmptyError extends Error {
  constructor() {
    super('No saved session');
    this.name = 'VaultEmptyError';
  }
}

function assertCrypto(): SubtleCrypto {
  // Requires a secure context. The app needs one for geolocation and the
  // camera regardless, so this is a clear message rather than a fallback —
  // there is no weaker way to do this that would be worth shipping.
  if (!globalThis.crypto?.subtle) {
    throw new Error(
      'This phone cannot store a session safely here. Open the app over https and try again.',
    );
  }
  return globalThis.crypto.subtle;
}

async function keyFrom(pin: string, salt: Uint8Array, iterations: number): Promise<CryptoKey> {
  const subtle = assertCrypto();
  const material = await subtle.importKey(
    'raw',
    new TextEncoder().encode(pin),
    'PBKDF2',
    false,
    ['deriveKey'],
  );
  return subtle.deriveKey(
    { name: 'PBKDF2', salt: salt as BufferSource, iterations, hash: 'SHA-256' },
    material,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  );
}

/** Whether a session is saved on this phone at all. */
export async function hasSavedSession(): Promise<boolean> {
  return Boolean(await (await db()).get('vault', RECORD_KEY));
}

export async function savedEmailHint(): Promise<string | null> {
  const record = (await (await db()).get('vault', RECORD_KEY)) as VaultRecord | undefined;
  return record?.emailHint ?? null;
}

export async function attemptsRemaining(): Promise<number> {
  const record = (await (await db()).get('vault', RECORD_KEY)) as VaultRecord | undefined;
  return record ? Math.max(0, MAX_ATTEMPTS - record.failures) : MAX_ATTEMPTS;
}

/** Writes the refresh token, sealed under the PIN. Replaces whatever was there. */
export async function saveSession(
  refreshToken: string,
  pin: string,
  emailHint: string,
): Promise<void> {
  const subtle = assertCrypto();
  const salt = globalThis.crypto.getRandomValues(new Uint8Array(16));
  const iv = globalThis.crypto.getRandomValues(new Uint8Array(12));
  const key = await keyFrom(pin, salt, ITERATIONS);
  const ciphertext = await subtle.encrypt(
    { name: 'AES-GCM', iv: iv as BufferSource },
    key,
    new TextEncoder().encode(refreshToken),
  );

  const record: VaultRecord = {
    ciphertext,
    iv,
    salt,
    iterations: ITERATIONS,
    emailHint,
    failures: 0,
    savedAt: Date.now(),
  };
  await (await db()).put('vault', record, RECORD_KEY);
}

/**
 * Re-seals the token under the PIN already in use.
 *
 * Every refresh mints a new token and spends the old one, so the vault has to
 * be rewritten on each exchange or the next unlock presents a spent token —
 * which the backend correctly treats as theft and answers by ending the
 * session. The PIN is held in memory by the caller for exactly this.
 */
export async function resealSession(refreshToken: string, pin: string): Promise<void> {
  const record = (await (await db()).get('vault', RECORD_KEY)) as VaultRecord | undefined;
  if (!record) throw new VaultEmptyError();
  await saveSession(refreshToken, pin, record.emailHint);
}

/**
 * Opens the vault, or counts the failure.
 *
 * AES-GCM authenticates: a wrong key does not produce a wrong answer, it
 * produces no answer at all. So "did it decrypt" is the whole test, and there
 * is nothing stored that a wrong PIN could be compared against.
 */
export async function unlock(pin: string): Promise<string> {
  const database = await db();
  const record = (await database.get('vault', RECORD_KEY)) as VaultRecord | undefined;
  if (!record) throw new VaultEmptyError();

  const subtle = assertCrypto();
  try {
    const key = await keyFrom(pin, record.salt, record.iterations);
    const plain = await subtle.decrypt(
      { name: 'AES-GCM', iv: record.iv as BufferSource },
      key,
      record.ciphertext,
    );
    if (record.failures !== 0) {
      await database.put('vault', { ...record, failures: 0 }, RECORD_KEY);
    }
    return new TextDecoder().decode(plain);
  } catch {
    const failures = record.failures + 1;
    if (failures >= MAX_ATTEMPTS) {
      await database.delete('vault', RECORD_KEY);
      throw new WrongPinError(0);
    }
    await database.put('vault', { ...record, failures }, RECORD_KEY);
    throw new WrongPinError(MAX_ATTEMPTS - failures);
  }
}

/** Forgets the saved session. The outbox is not touched — that is a separate decision. */
export async function forgetSession(): Promise<void> {
  await (await db()).delete('vault', RECORD_KEY);
}
