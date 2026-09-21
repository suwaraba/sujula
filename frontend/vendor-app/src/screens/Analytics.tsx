import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { analyticsApi } from '@/api/endpoints/analytics';
import { useCurrencies } from '@/lib/hooks';
import { formatDate, formatNumber, formatPercent } from '@/lib/format';
import type { SalesSeries } from '@/api/types';
import {
  Card, EmptyState, Notice, PageHeader, Skeleton, Stat,
} from '@/components/ui';
import { SelectField } from '@/components/form';

const RANGES = [
  { label: 'Last 7 days', days: 7, groupBy: 'day' as const },
  { label: 'Last 30 days', days: 30, groupBy: 'day' as const },
  { label: 'Last 90 days', days: 90, groupBy: 'week' as const },
  { label: 'Last 12 months', days: 365, groupBy: 'month' as const },
];

function isoDaysAgo(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() - days);
  return date.toISOString().slice(0, 10);
}

export function Analytics() {
  const [rangeIndex, setRangeIndex] = useState(1);
  const range = RANGES[rangeIndex]!;
  const { money } = useCurrencies();

  const window = useMemo(
    () => ({ from: isoDaysAgo(range.days), to: new Date().toISOString().slice(0, 10) }),
    [range.days],
  );

  const overview = useQuery({
    queryKey: ['analytics', 'overview', window],
    queryFn: () => analyticsApi.overview(window),
  });

  const sales = useQuery({
    queryKey: ['analytics', 'sales', window, range.groupBy],
    queryFn: () => analyticsApi.sales({ ...window, groupBy: range.groupBy }),
  });

  const products = useQuery({
    queryKey: ['analytics', 'products', window],
    queryFn: () => analyticsApi.products({ ...window, limit: 10 }),
  });

  const customers = useQuery({
    queryKey: ['analytics', 'customers', window],
    queryFn: () => analyticsApi.customers(window),
  });

  const delivery = useQuery({
    queryKey: ['analytics', 'delivery', window],
    queryFn: () => analyticsApi.delivery(window),
  });

  return (
    <div className="page stack stack--loose">
      <PageHeader
        title="Insights"
        subtitle="What sold, what was only looked at, and how the parcels went."
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

      {overview.isLoading ? (
        <div className="grid grid--4">
          {[0, 1, 2, 3].map((i) => <Skeleton key={i} height={100} />)}
        </div>
      ) : overview.data ? (
        <>
          <div className="grid grid--4">
            <Card flush>
              <Stat
                label="Orders"
                value={formatNumber(overview.data.orders)}
                delta={overview.data.ordersChangePercent}
                note={`vs ${formatNumber(overview.data.ordersBefore)} before`}
              />
            </Card>
            <Card flush>
              <Stat label="Units sold" value={formatNumber(overview.data.unitsSold)} />
            </Card>
            <Card flush>
              <Stat
                label="Views"
                value={formatNumber(overview.data.productViews)}
                note={overview.data.conversionNote ?? undefined}
              />
            </Card>
            <Card flush>
              <Stat
                label="Views that bought"
                value={formatPercent(overview.data.conversionPercent)}
                delta={
                  overview.data.conversionPercent != null && overview.data.conversionPercentBefore != null
                    ? overview.data.conversionPercent - overview.data.conversionPercentBefore
                    : null
                }
              />
            </Card>
          </div>

          {/*
            One card per currency rather than one total. A seller paid in two
            currencies holds two revenues, and a single "revenue" figure would
            have had to pick a rate to add them — which is a number nobody
            was ever charged or paid.
          */}
          <div className="grid grid--2">
            {overview.data.byCurrency.map((row) => (
              <Card key={row.currency} title={`Money in ${row.currency}`}>
                <div className="grid grid--2">
                  <Stat
                    label="Revenue"
                    value={money(row.revenue, row.currency)}
                    delta={row.revenueChangePercent}
                  />
                  <Stat label="After commission" value={money(row.netRevenue, row.currency)} />
                  <Stat label="Average order" value={money(row.averageOrderValue, row.currency)} />
                  <Stat label="Refunded" value={money(row.refunded, row.currency)} />
                </div>
              </Card>
            ))}
          </div>

          {(overview.data.cancelledOrders > 0 || overview.data.refundedOrders > 0) && (
            <Notice tone="warn">
              {overview.data.cancelledOrders} cancelled and {overview.data.refundedOrders} refunded
              in this period.
            </Notice>
          )}
        </>
      ) : null}

      <Card title="Sales over time">
        {sales.isLoading ? (
          <Skeleton height={180} />
        ) : (sales.data?.byCurrency.length ?? 0) === 0 ? (
          <EmptyState icon="◈" title="Nothing sold in this period" />
        ) : (
          <div className="stack">
            {sales.data!.byCurrency.map((series) => (
              <SalesChart key={series.currency} series={series} />
            ))}
          </div>
        )}
      </Card>

      <Card title="What sold, and what was only looked at" flush>
        {products.isLoading ? (
          <Skeleton height={160} />
        ) : (products.data?.rows.length ?? 0) === 0 ? (
          <EmptyState icon="☰" title="No product activity in this period" />
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Product</th>
                  <th className="num">Sold</th>
                  <th className="num">Revenue</th>
                  <th className="num">Views</th>
                  <th className="num">Bought</th>
                  <th className="num">In stock</th>
                </tr>
              </thead>
              <tbody>
                {products.data!.rows.map((row) => (
                  <tr key={row.productId}>
                    <td style={{ whiteSpace: 'normal', maxWidth: 260 }}>{row.name}</td>
                    <td className="num">{formatNumber(row.unitsSold)}</td>
                    <td className="num">{money(row.revenue, row.currency)}</td>
                    <td className="num">{formatNumber(row.views)}</td>
                    <td className="num">{formatPercent(row.conversionPercent)}</td>
                    <td className="num">{row.stockOnHand ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <div className="grid grid--2">
        <Card title="Who is buying">
          {customers.isLoading ? (
            <Skeleton height={140} />
          ) : customers.data ? (
            <div className="stack">
              <div className="grid grid--2">
                <Stat label="Buyers" value={formatNumber(customers.data.buyers)} />
                <Stat
                  label="Coming back"
                  value={formatPercent(customers.data.returningPercent)}
                  note={`${formatNumber(customers.data.returningBuyers)} returning`}
                />
              </div>

              {customers.data.destinations.length > 0 && (
                <div>
                  <h4 style={{ marginBottom: 'var(--space-2)' }}>Where parcels went</h4>
                  <div className="stack stack--tight">
                    {customers.data.destinations.map((row) => (
                      <div key={row.country} className="row row--between">
                        <span>{row.country}</span>
                        <span className="num muted">
                          {formatNumber(row.orders)} order{row.orders === 1 ? '' : 's'}
                        </span>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {customers.data.suppressedDestinations > 0 && (
                <p className="small muted">
                  {customers.data.privacyNote ??
                    `${customers.data.suppressedDestinations} destination(s) hidden because too few orders went there to show them without identifying somebody.`}
                </p>
              )}
            </div>
          ) : null}
        </Card>

        <Card title="How deliveries went">
          {delivery.isLoading ? (
            <Skeleton height={140} />
          ) : delivery.data ? (
            <div className="stack">
              <div className="grid grid--2">
                <Stat label="Parcels" value={formatNumber(delivery.data.parcels)} />
                <Stat label="Arrived" value={formatPercent(delivery.data.successPercent)} />
                <Stat
                  label="Hours to pack"
                  value={delivery.data.averageHoursToReady?.toFixed(1) ?? '—'}
                  note="From accepting to calling a driver — the part you control"
                />
                <Stat
                  label="Hours to arrive"
                  value={delivery.data.averageHoursToDelivered?.toFixed(1) ?? '—'}
                />
              </div>

              {delivery.data.failed > 0 && (
                <Notice tone="warn">
                  {formatNumber(delivery.data.failed)} parcel
                  {delivery.data.failed === 1 ? '' : 's'} failed to arrive.
                  {delivery.data.failuresByZone.length > 0 && (
                    <> Worst area: {delivery.data.failuresByZone[0]!.zone}.</>
                  )}
                </Notice>
              )}
            </div>
          ) : null}
        </Card>
      </div>

      <p className="small muted">
        Covering {formatDate(window.from)} to {formatDate(window.to)}.
      </p>
    </div>
  );
}

/**
 * A plain bar chart in SVG.
 *
 * Deliberately not a charting library: this is one series of at most 52 bars
 * with no interaction beyond a tooltip, and a dependency for that would cost
 * more download than the whole screen. The bars carry `<title>` so hovering or
 * focusing reads the real figure, and the table below it is the accessible
 * version rather than an afterthought.
 */
function SalesChart({ series }: { series: SalesSeries }) {
  const { money } = useCurrencies();
  const max = Math.max(...series.points.map((p) => p.revenue), 0);

  if (series.points.length === 0) {
    return <p className="muted">No sales in {series.currency} this period.</p>;
  }

  return (
    <div className="stack stack--tight">
      <div className="row row--between">
        <h4>{series.currency}</h4>
        <span className="muted small num">
          {money(series.total, series.currency)} across {formatNumber(series.orders)} order
          {series.orders === 1 ? '' : 's'}
        </span>
      </div>

      <div
        style={{
          display: 'flex', alignItems: 'flex-end', gap: 2,
          height: 140, padding: 'var(--space-2) 0',
        }}
        role="img"
        aria-label={`Revenue in ${series.currency} over the period, peaking at ${money(max, series.currency)}`}
      >
        {series.points.map((point) => {
          const height = max > 0 ? Math.max(2, (point.revenue / max) * 100) : 2;
          return (
            <div
              key={point.bucketStart}
              style={{
                flex: 1,
                minWidth: 3,
                height: `${height}%`,
                background: point.revenue > 0 ? 'var(--accent)' : 'var(--border)',
                borderRadius: '2px 2px 0 0',
              }}
            >
              <title>
                {formatDate(point.bucketStart)}: {money(point.revenue, series.currency)},{' '}
                {point.orders} order{point.orders === 1 ? '' : 's'}
              </title>
            </div>
          );
        })}
      </div>

      <div className="row row--between small faint">
        <span>{formatDate(series.points[0]!.bucketStart)}</span>
        <span>{formatDate(series.points[series.points.length - 1]!.bucketEnd)}</span>
      </div>
    </div>
  );
}
