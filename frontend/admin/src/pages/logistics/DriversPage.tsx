import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { drivers as driversApi } from '@/api/endpoints';
import { DRIVER_STATUSES } from '@/api/enums';
import type { DriverRow } from '@/api/types';
import { ActionModal } from '@/components/ActionModal';
import { DataTable, type Column } from '@/components/DataTable';
import { DecideButton } from '@/components/Decide';
import { Field, FilterBar, Select, TextArea, TextInput, TriState } from '@/components/forms';
import { Pagination } from '@/components/Pagination';
import { Flags, Muted, PageHeader, Pill, StatusPill } from '@/components/primitives';
import { DateTime, nowLocalInput, toLocalDateTime } from '@/components/Time';
import { keys, useAction, useFilters, usePaging } from '@/hooks';

const DEFAULTS = {
  q: undefined as string | undefined,
  status: undefined as string | undefined,
  countryCode: undefined as string | undefined,
  zoneCode: undefined as string | undefined,
  availableOnly: undefined as boolean | undefined,
};

export function DriversPage() {
  const [filters, setFilters, reset] = useFilters(DEFAULTS);
  const { page, size, setPage, setSize } = usePaging();

  const query = useQuery({
    queryKey: [...keys.drivers, filters, page, size],
    queryFn: () => driversApi.search({ ...filters, status: filters.status as never, page, size }),
  });

  const columns: Column<DriverRow>[] = [
    {
      key: 'driver',
      header: 'Driver',
      render: (row) => (
        <>
          <strong>{row.name}</strong>
          <br />
          <Muted>
            {row.phone ?? row.email ?? '—'} · #{row.driverId}
          </Muted>
          <Flags flags={row.flags} />
        </>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <>
          <StatusPill status={row.status} />
          <br />
          {row.available ? <Pill tone="good">Online</Pill> : <Muted>offline</Muted>}
        </>
      ),
    },
    {
      key: 'zones',
      header: 'Works in',
      render: (row) =>
        row.zones.length === 0 ? (
          <Muted>no zones — cannot be offered work</Muted>
        ) : (
          <span className="flag-row">
            {row.zones.map((zone) => (
              <Pill key={zone.zoneId} tone={zone.serviceable ? 'neutral' : 'warn'} title={zone.name}>
                {zone.code}
              </Pill>
            ))}
          </span>
        ),
    },
    {
      key: 'acceptance',
      header: 'Accepts',
      align: 'right',
      render: (row) => (
        <>
          {row.acceptanceScore === null ? <Muted>—</Muted> : `${Number(row.acceptanceScore).toFixed(0)}%`}
          <br />
          <Muted>
            {row.offersAccepted ?? 0}/{row.offersReceived ?? 0} offers
          </Muted>
        </>
      ),
    },
    { key: 'open', header: 'Carrying', align: 'right', render: (row) => row.openJobs },
    {
      key: 'rating',
      header: 'Rated',
      align: 'right',
      render: (row) =>
        row.averageRating === null ? (
          <Muted>—</Muted>
        ) : (
          <>
            {Number(row.averageRating).toFixed(1)}
            <br />
            <Muted>{row.totalRatings ?? 0} ratings</Muted>
          </>
        ),
    },
    {
      key: 'ping',
      header: 'Last position',
      render: (row) => (
        <>
          <DateTime value={row.lastLocationAt} />
          {row.minutesSinceLastPing !== null && row.minutesSinceLastPing > 60 && (
            <>
              <br />
              <Muted className="overdue">{row.minutesSinceLastPing} minutes ago</Muted>
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
          <ApproveDriver driver={row} />
          <SuspendDriver driver={row} />
          <SetZones driver={row} />
        </div>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Drivers"
        description="What they are carrying and how they answer. A driver with no zones cannot be offered work at all, however available they say they are."
      />

      <FilterBar onReset={reset}>
        <Field label="Search">
          <TextInput
            value={filters.q ?? ''}
            placeholder="Name, phone or email"
            onChange={(q) => setFilters({ q: q || undefined })}
          />
        </Field>
        <Field label="Status">
          <Select
            value={(filters.status ?? '') as string}
            options={DRIVER_STATUSES}
            placeholder="Any status"
            onChange={(status) => setFilters({ status: status || undefined })}
          />
        </Field>
        <Field label="Country">
          <TextInput
            value={filters.countryCode ?? ''}
            maxLength={2}
            onChange={(countryCode) =>
              setFilters({ countryCode: countryCode ? countryCode.toUpperCase() : undefined })
            }
          />
        </Field>
        <Field label="Zone code">
          <TextInput
            value={filters.zoneCode ?? ''}
            onChange={(zoneCode) => setFilters({ zoneCode: zoneCode || undefined })}
          />
        </Field>
        <Field label="Online only">
          <TriState value={filters.availableOnly} onChange={(availableOnly) => setFilters({ availableOnly })} />
        </Field>
      </FilterBar>

      <DataTable
        columns={columns}
        rows={query.data?.content}
        rowKey={(row) => row.driverId}
        isLoading={query.isFetching}
        error={query.error}
        empty="No drivers match those filters."
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

function ApproveDriver({ driver }: { driver: DriverRow }) {
  const [open, setOpen] = useState(false);
  const [note, setNote] = useState('');
  const [zoneCodes, setZoneCodes] = useState(driver.declaredZone ?? '');

  const action = useAction(
    () =>
      driversApi.approve(driver.driverId, {
        note: note.trim() || null,
        zoneCodes: zoneCodes.trim() ? zoneCodes.split(/[\s,]+/).filter(Boolean) : null,
      }),
    { invalidate: [keys.drivers, keys.unassigned], message: (decision) => decision.message },
  );

  const already = driver.status === 'APPROVED' || driver.status === 'ACTIVE';

  return (
    <>
      <DecideButton
        variant="secondary"
        onClick={() => setOpen(true)}
        disabled={already}
        title={already ? 'This driver is already carrying.' : undefined}
      >
        Approve
      </DecideButton>
      <ActionModal
        open={open}
        title={`Let ${driver.name} start carrying`}
        description="Set the zones now. A driver approved with none is a driver the dispatch board will never offer anything to, and nobody finds out until a parcel sits."
        submitLabel="Approve"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
      >
        <Field
          label="Zones"
          hint={`Zone codes, separated by spaces.${driver.declaredZone ? ` They said they work in ${driver.declaredZone}.` : ''}`}
        >
          <TextInput value={zoneCodes} onChange={setZoneCodes} placeholder="BJL-CENTRAL SRK-NORTH" />
        </Field>
        <Field label="Note">
          <TextArea value={note} onChange={setNote} />
        </Field>
      </ActionModal>
    </>
  );
}

function SuspendDriver({ driver }: { driver: DriverRow }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [until, setUntil] = useState(nowLocalInput());
  const [indefinite, setIndefinite] = useState(true);

  const action = useAction(
    () =>
      driversApi.suspend(driver.driverId, {
        reason: reason.trim(),
        until: indefinite ? null : toLocalDateTime(until),
      }),
    { invalidate: [keys.drivers, keys.unassigned], message: (decision) => decision.message },
  );

  return (
    <>
      <DecideButton variant="ghost" onClick={() => setOpen(true)}>
        Suspend
      </DecideButton>
      <ActionModal
        open={open}
        title={`Stop offering ${driver.name} work`}
        description={
          driver.openJobs > 0
            ? `They are carrying ${driver.openJobs} ${driver.openJobs === 1 ? 'parcel' : 'parcels'}. Suspending stops new offers; those parcels still have to reach somebody.`
            : undefined
        }
        submitLabel="Suspend"
        tone="danger"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={reason.trim().length < 5}
      >
        <label className="checkbox">
          <input
            type="checkbox"
            checked={indefinite}
            onChange={(event) => setIndefinite(event.target.checked)}
          />
          <span>Until somebody lifts it</span>
        </label>
        {!indefinite && (
          <Field label="Until" required>
            <input
              className="input"
              type="datetime-local"
              required
              value={until}
              onChange={(event) => setUntil(event.target.value)}
            />
          </Field>
        )}
        <Field label="Reason" required>
          <TextArea value={reason} required minLength={5} onChange={setReason} />
        </Field>
      </ActionModal>
    </>
  );
}

function SetZones({ driver }: { driver: DriverRow }) {
  const [open, setOpen] = useState(false);
  const [zoneCodes, setZoneCodes] = useState(driver.zones.map((zone) => zone.code).join(' '));
  const [note, setNote] = useState('');

  const action = useAction(
    () =>
      driversApi.setZones(driver.driverId, {
        zoneCodes: zoneCodes.split(/[\s,]+/).filter(Boolean),
        note: note.trim() || null,
      }),
    { invalidate: [keys.drivers, keys.unassigned], message: (decision) => decision.message },
  );

  return (
    <>
      <DecideButton variant="ghost" onClick={() => setOpen(true)}>
        Zones
      </DecideButton>
      <ActionModal
        open={open}
        title={`Where ${driver.name} may be offered work`}
        description="This replaces their zones rather than adding to them."
        submitLabel="Save the zones"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
      >
        <Field label="Zone codes" required hint="Separated by spaces. Empty means nowhere.">
          <TextInput value={zoneCodes} onChange={setZoneCodes} />
        </Field>
        <Field label="Note">
          <TextArea value={note} onChange={setNote} />
        </Field>
      </ActionModal>
    </>
  );
}
