/**
 * One parcel, and the one thing to do with it next.
 *
 * The screen is built around the backend's own `whatToDoNext`, promoted to the
 * top in the largest type on the page. That sentence is written where the
 * parcel's state actually is, so it already accounts for the things this client
 * would get wrong: a recipient who has asked for the parcel to go to a counter
 * instead of her door, a safe drop she has authorised, an attempt that cannot
 * be retried until four o'clock.
 *
 * The privacy rule this platform cares most about is visible in the shape of
 * the data rather than enforced by this file: `destination` is either a whole
 * object or `null`. Before the driver accepts, and after they hand over, there
 * is no address here to render — not a blank one, none. So there is no field to
 * forget to hide, and the address card below simply does not exist for most of
 * a parcel's life.
 */

import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useShipment } from '../api/queries';
import { ApiError } from '../api/http';
import { Banner, Button, Card, Pill, Spinner, useToast } from '../components/ui';
import { ConnectionBar, TopBar } from '../components/Chrome';
import { LiveMap, type MapPoint } from '../maps/LiveMap';
import { mapsConfigured } from '../maps/loader';
import { callNumber, directionsUrl, hasTarget, whatsAppUrl } from '../maps/navigate';
import { useLivePosition } from '../location/position';
import { chainLook, dayAndTime, statusLook, timeOfDay } from '../lib/format';
import { Handover, type HandoverKind } from './Handover';
import { FailedAttempt } from './FailedAttempt';
import { Transfer } from './Transfer';
import * as outbox from '../offline/outbox';
import { useT } from '../i18n';
import type { ShipmentDetail } from '../api/types';

type Step = 'detail' | HandoverKind | 'failed' | 'transfer';

