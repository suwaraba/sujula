import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { jobs as jobsApi } from '@/api/endpoints';
import { JOB_RUN_STATUSES } from '@/api/enums';
import type { JobRow } from '@/api/types';
import { ActionModal } from '@/components/ActionModal';
import { DataTable, type Column } from '@/components/DataTable';
import { DecideButton } from '@/components/Decide';
import { Field, FilterBar, Select, TextArea, TextInput } from '@/components/forms';
import { Pagination } from '@/components/Pagination';
import { Card, Muted, PageHeader, Pill, StatusPill, Warnings } from '@/components/primitives';
import { DateTime } from '@/components/Time';
import { keys, useAction, useFilters, usePaging } from '@/hooks';

export function JobsPage() {
  const query = useQuery({ queryKey: keys.jobs, queryFn: jobsApi.list, refetchInterval: 30_000 });

  const columns: Column<JobRow>[] = [
    {
      key: 'job',
      header: 'Job',
      render: (row) => (
        <>
          <strong className="mono">{row.name}</strong>
          <br />
          <Muted>{row.description ?? '—'}</Muted>
          <Warnings warnings={row.warnings} />
        </>
      ),
    },
    {
      key: 'state',
      header: 'State',
      render: (row) => (
        <>
          {row.enabled ? <Pill tone="good">Enabled</Pill> : <Pill tone="neutral">Disabled</Pill>}
          {row.overdue && <Pill tone="bad">Overdue</Pill>}
          <br />
          <Muted>every {Math.round(row.intervalMs / 1000)}s</Muted>
        </>
      ),
    },
    {
      key: 'last',
      header: 'Last pass',
      render: (row) => (
        <>
          <StatusPill status={row.lastStatus} />
          <br />
          <Muted>
            <DateTime value={row.lastFinishedAt} />
            {row.lastDurationMs !== null && ` · ${row.lastDurationMs} ms`}
          </Muted>
          {row.lastFailureReason && (
            <>
              <br />
              <span className="overdue">{row.lastFailureReason}</span>
            </>
          )}
        </>
      ),
    },
    {
      key: 'items',
      header: 'Items',
      align: 'right',
      render: (row) => (row.lastItemsProcessed === null ? <Muted>—</Muted> : row.lastItemsProcessed),
    },
    {
      key: 'health',
      header: 'Health',
      render: (row) => (
        <>
          <Muted>
            last success <DateTime value={row.lastSuccessAt} />
          </Muted>
          <br />
          {row.failuresInLastDay > 0 ? (
            <span className="overdue">{row.failuresInLastDay} failures in 24h</span>
          ) : (
            <Muted>no failures in 24h</Muted>
          )}
        </>
      ),
    },
    { key: 'actions', header: '', render: (row) => <RunJob job={row} /> },
  ];

  return (
    <>
      <PageHeader
        title="Background jobs"
        description="What runs on its own, and whether it is still running. A job that has not succeeded since yesterday is the reason a queue somewhere looks stuck."
      />

      <DataTable
        columns={columns}
        rows={query.data}
        rowKey={(row) => row.name}
        isLoading={query.isFetching}
        error={query.error}
        empty="No jobs are registered."
        rowClassName={(row) => (row.overdue || row.failuresInLastDay > 0 ? 'row-overdue' : undefined)}
      />

      <JobHistory />
    </>
  );
}

const HISTORY_DEFAULTS = {
  jobName: undefined as string | undefined,
  status: undefined as string | undefined,
};

function JobHistory() {
  const [filters, setFilters, reset] = useFilters(HISTORY_DEFAULTS);
  const { page, size, setPage, setSize } = usePaging();

  const query = useQuery({
    queryKey: [...keys.jobHistory, filters, page, size],
    queryFn: () => jobsApi.history({ ...filters, page, size }),
  });

  const columns: Column<JobRow>[] = [
    { key: 'job', header: 'Job', render: (row) => <span className="mono">{row.name}</span> },
    { key: 'status', header: 'Outcome', render: (row) => <StatusPill status={row.lastStatus} /> },
    { key: 'started', header: 'Started', render: (row) => <DateTime value={row.lastStartedAt} /> },
    { key: 'finished', header: 'Finished', render: (row) => <DateTime value={row.lastFinishedAt} /> },
    {
      key: 'duration',
      header: 'Took',
      align: 'right',
      render: (row) => (row.lastDurationMs === null ? <Muted>—</Muted> : `${row.lastDurationMs} ms`),
    },
    {
      key: 'items',
      header: 'Items',
      align: 'right',
      render: (row) => (row.lastItemsProcessed === null ? <Muted>—</Muted> : row.lastItemsProcessed),
    },
    {
      key: 'failure',
      header: 'Failure',
      render: (row) => row.lastFailureReason ?? <Muted>—</Muted>,
    },
  ];

  return (
    <Card
      title="Every pass"
      subtitle="Including the ones that found nothing — a job that ran and did nothing is different from a job that did not run."
    >
      <FilterBar onReset={reset}>
        <Field label="Job">
          <TextInput
            value={filters.jobName ?? ''}
            onChange={(jobName) => setFilters({ jobName: jobName || undefined })}
          />
        </Field>
        <Field label="Outcome">
          <Select
            value={(filters.status ?? '') as string}
            options={JOB_RUN_STATUSES}
            placeholder="Any outcome"
            onChange={(status) => setFilters({ status: status || undefined })}
          />
        </Field>
      </FilterBar>

      <DataTable
        columns={columns}
        rows={query.data?.content}
        rowKey={(row) => `${row.name}-${row.lastStartedAt ?? ''}-${row.lastFinishedAt ?? ''}`}
        isLoading={query.isFetching}
        error={query.error}
        empty="Nothing has run."
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
    </Card>
  );
}

function RunJob({ job }: { job: JobRow }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');

  const action = useAction(() => jobsApi.run(job.name, { reason: reason.trim() }), {
    invalidate: [keys.jobs, keys.jobHistory, keys.dashboard],
    message: (triggered) => triggered.message,
  });

  return (
    <>
      <DecideButton variant="secondary" onClick={() => setOpen(true)}>
        Run now
      </DecideButton>
      <ActionModal
        open={open}
        title={`Run ${job.name} now`}
        description="It runs in the foreground of the request, so the answer tells you what it actually did rather than that it was queued."
        submitLabel="Run it"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={reason.trim().length < 3}
      >
        <Field label="Why" required>
          <TextArea value={reason} required minLength={3} onChange={setReason} />
        </Field>
      </ActionModal>
    </>
  );
}
