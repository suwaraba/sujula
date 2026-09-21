import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { pickupApi } from '@/api/endpoints';
import { useCounter } from '@/counter/CounterProvider';
import { useCurrencies } from '@/lib/hooks';
import { formatDate, formatDateTime, formatNumber, humanise, isoDaysAgo, isoToday } from '@/lib/format';
import { Card, EmptyState, Notice, PageHeader, Skeleton, Stat } from '@/components/ui';
import { SelectField } from '@/components/form';

const RANGES = [
  { label: 'Last 7 days', days: 7 },
  { label: 'Last 30 days', days: 30 },
  { label: 'Last 90 days', days: 90 },
];

/**
 * What the counter has earned.
 *
 * Per currency, and never summed across one. A counter that has held parcels
 * priced in dalasi and in CFA has two earnings, and adding them would need a
 * rate nobody agreed to — the same rule the seller's app follows, for the same
 * reason.
 */
export function Earnings() {
  const { pointId } = useCounter();
  const [rangeIndex, setRangeIndex] = useState(1);
  const { money } = useCurrencies();

  const range = RANGES[rangeIndex]!;
  const window = useMemo(
    () => ({ from: isoDaysAgo(range.days), to: isoToday() }),
    [range.days],
  );

  const earnings = useQuery({
    queryKey: ['earnings', pointId, window],
    queryFn: () => pickupApi.earnings(pointId!, window),
    enabled: pointId != null,
  });

  return (
    <div className="page stack stack--loose">
      <PageHeader
        title="Earnings"
        subtitle="A commission per parcel, paid in whatever the parcel was priced in."
        actions={
          <SelectField
            label={<span className="sr-only">Period</span>}
            value={String(rangeIndex)}
            onChange={(event) => setRangeIndex(Number(event.target.value))}
          >
            {RANGES.map((entry, index) => (
              <option key={entry.label} value={index}>{entry.label}</option>
            ))}
          </SelectField>
        }
      />

      {earnings.isLoading ? (
        <Skeleton height={180} />
      ) : (earnings.data?.byCurrency.length ?? 0) === 0 ? (
        <Card>
          <EmptyState icon="◫" title="Nothing yet in this period">
            A commission is earned when a parcel you took in is collected.
          </EmptyState>
        </Card>
      ) : (
        <>
          <Card flush>
            <Stat
              label="Parcels handled"
              value={formatNumber(earnings.data!.parcelsHandled)}
              note={`${formatDate(earnings.data!.from)} to ${formatDate(earnings.data!.to)}`}
            />
          </Card>

          {earnings.data!.byCurrency.map((row) => (
            <Card key={row.currency} title={`Earned in ${row.currency}`} flush>
              <div style={{ padding: 'var(--space-4)' }}>
                <Stat
                  label="Total"
                  value={money(row.total, row.currency)}
                  note={`across ${formatNumber(row.parcels)} parcel${row.parcels === 1 ? '' : 's'}`}
                />
              </div>

              {row.lines.length > 0 && (
                <div className="list">
                  {row.lines.map((line) => (
                    <div key={line.shipmentId} className="list__item">
                      <div className="list__main">
                        <div className="list__title mono">{line.reference}</div>
                        <div className="list__meta">
                          Taken in {formatDateTime(line.storedAt)}
                          {line.outcome && ` · ${humanise(line.outcome)}`}
                        </div>
                      </div>
                      <div className="list__side">
                        <div className="num" style={{ fontWeight: 650 }}>
                          {money(line.commission, line.currency)}
                        </div>
                        <div className="small muted">
                          {line.settledAt ? `paid ${formatDate(line.settledAt)}` : 'not yet paid'}
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </Card>
          ))}
        </>
      )}

      {earnings.data?.note && <Notice tone="info">{earnings.data.note}</Notice>}
    </div>
  );
}
