import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { cases as casesApi } from '@/api/endpoints';
import { MODERATION_CASE_STATUSES, MODERATION_REASONS, SANCTION_TYPES } from '@/api/enums';
import type { CaseRow } from '@/api/types';
import { ActionModal } from '@/components/ActionModal';
import { DataTable, type Column } from '@/components/DataTable';
import { DecideButton } from '@/components/Decide';
import { CheckBox, Field, FilterBar, NumberInput, Select, TextArea, TextInput } from '@/components/forms';
import { Pagination } from '@/components/Pagination';
import { Muted, PageHeader, Pill, StatusPill, humanise } from '@/components/primitives';
import { DateTime } from '@/components/Time';
import { keys, useAction, useFilters, usePaging } from '@/hooks';

const DEFAULTS = {
  status: undefined as string | undefined,
  reason: undefined as string | undefined,
  assigneeId: undefined as number | undefined,
};

export function CasesPage() {
  const [filters, setFilters, reset] = useFilters(DEFAULTS);
  const { page, size, setPage, setSize } = usePaging();

  const query = useQuery({
    queryKey: [...keys.cases, filters, page, size],
    queryFn: () =>
      casesApi.search({
        ...filters,
        status: filters.status as never,
        reason: filters.reason as never,
        page,
        size,
      }),
  });

  const columns: Column<CaseRow>[] = [
    {
      key: 'case',
      header: 'Case',
      render: (row) => (
        <>
          <strong className="mono">{row.reference}</strong>
          <br />
          <Muted>{humanise(row.reason)}</Muted>
        </>
      ),
    },
    { key: 'status', header: 'Status', render: (row) => <StatusPill status={row.status} /> },
    {
      key: 'subject',
      header: 'About',
      render: (row) => (
        <>
          {row.subjectLabel ?? `${humanise(row.subjectType)} #${row.subjectId}`}
          <br />
          <Muted>{row.storeName ?? row.accountableEmail ?? '—'}</Muted>
        </>
      ),
    },
    {
      key: 'prior',
      header: 'Prior cases',
      align: 'right',
      render: (row) =>
        row.priorCases > 0 ? (
          <Pill tone="warn" title="Against the same account. A first offence and a fifth are not the same decision.">
            {row.priorCases}
          </Pill>
        ) : (
          0
        ),
    },
    {
      key: 'raised',
      header: 'Raised',
      render: (row) => (
        <>
          <DateTime value={row.createdAt} />
          <br />
          <Muted>
            {row.source ? humanise(row.source) : '—'} · {row.raisedBy ?? 'automatically'}
          </Muted>
        </>
      ),
    },
    {
      key: 'due',
      header: 'Due',
      render: (row) => (
        <>
          <DateTime value={row.dueBy} />
          {row.overdue && (
            <>
              {' '}
              <Pill tone="bad">Past due</Pill>
            </>
          )}
        </>
      ),
    },
    { key: 'assigned', header: 'With', render: (row) => row.assignedTo ?? <Muted>nobody</Muted> },
    { key: 'actions', header: '', render: (row) => <ResolveCase moderationCase={row} /> },
  ];

  return (
    <>
      <PageHeader
        title="Policy cases"
        description="Soonest deadline first. The prior-case count is the whole point of keeping cases at all: a first offence and a fifth do not warrant the same thing."
      />

      <FilterBar onReset={reset}>
        <Field label="Status">
          <Select
            value={(filters.status ?? '') as string}
            options={MODERATION_CASE_STATUSES}
            placeholder="Any status"
            onChange={(status) => setFilters({ status: status || undefined })}
          />
        </Field>
        <Field label="Reason">
          <Select
            value={(filters.reason ?? '') as string}
            options={MODERATION_REASONS}
            placeholder="Any reason"
            onChange={(reason) => setFilters({ reason: reason || undefined })}
          />
        </Field>
        <Field label="Assigned to" hint="user id">
          <NumberInput
            value={filters.assigneeId ?? ''}
            onChange={(assigneeId) => setFilters({ assigneeId: assigneeId === '' ? undefined : assigneeId })}
          />
        </Field>
      </FilterBar>

      <DataTable
        columns={columns}
        rows={query.data?.content}
        rowKey={(row) => row.id}
        isLoading={query.isFetching}
        error={query.error}
        empty="No cases match those filters."
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

function ResolveCase({ moderationCase }: { moderationCase: CaseRow }) {
  const [open, setOpen] = useState(false);
  const [upheld, setUpheld] = useState(true);
  const [note, setNote] = useState('');
  const [sanctionType, setSanctionType] = useState<string>('');
  const [suspensionDays, setSuspensionDays] = useState<number | ''>('');
  const [restrictedPermission, setRestrictedPermission] = useState('');

  const action = useAction(
    () =>
      casesApi.resolve(moderationCase.id, {
        upheld,
        note: note.trim(),
        sanctionType: upheld && sanctionType ? (sanctionType as never) : null,
        suspensionDays: sanctionType === 'SUSPENSION' && suspensionDays !== '' ? suspensionDays : null,
        restrictedPermission:
          sanctionType === 'FEATURE_RESTRICTION' ? restrictedPermission.trim() || null : null,
      }),
    { invalidate: [keys.cases, keys.users, keys.dashboard], message: (resolved) => resolved.message },
  );

  const decided = moderationCase.status === 'RESOLVED' || moderationCase.status === 'DISMISSED';

  return (
    <>
      <DecideButton
        variant="secondary"
        onClick={() => setOpen(true)}
        disabled={decided}
        title={decided ? 'This case has already been decided.' : undefined}
      >
        Decide
      </DecideButton>
      <ActionModal
        open={open}
        width="wide"
        title={`Decide ${moderationCase.reference}`}
        description="Upholding a case can issue a sanction with it. A ban ends somebody's livelihood on this marketplace, so it is the last option rather than the strongest one."
        submitLabel="Decide it"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={note.trim().length < 5}
      >
        {moderationCase.priorCases > 0 && (
          <p className="notice notice-warning">
            {moderationCase.priorCases} prior{' '}
            {moderationCase.priorCases === 1 ? 'case' : 'cases'} against this account.
          </p>
        )}

        <CheckBox
          checked={upheld}
          onChange={setUpheld}
          label="Uphold the case"
          hint="Turn this off to dismiss it. Dismissing is a decision too, and it is recorded as one."
        />

        {upheld && (
          <>
            <Field label="Sanction" hint="Leave empty to uphold without issuing anything.">
              <Select
                value={sanctionType}
                options={SANCTION_TYPES}
                placeholder="No sanction"
                onChange={setSanctionType}
              />
            </Field>

            {sanctionType === 'SUSPENSION' && (
              <Field label="For how many days" required>
                <NumberInput value={suspensionDays} required min={1} max={365} onChange={setSuspensionDays} />
              </Field>
            )}

            {sanctionType === 'FEATURE_RESTRICTION' && (
              <Field
                label="Permission to withhold"
                required
                hint="One permission name, e.g. REVIEW_WRITE or CATALOGUE_WRITE."
              >
                <TextInput value={restrictedPermission} required onChange={setRestrictedPermission} />
              </Field>
            )}
          </>
        )}

        <Field label="Why" required hint="The person it is about may ask to see this.">
          <TextArea value={note} required minLength={5} rows={4} onChange={setNote} />
        </Field>
      </ActionModal>
    </>
  );
}