export function Job() {
  const { id } = useParams<{ id: string }>();
  const shipmentId = Number(id);
  const navigate = useNavigate();
  const t = useT();
  const toast = useToast();

  const query = useShipment(Number.isFinite(shipmentId) ? shipmentId : null);
  const [step, setStep] = useState<Step>('detail');
  const [saving, setSaving] = useState(false);
  // Only while this screen is the one showing. The step screens run their own
  // watch, and two registrations for one phone is a second GPS wake per fix on
  // a battery that may not be charged again until night.
  const { fix, refresh } = useLivePosition(step === 'detail');

  if (query.isLoading) {
    return (
      <>
        <TopBar title={t('app.loading')} onBack={() => navigate('/jobs')} />
        <div className="screen">
          <Spinner />
        </div>
      </>
    );
  }

  if (query.isError || !query.data) {
    const error = query.error;
    return (
      <>
        <TopBar title={t('nav.jobs')} onBack={() => navigate('/jobs')} />
        <div className="screen">
          <Banner tone="stop">
            {/* A not-found here is the backend declining to confirm that
                somebody else's parcel exists. It reads the same as a parcel
                that has been handed on, which is the intended answer. */}
            {error instanceof ApiError && error.isNotFound
              ? 'This parcel is not yours to move. It may have been handed on.'
              : error instanceof ApiError
                ? error.message
                : 'Could not load this parcel.'}
          </Banner>
          <Button tone="ghost" onClick={() => void query.refetch()}>
            {t('app.retry')}
          </Button>
        </div>
      </>
    );
  }

  const shipment = query.data;
  const done = () => {
    setStep('detail');
    void query.refetch();
  };

  if (step === 'collect' || step === 'deposit' || step === 'deliver') {
    return (
      <Handover
        shipment={shipment}
        kind={step}
        onDone={done}
        onCancel={() => setStep('detail')}
      />
    );
  }
  if (step === 'failed') {
    return <FailedAttempt shipment={shipment} onDone={done} onCancel={() => setStep('detail')} />;
  }
  if (step === 'transfer') {
    return <Transfer shipment={shipment} onDone={done} onCancel={() => setStep('detail')} />;
  }

  const look = statusLook(shipment.status);
  const mine = shipment.legs.find(
    (leg) => leg.mine && (leg.status === 'ACCEPTED' || leg.status === 'IN_PROGRESS'),
  );
  const endsAtCounter =
    mine?.legType === 'ORIGIN_TO_PICKUP' ||
    mine?.legType === 'PICKUP_TO_PICKUP' ||
    mine?.legType === 'PICKUP_TO_COUNTER';

  const recordArrival = async () => {
    setSaving(true);
    try {
      const at = await refresh();
      await outbox.record({
        shipmentId: shipment.id,
        type: 'ARRIVED_AT_ORIGIN',
        position: at
          ? {
              lat: at.lat,
              lng: at.lng,
              ...(at.accuracy !== undefined ? { accuracy: at.accuracy } : {}),
            }
          : null,
        label: `At the shop · ${shipment.reference ?? shipment.id}`,
      });
      toast('Recorded. The seller can see you are there.', 'go');
      void query.refetch();
    } catch {
      toast('Could not save that on this phone.', 'stop');
    } finally {
      setSaving(false);
    }
  };

  const primary = primaryAction(shipment, endsAtCounter);

  const points: MapPoint[] = [];
  if (shipment.origin?.lat !== undefined && shipment.origin.lng !== undefined) {
    points.push({
      lat: shipment.origin.lat,
      lng: shipment.origin.lng,
      label: shipment.origin.storeName ?? 'Shop',
      kind: 'origin',
    });
  }
  if (shipment.destination?.lat !== undefined && shipment.destination.lng !== undefined) {
    points.push({
      lat: shipment.destination.lat,
      lng: shipment.destination.lng,
      label: 'Delivery',
      kind: 'destination',
    });
  }
  if (fix) points.push({ lat: fix.lat, lng: fix.lng, label: 'You', kind: 'driver' });

  return (
    <>
      <ConnectionBar />
      <TopBar
        title={shipment.reference ?? `#${shipment.id}`}
        onBack={() => navigate('/jobs')}
        action={
          <Pill tone={look.tone} icon={look.icon}>
            {look.label}
          </Pill>
        }
      />

      <div className="screen">
        {shipment.whatToDoNext && (
          <Banner tone="go" icon="👉">
            <strong style={{ fontSize: 19 }}>{shipment.whatToDoNext}</strong>
          </Banner>
        )}

        {shipment.status === 'ATTEMPT_FAILED' && shipment.nextAttemptAfter && (
          <Banner tone="warn" icon="⏰">
            Attempt {shipment.failedAttempts}. Try again after{' '}
            {dayAndTime(shipment.nextAttemptAfter)}.
          </Banner>
        )}

        {points.length > 0 && mapsConfigured() && <LiveMap points={points} />}

        {shipment.origin && <OriginCard origin={shipment.origin} />}

        {shipment.destination ? (
          <DestinationCard destination={shipment.destination} />
        ) : (
          <Card>
            <p className="card__meta" style={{ margin: 0 }}>
              🔒 {shipment.privacyNote ?? t('job.privacy')}
            </p>
          </Card>
        )}

        <Card>
          <div className="card__row">
            <span className="card__title">📦 {shipment.parcelCount} parcel(s)</span>
          </div>
          {shipment.contentsSummary && <p className="card__meta">{shipment.contentsSummary}</p>}
        </Card>

        {shipment.chain.length > 0 && (
          <>
            <h2 style={{ marginTop: 24 }}>{t('job.chain')}</h2>
            <ol className="chain">
              {shipment.chain.map((entry, index) => {
                const look2 = chainLook(entry.type);
                return (
                  <li className="chain__item" key={`${entry.type}-${index}`}>
                    <span className="chain__icon" aria-hidden="true">
                      {look2.icon}
                    </span>
                    <div>
                      <div style={{ fontWeight: 700 }}>{look2.label}</div>
                      <div className="chain__when">
                        {timeOfDay(entry.occurredAt)}
                        {entry.capturedOffline && ' · saved with no signal'}
                        {!entry.attestedByPosition && ' · position did not match'}
                      </div>
                      {entry.note && <div className="card__meta">{entry.note}</div>}
                    </div>
                  </li>
                );
              })}
            </ol>
          </>
        )}

        {shipment.custodyActive && (
          <div className="stack" style={{ marginTop: 24 }}>
            <Button tone="ghost" icon="🚫" onClick={() => setStep('failed')}>
              {t('job.failed')}
            </Button>
            <Button tone="ghost" icon="🤝" onClick={() => setStep('transfer')}>
              {t('job.transfer')}
            </Button>
          </div>
        )}
      </div>

      {primary && (
        <div className="primary-slot">
          <Button
            tone="go"
            major
            icon={primary.icon}
            busy={saving}
            onClick={() =>
              primary.kind === 'arrived' ? void recordArrival() : setStep(primary.kind)
            }
          >
            {t(primary.labelKey)}
          </Button>
        </div>
      )}
    </>
  );
}

