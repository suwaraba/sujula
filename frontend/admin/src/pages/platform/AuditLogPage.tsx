import { useQuery } from '@tanstack/react-query';
import { audit as auditApi } from '@/api/endpoints';
import { AUDIT_ACTIONS } from '@/api/enums';
import type { AuditRow } from '@/api/types';
import { DataTable, type Column } from '@/components/DataTable';
import { Field, FilterBar, NumberInput, Select, TextInput } from '@/components/forms';
import { Pagination } from '@/components/Pagination';
import { EmptyState, Muted, PageHeader, Pill, humanise } from '@/components/primitives';
import { useCanDecide } from '@/components/Decide';
import { DateTime } from '@/components/Time';
import { keys, useFilters, usePaging } from '@/hooks';

const DEFAULTS = {
  actorUserId: undefined as number | undefined,
  action: undefined as string | undefined,
  targetType: undefined as string | undefined,
  targetId: undefined as number | undefined,
  q: undefined as string | undefined,
  from: undefined as string | undefined,
  to: undefined as string | undefined,
};

/**
 * Who did what, and when.
 *
 * Read-only for everybody, including administrators. There is no endpoint that
 * edits a row here and there should not be: an audit trail somebody can tidy
 * up is a record of what the last person wanted it to say.
 */
export function AuditLogPage() {
  const canDecide = useCanDecide();
  const [filters, setFilters, reset] = useFilters(DEFAULTS);
  const { page, size, setPage, setSize } = usePaging(50);

  const query = useQuery({
    queryKey: [...keys.audit, filters, page, size],
    queryFn: () => auditApi.search({ ...filters, action: filters.action as never, page, size }),
    // The endpoint gates on `staff.decider`, so support would only ever get a
    // 403 here. Not asking is better than asking and rendering the refusal.
    enabled: canDecide,
  });

  const columns: Column<AuditRow>[] = [
    { key: 'at', header: 'When', render: (row) => <DateTime value={row.at} /> },
    {
      key: 'actor',
      header: 'Who',
      render: (row) => (
        <>
          {row.actorName ?? row.actorEmail ?? <Muted>the system</Muted>}
          <br />
          <Muted>
            {row.actorUserId ? `#${row.actorUserId}` : ''} {row.ipAddress ?? ''}
          </Muted>
        </>
      ),
    },
    {
      key: 'action',
      header: 'Did',
      render: (row) => <Pill tone="neutral">{humanise(row.action)}</Pill>,
    },
    {
      key: 'target',
      header: 'To',
      render: (row) => (
        <>
          {row.targetLabel ?? <Muted>—</Muted>}
          <br />
          <Muted>
            {row.targetType} {row.targetId !== null ? `#${row.targetId}` : ''}
          </Muted>
        </>
      ),
    },
    {
      key: 'summary',
      header: 'Detail',
      render: (row) => (
        <>
          {row.summary ?? <Muted>—</Muted>}
          {row.details && <pre className="code-block code-block-inline">{row.details}</pre>}
        </>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Audit log"
        description="Every decision taken on this surface, against the name of whoever took it. Nothing here can be edited or removed — by anybody."
      />

      {!canDecide && (
        <EmptyState>
          The audit log is an administrator's to read. Support can see what happened to any single
          order, store or account on that thing's own screen — this is the view across all of them.
        </EmptyState>
      )}

      {canDecide && (
      <>
      <FilterBar onReset={reset}>
        <Field label="Search">
          <TextInput
            value={filters.q ?? ''}
            placeholder="Free text across summaries"
            onChange={(q) => setFilters({ q: q || undefined })}
          />
        </Field>
        <Field label="Action">
          <Select
            value={(filters.action ?? '') as string}
            options={AUDIT_ACTIONS}
            placeholder="Any action"
            onChange={(action) => setFilters({ action: action || undefined })}
          />
        </Field>
        <Field label="Who" hint="actor user id">
          <NumberInput
            value={filters.actorUserId ?? ''}
            onChange={(actorUserId) =>
              setFilters({ actorUserId: actorUserId === '' ? undefined : actorUserId })
            }
          />
        </Field>
        <Field label="Target type">
          <TextInput
            value={filters.targetType ?? ''}
            placeholder="USER, ORDER, VENDOR…"
            onChange={(targetType) => setFilters({ targetType: targetType || undefined })}
          />
        </Field>
        <Field label="Target id">
          <NumberInput
            value={filters.targetId ?? ''}
            onChange={(targetId) => setFilters({ targetId: targetId === '' ? undefined : targetId })}
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
        rowKey={(row) => row.id}
        isLoading={query.isFetching}
        error={query.error}
        empty="Nothing matches those filters."
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
      )}
    </>
  );
}
