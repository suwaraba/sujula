import { useQuery } from '@tanstack/react-query';
import { balances as balancesApi } from '@/api/endpoints';
import type { VendorBalance } from '@/api/types';
import { DataTable, type Column } from '@/components/DataTable';
import { Field, FilterBar, NumberInput, TextInput, TriState } from '@/components/forms';
import { Money } from '@/components/Money';
import { Pagination } from '@/components/Pagination';
import { Muted, PageHeader, Pill } from '@/components/primitives';
import { keys, useFilters, usePaging } from '@/hooks';

const DEFAULTS = {
  vendorId: undefined as number | undefined,
  currency: undefined as string | undefined,
  payableOnly: undefined as boolean | undefined,
};

export function BalancesPage() {
  const [filters, setFilters, reset] = useFilters(DEFAULTS);
  const { page, size, setPage, setSize } = usePaging(50);

  const query = useQuery({
    queryKey: [...keys.balances, filters, page, size],
    queryFn: () => balancesApi.search({ ...filters, page, size }),
  });

  const columns: Column<VendorBalance>[] = [
    {
      key: 'store',
      header: 'Store',
      render: (row) => (
        <>
          <strong>{row.storeName}</strong>
          <br />
          <Muted>#{row.vendorId}</Muted>
        </>
      ),
    },
    {
      key: 'currency',
      header: 'Currency',
      render: (row) => (
        <>
          {row.currency}
          {row.currencyMismatch && (
            <>
              {' '}
              <Pill
                tone="bad"
                title={`This balance is in ${row.currency} but the store settles in ${row.settlementCurrency}. It cannot go into a payout run for either until somebody decides which.`}
              >
                not the settlement currency
              </Pill>
            </>
          )}
        </>
      ),
    },
    {
      key: 'available',
      header: 'Available',
      align: 'right',
      render: (row) => <Money amount={row.available} currency={row.currency} />,
    },
    {
      key: 'held',
      header: 'Held',
      align: 'right',
      render: (row) => <Money amount={row.held} currency={row.currency} />,
    },
    {
      key: 'inFlight',
      header: 'In flight',
      align: 'right',
      render: (row) => <Money amount={row.inFlight} currency={row.currency} />,
    },
    {
      key: 'total',
      header: 'Total',
      align: 'right',
      render: (row) => <Money amount={row.total} currency={row.currency} />,
    },
    {
      key: 'payouts',
      header: 'Payouts',
      render: (row) =>
        row.payoutsHeld ? (
          <Pill tone="bad" title={row.payoutsHeldReason ?? undefined}>
            On hold
          </Pill>
        ) : (
          <Pill tone="good">Open</Pill>
        ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Balances"
        description="Every seller's balances, in every currency they hold. A seller who sold into two currencies has two rows and no third one — a vendor is paid in their own currency, not in a sum of what buyers happened to use."
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
        <Field label="Only those with something payable">
          <TriState
            value={filters.payableOnly}
            onChange={(payableOnly) => setFilters({ payableOnly })}
            yes="Payable only"
            no="Everything"
          />
        </Field>
      </FilterBar>

      <DataTable
        columns={columns}
        rows={query.data?.content}
        rowKey={(row) => `${row.vendorId}-${row.currency}`}
        isLoading={query.isFetching}
        error={query.error}
        empty="No balances match those filters."
        rowClassName={(row) => (row.currencyMismatch ? 'row-overdue' : undefined)}
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
