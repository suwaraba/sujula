import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { ledger as ledgerApi } from '@/api/endpoints';
import { LEDGER_ENTRY_TYPES } from '@/api/enums';
import type { LedgerRow } from '@/api/types';
import { DataTable, type Column } from '@/components/DataTable';
import { Field, FilterBar, NumberInput, Select, TextInput } from '@/components/forms';
import { Money } from '@/components/Money';
import { Pagination } from '@/components/Pagination';
import { Card, Muted, PageHeader, Pill, humanise } from '@/components/primitives';
import { DateTime } from '@/components/Time';
import { keys, useFilters, usePaging } from '@/hooks';

const DEFAULTS = {
  vendorId: undefined as number | undefined,
  currency: undefined as string | undefined,
  type: undefined as string | undefined,
  orderId: undefined as number | undefined,
  reference: undefined as string | undefined,
  from: undefined as string | undefined,
  to: undefined as string | undefined,
};

export function LedgerPage() {
  const [filters, setFilters, reset] = useFilters(DEFAULTS);
  const { page, size, setPage, setSize } = usePaging(50);

  const query = useQuery({
    queryKey: [...keys.ledger, filters, page, size],
    queryFn: () => ledgerApi.search({ ...filters, type: filters.type as never, page, size }),
  });

  const columns: Column<LedgerRow>[] = [
    { key: 'when', header: 'When', render: (row) => <DateTime value={row.occurredAt} /> },
    { key: 'type', header: 'Type', render: (row) => <Pill tone="neutral">{humanise(row.type)}</Pill> },
    {
      key: 'store',
      header: 'Store',
      render: (row) => (
        <>
          {row.storeName}
          <br />
          <Muted>#{row.vendorId}</Muted>
        </>
      ),
    },
    {
      key: 'amount',
      header: 'Amount',
      align: 'right',
      render: (row) => <Money amount={row.amount} currency={row.currency} signed />,
    },
    {
      key: 'available',
      header: 'Available from',
      render: (row) => <DateTime value={row.availableFrom} />,
    },
    {
      key: 'links',
      header: 'Against',
      render: (row) => (
        <>
          {row.orderId && <Link to={`/orders/${row.orderId}`}>order #{row.orderId}</Link>}
          {row.vendorOrderId && <Muted> · sub-order #{row.vendorOrderId}</Muted>}
          {row.payoutId && <Muted> · payout #{row.payoutId}</Muted>}
        </>
      ),
    },
    {
      key: 'description',
      header: 'Description',
      render: (row) => (
        <>
          {row.description ?? <Muted>—</Muted>}
          {row.reference && (
            <>
              <br />
              <Muted>
                <code>{row.reference}</code>
              </Muted>
            </>
          )}
        </>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Ledger"
        description="The journal, across every seller. Every entry stands in the currency it was written in."
      />

      <FilterBar onReset={reset}>
        <Field label="Vendor id">
          <NumberInput
            value={filters.vendorId ?? ''}
            onChange={(vendorId) => setFilters({ vendorId: vendorId === '' ? undefined : vendorId })}
          />
        </Field>
        <Field label="Currency">
          <TextInput
            value={filters.currency ?? ''}
            maxLength={3}
            placeholder="GMD"
            onChange={(currency) => setFilters({ currency: currency ? currency.toUpperCase() : undefined })}
          />
        </Field>
        <Field label="Entry type">
          <Select
            value={(filters.type ?? '') as string}
            options={LEDGER_ENTRY_TYPES}
            placeholder="Any type"
            onChange={(type) => setFilters({ type: type || undefined })}
          />
        </Field>
        <Field label="Order id">
          <NumberInput
            value={filters.orderId ?? ''}
            onChange={(orderId) => setFilters({ orderId: orderId === '' ? undefined : orderId })}
          />
        </Field>
        <Field label="Reference">
          <TextInput
            value={filters.reference ?? ''}
            onChange={(reference) => setFilters({ reference: reference || undefined })}
          />
        </Field>
        <Field label="From">
          <TextInput type="date" value={filters.from ?? ''} onChange={(from) => setFilters({ from: from || undefined })} />
        </Field>
        <Field label="To">
          <TextInput type="date" value={filters.to ?? ''} onChange={(to) => setFilters({ to: to || undefined })} />
        </Field>
      </FilterBar>

      {query.data && query.data.totals.length > 0 && (
        <Card
          title="Totals for this filter"
          subtitle="Per currency. There is no total across them, because there is no such number."
        >
          <table className="table table-compact">
            <thead>
              <tr>
                <th scope="col">Currency</th>
                <th scope="col" className="align-right">
                  Total
                </th>
                <th scope="col" className="align-right">
                  Rows
                </th>
              </tr>
            </thead>
            <tbody>
              {query.data.totals.map((total) => (
                <tr key={total.currency}>
                  <th scope="row">{total.currency}</th>
                  <td className="align-right">
                    <Money amount={total.total} currency={total.currency} signed />
                  </td>
                  <td className="align-right">{total.rows.toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}

      <DataTable
        columns={columns}
        rows={query.data?.rows}
        rowKey={(row) => row.entryId}
        isLoading={query.isFetching}
        error={query.error}
        empty="No ledger entries match those filters."
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
