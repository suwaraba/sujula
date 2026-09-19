import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { stores as storesApi } from '@/api/endpoints';
import { MODERATION_REASONS, PARTNER_STATUSES } from '@/api/enums';
import type { StoreRow } from '@/api/types';
import { ActionModal } from '@/components/ActionModal';
import { DataTable, type Column } from '@/components/DataTable';
import { DecideButton } from '@/components/Decide';
import { CheckBox, Field, FilterBar, NumberInput, Select, TextArea, TextInput, TriState } from '@/components/forms';
import { Commission } from '@/components/Money';
import { Pagination } from '@/components/Pagination';
import { Muted, PageHeader, Pill, StatusPill } from '@/components/primitives';
import { DateTime, nowLocalInput, toLocalDateTime } from '@/components/Time';
import { keys, useAction, useFilters, usePaging } from '@/hooks';

const DEFAULTS = {
  q: undefined as string | undefined,
  status: undefined as string | undefined,
  country: undefined as string | undefined,
  payoutsHeld: undefined as boolean | undefined,
};

export function StoresPage() {
  const [filters, setFilters, reset] = useFilters(DEFAULTS);
  const { page, size, setPage, setSize } = usePaging();

  const query = useQuery({
    queryKey: [...keys.stores, filters, page, size],
    queryFn: () => storesApi.search({ ...filters, status: filters.status as never, page, size }),
  });

  const columns: Column<StoreRow>[] = [
    {
      key: 'store',
      header: 'Store',
      render: (row) => (
        <>
          <strong>{row.storeName}</strong>
          <br />
          <Muted>
            {row.ownerEmail} · #{row.id}
          </Muted>
        </>
      ),
    },
    { key: 'status', header: 'Status', render: (row) => <StatusPill status={row.status} /> },
    { key: 'country', header: 'Country', render: (row) => row.countryCode ?? <Muted>—</Muted> },
    {
      key: 'settlement',
      header: 'Paid in',
      render: (row) => (
        <>
          {row.settlementCurrency}
          <br />
          <Muted>
            commission <Commission value={row.commissionRate} />
          </Muted>
        </>
      ),
    },
    {
      key: 'payouts',
      header: 'Payouts',
      render: (row) =>
        row.payoutsHeld ? (
          <>
            <Pill tone="bad" title={row.payoutsHeldReason ?? undefined}>
              Held
            </Pill>
            <br />
            <Muted>
              <DateTime value={row.payoutsHeldAt} />
            </Muted>
          </>
        ) : (
          <Pill tone="good">Open</Pill>
        ),
    },
    { key: 'products', header: 'Live listings', align: 'right', render: (row) => row.liveProducts },
    {
      key: 'cases',
      header: 'Open cases',
      align: 'right',
      render: (row) => (row.openCases > 0 ? <span className="overdue">{row.openCases}</span> : 0),
    },
    {
      key: 'actions',
      header: '',
      render: (row) => (
        <div className="row-actions">
          <ApproveStore store={row} />
          <RejectStore store={row} />
          <SuspendStore store={row} />
          <ChangeCommission store={row} />
        </div>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Stores"
        description="What is holding each one up. A store is approved once its documents are read; suspending one takes its listings down and can hold its payouts with it."
      />

      <FilterBar onReset={reset}>
        <Field label="Search">
          <TextInput
            value={filters.q ?? ''}
            placeholder="Store name or owner email"
            onChange={(q) => setFilters({ q: q || undefined })}
          />
        </Field>
        <Field label="Status">
          <Select
            value={(filters.status ?? '') as string}
            options={PARTNER_STATUSES}
            placeholder="Any status"
            onChange={(status) => setFilters({ status: status || undefined })}
          />
        </Field>
        <Field label="Country">
          <TextInput
            value={filters.country ?? ''}
            maxLength={2}
            onChange={(country) => setFilters({ country: country ? country.toUpperCase() : undefined })}
          />
        </Field>
        <Field label="Payouts held">
          <TriState value={filters.payoutsHeld} onChange={(payoutsHeld) => setFilters({ payoutsHeld })} />
        </Field>
      </FilterBar>

      <DataTable
        columns={columns}
        rows={query.data?.content}
        rowKey={(row) => row.id}
        isLoading={query.isFetching}
        error={query.error}
        empty="No stores match those filters."
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

const INVALIDATE = [keys.stores, keys.dashboard, keys.cases];

function ApproveStore({ store }: { store: StoreRow }) {
  const [open, setOpen] = useState(false);
  const [note, setNote] = useState('');
  const action = useAction(() => storesApi.approve(store.id, { note: note.trim() || null }), {
    invalidate: INVALIDATE,
    message: (decision) => decision.message,
  });

  const already = store.status === 'APPROVED' || store.status === 'ACTIVE';

  return (
    <>
      <DecideButton
        variant="secondary"
        onClick={() => setOpen(true)}
        disabled={already}
        title={already ? 'This store is already trading.' : undefined}
      >
        Approve
      </DecideButton>
      <ActionModal
        open={open}
        title={`Let ${store.storeName} trade`}
        submitLabel="Approve"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
      >
        <Field label="Note" hint="Internal.">
          <TextArea value={note} onChange={setNote} />
        </Field>
      </ActionModal>
    </>
  );
}

function RejectStore({ store }: { store: StoreRow }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  const action = useAction(() => storesApi.reject(store.id, { reason: reason.trim() }), {
    invalidate: INVALIDATE,
    message: (decision) => decision.message,
  });

  return (
    <>
      <DecideButton variant="ghost" onClick={() => setOpen(true)}>
        Reject
      </DecideButton>
      <ActionModal
        open={open}
        title={`Refuse ${store.storeName}'s application`}
        description="The applicant is told this reason, so write something they can act on rather than a code."
        submitLabel="Reject"
        tone="danger"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={reason.trim().length < 5}
      >
        <Field label="Reason" required>
          <TextArea value={reason} required minLength={5} rows={4} onChange={setReason} />
        </Field>
      </ActionModal>
    </>
  );
}

function SuspendStore({ store }: { store: StoreRow }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [category, setCategory] = useState<string>('');
  const [holdPayouts, setHoldPayouts] = useState(true);

  const action = useAction(
    () =>
      storesApi.suspend(store.id, {
        reason: reason.trim(),
        category: category as never,
        holdPayouts,
      }),
    { invalidate: [...INVALIDATE, keys.balances, keys.batches], message: (decision) => decision.message },
  );

  return (
    <>
      <DecideButton variant="danger" onClick={() => setOpen(true)}>
        Suspend
      </DecideButton>
      <ActionModal
        open={open}
        title={`Stop ${store.storeName}`}
        description="Everything follows: the listings come down, and the money can be held with them. Orders already in flight still have to be delivered."
        submitLabel="Suspend the store"
        tone="danger"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={!category || reason.trim().length < 5}
      >
        <Field label="Category" required>
          <Select value={category} required options={MODERATION_REASONS} placeholder="Choose" onChange={setCategory} />
        </Field>
        <CheckBox
          checked={holdPayouts}
          onChange={setHoldPayouts}
          label="Hold this store's payouts as well"
          hint="Leave on where the reason is fraud. Turn it off where the seller is simply not allowed to list — money they have already earned on delivered orders is still theirs."
        />
        <Field label="Reason" required hint="The seller is told this.">
          <TextArea value={reason} required minLength={5} rows={4} onChange={setReason} />
        </Field>
      </ActionModal>
    </>
  );
}

function ChangeCommission({ store }: { store: StoreRow }) {
  const [open, setOpen] = useState(false);
  const [rate, setRate] = useState<number | ''>('');
  const [effectiveFrom, setEffectiveFrom] = useState(nowLocalInput());
  const [note, setNote] = useState('');

  const action = useAction(
    () =>
      storesApi.changeCommission(store.id, {
        rate: String(rate),
        effectiveFrom: toLocalDateTime(effectiveFrom),
        note: note.trim() || null,
      }),
    { invalidate: INVALIDATE, message: (changed) => changed.message },
  );

  return (
    <>
      <DecideButton variant="ghost" onClick={() => setOpen(true)}>
        Commission
      </DecideButton>
      <ActionModal
        open={open}
        title={`Change what ${store.storeName} is charged`}
        description="From a date. Orders already placed keep the commission that was in force when they were placed — a rate change is never retroactive."
        submitLabel="Change it"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={rate === ''}
      >
        <p className="notice notice-muted">
          Currently <Commission value={store.commissionRate} />.
        </p>
        <Field label="New rate" required hint="As a percentage, e.g. 12.5.">
          <NumberInput value={rate} required min={0} max={100} step="0.01" onChange={setRate} />
        </Field>
        <Field label="Effective from" required>
          <input
            className="input"
            type="datetime-local"
            required
            value={effectiveFrom}
            onChange={(event) => setEffectiveFrom(event.target.value)}
          />
        </Field>
        <Field label="Note">
          <TextArea value={note} onChange={setNote} />
        </Field>
      </ActionModal>
    </>
  );
}
