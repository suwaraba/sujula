/**
 * What the driver has earned.
 *
 * Grouped by currency and never summed across one, because on this platform a
 * driver who has worked legs paid in dalasi and in CFA has two earnings, not
 * one. Adding them would need an exchange rate, and nobody agreed to a rate for
 * a driver's wages. The API returns them apart for exactly this reason and the
 * screen keeps them apart.
 *
 * XOF has no minor unit — 1250.50 CFA is not an amount that exists — so every
 * figure goes through the currency-aware formatter rather than `toFixed(2)`.
 */

import { useState } from 'react';
import { useEarnings } from '../api/queries';
import { Banner, Card, Empty, Pill, Spinner } from '../components/ui';
import { ConnectionBar, TopBar } from '../components/Chrome';
import { dayAndTime, money } from '../lib/format';
import { useT } from '../i18n';

type Range = 'today' | 'week' | 'month';

function rangeFor(range: Range): { from: string; to: string } {
  const now = new Date();
  const iso = (date: Date) => date.toISOString().slice(0, 10);
  const start = new Date(now);
  if (range === 'week') start.setDate(now.getDate() - 6);
  if (range === 'month') start.setDate(now.getDate() - 29);
  return { from: iso(start), to: iso(now) };
}

export function Earnings() {
  const t = useT();
  const [range, setRange] = useState<Range>('week');
  const { from, to } = rangeFor(range);
  const query = useEarnings(from, to);

  return (
    <>
      <ConnectionBar />
      <TopBar title={t('earnings.title')} />

      <div className="screen">
        <div className="chips" style={{ marginBottom: 16 }}>
          {(
            [
              ['today', 'Today'],
              ['week', '7 days'],
              ['month', '30 days'],
            ] as const
          ).map(([value, label]) => (
            <button
              key={value}
              type="button"
              className="chip"
              aria-pressed={range === value}
              onClick={() => setRange(value)}
            >
              {label}
            </button>
          ))}
        </div>

        {query.isLoading && <Spinner />}
        {query.isError && <Banner tone="stop">Could not load your earnings.</Banner>}

        {query.data && (
          <>
            <Pill icon="✅">{t('earnings.deliveries', { count: query.data.deliveries })}</Pill>

            {query.data.byCurrency.length === 0 && (
              <Empty icon="💵">{t('earnings.none')}</Empty>
            )}

            {query.data.byCurrency.map((bucket) => (
              <div key={bucket.currency} style={{ marginTop: 20 }}>
                <Card>
                  <div className="card__row">
                    <span className="card__title">{bucket.currency}</span>
                    <span className="money">{money(bucket.total, bucket.currency)}</span>
                  </div>
                  <p className="card__meta">
                    {bucket.legs} job(s) · average {money(bucket.averagePerLeg, bucket.currency)}
                  </p>
                </Card>

                {bucket.lines.map((line) => (
                  <Card key={line.legId}>
                    <div className="card__row">
                      <div>
                        <div style={{ fontWeight: 700 }}>
                          {line.dropTo ?? line.shipmentReference ?? `#${line.shipmentId}`}
                        </div>
                        <div className="card__meta">{dayAndTime(line.completedAt)}</div>
                      </div>
                      <span className="money" style={{ fontSize: 19 }}>
                        {money(line.amount, line.currency)}
                      </span>
                    </div>
                  </Card>
                ))}
              </div>
            ))}

            {query.data.note && <p className="muted">{query.data.note}</p>}
          </>
        )}
      </div>
    </>
  );
}
