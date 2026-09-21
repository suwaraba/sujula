import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { orders as ordersApi } from '@/api/endpoints';
import { ORDER_STATUSES } from '@/api/enums';
import type { OrderRow } from '@/api/types';
import { DataTable, type Column } from '@/components/DataTable';
import { FilterBar, Field, NumberInput, Select, TextInput } from '@/components/forms';
import { Money } from '@/components/Money';
import { Pagination } from '@/components/Pagination';
import { Muted, PageHeader, StatusPill } from '@/components/primitives';
import { DateTime, Hours } from '@/components/Time';
import { keys, useFilters, usePaging } from '@/hooks';
import { PlaceOrderOnBehalf } from './PlaceOrderOnBehalf';

const DEFAULTS = {
  q: undefined as string | undefined,
  status: undefined as string | undefined,
  destinationCountry: undefined as string | undefined,
  vendorId: undefined as number | undefined,
  stuckForHours: undefined as number | undefined,
};

export function OrdersPage() {
  const navigate = useNavigate();
  const [filters, setFilters, reset] = useFilters(DEFAULTS);
  const { page, size, setPage, setSize } = usePaging();

  const query = useQuery({
    queryKey: [...keys.orders, filters, page, size],
    queryFn: () => ordersApi.search({ ...filters, page, size }),
  });

  const columns: Column<OrderRow>[] = [
    {
      key: 'order',
      header: 'Order',
      render: (row) => (
        <>
          <strong className="mono">{row.orderNumber}</strong>
          <br />
          <Muted>
            {row.vendorOrders} {row.vendorOrders === 1 ? 'vendor' : 'vendors'}
            {row.storeNames.length > 0 && ` — ${row.storeNames.join(', ')}`}
          </Muted>
        </>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <>
          <StatusPill status={row.status} />
          <br />
          <Muted>
            payment <StatusPill status={row.paymentStatus} />
          </Muted>
        </>
      ),
    },
    {
      key: 'buyer',
      header: 'Buyer',
      render: (row) => (
        <>
          {row.buyerName ?? <Muted>—</Muted>}
          <br />
          <Muted>{row.buyerEmail}</Muted>
        </>
      ),
    },
    {
      key: 'destination',
      header: 'Goes to',
      render: (row) => (
        <>
          {row.destinationCity ?? <Muted>—</Muted>}
          <br />
          <Muted>{row.destinationCountry}</Muted>
        </>
      ),
    },
    {
      key: 'total',
      header: 'Charged',
      align: 'right',
      render: (row) => <Money amount={row.total} currency={row.currency} />,
    },
    {
      key: 'age',
      header: 'In status',
      align: 'right',
      render: (row) => <Hours value={row.hoursInStatus} overdue={row.hoursInStatus >= 72} />,
    },
    { key: 'placed', header: 'Placed', render: (row) => <DateTime value={row.placedAt} /> },
  ];

  return (
    <>
      <PageHeader
        title="Orders"
        description="One payment, many vendors. An order's slices ship, cancel and refund on their own — the order row is a summary of them, never a thing you can act on as a whole."
        actions={<PlaceOrderOnBehalf />}
      />

      <FilterBar onReset={reset}>
        <Field label="Search">
          <TextInput
            value={filters.q ?? ''}
            placeholder="Order number, buyer name or email"
            onChange={(q) => setFilters({ q: q || undefined })}
          />
        </Field>
        <Field label="Status">
          <Select
            value={(filters.status ?? '') as string}
            options={ORDER_STATUSES}
            placeholder="Any status"
            onChange={(status) => setFilters({ status: status || undefined })}
          />
        </Field>
        <Field
          label="Destination country"
          hint="Where the goods go. Not where the buyer paid from."
        >
          <TextInput
            value={filters.destinationCountry ?? ''}
            placeholder="GM"
            maxLength={2}
            onChange={(value) =>
              setFilters({ destinationCountry: value ? value.toUpperCase() : undefined })
            }
          />
        </Field>
        <Field label="Vendor id">
          <NumberInput
            value={filters.vendorId ?? ''}
            onChange={(vendorId) => setFilters({ vendorId: vendorId === '' ? undefined : vendorId })}
          />
        </Field>
        <Field label="Stuck for at least" hint="hours in the same status">
          <NumberInput
            value={filters.stuckForHours ?? ''}
            min={1}
            onChange={(hours) => setFilters({ stuckForHours: hours === '' ? undefined : hours })}
          />
        </Field>
      </FilterBar>

      <DataTable
        columns={columns}
        rows={query.data?.content}
        rowKey={(row) => row.id}
        isLoading={query.isFetching}
        error={query.error}
        empty="No orders match those filters."
        onRowClick={(row) => navigate(`/orders/${row.id}`)}
      />

      {query.data && (
        <Pagination
          page={query.data.page}
          size={query.data.size}
          totalElements={query.data.totalElements}
          totalPages={query.data.totalPages}
          last={query.data.last}
          onPage={setPage}
          onSize={setSize}
        />
      )}
    </>
  );
}
