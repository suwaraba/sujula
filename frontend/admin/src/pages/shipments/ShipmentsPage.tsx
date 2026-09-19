import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { shipments as shipmentsApi } from '@/api/endpoints';
import { SHIPMENT_STATUSES } from '@/api/enums';
import type { ShipmentRow } from '@/api/types';
import { ActionModal } from '@/components/ActionModal';
import { DataTable, type Column } from '@/components/DataTable';
import { DecideButton } from '@/components/Decide';
import { Field, FilterBar, NumberInput, Select, TextArea, TextInput } from '@/components/forms';
import { Pagination } from '@/components/Pagination';
import { Muted, PageHeader, StatusPill } from '@/components/primitives';
import { DateTime, Hours } from '@/components/Time';
import { keys, useAction, useFilters, usePaging } from '@/hooks';

const DEFAULTS = {
  status: undefined as string | undefined,
  country: undefined as string | undefined,
  driverId: undefined as number | undefined,
  waitingOverHours: undefined as number | undefined,
};

export function ShipmentsPage() {
  const [filters, setFilters, reset] = useFilters(DEFAULTS);
  const { page, size, setPage, setSize } = usePaging();

  const query = useQuery({
    queryKey: [...keys.shipments, filters, page, size],
    queryFn: () => shipmentsApi.search({ ...filters, status: filters.status as never, page, size }),
  });

  const columns: Column<ShipmentRow>[] = [
    {
      key: 'reference',
      header: 'Parcel',
      render: (row) => (
        <>
          <strong className="mono">{row.reference}</strong>
          <br />
          <Muted>{row.storeName}</Muted>
        </>
      ),
    },
    { key: 'status', header: 'Status', render: (row) => <StatusPill status={row.status} /> },
    {
      key: 'destination',
      header: 'Goes to',
      render: (row) => (
        <>
          {row.destinationCity ?? <Muted>—</Muted>}
          <br />
          <Muted>{row.destinationCountry}</Muted>
        </>
      ),
    },
    { key: 'driver', header: 'Carried by', render: (row) => row.driverName ?? <Muted>nobody</Muted> },
    {
      key: 'attempts',
      header: 'Failed attempts',
      align: 'right',
      render: (row) => (row.failedAttempts > 0 ? <span className="overdue">{row.failedAttempts}</span> : row.failedAttempts),
    },
    {
      key: 'waiting',
      header: 'Waiting',
      align: 'right',
      render: (row) => <Hours value={row.hoursWaiting} overdue={row.overdue} />,
    },
    { key: 'created', header: 'Created', render: (row) => <DateTime value={row.createdAt} /> },
    {
      key: 'actions',
      header: '',
      render: (row) => (
        <div className="row-actions">
          <Link className="button button-ghost" to={`/shipments/${row.id}/custody-chain`}>
            Chain
          </Link>
          <CancelShipment shipment={row} />
        </div>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Dispatch board"
        description="Every parcel in motion, and how long it has been where it is. A parcel with failed attempts and nobody carrying it is the one to open first."
        actions={
          <Link className="button button-secondary" to="/shipments/unassigned">
            Parcels nobody is carrying
          </Link>
        }
      />

      <FilterBar onReset={reset}>
        <Field label="Status">
          <Select
            value={(filters.status ?? '') as string}
            options={SHIPMENT_STATUSES}
            placeholder="Any status"
            onChange={(status) => setFilters({ status: status || undefined })}
          />
        </Field>
        <Field label="Destination country" hint="Where the parcel is going.">
          <TextInput
            value={filters.country ?? ''}
            maxLength={2}
            placeholder="GM"
            onChange={(country) => setFilters({ country: country ? country.toUpperCase() : undefined })}
          />
        </Field>
        <Field label="Driver id">
          <NumberInput
            value={filters.driverId ?? ''}
            onChange={(driverId) => setFilters({ driverId: driverId === '' ? undefined : driverId })}
          />
        </Field>
        <Field label="Waiting over" hint="hours">
          <NumberInput
            value={filters.waitingOverHours ?? ''}
            min={1}
            onChange={(hours) => setFilters({ waitingOverHours: hours === '' ? undefined : hours })}
          />
        </Field>
      </FilterBar>

      <DataTable
        columns={columns}
        rows={query.data?.content}
        rowKey={(row) => row.id}
        isLoading={query.isFetching}
        error={query.error}
        empty="No parcels match those filters."
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

function CancelShipment({ shipment }: { shipment: ShipmentRow }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');

  const action = useAction(() => shipmentsApi.cancel(shipment.id, { reason: reason.trim() }), {
    invalidate: [keys.shipments, keys.unassigned, keys.dashboard],
    message: (cancelled) => cancelled.message,
  });

  return (
    <>
      <DecideButton variant="ghost" onClick={() => setOpen(true)}>
        Cancel
      </DecideButton>
      <ActionModal
        open={open}
        title={`Stop parcel ${shipment.reference}`}
        description="Every open leg is cancelled with it. A driver holding this parcel is told, and whatever they are carrying has to be brought back."
        submitLabel="Stop the parcel"
        tone="danger"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={reason.trim().length < 5}
      >
        <Field label="Reason" required>
          <TextArea value={reason} onChange={setReason} required minLength={5} />
        </Field>
      </ActionModal>
    </>
  );
}
