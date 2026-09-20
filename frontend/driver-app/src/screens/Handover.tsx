/**
 * The act of a parcel changing hands, in the fewest taps that still produce
 * proof.
 *
 * Collection, deposit and delivery are the same shape with different parties,
 * which is why they are one component here and one request record on the
 * backend. What differs is what counts as enough evidence, and that is decided
 * by the server — this screen gathers, it does not adjudicate.
 *
 * Three things are gathered, in this order, because that is the order they
 * happen in on a doorstep:
 *
 *  1. **the code the other person reads out.** The driver never sees it and is
 *     never sent it. That is the entire point of a code: it proves two people
 *     were in the same place at the same time, and a driver who could read it
 *     could mark a parcel delivered without meeting anybody.
 *  2. **a photograph**, required on delivery, because it is what settles a
 *     dispute months later about whether a parcel actually arrived.
 *  3. **the position**, taken fresh at the moment of recording rather than
 *     reused from the map, and shown to the driver with its accuracy so they
 *     know what is being attested to.
 *
 * The scan is a convenience and nothing more. A label can be photographed
 * through a shop window; only the spoken code proves a meeting.
 *
 * Everything is written to the phone before anything is sent. The driver gets
 * their confirmation from the write, which is why this works identically in a
 * village with no signal and why nobody is tempted to tap twice.
 */

import { useEffect, useState } from 'react';
import { CodePad } from '../components/CodePad';
import { PhotoCapture } from '../components/PhotoCapture';
import { Scanner } from '../scan/Scanner';
import { Banner, Button, Card, Pill, useToast } from '../components/ui';
import { TopBar } from '../components/Chrome';
import { FocusLayer } from '../components/FocusLayer';
import { tokenFromScan } from '../scan/qr';
import { useLivePosition, isUsable, isVague, metresBetween, type Fix } from '../location/position';
import { canUploadPhotos } from '../media/photos';
import * as outbox from '../offline/outbox';
import { useRequestRecipientCode } from '../api/queries';
import { ApiError } from '../api/http';
import { metres } from '../lib/format';
import { useT } from '../i18n';
import type { CustodyEventType, ShipmentDetail } from '../api/types';

export type HandoverKind = 'collect' | 'deposit' | 'deliver';

const EVENT: Record<HandoverKind, CustodyEventType> = {
  collect: 'COLLECTED',
  deposit: 'DEPOSITED',
  deliver: 'RELEASED',
};

interface Props {
  shipment: ShipmentDetail;
  kind: HandoverKind;
  onDone: () => void;
  onCancel: () => void;
}

