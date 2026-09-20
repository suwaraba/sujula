/**
 * A delivery that did not happen.
 *
 * Custody does not move: the driver still has the parcel, which is exactly why
 * this is an event on the chain rather than the end of it. The reason is a
 * fixed choice rather than free text because the reason decides what happens
 * next — nobody home is another attempt, a refused parcel goes back, and a
 * wrong address is neither until a human has looked at it. A text box would
 * have produced "not there" a thousand times and told dispatch nothing.
 */

import { useEffect, useState } from 'react';
import { PhotoCapture } from '../components/PhotoCapture';
import { Banner, Button, Card } from '../components/ui';
import { TopBar } from '../components/Chrome';
import { FocusLayer } from '../components/FocusLayer';
import { useToast } from '../components/ui';
import { useLivePosition } from '../location/position';
import * as outbox from '../offline/outbox';
import type { FailureReason, ShipmentDetail } from '../api/types';

const REASONS: Array<{
  value: FailureReason;
  icon: string;
  label: string;
  consequence: string;
}> = [
  {
    value: 'NOBODY_HOME',
    icon: '🚪',
    label: 'Nobody was there',
    consequence: 'You will be asked to try again later.',
  },
  {
    value: 'NO_CODE',
    icon: '🔢',
    label: 'They could not give the code',
    consequence: 'You will be asked to try again later.',
  },
  {
    value: 'ADDRESS_NOT_FOUND',
    icon: '🗺️',
    label: 'I could not find the place',
    consequence: 'Somebody will check the address before you go again.',
  },
  {
    value: 'REFUSED',
    icon: '🙅',
    label: 'They would not take it',
    consequence: 'The parcel goes back to the seller.',
  },
  {
    value: 'UNSAFE',
    icon: '⚠️',
    label: 'It was not safe to go',
    consequence: 'You will be asked to try again later.',
  },
  {
    value: 'DRIVER_UNABLE',
    icon: '🛠️',
    label: 'I could not finish the round',
    consequence: 'You will be asked to try again later.',
  },
];

export function FailedAttempt({
  shipment,
  onDone,
  onCancel,
}: {
  shipment: ShipmentDetail;
  onDone: () => void;
  onCancel: () => void;
}) {
  const toast = useToast();
  const { refresh } = useLivePosition(true);

  const [reason, setReason] = useState<FailureReason | null>(null);
  const [note, setNote] = useState('');
  const [photo, setPhoto] = useState<File | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const chosen = REASONS.find((option) => option.value === reason);

  const record = async () => {
    if (!reason) return;
    setSaving(true);
    try {
      const at = await refresh();
      await outbox.record({
        shipmentId: shipment.id,
        type: 'FAILED_ATTEMPT',
        reason,
        ...(note.trim() ? { note: note.trim() } : {}),
        ...(photo ? { photo } : {}),
        position: at
          ? {
              lat: at.lat,
              lng: at.lng,
              ...(at.accuracy !== undefined ? { accuracy: at.accuracy } : {}),
            }
          : null,
        label: `Could not deliver · ${shipment.reference ?? shipment.id}`,
      });
      toast('Recorded. You still have the parcel.', 'go');
      onDone();
    } catch {
      toast('Could not save that on this phone. Try once more.', 'stop');
    } finally {
      setSaving(false);
    }
  };

  return (
    <FocusLayer
      footer={
        <Button
          tone="stop"
          major
          icon="✓"
          busy={saving}
          disabled={!reason || saving}
          onClick={() => void record()}
        >
          Record this
        </Button>
      }
    >
      <TopBar title="It did not work" onBack={onCancel} />

      <div className="screen screen--inline">
        <Banner icon="📦">
          You keep the parcel. This only records what happened at the door.
        </Banner>

        <div className="stack">
          {REASONS.map((option) => (
            <button
              key={option.value}
              type="button"
              className="chip"
              style={{ width: '100%', justifyContent: 'flex-start', minHeight: 72 }}
              aria-pressed={reason === option.value}
              onClick={() => setReason(option.value)}
            >
              <span aria-hidden="true" style={{ fontSize: 26 }}>
                {option.icon}
              </span>
              {option.label}
            </button>
          ))}
        </div>

        {chosen && (
          <Card>
            <p className="card__meta" style={{ margin: 0 }}>
              {chosen.consequence}
            </p>
          </Card>
        )}

        <div style={{ marginTop: 16 }}>
          <PhotoCapture
            label="Photograph the door or the parcel"
            onCapture={setPhoto}
          />
        </div>

        <div className="field">
          <label className="field__label" htmlFor="failure-note">
            Anything to add?
          </label>
          <textarea
            id="failure-note"
            className="field__textarea"
            maxLength={400}
            value={note}
            onChange={(event) => setNote(event.target.value)}
          />
        </div>
      </div>
    </FocusLayer>
  );
}
