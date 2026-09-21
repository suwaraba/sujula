/**
 * Jobs already finished.
 *
 * Towns rather than addresses, and no recipient names — which is the API's
 * decision, not a choice this screen makes. A round from three months ago is
 * not a reason for a phone in a shared vehicle to still hold somebody's front
 * door.
 */

import { useState } from 'react';
import { useHistory } from '../api/queries';
import { Banner, Button, Card, Empty, Pill, Spinner } from '../components/ui';
import { ConnectionBar, TopBar } from '../components/Chrome';
import { dayAndTime, money, statusLook } from '../lib/format';
import { useT } from '../i18n';

export function History() {
  const t = useT();
  const [page, setPage] = useState(0);
  const query = useHistory(page);

  return (
    <>
      <ConnectionBar />
      <TopBar title={t('history.title')} />

      <div className="screen">
        {query.isLoading && <Spinner />}
        {query.isError && <Banner tone="stop">Could not load your history.</Banner>}

        {query.data?.rows.length === 0 && <Empty icon="✅">{t('history.none')}</Empty>}

        {query.data?.rows.map((row) => {
          const look = statusLook(row.status);
          return (
            <Card key={`${row.shipmentId}-${row.completedAt}`}>
              <div className="card__row">
                <div>
                  <div style={{ fontWeight: 700 }}>
                    {[row.dropCity, row.dropCountry].filter(Boolean).join(', ') ||
                      row.reference ||
                      `#${row.shipmentId}`}
                  </div>
                  <div className="card__meta">{dayAndTime(row.completedAt)}</div>
                </div>
                <div style={{ textAlign: 'right' }}>
                  <div className="money" style={{ fontSize: 19 }}>
                    {money(row.earning, row.earningCurrency)}
                  </div>
                  <Pill tone={look.tone} icon={look.icon}>
                    {look.label}
                  </Pill>
                </div>
              </div>
            </Card>
          );
        })}

        {query.data && query.data.totalPages > 1 && (
          <div className="button-row" style={{ marginTop: 16 }}>
            <Button tone="ghost" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
              ‹ Newer
            </Button>
            <Button
              tone="ghost"
              disabled={page + 1 >= query.data.totalPages}
              onClick={() => setPage((p) => p + 1)}
            >
              Older ›
            </Button>
          </div>
        )}
      </div>
    </>
  );
}
