import type { VendorOrderStatus } from '@/api/types';
import { formatDateTime, humanise } from '@/lib/format';
import { Badge, type Tone } from './ui';

const TONES: Record<VendorOrderStatus, Tone> = {
  PENDING: 'warn',
  PREPARING: 'info',
  READY_FOR_PICKUP: 'accent',
  SHIPPED: 'info',
  DELIVERED: 'ok',
  CANCELLED: 'danger',
  REFUNDED: 'danger',
};

const LABELS: Record<VendorOrderStatus, string> = {
  PENDING: 'To accept',
  PREPARING: 'Packing',
  READY_FOR_PICKUP: 'Waiting for driver',
  SHIPPED: 'With the driver',
  DELIVERED: 'Delivered',
  CANCELLED: 'Cancelled',
  REFUNDED: 'Refunded',
};

export function OrderStatusBadge({ status }: { status: VendorOrderStatus }) {
  return (
    <Badge tone={TONES[status] ?? 'neutral'} dot>
      {LABELS[status] ?? humanise(status)}
    </Badge>
  );
}

/**
 * The custody chain, as the seller sees it.
 *
 * The last two rungs are not buttons and there is nothing on this screen that
 * would make them buttons. A seller's ladder ends at "waiting for driver":
 * `VendorOrderStatus.isVendorSettable()` allows PREPARING, READY_FOR_PICKUP and
 * CANCELLED, and nothing else. SHIPPED is what a driver produces by presenting
 * the collection code, and DELIVERED is what the recipient produces with the
 * code sent to their phone — including a recipient who has no account, which is
 * the case this marketplace exists to serve.
 *
 * So this component *reports* the last two and *acts* on the first three. A
 * seller can see exactly where their parcel is and who is holding it; they
 * cannot assert that it arrived, because their saying so would not be evidence
 * that it did.
 */
type Step = {
  key: string;
  title: string;
  done: boolean;
  current: boolean;
  at: string | null;
  proof: string | null;
  theirs: boolean;
};

export function CustodyChain({
  status, acceptedAt, readyAt, collectedAt, placedAt, recipientName, pickupPointName, deliveryMode,
}: {
  status: VendorOrderStatus;
  placedAt: string;
  acceptedAt: string | null;
  readyAt: string | null;
  collectedAt: string | null;
  recipientName: string | null;
  pickupPointName: string | null;
  deliveryMode: string | null;
}) {
  const cancelled = status === 'CANCELLED' || status === 'REFUNDED';
  const shipped = status === 'SHIPPED' || status === 'DELIVERED';
  const delivered = status === 'DELIVERED';
  const viaPickupPoint = deliveryMode === 'PICKUP_POINT';

  const steps: Step[] = [
    {
      key: 'paid',
      title: 'Buyer paid',
      done: true,
      current: false,
      at: placedAt,
      proof: 'Payment confirmed before this order reached you.',
      theirs: false,
    },
    {
      key: 'accepted',
      title: 'You accepted it',
      done: acceptedAt != null,
      current: status === 'PENDING',
      at: acceptedAt,
      proof: null,
      theirs: true,
    },
    {
      key: 'ready',
      title: 'You packed it',
      done: readyAt != null,
      current: status === 'PREPARING',
      at: readyAt,
      proof: readyAt ? 'Collection code issued.' : null,
      theirs: true,
    },
    {
      key: 'collected',
      title: 'Driver collected it',
      done: shipped,
      current: status === 'READY_FOR_PICKUP',
      at: collectedAt,
      proof: shipped
        ? 'The driver presented your collection code.'
        : 'Happens when the driver presents your collection code — not something you mark.',
      theirs: false,
    },
    ...(viaPickupPoint
      ? [{
          key: 'deposited',
          title: `Left at ${pickupPointName ?? 'the pickup point'}`,
          done: delivered,
          current: false,
          at: null,
          proof: 'The counter signs for it when the driver hands it over.',
          theirs: false,
        }]
      : []),
    {
      key: 'delivered',
      title: viaPickupPoint
        ? `Collected by ${recipientName ?? 'the recipient'}`
        : `Delivered to ${recipientName ?? 'the recipient'}`,
      done: delivered,
      current: status === 'SHIPPED',
      at: null,
      proof: delivered
        ? 'The recipient gave the code sent to their phone.'
        : 'Happens when the recipient reads back the code texted to them — not something you mark.',
      theirs: false,
    },
  ];

  if (cancelled) {
    return (
      <div className="chain">
        <ChainStep
          step={{
            key: 'cancelled',
            title: status === 'REFUNDED' ? 'Refunded' : 'Cancelled',
            done: true,
            current: false,
            at: null,
            proof: 'This order will not be delivered.',
            theirs: false,
          }}
          last
        />
      </div>
    );
  }

  return (
    <div className="chain">
      {steps.map((step, index) => (
        <ChainStep key={step.key} step={step} last={index === steps.length - 1} />
      ))}
    </div>
  );
}

function ChainStep({ step, last }: { step: Step; last: boolean }) {
  const dotClass = step.done ? 'chain__dot is-done' : step.current ? 'chain__dot is-current' : 'chain__dot';
  return (
    <div className="chain__step">
      <div className="chain__rail">
        <span className={dotClass} aria-hidden="true" />
        {!last && <span className={step.done ? 'chain__line is-done' : 'chain__line'} aria-hidden="true" />}
      </div>
      <div className="chain__content">
        <div className={step.done || step.current ? 'chain__title' : 'chain__title is-pending'}>
          {step.title}
          {step.theirs && !step.done && step.current && (
            <span className="badge badge--accent" style={{ marginLeft: 'var(--space-2)' }}>
              Your turn
            </span>
          )}
        </div>
        {step.at && <div className="chain__meta">{formatDateTime(step.at)}</div>}
        {step.proof && <div className="chain__proof">{step.proof}</div>}
      </div>
    </div>
  );
}
