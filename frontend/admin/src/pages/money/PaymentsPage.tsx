import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { payments as paymentsApi } from '@/api/endpoints';
import { PAYMENT_STATUSES } from '@/api/enums';
import type { PaymentRow } from '@/api/types';
import { DataTable, type Column } from '@/components/DataTable';
import { Field, FilterBar, Select, TextInput } from '@/components/forms';
import { Money } from '@/components/Money';
import { Pagination } from '@/components/Pagination';
import { Flags, Muted, PageHeader, StatusPill, humanise } from '@/components/primitives';
import { DateTime } from '@/components/Time';
import { keys, useFilters, usePaging } from '@/hooks';

const DEFAULTS = {
  q: undefined as string | undefined,
  status: undefined as string | undefined,
  currency: undefined as string | undefined,
  transactionId: undefined as string | undefined,
  from: undefined as string | undefined,
  to: undefined as string | undefined,
};

export function PaymentsPage() {
  const navigate = useNavigate();
  const [filters, setFilters, reset] = useFilters(DEFAULTS);
  const { page, size, setPage, setSize } = usePaging();

  const query = useQuery({
    queryKey: [...keys.payments, filters, page, size],
    queryFn: () => paymentsApi.search({ ...filters, status: filters.status as never, page, size }),
  });

  const columns: Column<PaymentRow>[] = [
    {
      key: 'payment',
      header: 'Payment',
      render: (row) => (
        <>
          <strong className="mono">{row.orderNumber}</strong>
          <br />
          <Muted>#{row.paymentId} · {humanise(row.method)}</Muted>
        </>
      ),
    },
    { key: 'status', header: 'Status', render: (row) => <StatusPill status={row.status} /> },
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
      key: 'contexts',
      header: 'Paid from → goes to',
      render: (row) => (
        <>
          <span title="Payer context — where the buyer was">{row.payerCountry ?? '—'}</span>
          {' → '}
          <span title="Delivery context — where the goods go">{row.destinationCountry ?? '—'}</span>
        </>
      ),
    },
    {
      key: 'amount',
      header: 'Charged',
      align: 'right',
      render: (row) => <Money amount={row.amount} currency={row.currency} />,
    },
    {
      key: 'refunded',
      header: 'Refunded',
      align: 'right',
      render: (row) => <Money amount={row.amountRefunded} currency={row.currency} />,
    },
    {
      key: 'flags',
      header: '',
      render: (row) => <Flags flags={row.flags} />,
    },
    { key: 'paid', header: 'Paid', render: (row) => <DateTime value={row.paidAt} /> },
  ];

  return (
    <>
      <PageHeader
        title="Payments"
        description="One payment, many vendors. Refunding is done against a vendor's sub-order on the payment's own screen — there is no such thing as refunding a proportion of the whole."
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
            options={PAYMENT_STATUSES}
            placeholder="Any status"
            onChange={(status) => setFilters({ status: status || undefined })}
          />
        </Field>
        <Field label="Currency" hint="What the buyer was charged in.">
          <TextInput
            value={filters.currency ?? ''}
            maxLength={3}
            placeholder="EUR"
            onChange={(currency) => setFilters({ currency: currency ? currency.toUpperCase() : undefined })}
          />
        </Field>
        <Field label="Provider reference">
          <TextInput
            value={filters.transactionId ?? ''}
            onChange={(transactionId) => setFilters({ transactionId: transactionId || undefined })}
          />
        </Field>
        <Field label="From">
          <TextInput type="date" value={filters.from ?? ''} onChange={(from) => setFilters({ from: from || undefined })} />
        </Field>
        <Field label="To">
          <TextInput type="date" value={filters.to ?? ''} onChange={(to) => setFilters({ to: to || undefined })} />
        </Field>
      </FilterBar>

      <DataTable
        columns={columns}
        rows={query.data?.content}
        rowKey={(row) => row.paymentId}
        isLoading={query.isFetching}
        error={query.error}
        empty="No payments match those filters."
        onRowClick={(row) => navigate(`/payments/${row.paymentId}`)}
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
