import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { payouts as payoutsApi } from '@/api/endpoints';
import { PAYOUT_BATCH_STATUSES } from '@/api/enums';
import type { BatchRow } from '@/api/types';
import { ActionModal } from '@/components/ActionModal';
import { DataTable, type Column } from '@/components/DataTable';
import { DecideButton } from '@/components/Decide';
import { Field, FilterBar, MoneyInput, Select, TextArea, TextInput } from '@/components/forms';
import { Money } from '@/components/Money';
import { Pagination } from '@/components/Pagination';
import { EMPTY_STEP_UP, StepUpFields, stepUpBody, type StepUpValue } from '@/components/StepUp';
import { Muted, PageHeader, StatusPill } from '@/components/primitives';
import { DateTime } from '@/components/Time';
import { currencyInfo, knownCurrencies } from '@/money/currency';
import { keys, useAction, useFilters, usePaging } from '@/hooks';

const DEFAULTS = {
  status: undefined as string | undefined,
  currency: undefined as string | undefined,
};

export function PayoutsPage() {
  const navigate = useNavigate();
  const [filters, setFilters, reset] = useFilters(DEFAULTS);
  const { page, size, setPage, setSize } = usePaging();

  const query = useQuery({
    queryKey: [...keys.batches, filters, page, size],
    queryFn: () => payoutsApi.batches({ ...filters, status: filters.status as never, page, size }),
  });

  const columns: Column<BatchRow>[] = [
    {
      key: 'reference',
      header: 'Run',
      render: (row) => (
        <>
          <strong className="mono">{row.reference}</strong>
          <br />
          <Muted>#{row.batchId}</Muted>
        </>
      ),
    },
    { key: 'status', header: 'Status', render: (row) => <StatusPill status={row.status} /> },
    { key: 'currency', header: 'Currency', render: (row) => row.currency },
    {
      key: 'total',
      header: 'Total',
      align: 'right',
      render: (row) => <Money amount={row.total} currency={row.currency} />,
    },
    { key: 'items', header: 'Items', align: 'right', render: (row) => row.itemCount },
    {
      key: 'prepared',
      header: 'Prepared by',
      render: (row) => (
        <>
          {row.preparedByEmail ?? <Muted>—</Muted>}
          <br />
          <Muted>
            <DateTime value={row.preparedAt} />
          </Muted>
        </>
      ),
    },
    {
      key: 'approved',
      header: 'Released by',
      render: (row) => (
        <>
          {row.approvedByEmail ?? <Muted>nobody yet</Muted>}
          <br />
          <Muted>
            <DateTime value={row.approvedAt} />
          </Muted>
        </>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Payout runs"
        description="A run is per currency, because a vendor is paid in their own. Whoever prepares a run cannot release it — the server refuses, and that is the point of two people."
        actions={<PrepareBatch />}
      />

      <FilterBar onReset={reset}>
        <Field label="Status">
          <Select
            value={(filters.status ?? '') as string}
            options={PAYOUT_BATCH_STATUSES}
            placeholder="Any status"
            onChange={(status) => setFilters({ status: status || undefined })}
          />
        </Field>
        <Field label="Currency">
          <TextInput
            value={filters.currency ?? ''}
            maxLength={3}
            onChange={(currency) => setFilters({ currency: currency ? currency.toUpperCase() : undefined })}
          />
        </Field>
      </FilterBar>

      <DataTable
        columns={columns}
        rows={query.data?.content}
        rowKey={(row) => row.batchId}
        isLoading={query.isFetching}
        error={query.error}
        empty="No payout runs match those filters."
        onRowClick={(row) => navigate(`/payouts/${row.batchId}`)}
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

function PrepareBatch() {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [currency, setCurrency] = useState('');
  const [minimumAmount, setMinimumAmount] = useState('');
  const [vendorIds, setVendorIds] = useState('');
  const [note, setNote] = useState('');
  const [stepUp, setStepUp] = useState<StepUpValue>(EMPTY_STEP_UP);

  const action = useAction(
    () =>
      payoutsApi.prepareBatch({
        currency,
        minimumAmount: minimumAmount === '' ? null : minimumAmount,
        vendorIds: vendorIds.trim()
          ? vendorIds
              .split(/[\s,]+/)
              .filter(Boolean)
              .map(Number)
          : null,
        note: note.trim() || null,
        ...stepUpBody(stepUp),
      }),
    {
      invalidate: [keys.batches, keys.balances, keys.dashboard],
      message: (saved) => saved.message,
      onDone: (saved) => {
        setStepUp(EMPTY_STEP_UP);
        navigate(`/payouts/${saved.batchId}`);
      },
    },
  );

  const settlement = knownCurrencies().filter((entry) => entry.settlement);
  const scale = currencyInfo(currency)?.minorUnits ?? 2;

  return (
    <>
      <DecideButton onClick={() => setOpen(true)}>Assemble a run</DecideButton>
      <ActionModal
        open={open}
        title="Assemble a payout run"
        description="Gathers every vendor with an available balance in one currency. It is assembled, not sent: somebody else releases it."
        submitLabel="Assemble it"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={!currency || !stepUp.password}
      >
        <Field label="Currency" required hint="A run holds one currency. There is no mixed run.">
          <Select
            value={currency}
            required
            placeholder="Choose a settlement currency"
            options={settlement.map((entry) => ({ value: entry.code, label: `${entry.code} — ${entry.name}` }))}
            onChange={setCurrency}
          />
        </Field>

        <Field
          label={`Minimum balance to include${currency ? ` (${currency})` : ''}`}
          hint="Leaves small balances to accumulate rather than paying a transfer fee on each."
        >
          <MoneyInput value={minimumAmount} minorUnits={scale} min={0} onChange={setMinimumAmount} />
        </Field>

        <Field label="Only these vendors" hint="Vendor ids, separated by spaces or commas. Empty means every eligible vendor.">
          <TextInput value={vendorIds} onChange={setVendorIds} placeholder="12 47 108" />
        </Field>

        <Field label="Note">
          <TextArea value={note} onChange={setNote} />
        </Field>

        <StepUpFields value={stepUp} onChange={setStepUp} what="Assembling a payout run" />
      </ActionModal>
    </>
  );
}
