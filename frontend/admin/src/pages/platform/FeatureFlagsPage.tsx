import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { featureFlags as flagsApi } from '@/api/endpoints';
import type { FlagRow } from '@/api/types';
import { ActionModal } from '@/components/ActionModal';
import { DataTable, type Column } from '@/components/DataTable';
import { DecideButton } from '@/components/Decide';
import { CheckBox, Field, TextArea } from '@/components/forms';
import { Muted, PageHeader, Pill } from '@/components/primitives';
import { DateTime } from '@/components/Time';
import { keys, useAction } from '@/hooks';

export function FeatureFlagsPage() {
  const query = useQuery({ queryKey: keys.flags, queryFn: flagsApi.list });

  const columns: Column<FlagRow>[] = [
    {
      key: 'flag',
      header: 'Switch',
      render: (row) => (
        <>
          <strong>{row.label ?? row.key}</strong>
          <br />
          <Muted>
            <code>{row.key}</code>
          </Muted>
          {row.description && (
            <>
              <br />
              <Muted>{row.description}</Muted>
            </>
          )}
        </>
      ),
    },
    {
      key: 'state',
      header: 'State',
      render: (row) => (
        <>
          {row.enabled ? <Pill tone="good">On</Pill> : <Pill tone="neutral">Off</Pill>}
          <br />
          {row.clientVisible ? (
            <Muted>published to clients</Muted>
          ) : (
            <Muted>server-side only</Muted>
          )}
        </>
      ),
    },
    {
      key: 'changed',
      header: 'Last moved',
      render: (row) => (
        <>
          <DateTime value={row.lastChangedAt} />
          <br />
          <Muted>
            {row.lastChangedByUserId ? `user #${row.lastChangedByUserId}` : '—'}
            {row.lastChangeReason && ` — ${row.lastChangeReason}`}
          </Muted>
        </>
      ),
    },
    { key: 'actions', header: '', render: (row) => <SetFlag flag={row} /> },
  ];

  return (
    <>
      <PageHeader
        title="Feature flags"
        description="The switches, and who last moved each one. A flag published to clients changes what every app in the field shows on its next start — the reason you give is what the next person reads when they wonder why."
      />

      <DataTable
        columns={columns}
        rows={query.data}
        rowKey={(row) => row.id}
        isLoading={query.isFetching}
        error={query.error}
        empty="No flags are defined."
      />
    </>
  );
}

function SetFlag({ flag }: { flag: FlagRow }) {
  const [open, setOpen] = useState(false);
  const [enabled, setEnabled] = useState(flag.enabled);
  const [clientVisible, setClientVisible] = useState(flag.clientVisible);
  const [reason, setReason] = useState('');

  const action = useAction(
    () => flagsApi.set(flag.key, { enabled, reason: reason.trim(), clientVisible }),
    { invalidate: [keys.flags], message: () => `${flag.key} is now ${enabled ? 'on' : 'off'}.` },
  );

  return (
    <>
      <DecideButton variant="secondary" onClick={() => setOpen(true)}>
        Move it
      </DecideButton>
      <ActionModal
        open={open}
        title={`Move ${flag.key}`}
        submitLabel="Move the switch"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={reason.trim().length < 5}
      >
        <CheckBox checked={enabled} onChange={setEnabled} label="On" />
        <CheckBox
          checked={clientVisible}
          onChange={setClientVisible}
          label="Publish this flag to clients"
          hint="Client-visible flags appear in GET /config/public and change what the apps render."
        />
        <Field label="Reason" required>
          <TextArea value={reason} required minLength={5} onChange={setReason} />
        </Field>
      </ActionModal>
    </>
  );
}
