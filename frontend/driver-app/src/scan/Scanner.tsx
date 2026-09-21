/**
 * The camera, pointed at a parcel label.
 *
 * Two readers, in this order:
 *
 *  1. `BarcodeDetector`, which Android Chrome implements natively. It is
 *     hardware-accelerated, decodes in a few milliseconds, and costs nothing to
 *     ship.
 *  2. ZXing, lazily imported, for iOS Safari and everything else. Two hundred
 *     kilobytes, which is why it is not in the main bundle and is fetched only
 *     when a driver actually opens the scanner on a phone that needs it.
 *
 * The torch is exposed because half of these scans happen at dusk in a
 * compound with no outside light, and a scanner that cannot turn a light on is
 * a scanner the driver gives up on and types the reference instead.
 */

import { useCallback, useEffect, useRef, useState } from 'react';

type BarcodeDetectorLike = {
  detect(source: CanvasImageSource): Promise<Array<{ rawValue: string }>>;
};

interface Props {
  onRead: (text: string) => void;
  onClose: () => void;
  /** Shown over the viewfinder. One short line; the driver is holding a parcel. */
  hint?: string;
}

export function Scanner({ onRead, onClose, hint }: Props) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const stopRef = useRef<(() => void) | null>(null);
  const firedRef = useRef(false);

  const [problem, setProblem] = useState<string | null>(null);
  const [torchOn, setTorchOn] = useState(false);
  const [torchAvailable, setTorchAvailable] = useState(false);

  const report = useCallback(
    (text: string) => {
      if (firedRef.current) return;
      firedRef.current = true;
      // A short buzz. On a phone held at arm's length in the dark, the driver
      // feels the read before they can see it.
      navigator.vibrate?.(60);
      onRead(text);
    },
    [onRead],
  );

  useEffect(() => {
    let cancelled = false;

    void (async () => {
      if (!navigator.mediaDevices?.getUserMedia) {
        setProblem('This phone cannot open the camera from the browser.');
        return;
      }
      if (!window.isSecureContext) {
        setProblem('The camera only works over https. Tell dispatch this app is on the wrong address.');
        return;
      }

      let stream: MediaStream;
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: { ideal: 'environment' }, width: { ideal: 1280 } },
          audio: false,
        });
      } catch {
        setProblem('The camera is not available. Type the code instead.');
        return;
      }
      if (cancelled) {
        stream.getTracks().forEach((track) => track.stop());
        return;
      }

      streamRef.current = stream;
      const track = stream.getVideoTracks()[0];
      const capabilities = track?.getCapabilities?.() as
        | (MediaTrackCapabilities & { torch?: boolean })
        | undefined;
      setTorchAvailable(Boolean(capabilities?.torch));

      const video = videoRef.current;
      if (!video) return;
      video.srcObject = stream;
      video.setAttribute('playsinline', 'true');
      await video.play().catch(() => undefined);

      const Native = (window as unknown as { BarcodeDetector?: new (init: { formats: string[] }) => BarcodeDetectorLike })
        .BarcodeDetector;

      if (Native) {
        const detector = new Native({ formats: ['qr_code', 'code_128', 'code_39'] });
        let frame = 0;
        const tick = async () => {
          if (cancelled || firedRef.current) return;
          try {
            const found = await detector.detect(video);
            const value = found[0]?.rawValue;
            if (value) {
              report(value);
              return;
            }
          } catch {
            /* A frame that could not be decoded is the ordinary case. */
          }
          frame = requestAnimationFrame(() => void tick());
        };
        void tick();
        stopRef.current = () => cancelAnimationFrame(frame);
        return;
      }

      // Everything else. Imported here so the bytes are only fetched on a phone
      // that actually needs them.
      const { BrowserMultiFormatReader } = await import('@zxing/browser');
      if (cancelled) return;
      const reader = new BrowserMultiFormatReader();
      const controls = await reader.decodeFromVideoElement(video, (result) => {
        if (result) report(result.getText());
      });
      stopRef.current = () => controls.stop();
    })();

    return () => {
      cancelled = true;
      stopRef.current?.();
      streamRef.current?.getTracks().forEach((track) => track.stop());
      streamRef.current = null;
    };
  }, [report]);

  const toggleTorch = async () => {
    const track = streamRef.current?.getVideoTracks()[0];
    if (!track) return;
    const next = !torchOn;
    try {
      // `torch` is real and widely implemented on Android, and absent from the
      // DOM constraint types, so the cast is the honest way to say so.
      await track.applyConstraints({
        advanced: [{ torch: next }],
      } as unknown as MediaTrackConstraints);
      setTorchOn(next);
    } catch {
      setTorchAvailable(false);
    }
  };

  return (
    <div className="scanner" role="dialog" aria-modal="true" aria-label="Scan the parcel label">
      <video ref={videoRef} className="scanner__video" muted playsInline />
      <div className="scanner__frame" aria-hidden="true" />

      <p className="scanner__hint">{hint ?? 'Point the camera at the code on the parcel'}</p>

      {problem && <p className="scanner__problem">{problem}</p>}

      <div className="scanner__actions">
        {torchAvailable && (
          <button
            type="button"
            className="button button--ghost"
            onClick={() => void toggleTorch()}
            aria-pressed={torchOn}
          >
            {torchOn ? '🔦 Light off' : '🔦 Light on'}
          </button>
        )}
        <button type="button" className="button button--ghost" onClick={onClose}>
          ✕ Close
        </button>
      </div>
    </div>
  );
}
