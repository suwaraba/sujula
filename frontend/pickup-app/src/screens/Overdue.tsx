import { useState } from 'react';
import { useCounter } from '@/counter/CounterProvider';
import { formatDateTime, formatDeadline } from '@/lib/format';
import type { StoredParcel } from '@/api/types';
import { Badge, Button, Card, EmptyState, Notice, PageHeader, ShelfCode, SkeletonList } from '@/components/ui';
import { ParcelSheet } from './ParcelSheet';
import { ReleaseSheet } from './ReleaseSheet';

/**
 * Parcels nobody came for.
 *
 * Its own screen rather than a filter, because it is its own job — worked at
 * the end of a week rather than across a day — and because a shelf that is
 * filling up is cleared from here.
 */
export function Overdue() {
  const { parcels, parcelsLoading, point } = useCounter();
  const [viewing, setViewing] = useState<StoredParcel | null>(null);
  const [releasing, setReleasing] = useState<StoredParcel | null>(null);

  const overdue = parcels?.overdue ?? [];

  // Not yet overdue, but will be within the day. Worth a nudge before it
  // becomes a return.
  const soon = (parcels?.stored ?? []).filter((parcel) => parcel.daysRemaining <= 1);

  return (
    <div className="page stack">
      <PageHeader
        title="To return"
        subtitle={`Parcels past their ${point?.storageDays ?? ''} day${point?.storageDays === 1 ? '' : 's'} on the shelf`.replace('  ', ' ')}
      />

      <Card flush>
        {parcelsLoading ? (
          <SkeletonList rows={3} />
        ) : overdue.length === 0 ? (
          <EmptyState icon="✓" title="Nothing to send back">
            Everything on the shelf is still within its time.
          </EmptyState>
        ) : (
          <div className="list">
            {overdue.map((parcel) => (
              <div key={parcel.shipmentId} className="list__item">
                <ShelfCode code={parcel.shelfCode} />
                <div className="list__main">
                  <div className="list__title">{parcel.recipientName ?? 'No name given'}</div>
                  <div className="list__meta">
                    <span className="mono">{parcel.reference}</span>
                    {' · here since '}{formatDateTime(parcel.storedAt)}
                  </div>
                  <div style={{ marginTop: 4 }}>
                    <Badge tone="danger">{formatDeadline(parcel.daysRemaining, true)}</Badge>
                  </div>
                </div>
                <div className="row" style={{ gap: 'var(--space-2)', flexWrap: 'nowrap' }}>
                  <Button variant="secondary" onClick={() => setReleasing(parcel)}>
                    Hand over
                  </Button>
                  <Button variant="ghost" size="sm" onClick={() => setViewing(parcel)}>
                    Send back
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      {overdue.length > 0 && (
        <Notice tone="info">
          Somebody can still collect these — that is why "hand over" is the first button. Sending
          one back is the last resort, not the default.
        </Notice>
      )}

      {soon.length > 0 && (
        <Card title={`Due back within a day (${soon.length})`} flush>
          <div className="list">
            {soon.map((parcel) => (
              <div key={parcel.shipmentId} className="list__item">
                <ShelfCode code={parcel.shelfCode} />
                <div className="list__main">
                  <div className="list__title">{parcel.recipientName ?? 'No name given'}</div>
                  <div className="list__meta">
                    <span className="mono">{parcel.reference}</span>
                  </div>
                </div>
                <Badge tone="warn">{formatDeadline(parcel.daysRemaining, false)}</Badge>
                <Button variant="ghost" size="sm" onClick={() => setViewing(parcel)}>
                  Remind them
                </Button>
              </div>
            ))}
          </div>
        </Card>
      )}

      {viewing && (
        <ParcelSheet
          parcel={viewing}
          onClose={() => setViewing(null)}
          onRelease={() => {
            setViewing(null);
            setReleasing(viewing);
          }}
        />
      )}

      {releasing && <ReleaseSheet parcel={releasing} onClose={() => setReleasing(null)} />}
    </div>
  );
}
