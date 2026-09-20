/**
 * Turning what the API says into what a driver reads.
 *
 * The money rules here are the platform's, not this app's, and they are the
 * reason none of this is a one-liner:
 *
 *  - **Amounts arrive as strings** and stay strings until the moment they are
 *    formatted. Parsing a decimal into a JavaScript number and back is how a
 *    figure somebody is owed becomes a figure nobody can explain.
 *  - **Currencies have their own scale.** XOF has no minor unit at all: 1250.50
 *    CFA is not an amount that exists, and rendering it with two decimal places
 *    invents one. `Intl.NumberFormat` already knows this per currency, which is
 *    why it is used rather than a hand-rolled `toFixed(2)`.
 *  - **Currencies are never mixed.** A driver who has worked legs paid in
 *    dalasi and in CFA has two earnings. Adding them would need a rate nobody
 *    agreed to, so nothing here sums across a currency boundary.
 */

import type { ShipmentStatus } from '../api/types';

export function money(amount: string | undefined, currency: string | undefined): string {
  if (amount === undefined || amount === null || amount === '') return '—';
  if (!currency) return amount;

  const value = Number(amount);
  if (!Number.isFinite(value)) return `${amount} ${currency}`;

  try {
    // No explicit fraction digits: the formatter applies the currency's own
    // scale, which is the whole point. Forcing two would print CFA centimes
    // that do not exist.
    return new Intl.NumberFormat(undefined, {
      style: 'currency',
      currency,
      currencyDisplay: 'narrowSymbol',
    }).format(value);
  } catch {
    return `${amount} ${currency}`;
  }
}

/** The API's `LocalDateTime` strings carry no zone; they are read as local. */
export function parseTimestamp(value: string | undefined): Date | null {
  if (!value) return null;
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}

export function timeOfDay(value: string | undefined): string {
  const at = parseTimestamp(value);
  return at ? at.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }) : '—';
}

export function dayAndTime(value: string | undefined): string {
  const at = parseTimestamp(value);
  return at
    ? at.toLocaleString(undefined, {
        day: 'numeric',
        month: 'short',
        hour: '2-digit',
        minute: '2-digit',
      })
    : '—';
}

/** "2 min ago" — short, because it sits beside a queued record in a narrow row. */
export function sinceShort(millis: number): string {
  const seconds = Math.max(0, Math.round((Date.now() - millis) / 1000));
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.round(minutes / 60);
  return hours < 24 ? `${hours} h` : `${Math.round(hours / 24)} d`;
}

export function distance(km: string | undefined): string {
  if (!km) return '';
  const value = Number(km);
  if (!Number.isFinite(value)) return '';
  return value < 1 ? `${Math.round(value * 1000)} m` : `${value.toFixed(1)} km`;
}

export function metres(value: number | string | undefined): string {
  const number = typeof value === 'string' ? Number(value) : value;
  if (number === undefined || !Number.isFinite(number)) return '';
  return number < 1000 ? `${Math.round(number)} m` : `${(number / 1000).toFixed(1)} km`;
}

/**
 * A parcel's state, as a picture and a few words.
 *
 * The icon is not decoration. It is the part of this a driver who does not read
 * English recognises, and it is why the tone and the glyph always travel
 * together.
 */
export function statusLook(status: ShipmentStatus): {
  icon: string;
  tone: 'go' | 'warn' | 'stop' | 'plain';
  label: string;
} {
  switch (status) {
    case 'AWAITING_COLLECTION':
      return { icon: '🏪', tone: 'plain', label: 'Waiting at the shop' };
    case 'DRIVER_OFFERED':
      return { icon: '🔔', tone: 'warn', label: 'Offered' };
    case 'DRIVER_ASSIGNED':
      return { icon: '🛵', tone: 'warn', label: 'Go to the shop' };
    case 'AT_ORIGIN':
      return { icon: '📍', tone: 'warn', label: 'At the shop' };
    case 'IN_TRANSIT':
      return { icon: '📦', tone: 'go', label: 'With you' };
    case 'AT_PICKUP_POINT':
      return { icon: '🏬', tone: 'plain', label: 'At the counter' };
    case 'OUT_FOR_DELIVERY':
      return { icon: '🚪', tone: 'go', label: 'Out for delivery' };
    case 'DELIVERED':
      return { icon: '✅', tone: 'go', label: 'Delivered' };
    case 'ATTEMPT_FAILED':
      return { icon: '🔁', tone: 'stop', label: 'Try again' };
    case 'RETURNED':
      return { icon: '↩️', tone: 'stop', label: 'Sent back' };
    case 'CANCELLED':
      return { icon: '✖️', tone: 'stop', label: 'Cancelled' };
    default:
      return { icon: '📦', tone: 'plain', label: status };
  }
}

export function chainLook(type: string): { icon: string; label: string } {
  switch (type) {
    case 'ARRIVED_AT_ORIGIN':
      return { icon: '📍', label: 'You arrived at the shop' };
    case 'COLLECTED':
      return { icon: '📦', label: 'You collected it' };
    case 'DEPOSITED':
      return { icon: '🏬', label: 'Left at the counter' };
    case 'REDISPATCHED':
      return { icon: '🔄', label: 'Picked up again' };
    case 'RELEASED':
      return { icon: '✅', label: 'Handed to the person' };
    case 'TRANSFERRED':
      return { icon: '🤝', label: 'Given to another driver' };
    case 'FAILED_ATTEMPT':
      return { icon: '🚫', label: 'Could not deliver' };
    case 'RETURNED':
      return { icon: '↩️', label: 'Sent back' };
    default:
      return { icon: '•', label: type };
  }
}
