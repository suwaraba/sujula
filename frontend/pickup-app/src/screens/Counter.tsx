import { useMemo, useState } from 'react';
import { useCounter } from '@/counter/CounterProvider';
import { useCurrencies, useDebounced } from '@/lib/hooks';
import { formatDateTime, formatDeadline } from '@/lib/format';
import type { StoredParcel } from '@/api/types';
import {
  Badge, Card, EmptyState, PageHeader, ShelfCode, SkeletonList, Button,
} from '@/components/ui';
import { ReleaseSheet } from './ReleaseSheet';
import { ParcelSheet } from './ParcelSheet';

/**
 * The shelf.
 *
 * What an operator looks at all day. Somebody walks up, says a name or holds
 * out a phone with a reference on it, and this screen's whole job is to get
 * from that to the right parcel in one step — which is why the search matches
 * the name, the reference and the shelf label at once, and why the shelf label
 * is the largest thing on each row.
 */
export function Counter() {
  const { parcels, parcelsLoading, point } = useCounter();
  const [search, setSearch] = useState('');
  const [releasing, setReleasing] = useState<StoredParcel | null>(null);
  const [viewing, setViewing] = useState<StoredParcel | null>(null);
  const query = useDebounced(search, 150);
  const { money } = useCurrencies();

  // Everything on the shelf, overdue ones included: somebody arriving to
  // collect does not care that their parcel is late, and hiding it would make
  // the search fail for the one person most likely to be standing there.
  const shelf = useMemo(
    () => [...(parcels?.stored ?? []), ...(parcels?.overdue ?? [])],
    [parcels],
  );

  const matches = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return shelf;
    return shelf.filter((parcel) =>
      [parcel.recipientName, parcel.reference, parcel.shelfCode, parcel.fromStore]
        .filter(Boolean)
        .some((field) => field!.toLowerCase().includes(needle)),
    );
  }, [shelf, query]);

  return (
    <div className="page stack">
      <PageHeader
        title="On the shelf"
        subtitle={
          parcels
            ? `${parcels.storedCount} of ${parcels.capacity} shelves used`
            : point?.name
        }
      />

      {parcels && <CapacityBar used={parcels.storedCount} total={parcels.capacity} band={parcels.capacityBand} />}

      <input
        className="input input--lg"
        type="search"
        placeholder="Name, reference or shelf"
        value={search}
        onChange={(event) => setSearch(event.target.value)}
        aria-label="Find a parcel by name, reference or shelf"
        autoComplete="off"
        autoCorrect="off"
        spellCheck={false}
      />

      <Card flush>
        {parcelsLoading ? (
          <SkeletonList rows={5} />
        ) : shelf.length === 0 ? (
          <EmptyState icon="▤" title="The shelf is empty">
            Parcels appear here once you have taken them in from a driver.
          </EmptyState>
        ) : matches.length === 0 ? (
          <EmptyState icon="⌕" title="Nothing matched">
            Try part of the name, or the reference on their phone.
          </EmptyState>
        ) : (
          <div className="list">
            {matches.map((parcel) => (
              <div key={parcel.shipmentId} className="list__item">
                <ShelfCode code={parcel.shelfCode} />
                <div className="list__main">
                  <div className="list__title">{parcel.recipientName ?? 'No name given'}</div>
                  <div className="list__meta">
                    <span className="mono">{parcel.reference}</span>
                    {parcel.recipientPhoneHint && ` · ${parcel.recipientPhoneHint}`}
                    {parcel.parcelCount > 1 && ` · ${parcel.parcelCount} parcels`}
                  </div>
                  <div className="row" style={{ gap: 'var(--space-2)', marginTop: 4 }}>
                    <Badge tone={parcel.overdue ? 'danger' : parcel.daysRemaining <= 1 ? 'warn' : 'neutral'}>
                      {formatDeadline(parcel.daysRemaining, parcel.overdue)}
                    </Badge>
                    {parcel.commission != null && (
                      <Badge tone="ok">{money(parcel.commission, parcel.commissionCurrency)}</Badge>
                    )}
                  </div>
                </div>
                <div className="row" style={{ gap: 'var(--space-2)', flexWrap: 'nowrap' }}>
                  <Button variant="primary" onClick={() => setReleasing(parcel)}>
                    Hand over
                  </Button>
                  <Button variant="ghost" size="sm" onClick={() => setViewing(parcel)}>
                    Details
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      {parcels?.note && <p className="small muted">{parcels.note}</p>}

      {releasing && (
        <ReleaseSheet parcel={releasing} onClose={() => setReleasing(null)} />
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
    </div>
  );
}

export function CapacityBar({
  used, total, band,
}: { used: number; total: number; band: string }) {
  const percent = total > 0 ? Math.min(100, Math.round((used / total) * 100)) : 0;
  const fillClass =
    band === 'FULL' ? 'capacity__fill is-full'
    : band === 'LIMITED' ? 'capacity__fill is-limited'
    : 'capacity__fill';

  return (
    <div className="capacity">
      <div
        className="capacity__track"
        role="meter"
        aria-valuenow={used}
        aria-valuemin={0}
        aria-valuemax={total}
        aria-label={`${used} of ${total} shelves used`}
      >
        <div className={fillClass} style={{ width: `${percent}%` }} />
      </div>
    </div>
  );
}

/** Shared by the shelf and the detail sheet. */
export function ParcelFacts({ parcel }: { parcel: StoredParcel }) {
  const { money } = useCurrencies();
  return (
    <dl className="kv">
      <dt className="kv__key">Reference</dt>
      <dd className="kv__value mono" style={{ margin: 0 }}>{parcel.reference}</dd>
      <dt className="kv__key">For</dt>
      <dd className="kv__value" style={{ margin: 0 }}>{parcel.recipientName ?? '—'}</dd>
      <dt className="kv__key">Phone</dt>
      <dd className="kv__value mono" style={{ margin: 0 }}>{parcel.recipientPhoneHint ?? '—'}</dd>
      <dt className="kv__key">From</dt>
      <dd className="kv__value" style={{ margin: 0 }}>{parcel.fromStore ?? '—'}</dd>
      <dt className="kv__key">Parcels</dt>
      <dd className="kv__value" style={{ margin: 0 }}>{parcel.parcelCount}</dd>
      <dt className="kv__key">Taken in</dt>
      <dd className="kv__value" style={{ margin: 0 }}>{formatDateTime(parcel.storedAt)}</dd>
      <dt className="kv__key">Goes back</dt>
      <dd className="kv__value" style={{ margin: 0 }}>
        {formatDateTime(parcel.storageDeadline)}
        {' — '}
        {formatDeadline(parcel.daysRemaining, parcel.overdue)}
      </dd>
      <dt className="kv__key">You earn</dt>
      <dd className="kv__value" style={{ margin: 0 }}>
        {money(parcel.commission, parcel.commissionCurrency)}
      </dd>
    </dl>
  );
}
