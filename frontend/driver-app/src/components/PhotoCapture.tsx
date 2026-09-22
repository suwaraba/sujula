/**
 * The photograph.
 *
 * Required on every delivery, and the backend refuses one without it: it is
 * what settles a dispute months later about whether a parcel actually arrived.
 * For an authorised safe drop it is the *only* proof there will be, because
 * there is nobody at the door to read out a code.
 *
 * `capture="environment"` on a file input rather than a `getUserMedia`
 * viewfinder. That hands the job to the phone's own camera app, which has the
 * autofocus, the exposure and the flash the browser does not, and which every
 * driver already knows how to use. The bytes come back here to be downscaled
 * before they go anywhere near the network.
 */

import { useEffect, useRef, useState } from 'react';
import { Button } from './ui';

interface Props {
  onCapture: (file: File | null) => void;
  label: string;
  required?: boolean;
}

export function PhotoCapture({ onCapture, label, required = false }: Props) {
  const input = useRef<HTMLInputElement | null>(null);
  const [preview, setPreview] = useState<string | null>(null);

  // Object URLs are a real leak on a phone that stays open all day: each one
  // pins a whole JPEG in memory until it is revoked.
  useEffect(() => () => {
    if (preview) URL.revokeObjectURL(preview);
  }, [preview]);

  const choose = (file: File | null) => {
    if (preview) URL.revokeObjectURL(preview);
    setPreview(file ? URL.createObjectURL(file) : null);
    onCapture(file);
  };

  return (
    <div>
      <input
        ref={input}
        type="file"
        accept="image/*"
        capture="environment"
        className="visually-hidden"
        onChange={(event) => choose(event.target.files?.[0] ?? null)}
      />

      <div className={`photo ${preview ? 'photo--taken' : ''}`.trim()}>
        {preview ? (
          <img className="photo__image" src={preview} alt="The photograph you just took" />
        ) : (
          <p className="photo__prompt">
            <span style={{ fontSize: 44, display: 'block' }} aria-hidden="true">
              📷
            </span>
            {label}
            {required && ' — required'}
          </p>
        )}
      </div>

      <Button
        tone={preview ? 'ghost' : 'brand'}
        icon="📷"
        onClick={() => input.current?.click()}
      >
        {preview ? 'Take it again' : label}
      </Button>
    </div>
  );
}