export function Handover({ shipment, kind, onDone, onCancel }: Props) {
  const t = useT();
  const toast = useToast();
  const requestCode = useRequestRecipientCode();

  const { fix, problem, refresh } = useLivePosition(true);
  const [code, setCode] = useState('');
  const [qrToken, setQrToken] = useState<string | null>(null);
  const [photo, setPhoto] = useState<File | null>(null);
  const [scanning, setScanning] = useState(false);
  const [note, setNote] = useState('');
  const [noCode, setNoCode] = useState(false);
  const [saving, setSaving] = useState(false);
  const [codeSentTo, setCodeSentTo] = useState<string | null>(null);

  // One fresh fix on open. The map's last known position may be ten minutes
  // and two streets old, and this one is going into the chain as evidence.
  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const title =
    kind === 'collect'
      ? t('job.collect')
      : kind === 'deposit'
        ? t('job.deposit')
        : t('job.deliver');

  const whoseCode = kind === 'collect' ? t('code.sellerCode') : t('code.recipientCode');
  const photoRequired = kind === 'deliver';
  const expected =
    kind === 'collect'
      ? shipment.origin?.lat !== undefined && shipment.origin?.lng !== undefined
        ? { lat: shipment.origin.lat, lng: shipment.origin.lng }
        : null
      : shipment.destination?.lat !== undefined && shipment.destination?.lng !== undefined
        ? { lat: shipment.destination.lat, lng: shipment.destination.lng }
        : null;

  const away = expected && fix ? metresBetween(fix, expected) : null;

  const codeReady = noCode || code.length >= 4;
  const photoReady = !photoRequired || photo !== null;
  const canRecord = codeReady && photoReady && !saving;

  const askForCode = async () => {
    try {
      const result = await requestCode.mutateAsync(shipment.id);
      setCodeSentTo(result.sentTo ?? null);
      toast(result.message ?? t('code.sent'), 'go');
    } catch (failure) {
      toast(failure instanceof ApiError ? failure.message : 'Could not send it.', 'stop');
    }
  };

  const record = async () => {
    setSaving(true);
    try {
      // The freshest fix the phone can give, taken now rather than when the
      // screen opened. A safe drop is refused outright without one.
      const at: Fix | null = await refresh();

      await outbox.record({
        shipmentId: shipment.id,
        type: EVENT[kind],
        ...(noCode ? {} : { code }),
        ...(qrToken ? { qrToken } : {}),
        ...(note.trim() ? { note: note.trim() } : {}),
        ...(photo ? { photo } : {}),
        position: at
          ? { lat: at.lat, lng: at.lng, ...(at.accuracy !== undefined ? { accuracy: at.accuracy } : {}) }
          : null,
        label: `${title} · ${shipment.reference ?? shipment.id}`,
      });

      navigator.vibrate?.([40, 60, 40]);
      toast('Recorded on this phone. It will be sent when you have signal.', 'go');
      onDone();
    } catch {
      toast('Could not save that on this phone. Try once more.', 'stop');
    } finally {
      setSaving(false);
    }
  };

  if (scanning) {
    return (
      <Scanner
        hint={t('code.scan')}
        onClose={() => setScanning(false)}
        onRead={(text) => {
          const token = tokenFromScan(text);
          setScanning(false);
          if (token) {
            setQrToken(token);
            toast('Label read.', 'go');
          } else {
            toast('That is not a Sujula label. Type the code instead.', 'stop');
          }
        }}
      />
    );
  }

  return (
    <FocusLayer
      footer={
        <Button
          tone="go"
          major
          icon="✓"
          busy={saving}
          disabled={!canRecord}
          onClick={() => void record()}
        >
          {title}
        </Button>
      }
    >
      <TopBar title={title} onBack={onCancel} />

      <div className="screen screen--inline">
        <Card>
          <div className="card__row">
            <span className="card__title">{shipment.reference ?? `#${shipment.id}`}</span>
            <Pill icon="📦">{shipment.parcelCount}</Pill>
          </div>
          {shipment.contentsSummary && <p className="card__meta">{shipment.contentsSummary}</p>}
        </Card>

        <PositionNote problem={problem} fix={fix} away={away} />

        {kind === 'deliver' && (
          <Card>
            <p className="muted">{t('code.neverShown')}</p>
            <Button
              tone="ghost"
              icon="✉️"
              busy={requestCode.isPending}
              onClick={() => void askForCode()}
            >
              {t('code.ask')}
            </Button>
            {codeSentTo && (
              <p className="card__meta" style={{ marginTop: 8 }}>
                Sent to {codeSentTo}
              </p>
            )}
          </Card>
        )}

        {!noCode && (
          <>
            <CodePad label={whoseCode} value={code} onChange={setCode} length={6} />

            <div style={{ marginTop: 16 }}>
              <Button tone="ghost" icon="📷" onClick={() => setScanning(true)}>
                {qrToken ? '✓ Label read — scan again' : t('code.scan')}
              </Button>
            </div>
          </>
        )}

        {kind === 'deliver' && (
          <Card>
            <label
              className="chip"
              style={{ width: '100%', justifyContent: 'flex-start', minHeight: 64 }}
            >
              <input
                type="checkbox"
                checked={noCode}
                onChange={(event) => {
                  setNoCode(event.target.checked);
                  if (event.target.checked) setCode('');
                }}
                style={{ width: 26, height: 26 }}
              />
              Nobody is here — she told us to leave it
            </label>
            {noCode && (
              <Banner tone="warn" icon="⚠️">
                This only works if she has already authorised it, and only at the place she
                named. Your photograph is the only proof there will be — take it where you leave
                the parcel.
              </Banner>
            )}
          </Card>
        )}

        {(photoRequired || kind !== 'collect') && (
          <div style={{ marginTop: 16 }}>
            <PhotoCapture
              label={t('photo.take')}
              required={photoRequired}
              onCapture={setPhoto}
            />
            {photoRequired && !canUploadPhotos() && (
              <Banner tone="stop">
                This app has no way to send photographs and a delivery needs one. Tell dispatch
                before you set off.
              </Banner>
            )}
          </div>
        )}

        <div className="field" style={{ marginTop: 16 }}>
          <label className="field__label" htmlFor="handover-note">
            Anything to add?
          </label>
          <textarea
            id="handover-note"
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

/**
 * What the phone knows about where it is, said plainly.
 *
 * The driver is told rather than silently attached to: the backend measures
 * this position against where the parcel was expected and flags a handover
 * recorded far away, and somebody has to look at those. A driver who knew their
 * fix was vague would have stepped outside.
 */
function PositionNote({
  problem,
  fix,
  away,
}: {
  problem: ReturnType<typeof useLivePosition>['problem'];
  fix: Fix | null;
  away: number | null;
}) {
  const t = useT();

  if (problem === 'denied') return <Banner tone="stop" icon="📍">{t('position.denied')}</Banner>;
  if (problem === 'insecure')
    return (
      <Banner tone="stop" icon="📍">
        This app cannot read your position on this address. Tell dispatch.
      </Banner>
    );
  if (!fix) return <Banner icon="📍">{t('position.waiting')}</Banner>;
  if (!isUsable(fix)) return <Banner tone="warn" icon="📍">{t('position.stale')}</Banner>;
  if (isVague(fix)) return <Banner tone="warn" icon="📍">{t('position.vague')}</Banner>;
  if (away !== null && away > 300)
    return (
      <Banner tone="warn" icon="📍">
        {t('position.far', { metres: metres(away) })}
      </Banner>
    );

  return (
    <Banner tone="go" icon="📍">
      Position good{fix.accuracy ? ` (±${fix.accuracy} m)` : ''}
    </Banner>
  );
}
