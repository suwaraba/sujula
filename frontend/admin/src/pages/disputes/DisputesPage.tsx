import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { disputes as disputesApi } from '@/api/endpoints';
import { DISPUTE_REASONS, DISPUTE_STATUSES } from '@/api/enums';
import type { DisputeRow } from '@/api/types';
import { DataTable, type Column } from '@/components/DataTable';
import { Field, FilterBar, NumberInput, Select, TriState } from '@/components/forms';
import { Money } from '@/components/Money';
import { Pagination } from '@/components/Pagination';
import { Muted, PageHeader, Pill, StatusPill, humanise } from '@/components/primitives';
import { DateTime, Hours } from '@/components/Time';
import { keys, useFilters, usePaging } from '@/hooks';

const DEFAULTS = {
  status: undefined as string | undefined,
  openOnly: undefined as boolean | undefined,
  assignedTo: undefined as number | undefined,
  unassignedOnly: undefined as boolean | undefined,
  vendorId: undefined as number | undefined,
  reason: undefined as string | undefined,
  overdueOnly: undefined as boolean | undefined,
};

export function DisputesPage() {
  const navigate = useNavigate();
  const [filters, setFilters, reset] = useFilters(DEFAULTS);
  const { page, size, setPage, setSize } = usePaging();

  const query = useQuery({
    queryKey: [...keys.disputes, filters, page, size],
    queryFn: () =>
      disputesApi.search({
        ...filters,
        status: filters.status as never,
        reason: filters.reason as never,
        page,
        size,
      }),
  });

  const columns: Column<DisputeRow>[] = [
    {
      key: 'reference',
      header: 'Dispute',
      render: (row) => (
        <>
          <strong className="mono">{row.reference}</strong>
          <br />
          <Muted>{humanise(row.reason)}</Muted>
        </>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <>
          <StatusPill status={row.status} />
          {row.moneyFrozen && (
            <Pill tone="bad" title="The vendor's money on this sub-order cannot move until it is decided.">
              Money frozen
            </Pill>
          )}
        </>
      ),
    },
    {
      key: 'store',
      header: 'Store',
      render: (row) => (
        <>
          {row.storeName}
          <br />
          <Muted>order {row.orderNumber}</Muted>
        </>
      ),
    },
    {
      key: 'amounts',
      header: 'At stake',
      align: 'right',
      render: (row) => (
        <>
          <Money amount={row.amountNative} currency={row.nativeCurrency} />
          <br />
          <Muted>
            buyer paid <Money amount={row.amount} currency={row.currency} />
          </Muted>
        </>
      ),
    },
    {
      key: 'assigned',
      header: 'With',
      render: (row) => row.assignedToEmail ?? <Muted>nobody</Muted>,
    },
    {
      key: 'due',
      header: 'Due',
      render: (row) => (
        <>
          <DateTime value={row.dueBy} />
          <br />
          <Muted>
            <Hours value={row.hoursRemaining} overdue={row.overdue} /> left
          </Muted>
        </>
      ),
    },
    {
      key: 'activity',
      header: '',
      render: (row) => (
        <>
          {row.callbackOutstanding && <Pill tone="warn">Callback owed</Pill>}
          {row.messageCount > 0 && <Muted>{row.messageCount} messages</Muted>}
          {row.evidenceCount > 0 && <Muted> · {row.evidenceCount} pieces of evidence</Muted>}
        </>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Disputes"
        description="Sorted by deadline rather than by age. The amount at stake is the vendor's own currency, because that is the money that is frozen; what the buyer paid is shown beside it."
      />

      <FilterBar onReset={reset}>
        <Field label="Status">
          <Select
            value={(filters.status ?? '') as string}
            options={DISPUTE_STATUSES}
            placeholder="Any status"
            onChange={(status) => setFilters({ status: status || undefined })}
          />
        </Field>
        <Field label="Reason">
          <Select
            value={(filters.reason ?? '') as string}
            options={DISPUTE_REASONS}
            placeholder="Any reason"
            onChange={(reason) => setFilters({ reason: reason || undefined })}
          />
        </Field>
        <Field label="Open only">
          <TriState value={filters.openOnly} onChange={(openOnly) => setFilters({ openOnly })} />
        </Field>
        <Field label="Past the deadline only">
          <TriState value={filters.overdueOnly} onChange={(overdueOnly) => setFilters({ overdueOnly })} />
        </Field>
        <Field label="Unassigned only">
          <TriState
            value={filters.unassignedOnly}
            onChange={(unassignedOnly) => setFilters({ unassignedOnly })}
          />
        </Field>
        <Field label="Assigned to" hint="user id">
          <NumberInput
            value={filters.assignedTo ?? ''}
            onChange={(assignedTo) => setFilters({ assignedTo: assignedTo === '' ? undefined : assignedTo })}
          />
        </Field>
        <Field label="Vendor id">
          <NumberInput
            value={filters.vendorId ?? ''}
            onChange={(vendorId) => setFilters({ vendorId: vendorId === '' ? undefined : vendorId })}
          />
        </Field>
      </FilterBar>

      <DataTable
        columns={columns}
        rows={query.data?.content}
        rowKey={(row) => row.disputeId}
        isLoading={query.isFetching}
        error={query.error}
        empty="No disputes match those filters."
        onRowClick={(row) => navigate(`/disputes/${row.disputeId}`)}
        rowClassName={(row) => (row.overdue ? 'row-overdue' : undefined)}
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
