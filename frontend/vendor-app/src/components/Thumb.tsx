import { useState } from 'react';

/**
 * A product thumbnail that fails quietly.
 *
 * Images live in object storage on another host, and that host can be
 * unreachable — a bucket rotated, a CDN down, a seller on a connection that
 * drops large requests first. A broken-image icon in the middle of a packing
 * list looks like the app is broken; the placeholder looks like a listing
 * without a photo, which is what it is from here.
 */
export function Thumb({
  src, alt, size = 'md',
}: { src: string | null | undefined; alt: string; size?: 'md' | 'lg' }) {
  const [failed, setFailed] = useState(false);
  const className = size === 'lg' ? 'thumb thumb--lg' : 'thumb';

  if (!src || failed) {
    return (
      <div className={`${className} thumb--placeholder`} aria-hidden="true">▣</div>
    );
  }

  return (
    <img
      className={className}
      src={src}
      alt={alt}
      loading="lazy"
      onError={() => setFailed(true)}
    />
  );
}
