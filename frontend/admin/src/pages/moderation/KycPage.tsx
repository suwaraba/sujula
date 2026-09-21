import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { kyc as kycApi } from '@/api/endpoints';
import { KYC_DOCUMENT_STATUSES } from '@/api/enums';
import type { KycRow } from '@/api/types';
import { ActionModal } from '@/components/ActionModal';
import { DataTable, type Column } from '@/components/DataTable';
import { DecideButton } from '@/components/Decide';
import { Field, FilterBar, NumberInput, Select, TextArea } from '@/components/forms';
import { Pagination } from '@/components/Pagination';
import { Muted, PageHeader, Pill, StatusPill, humanise } from '@/components/primitives';
import { DateOnly, DateTime } from '@/components/Time';
import { keys, useAction, useFilters, usePaging } from '@/hooks';

const DEFAULTS = {
  status: undefined as string | undefined,
  vendorId: undefined as number | undefined,
};

export function KycPage() {
  const [filters, setFilters, reset] = useFilters(DEFAULTS);
  const { page, size, setPage, setSize } = usePaging();

  const query = useQuery({
    queryKey: [...keys.kyc, filters, page, size],
    queryFn: () => kycApi.queue({ ...filters, status: filters.status as never, page, size }),
  });

  const columns: Column<KycRow>[] = [
    {
      key: 'document',
      header: 'Document',
      render: (row) => (
        <>
          <strong>{humanise(row.type)}</strong>
          <br />
          <Muted>{row.originalFilename ?? `#${row.id}`}</Muted>
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
          <Muted>#{row.vendorId}</Muted>
        </>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <>
          <StatusPill status={row.status} />
          {row.completesTheSet && (
            <Pill tone="info" title="Accepting this one completes the store's document set.">
              Last one needed
            </Pill>
          )}
        </>
      ),
    },
    { key: 'expires', header: 'Expires', render: (row) => <DateOnly value={row.expiresOn} /> },
    { key: 'submitted', header: 'Submitted', render: (row) => <DateTime value={row.submittedAt} /> },
    {
      key: 'reviewed',
      header: 'Reviewed',
      render: (row) => (
        <>
          {row.reviewedBy ?? <Muted>—</Muted>}
          <br />
          <Muted>
            <DateTime value={row.reviewedAt} />
          </Muted>
          {row.rejectionReason && (
            <>
              <br />
              <span className="overdue">{row.rejectionReason}</span>
            </>
          )}
        </>
      ),
    },
    {
      key: 'actions',
      header: '',
      render: (row) => (
        <div className="row-actions">
          {row.fileUrl && (
            <a className="button button-ghost" href={row.fileUrl} target="_blank" rel="noreferrer">
              Open
            </a>
          )}
          <ApproveKyc document={row} />
          <RejectKyc document={row} />
        </div>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="KYC queue"
        description="Documents waiting to be read. Nothing here is decided by a checkbox — open the document, then say what you saw."
      />

      <FilterBar onReset={reset}>
        <Field label="Status">
          <Select
            value={(filters.status ?? '') as string}
            options={KYC_DOCUMENT_STATUSES}
            placeholder="Any status"
            onChange={(status) => setFilters({ status: status || undefined })}
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
        rowKey={(row) => row.id}
        isLoading={query.isFetching}
        error={query.error}
        empty="No documents waiting."
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

function ApproveKyc({ document }: { document: KycRow }) {
  const [open, setOpen] = useState(false);
  const [note, setNote] = useState('');
  const action = useAction(() => kycApi.approve(document.id, { note: note.trim() || null }), {
    invalidate: [keys.kyc, keys.stores, keys.dashboard],
    message: (decision) => decision.message,
  });

  return (
    <>
      <DecideButton
        variant="secondary"
        onClick={() => setOpen(true)}
        disabled={document.status === 'ACCEPTED'}
      >
        Accept
      </DecideButton>
      <ActionModal
        open={open}
        title="Accept this document"
        description={
          document.completesTheSet
            ? 'This is the last document the store needs. Accepting it moves the store forward.'
            : undefined
        }
        submitLabel="Accept"
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

function RejectKyc({ document }: { document: KycRow }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  const action = useAction(() => kycApi.reject(document.id, { reason: reason.trim() }), {
    invalidate: [keys.kyc, keys.stores, keys.dashboard],
    message: (decision) => decision.message,
  });

  return (
    <>
      <DecideButton variant="ghost" onClick={() => setOpen(true)}>
        Refuse
      </DecideButton>
      <ActionModal
        open={open}
        title="Refuse this document"
        description="Say what is wrong with it. The seller reads this and sends another one — 'invalid' sends them back with the same photograph."
        submitLabel="Refuse it"
        tone="danger"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={reason.trim().length < 5}
      >
        <Field label="What is wrong with it" required>
          <TextArea
            value={reason}
            required
            minLength={5}
            rows={4}
            onChange={setReason}
            placeholder="The photograph cuts off the expiry date. Send one showing the whole card."
          />
        </Field>
      </ActionModal>
    </>
  );
}
