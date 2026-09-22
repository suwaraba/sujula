import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { callbacks as callbacksApi } from '@/api/endpoints';
import { CALLBACK_OUTCOMES } from '@/api/enums';
import type { CallbackRow } from '@/api/types';
import { ActionModal } from '@/components/ActionModal';
import { DataTable, type Column } from '@/components/DataTable';
import { DecideButton } from '@/components/Decide';
import { Field, Select, TextArea } from '@/components/forms';
import { Pagination } from '@/components/Pagination';
import { Muted, PageHeader, Pill, humanise } from '@/components/primitives';
import { DateTime, nowLocalInput, toLocalDateTime } from '@/components/Time';
import { keys, useAction, usePaging } from '@/hooks';

/**
 * Calls somebody still owes, soonest deadline first.
 *
 * The queue exists because the person on the other end of a dispute may have a
 * telephone and nothing else — no account, no app, no email. A platform that
 * can only reach people who signed up cannot settle a dispute about a parcel
 * delivered to somebody's sister.
 */
export function CallbacksPage() {
  const { page, size, setPage, setSize } = usePaging();

  const query = useQuery({
    queryKey: [...keys.callbacks, page, size],
    queryFn: () => callbacksApi.outstanding({ page, size }),
    refetchInterval: 60_000,
  });

  const columns: Column<CallbackRow>[] = [
    {
      key: 'who',
      header: 'Call',
      render: (row) => (
        <>
          <strong>{row.contactName ?? 'Whoever answers'}</strong>
          <br />
          <a href={`tel:${row.phone}`} className="mono">
            {row.phone}
          </a>
          {row.preferredLanguage && <Muted> · speaks {row.preferredLanguage}</Muted>}
        </>
      ),
    },
    {
      key: 'dispute',
      header: 'About',
      render: (row) => (
        <>
          <Link to={`/disputes/${row.disputeId}`}>{row.disputeReference}</Link>
          <br />
          <Muted>{row.reason ?? '—'}</Muted>
        </>
      ),
    },
    {
      key: 'by',
      header: 'Call by',
      render: (row) => (
        <>
          <DateTime value={row.callBy} />
          {row.overdue && (
            <>
              {' '}
              <Pill tone="bad">Past due</Pill>
            </>
          )}
        </>
      ),
    },
    { key: 'attempts', header: 'Attempts', align: 'right', render: (row) => row.attempts },
    {
      key: 'outcome',
      header: 'Last outcome',
      render: (row) =>
        row.outcome ? (
          <>
            <Pill tone="neutral">{humanise(row.outcome)}</Pill>
            <br />
            <Muted>
              <DateTime value={row.calledAt} />
            </Muted>
          </>
        ) : (
          <Muted>never called</Muted>
        ),
    },
    { key: 'actions', header: '', render: (row) => <RecordOutcome callback={row} /> },
  ];

  return (
    <>
      <PageHeader
        title="Callbacks"
        description="Soonest deadline first. Somebody at the other end of one of these has a telephone and nothing else — no account, no app, no email address."
      />

      <DataTable
        columns={columns}
        rows={query.data?.content}
        rowKey={(row) => row.callbackId}
        isLoading={query.isFetching}
        error={query.error}
        empty="Nobody is owed a call."
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

function RecordOutcome({ callback }: { callback: CallbackRow }) {
  const [open, setOpen] = useState(false);
  const [outcome, setOutcome] = useState<string>('');
  const [notes, setNotes] = useState('');
  const [callBy, setCallBy] = useState(nowLocalInput());

  const action = useAction(
    () =>
      callbacksApi.record(callback.callbackId, {
        outcome: outcome as never,
        notes: notes.trim() || null,
        callBy: outcome === 'RESCHEDULED' ? toLocalDateTime(callBy) : null,
      }),
    {
      invalidate: [keys.callbacks, keys.dispute(callback.disputeId), keys.dashboard],
      message: () => 'Call recorded.',
    },
  );

  return (
    <>
      <DecideButton variant="secondary" onClick={() => setOpen(true)}>
        Record the call
      </DecideButton>
      <ActionModal
        open={open}
        title="Record what came of the call"
        submitLabel="Record it"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={!outcome}
      >
        <Field label="Outcome" required>
          <Select value={outcome} required options={CALLBACK_OUTCOMES} placeholder="Choose" onChange={setOutcome} />
        </Field>

        {outcome === 'RESCHEDULED' && (
          <Field label="Call again by" required>
            <input
              className="input"
              type="datetime-local"
              required
              value={callBy}
              onChange={(event) => setCallBy(event.target.value)}
            />
          </Field>
        )}

        <Field label="What they said" hint="Goes on the dispute for whoever decides it.">
          <TextArea value={notes} rows={4} onChange={setNotes} />
        </Field>
      </ActionModal>
    </>
  );
}
