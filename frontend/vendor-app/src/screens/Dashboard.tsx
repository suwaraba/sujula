import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ordersApi } from '@/api/endpoints/orders';
import { moneyApi } from '@/api/endpoints/money';
import { inventoryApi } from '@/api/endpoints/inventory';
import { useStore } from '@/store/StoreProvider';
import { useCurrencies } from '@/lib/hooks';
import { formatDateTime, formatNumber } from '@/lib/format';
import { Badge, Card, EmptyState, PageHeader, Skeleton, Stat } from '@/components/ui';
import { OrderStatusBadge } from '@/components/OrderStatus';

export function Dashboard() {
  const { canTrade, currency } = useStore();
  const { money } = useCurrencies();

  const stats = useQuery({
    queryKey: ['orders', 'stats'],
    queryFn: () => ordersApi.stats(),
    refetchInterval: 60_000,
  });

  const queue = useQuery({
    queryKey: ['orders', 'list', { status: 'PENDING', page: 0, size: 5 }],
    queryFn: () => ordersApi.list({ status: 'PENDING', page: 0, size: 5 }),
    refetchInterval: 60_000,
  });

  const balance = useQuery({ queryKey: ['balance'], queryFn: () => moneyApi.balance() });

  const lowStock = useQuery({
    queryKey: ['inventory', 'low', { page: 0, size: 5 }],
    queryFn: () => inventoryApi.list({ lowStock: true, page: 0, size: 5 }),
    enabled: canTrade,
  });

  const settlement = balance.data?.byCurrency.find((row) => row.currency === currency)
    ?? balance.data?.byCurrency[0];

  return (
    <div className="page stack stack--loose">
      <PageHeader
        title="Today"
        subtitle={
          stats.data
            ? stats.data.awaitingAction > 0
              ? `${stats.data.awaitingAction} order${stats.data.awaitingAction === 1 ? '' : 's'} still in your shop`
              : 'Nothing is waiting on you right now.'
            : undefined
        }
      />

      <div className="grid grid--4">
        <Card flush>
          <Stat
            label="To accept"
            value={
              stats.isLoading
                ? <Skeleton height={28} width={56} />
                : formatNumber(stats.data?.ordersByStatus?.PENDING ?? 0)
            }
            note="New orders, already paid for"
          />
        </Card>
        <Card flush>
          <Stat
            label="Still in your shop"
            value={
              stats.isLoading
                ? <Skeleton height={28} width={56} />
                : formatNumber(stats.data?.awaitingAction ?? 0)
            }
            note="Not yet collected by a driver"
          />
        </Card>
        <Card flush>
          <Stat
            label="In flight"
            value={
              stats.isLoading
                ? <Skeleton height={28} width={80} />
                : money(stats.data?.inFlight, stats.data?.currency)
            }
            note="Earned, not yet released"
          />
        </Card>
        <Card flush>
          <Stat
            label="Available now"
            value={
              balance.isLoading
                ? <Skeleton height={28} width={80} />
                : money(settlement?.available, settlement?.currency)
            }
            note={
              balance.data && balance.data.byCurrency.length > 1
                ? `and ${balance.data.byCurrency.length - 1} other currency`
                : 'Ready to be paid out'
            }
          />
        </Card>
      </div>

      <Card
        title="Orders to accept"
        actions={<Link to="/orders" className="btn btn--ghost btn--sm">All orders →</Link>}
        flush
      >
        {queue.isLoading ? (
          <div style={{ padding: 'var(--space-4)' }}><Skeleton height={60} /></div>
        ) : (queue.data?.content.length ?? 0) === 0 ? (
          <EmptyState icon="✓" title="Nothing waiting">
            New orders appear here the moment a buyer pays.
          </EmptyState>
        ) : (
          <div className="list">
            {queue.data!.content.map((order) => (
              <Link key={order.id} to={`/orders/${order.id}`} className="list__item">
                <div className="list__main">
                  <div className="list__title">{order.orderNumber}</div>
                  <div className="list__meta">
                    {order.totalUnits} item{order.totalUnits === 1 ? '' : 's'} ·{' '}
                    placed {formatDateTime(order.placedAt)}
                  </div>
                </div>
                <div className="list__side">
                  <div className="num" style={{ fontWeight: 650 }}>
                    {money(order.payout, order.currency)}
                  </div>
                  <div className="small muted">your payout</div>
                </div>
                <span className="list__chevron" aria-hidden="true">›</span>
              </Link>
            ))}
          </div>
        )}
      </Card>

      <div className="grid grid--2">
        <Card
          title="Running low"
          actions={<Link to="/inventory" className="btn btn--ghost btn--sm">Stock →</Link>}
          flush
        >
          {!canTrade ? (
            <EmptyState icon="⌛" title="Not selling yet">
              Stock warnings start once your shop is verified.
            </EmptyState>
          ) : lowStock.isLoading ? (
            <div style={{ padding: 'var(--space-4)' }}><Skeleton height={48} /></div>
          ) : (lowStock.data?.items.length ?? 0) === 0 ? (
            <EmptyState icon="✓" title="Stock looks healthy" />
          ) : (
            <div className="list">
              {lowStock.data!.items.map((item) => (
                <Link
                  key={item.variantId}
                  to={`/products/${item.productId}`}
                  className="list__item"
                >
                  <div className="list__main">
                    <div className="list__title">{item.productName}</div>
                    <div className="list__meta">{item.variantLabel ?? item.sku ?? '—'}</div>
                  </div>
                  <Badge tone={item.outOfStock ? 'danger' : 'warn'}>
                    {item.outOfStock ? 'Out of stock' : `${item.stock} left`}
                  </Badge>
                </Link>
              ))}
            </div>
          )}
        </Card>

        <Card title="Your money" actions={<Link to="/earnings" className="btn btn--ghost btn--sm">Earnings →</Link>}>
          {balance.isLoading ? (
            <Skeleton height={80} />
          ) : (balance.data?.byCurrency.length ?? 0) === 0 ? (
            <EmptyState icon="◫" title="Nothing yet">
              Money appears here once an order is delivered and released.
            </EmptyState>
          ) : (
            <div className="stack stack--tight">
              {balance.data!.byCurrency.map((row) => (
                <div key={row.currency} className="row row--between">
                  <div>
                    <div style={{ fontWeight: 600 }}>{row.currency}</div>
                    <div className="small muted">
                      {money(row.onHold, row.currency)} held until delivery
                    </div>
                  </div>
                  <div className="num" style={{ fontWeight: 650, fontSize: 17 }}>
                    {money(row.available, row.currency)}
                  </div>
                </div>
              ))}
              {balance.data!.note && <p className="small muted">{balance.data!.note}</p>}
            </div>
          )}
        </Card>
      </div>

      {stats.data && (
        <Card title="Where your orders stand" flush>
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr><th>Stage</th><th className="num">Orders</th></tr>
              </thead>
              <tbody>
                {Object.entries(stats.data.ordersByStatus ?? {}).map(([status, count]) => (
                  <tr key={status}>
                    <td><OrderStatusBadge status={status as never} /></td>
                    <td className="num">{formatNumber(count)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}
    </div>
  );
}
