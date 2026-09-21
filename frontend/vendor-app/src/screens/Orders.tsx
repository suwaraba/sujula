import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ordersApi } from '@/api/endpoints/orders';
import { useCurrencies } from '@/lib/hooks';
import { formatDateTime } from '@/lib/format';
import type { VendorOrderStatus } from '@/api/types';
import { Card, EmptyState, PageHeader, SkeletonList } from '@/components/ui';
import { OrderStatusBadge } from '@/components/OrderStatus';
import { Pagination } from '@/components/Pagination';

const TABS: { label: string; status?: VendorOrderStatus }[] = [
  { label: 'To accept', status: 'PENDING' },
  { label: 'Packing', status: 'PREPARING' },
  { label: 'Waiting for driver', status: 'READY_FOR_PICKUP' },
  { label: 'On the way', status: 'SHIPPED' },
  { label: 'Delivered', status: 'DELIVERED' },
  { label: 'Cancelled', status: 'CANCELLED' },
  { label: 'All', status: undefined },
];

export function Orders() {
  const [tab, setTab] = useState(0);
  const [page, setPage] = useState(0);
  const { money } = useCurrencies();

  const status = TABS[tab]?.status;

  const stats = useQuery({
    queryKey: ['orders', 'stats'],
    queryFn: () => ordersApi.stats(),
    refetchInterval: 60_000,
  });

  const orders = useQuery({
    queryKey: ['orders', 'list', { status: status ?? null, page }],
    queryFn: () => ordersApi.list({ ...(status ? { status } : {}), page, size: 20 }),
    refetchInterval: 60_000,
  });

  return (
    <div className="page stack">
      <PageHeader
        title="Orders"
        subtitle="Oldest first — which is the order to work in."
      />

      <div className="tabs" role="tablist">
        {TABS.map((entry, index) => {
          const count = entry.status ? stats.data?.ordersByStatus?.[entry.status] : undefined;
          return (
            <button
              key={entry.label}
              type="button"
              role="tab"
              aria-selected={index === tab}
              className={index === tab ? 'tabs__item is-active' : 'tabs__item'}
              onClick={() => {
                setTab(index);
                setPage(0);
              }}
            >
              {entry.label}
              {count !== undefined && count > 0 && <span className="tabs__count">{count}</span>}
            </button>
          );
        })}
      </div>

      <Card flush>
        {orders.isLoading ? (
          <SkeletonList rows={5} />
        ) : (orders.data?.content.length ?? 0) === 0 ? (
          <EmptyState icon="▣" title="No orders here">
            {status === 'PENDING'
              ? 'New orders appear the moment a buyer pays.'
              : 'Nothing at this stage right now.'}
          </EmptyState>
        ) : (
          <>
            <div className="list">
              {orders.data!.content.map((order) => (
                <Link key={order.id} to={`/orders/${order.id}`} className="list__item">
                  <div className="list__main">
                    <div className="row row--tight" style={{ gap: 'var(--space-2)' }}>
                      <span className="list__title">{order.orderNumber}</span>
                      <OrderStatusBadge status={order.status} />
                    </div>
                    <div className="list__meta">
                      {order.totalUnits} item{order.totalUnits === 1 ? '' : 's'} across{' '}
                      {order.itemCount} line{order.itemCount === 1 ? '' : 's'} ·{' '}
                      {formatDateTime(order.placedAt)}
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
            <Pagination
              page={orders.data!.page}
              totalPages={orders.data!.totalPages}
              totalElements={orders.data!.totalElements}
              onChange={setPage}
              unit="orders"
            />
          </>
        )}
      </Card>
    </div>
  );
}