/**
 * What the big green button does right now.
 *
 * Derived from the parcel's status rather than from anything this app
 * remembers, because the status is itself a consequence of the events on the
 * chain — there is no local flag that could get out of step with it.
 */
function primaryAction(
  shipment: ShipmentDetail,
  endsAtCounter: boolean,
): { kind: 'arrived' | HandoverKind; icon: string; labelKey: 'job.arrived' | 'job.collect' | 'job.deliver' | 'job.deposit' } | null {
  switch (shipment.status) {
    case 'DRIVER_ASSIGNED':
      return { kind: 'arrived', icon: '📍', labelKey: 'job.arrived' };
    case 'AT_ORIGIN':
      return { kind: 'collect', icon: '📦', labelKey: 'job.collect' };
    case 'IN_TRANSIT':
    case 'OUT_FOR_DELIVERY':
    case 'ATTEMPT_FAILED':
      return endsAtCounter
        ? { kind: 'deposit', icon: '🏬', labelKey: 'job.deposit' }
        : { kind: 'deliver', icon: '✅', labelKey: 'job.deliver' };
    default:
      return null;
  }
}

function OriginCard({ origin }: { origin: NonNullable<ShipmentDetail['origin']> }) {
  const t = useT();
  const target = { lat: origin.lat, lng: origin.lng, label: origin.label };

  return (
    <Card>
      <div className="card__row">
        <span className="card__title">🏪 {origin.storeName ?? 'The shop'}</span>
      </div>
      {origin.label && <p className="card__meta">{origin.label}</p>}

      <div className="button-row" style={{ marginTop: 12 }}>
        {hasTarget(target) && (
          <a
            className="button button--brand"
            href={directionsUrl(target)}
            target="_blank"
            rel="noopener noreferrer"
          >
            <span className="button__icon" aria-hidden="true">
              🧭
            </span>
            <span>{t('job.navigate')}</span>
          </a>
        )}
        {origin.storePhone && (
          <Button tone="ghost" icon="📞" onClick={() => callNumber(origin.storePhone!)}>
            {t('job.call')}
          </Button>
        )}
      </div>
    </Card>
  );
}

/**
 * The address, and who is expecting the parcel.
 *
 * Rendered only because the backend sent it, which it only does while the
 * driver is actually carrying the goods. The recipient is very often not the
 * buyer — she is the sister the parcel was sent to, and she may have no account
 * on this platform at all — so what is here is what a person needs to put a box
 * in somebody's hands, and nothing else.
 */
function DestinationCard({
  destination,
}: {
  destination: NonNullable<ShipmentDetail['destination']>;
}) {
  const t = useT();
  const target = {
    lat: destination.lat,
    lng: destination.lng,
    label: [destination.street, destination.city, destination.country].filter(Boolean).join(', '),
  };

  return (
    <Card>
      <div className="card__row">
        <span className="card__title">🏠 {destination.recipientName ?? 'The recipient'}</span>
      </div>
      <p className="card__meta">
        {[destination.street, destination.city, destination.country].filter(Boolean).join(', ')}
      </p>

      {destination.instructions && (
        <Banner tone="warn" icon="💬">
          {destination.instructions}
        </Banner>
      )}

      <div className="stack" style={{ marginTop: 12 }}>
        {hasTarget(target) && (
          <a
            className="button button--brand"
            href={directionsUrl(target)}
            target="_blank"
            rel="noopener noreferrer"
          >
            <span className="button__icon" aria-hidden="true">
              🧭
            </span>
            <span>{t('job.navigate')}</span>
          </a>
        )}
        {destination.recipientPhone && (
          <div className="button-row">
            <Button tone="ghost" icon="📞" onClick={() => callNumber(destination.recipientPhone!)}>
              {t('job.call')}
            </Button>
            <a
              className="button button--ghost"
              href={whatsAppUrl(destination.recipientPhone)}
              target="_blank"
              rel="noopener noreferrer"
            >
              <span className="button__icon" aria-hidden="true">
                💬
              </span>
              <span>{t('job.whatsapp')}</span>
            </a>
          </div>
        )}
      </div>
    </Card>
  );
}
