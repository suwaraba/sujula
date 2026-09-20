/**
 * Photographs, from the camera to a URL a custody event can carry.
 *
 * A delivery is refused without one — it is what settles a dispute months later
 * about whether a parcel actually arrived — so this path has to work on the
 * worst connection the platform runs on. Two things follow:
 *
 *  - the image is downscaled and re-encoded on the phone before it is sent. A
 *    modern camera produces four megabytes; 1280px of JPEG at quality 0.7 is
 *    about two hundred kilobytes and shows a doorway and a parcel just as
 *    clearly. On a 2G connection that is the difference between twenty seconds
 *    and four minutes standing at somebody's gate.
 *  - the bytes go straight from the phone to storage through a presigned URL
 *    and never pass through the application server, which is how the rest of
 *    this platform uploads. Bytes that pass through an application server are
 *    bytes in an access log.
 *
 * The photograph is also held in IndexedDB until its event is acknowledged, so
 * a driver who photographs a handover in a dead spot still has the photograph
 * when they come back into range.
 */

import { driver } from '../api/endpoints';

const MODE = (import.meta.env.VITE_MEDIA_MODE ?? 'presign') as 'presign' | 'off';

const MAX_EDGE = 1280;
const QUALITY = 0.7;

export class MediaUnavailableError extends Error {
  constructor() {
    super(
      'This app has no way to send photographs, and a delivery needs one. Tell dispatch before ' +
        'you set off.',
    );
    this.name = 'MediaUnavailableError';
  }
}

/** Whether the app can produce a `photoUrl` at all. */
export function canUploadPhotos(): boolean {
  return MODE === 'presign';
}

/**
 * Shrinks a camera frame to something a phone on 2G can actually send.
 *
 * Falls back to the original file if anything in the pipeline is unavailable —
 * a large upload that succeeds beats a small one that was never made.
 */
export async function compress(file: Blob): Promise<{ blob: Blob; contentType: string }> {
  try {
    const bitmap = await createImageBitmap(file);
    const scale = Math.min(1, MAX_EDGE / Math.max(bitmap.width, bitmap.height));
    const width = Math.round(bitmap.width * scale);
    const height = Math.round(bitmap.height * scale);

    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext('2d');
    if (!context) throw new Error('no 2d context');
    context.drawImage(bitmap, 0, 0, width, height);
    bitmap.close?.();

    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob(resolve, 'image/jpeg', QUALITY),
    );
    if (!blob) throw new Error('encode failed');
    return { blob, contentType: 'image/jpeg' };
  } catch {
    return { blob: file, contentType: file.type || 'image/jpeg' };
  }
}

/**
 * Puts the bytes in storage and returns the URL the event will carry.
 *
 * Throws on any failure rather than returning a half-answer: an event sent with
 * a photo URL that points at nothing is worse than an event that waits in the
 * outbox until the phone has signal for both.
 */
export async function uploadEvidence(blob: Blob, contentType: string): Promise<string> {
  if (MODE !== 'presign') throw new MediaUnavailableError();

  const presigned = await driver.presignEvidence(contentType);

  const response = await fetch(presigned.uploadUrl, {
    method: 'PUT',
    headers: { 'Content-Type': contentType },
    body: blob,
    // The presigned URL is a bearer credential in itself. Sending cookies or an
    // Authorization header to a storage host would leak one credential to a
    // service that has no use for it.
    credentials: 'omit',
    cache: 'no-store',
  });

  if (!response.ok) {
    throw new Error(`The photograph could not be sent (${response.status}).`);
  }
  return presigned.publicUrl;
}
