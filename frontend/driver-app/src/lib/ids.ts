/**
 * Identifiers the app mints for itself.
 *
 * Two of them, and the difference matters:
 *
 *  - a **client event id** names a thing that happened — this collection, this
 *    delivery. It is generated once, when the driver taps the button, and it
 *    stays with the event for as long as the event exists, including across an
 *    app restart. It is what lets the server tell a retry from a second
 *    collection of the same parcel.
 *  - an **idempotency key** names a request. It is generated alongside the
 *    event and reused by every retry of that same request.
 *
 * Both are persisted with the queued event rather than generated at send time.
 * A key generated at send time is a new key on every retry, which is the same
 * as having no key at all.
 */

export function newId(): string {
  // crypto.randomUUID needs a secure context. The app requires one anyway —
  // geolocation and the camera both do — but a driver on a captive-portal wifi
  // can end up on plain http, and failing to mint an id would lose the event
  // rather than the connection.
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  const bytes = new Uint8Array(16);
  if (typeof crypto !== 'undefined' && crypto.getRandomValues) {
    crypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
  }
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40;
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80;
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/**
 * The device's clock, in the shape the backend's `LocalDateTime` binder reads.
 *
 * No zone offset and no trailing `Z`: the API takes local date-times, and
 * appending an offset the binder does not parse would reject the whole request
 * — which, for an event captured hours ago with no signal, would throw away the
 * only record of it.
 */
export function localTimestamp(at: Date = new Date()): string {
  const pad = (n: number, width = 2) => String(n).padStart(width, '0');
  return (
    `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}` +
    `T${pad(at.getHours())}:${pad(at.getMinutes())}:${pad(at.getSeconds())}` +
    `.${pad(at.getMilliseconds(), 3)}`
  );
}
