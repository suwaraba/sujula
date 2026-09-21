import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { dashboard as dashboardApi } from '@/api/endpoints';
import { Money } from '@/components/Money';
import { Card, ErrorBanner, Grid, Loading, Muted, PageHeader } from '@/components/primitives';
import { DateTime } from '@/components/Time';
import { keys } from '@/hooks';

function Stat({
  label,
  value,
  to,
  tone,
  hint,
}: {
  label: string;
  value: number | string;
  to?: string;
  tone?: 'warn' | 'bad';
  hint?: string;
}) {
  const body = (
    <>
      <span className="stat-value">{typeof value === 'number' ? value.toLocaleString() : value}</span>
      <span className="stat-label">{label}</span>
      {hint && <span className="stat-hint">{hint}</span>}
    </>
  );
  const className = `stat${tone ? ` stat-${tone}` : ''}`;
  return to ? (
    <Link className={className} to={to}>
      {body}
    </Link>
  ) : (
    <div className={className}>{body}</div>
  );
}

export function DashboardPage() {
  const { data, isLoading, error } = useQuery({
    queryKey: keys.dashboard,
    queryFn: dashboardApi.get,
    refetchInterval: 60_000,
  });

  return (
    <>
      <PageHeader
        title="The platform at a glance"
        description={
          data ? (
            <>
              As of <DateTime value={data.at} />
            </>
          ) : undefined
        }
      />

      <ErrorBanner error={error} />
      {isLoading && <Loading what="Reading the platform" />}

      {data && (
        <>
          {data.attention.length > 0 && (
            <Card title="Wants attention" tone="warning">
              <ul className="attention-list">
                {data.attention.map((item, index) => (
                  <li key={index}>{item}</li>
                ))}
              </ul>
            </Card>
          )}

          <Card
            title="Gross merchandise value"
            subtitle="Per currency. Two currencies are not one number — 400 EUR and 12,000 GMD is not 12,400 of anything."
          >
            {data.gmv.length === 0 ? (
              <Muted>Nothing sold yet.</Muted>
            ) : (
              <table className="table">
                <thead>
                  <tr>
                    <th scope="col">Currency</th>
                    <th scope="col" className="align-right">
                      Today
                    </th>
                    <th scope="col" className="align-right">
                      This month
                    </th>
                    <th scope="col" className="align-right">
                      Orders
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {data.gmv.map((line) => (
                    <tr key={line.currency}>
                      <th scope="row">{line.currency}</th>
                      <td className="align-right">
                        <Money amount={line.today} currency={line.currency} />
                      </td>
                      <td className="align-right">
                        <Money amount={line.thisMonth} currency={line.currency} />
                      </td>
                      <td className="align-right">{line.orders.toLocaleString()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Card>

          <Card title="Queues">
            <Grid columns={4}>
              <Stat
                label="Disputes open"
                value={data.disputesOpen}
                to="/disputes?openOnly=true"
                tone={data.disputesOverdue > 0 ? 'bad' : undefined}
                hint={data.disputesOverdue > 0 ? `${data.disputesOverdue} past their deadline` : undefined}
              />
              <Stat
                label="Callbacks owed"
                value={data.callbacksOutstanding}
                to="/callbacks"
                tone={data.callbacksOutstanding > 0 ? 'warn' : undefined}
              />
              <Stat
                label="Policy cases open"
                value={data.moderationCasesOpen}
                to="/moderation/cases?status=OPEN"
              />
              <Stat label="KYC documents waiting" value={data.kycWaiting} to="/kyc?status=SUBMITTED" />
              <Stat
                label="Stores awaiting approval"
                value={data.storesAwaitingApproval}
                to="/stores?status=PENDING"
              />
              <Stat
                label="Parcels stuck"
                value={data.stuckShipments}
                to="/shipments?waitingOverHours=24"
                tone={data.stuckShipments > 0 ? 'warn' : undefined}
              />
              <Stat
                label="Payout runs awaiting release"
                value={data.payoutBatchesAwaitingApproval}
                to="/payouts?status=AWAITING_APPROVAL"
              />
              <Stat
                label="Payouts failed"
                value={data.failedPayouts}
                to="/payouts"
                tone={data.failedPayouts > 0 ? 'bad' : undefined}
              />
            </Grid>
          </Card>

          <Grid columns={2}>
            <Card title="Orders">
              <Grid columns={2}>
                <Stat label="Placed today" value={data.ordersToday} to="/orders" />
                <Stat label="Placed this month" value={data.ordersThisMonth} to="/orders" />
              </Grid>
            </Card>

            <Card title="Parcels">
              <Grid columns={2}>
                <Stat label="In flight" value={data.parcelsInFlight} to="/shipments" />
                <Stat label="Delivered this month" value={data.parcelsDeliveredThisMonth} />
                <Stat label="Failed this month" value={data.parcelsFailedThisMonth} />
                <Stat
                  label="Delivery success"
                  value={
                    data.deliverySuccessRate === null || data.deliverySuccessRate === undefined
                      ? '—'
                      : `${Number(data.deliverySuccessRate).toFixed(1)}%`
                  }
                />
              </Grid>
            </Card>
          </Grid>
        </>
      )}
    </>
  );
}
