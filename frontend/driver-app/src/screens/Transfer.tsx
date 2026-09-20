/**
 * Handing a parcel to another driver.
 *
 * Both sides present something, and the screen says so out loud, because a
 * transfer attested by one person is a link nobody can corroborate — and it is
 * precisely the link at which a parcel would go missing if either half could be
 * forged. So this asks for the other driver's id, their code, and the code on
 * this driver's own phone.
 *
 * Unlike every other custody event, this one never goes through the batch sync
 * endpoint: `/driver/custody-events/sync` has no field for the second party or
 * the second code, and recording half a transfer is worse than recording none.
 * The outbox knows that and always sends a transfer on its own endpoint.
 */

import { useEffect, useState } from 'react';
import { Banner, Button, Field } from '../components/ui';
import { useToast } from '../components/ui';
import { CodePad } from '../components/CodePad';
import { TopBar } from '../components/Chrome';
import { FocusLayer } from '../components/FocusLayer';
import { useLivePosition } from '../location/position';
import * as outbox from '../offline/outbox';
import type { ShipmentDetail } from '../api/types';

export function Transfer({
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

  const [toDriverId, setToDriverId] = useState('');
  const [theirCode, setTheirCode] = useState('');
  const [myCode, setMyCode] = useState('');
  const [stage, setStage] = useState<'who' | 'theirs' | 'mine'>('who');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const record = async () => {
    setSaving(true);
    try {
      const at = await refresh();
      await outbox.record({
        shipmentId: shipment.id,
        type: 'TRANSFERRED',
        toDriverId: Number(toDriverId),
        receivingDriverCode: theirCode,
        myCode,
        position: at
          ? {
              lat: at.lat,
              lng: at.lng,
              ...(at.accuracy !== undefined ? { accuracy: at.accuracy } : {}),
            }
          : null,
        label: `Handed on · ${shipment.reference ?? shipment.id}`,
      });
      toast('Handed over. It is no longer your parcel.', 'go');
      onDone();
    } catch {
      toast('Could not save that on this phone. Try once more.', 'stop');
    } finally {
      setSaving(false);
    }
  };

  return (
    <FocusLayer>
      <TopBar title="Give to another driver" onBack={onCancel} />

      <div className="screen screen--inline">
        <Banner icon="🤝">
          Both of you have to prove this. You give your code, they give theirs.
        </Banner>

        {stage === 'who' && (
          <>
            <Field label="The other driver's number" hint="they will read it from their app">
              <input
                className="field__input"
                type="text"
                inputMode="numeric"
                value={toDriverId}
                onChange={(event) => setToDriverId(event.target.value.replace(/\D/g, ''))}
              />
            </Field>
            <Button
              tone="brand"
              major
              icon="→"
              disabled={!toDriverId}
              onClick={() => setStage('theirs')}
            >
              Next
            </Button>
          </>
        )}

        {stage === 'theirs' && (
          <CodePad
            label="Their code"
            value={theirCode}
            onChange={setTheirCode}
            length={6}
            onComplete={() => setStage('mine')}
          />
        )}

        {stage === 'mine' && (
          <>
            <CodePad label="Your own code" value={myCode} onChange={setMyCode} length={6} />
            <div style={{ marginTop: 20 }}>
              <Button
                tone="go"
                major
                icon="✓"
                busy={saving}
                disabled={myCode.length < 4 || saving}
                onClick={() => void record()}
              >
                Hand it over
              </Button>
            </div>
          </>
        )}
      </div>
    </FocusLayer>
  );
}
