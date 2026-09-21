import { Link } from 'react-router-dom';
import { useCounter } from '@/counter/CounterProvider';
import { formatDateTime } from '@/lib/format';
import { Notice } from './ui';

/**
 * What is standing between this counter and taking a parcel in.
 *
 * Above every screen rather than only on settings, because the moment an
 * operator finds out is otherwise the moment a driver is standing at the
 * counter with a parcel and the accept button answers 400.
 */
export function CounterAlerts() {
  const { point, canTakeParcels, parcels } = useCounter();
  if (!point) return null;

  const alerts = [];

  if (point.status === 'PENDING' || point.status === 'PENDING_KYC') {
    alerts.push(
      <Notice key="pending" tone="warn" title="This counter has not been approved yet">
        Somebody is checking it is real. Until that is done, drivers are not sent here and nothing
        can be taken in.
      </Notice>,
    );
  } else if (point.status === 'SUSPENDED' || point.status === 'REJECTED') {
    alerts.push(
      <Notice key="status" tone="danger" title={`This counter is ${point.status.toLowerCase()}`}>
        {point.closureReason ?? 'Contact support. Parcels already on the shelf still have to be handed over.'}
      </Notice>,
    );
  } else if (!canTakeParcels) {
    alerts.push(
      <Notice key="closed" tone="warn" title="This counter is closed">
        {point.closedUntil ? `Until ${formatDateTime(point.closedUntil)}. ` : ''}
        {point.closureReason ?? ''} Parcels already here can still be handed over —{' '}
        the people waiting on them did not choose the closure.{' '}
        <Link to="/settings">Reopen</Link>
      </Notice>,
    );
  }

  if (parcels && parcels.capacityBand === 'FULL') {
    alerts.push(
      <Notice key="full" tone="danger" title="The shelf is full">
        {parcels.storedCount} of {parcels.capacity}. New parcels will be turned away until some
        are collected. <Link to="/overdue">Send back what is overdue</Link> to make room.
      </Notice>,
    );
  } else if (parcels && parcels.capacityBand === 'LIMITED') {
    alerts.push(
      <Notice key="limited" tone="warn">
        Running out of room: {parcels.storedCount} of {parcels.capacity} shelves used.
      </Notice>,
    );
  }

  if (alerts.length === 0) return null;
  return <div className="stack stack--tight" style={{ marginBottom: 'var(--space-4)' }}>{alerts}</div>;
}
