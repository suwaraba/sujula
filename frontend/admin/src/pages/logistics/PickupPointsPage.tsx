import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { pickupPoints as pickupApi } from '@/api/endpoints';
import { PARTNER_STATUSES } from '@/api/enums';
import type { PickupPointRow } from '@/api/types';
import type { CreatePickupPointRequest } from '@/api/requests';
import { ActionModal } from '@/components/ActionModal';
import { DataTable, type Column } from '@/components/DataTable';
import { DecideButton } from '@/components/Decide';
import {
  CheckBox,
  Field,
  FilterBar,
  FormRow,
  MoneyInput,
  NumberInput,
  Select,
  TextArea,
  TextInput,
  TriState,
} from '@/components/forms';
import { CountrySelect } from '@/components/CountrySelect';
import { Money } from '@/components/Money';
import { Pagination } from '@/components/Pagination';
import { Flags, Muted, PageHeader, Pill, StatusPill } from '@/components/primitives';
import { DateTime } from '@/components/Time';
import { currencyInfo, knownCurrencies } from '@/money/currency';
import { keys, useAction, useFilters, usePaging } from '@/hooks';

const DEFAULTS = {
  q: undefined as string | undefined,
  status: undefined as string | undefined,
  countryCode: undefined as string | undefined,
  overdueOnly: undefined as boolean | undefined,
  fullOnly: undefined as boolean | undefined,
};

/**
 * Collection points, with what is sitting on each shelf.
 *
 * A counter is part of the custody chain: a parcel deposited there has been
 * handed over, and a parcel overdue there is one somebody has not come for.
 * Both numbers are on the row, because a counter with no space is a counter the
 * serviceability path must stop offering.
 */
export function PickupPointsPage() {
  const [filters, setFilters, reset] = useFilters(DEFAULTS);
  const { page, size, setPage, setSize } = usePaging();

  const query = useQuery({
    queryKey: [...keys.pickupPoints, filters, page, size],
    queryFn: () => pickupApi.search({ ...filters, status: filters.status as never, page, size }),
  });

  const columns: Column<PickupPointRow>[] = [
    {
      key: 'point',
      header: 'Counter',
      render: (row) => (
        <>
          <strong>{row.name}</strong>
          <br />
          <Muted>
            {row.city ?? '—'}, {row.countryCode ?? '—'} · #{row.id}
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
          {!row.active && <Pill tone="bad">Closed</Pill>}
          {row.closedUntil && (
            <Muted>
              {' '}
              until <DateTime value={row.closedUntil} />
            </Muted>
          )}
          {row.closureReason && (
            <>
              <br />
              <Muted>{row.closureReason}</Muted>
            </>
          )}
        </>
      ),
    },
    {
      key: 'operator',
      header: 'Run by',
      render: (row) => (
        <>
          {row.operatorName ?? <Muted>nobody</Muted>}
          {row.operatorUserId && <Muted> · #{row.operatorUserId}</Muted>}
        </>
      ),
    },
    {
      key: 'shelf',
      header: 'On the shelf',
      align: 'right',
      render: (row) => (
        <>
          {row.storedParcels ?? 0}
          {row.capacity !== null && <Muted> / {row.capacity}</Muted>}
          <br />
          <Muted className={row.spaceLeft <= 0 ? 'overdue' : undefined}>
            {row.spaceLeft <= 0 ? 'full' : `${row.spaceLeft} spaces`}
          </Muted>
        </>
      ),
    },
    {
      key: 'overdue',
      header: 'Overdue',
      align: 'right',
      render: (row) =>
        row.overdueParcels > 0 ? (
          <>
            <span className="overdue">{row.overdueParcels}</span>
            <br />
            <Muted>
              since <DateTime value={row.oldestOverdueSince} />
            </Muted>
          </>
        ) : (
          0
        ),
    },
    {
      key: 'commission',
      header: 'Per parcel',
      align: 'right',
      render: (row) => <Money amount={row.commissionPerParcel} currency={row.commissionCurrency} />,
    },
    {
      key: 'actions',
      header: '',
      render: (row) => (
        <div className="row-actions">
          <EditPoint point={row} />
          <SuspendPoint point={row} />
        </div>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Pickup points"
        description="A counter is a link in the custody chain, not a cupboard. Suspending one stops new parcels being sent there; what is already on the shelf still has to be collected."
        actions={<CreatePoint />}
      />

      <FilterBar onReset={reset}>
        <Field label="Search">
          <TextInput value={filters.q ?? ''} onChange={(q) => setFilters({ q: q || undefined })} />
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
            value={filters.countryCode ?? ''}
            maxLength={2}
            onChange={(countryCode) =>
              setFilters({ countryCode: countryCode ? countryCode.toUpperCase() : undefined })
            }
          />
        </Field>
        <Field label="With overdue parcels">
          <TriState value={filters.overdueOnly} onChange={(overdueOnly) => setFilters({ overdueOnly })} />
        </Field>
        <Field label="Full only">
          <TriState value={filters.fullOnly} onChange={(fullOnly) => setFilters({ fullOnly })} />
        </Field>
      </FilterBar>

      <DataTable
        columns={columns}
        rows={query.data?.content}
        rowKey={(row) => row.id}
        isLoading={query.isFetching}
        error={query.error}
        empty="No counters match those filters."
        rowClassName={(row) => (row.overdueParcels > 0 || row.spaceLeft <= 0 ? 'row-overdue' : undefined)}
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

const BLANK: CreatePickupPointRequest = {
  name: '',
  addressStreet: '',
  addressApartment: null,
  city: '',
  state: null,
  postalCode: null,
  countryCode: '',
  latitude: 0,
  longitude: 0,
  contactPhone: null,
  contactEmail: null,
  managerName: null,
  openingHours: null,
  capacity: null,
  storageDays: null,
  commissionPerParcel: null,
  commissionCurrency: null,
  operatorUserId: null,
  adminNote: null,
};

function PointFields({
  value,
  onChange,
  requireAddress,
}: {
  value: CreatePickupPointRequest;
  onChange(next: CreatePickupPointRequest): void;
  requireAddress: boolean;
}) {
  const scale = currencyInfo(value.commissionCurrency)?.minorUnits ?? 2;
  const set = (patch: Partial<CreatePickupPointRequest>) => onChange({ ...value, ...patch });

  return (
    <>
      <FormRow>
        <Field label="Name" required={requireAddress}>
          <TextInput value={value.name} required={requireAddress} onChange={(name) => set({ name })} />
        </Field>
        <Field label="Who runs it" hint="A user id for the operator's own account.">
          <NumberInput
            value={value.operatorUserId ?? ''}
            onChange={(operatorUserId) =>
              set({ operatorUserId: operatorUserId === '' ? null : operatorUserId })
            }
          />
        </Field>
      </FormRow>

      <Field label="Street" required={requireAddress}>
        <TextInput
          value={value.addressStreet}
          required={requireAddress}
          onChange={(addressStreet) => set({ addressStreet })}
        />
      </Field>

      <FormRow columns={3}>
        <Field label="Apartment or unit">
          <TextInput
            value={value.addressApartment ?? ''}
            onChange={(addressApartment) => set({ addressApartment: addressApartment || null })}
          />
        </Field>
        <Field label="City" required={requireAddress}>
          <TextInput value={value.city} required={requireAddress} onChange={(city) => set({ city })} />
        </Field>
        <Field label="Region">
          <TextInput value={value.state ?? ''} onChange={(state) => set({ state: state || null })} />
        </Field>
      </FormRow>

      <FormRow columns={3}>
        <Field label="Postal code">
          <TextInput
            value={value.postalCode ?? ''}
            onChange={(postalCode) => set({ postalCode: postalCode || null })}
          />
        </Field>
        {requireAddress && (
          <Field label="Country" required hint="Where the counter is. Fixed once it exists.">
            <CountrySelect
              value={value.countryCode}
              purpose="ship"
              required
              onChange={(countryCode) => set({ countryCode })}
            />
          </Field>
        )}
        <Field label="Opening hours">
          <TextInput
            value={value.openingHours ?? ''}
            placeholder="Mon–Sat 09:00–19:00"
            onChange={(openingHours) => set({ openingHours: openingHours || null })}
          />
        </Field>
      </FormRow>

      <FormRow>
        <Field
          label="Latitude"
          required={requireAddress}
          hint="A counter with the wrong coordinates is one drivers are sent to the wrong street for."
        >
          <NumberInput
            value={value.latitude}
            required={requireAddress}
            step="0.000001"
            min={-90}
            max={90}
            onChange={(latitude) => set({ latitude: latitude === '' ? 0 : latitude })}
          />
        </Field>
        <Field label="Longitude" required={requireAddress}>
          <NumberInput
            value={value.longitude}
            required={requireAddress}
            step="0.000001"
            min={-180}
            max={180}
            onChange={(longitude) => set({ longitude: longitude === '' ? 0 : longitude })}
          />
        </Field>
      </FormRow>

      <FormRow columns={3}>
        <Field label="Contact phone">
          <TextInput
            value={value.contactPhone ?? ''}
            onChange={(contactPhone) => set({ contactPhone: contactPhone || null })}
          />
        </Field>
        <Field label="Contact email">
          <TextInput
            type="email"
            value={value.contactEmail ?? ''}
            onChange={(contactEmail) => set({ contactEmail: contactEmail || null })}
          />
        </Field>
        <Field label="Manager's name">
          <TextInput
            value={value.managerName ?? ''}
            onChange={(managerName) => set({ managerName: managerName || null })}
          />
        </Field>
      </FormRow>

      <FormRow columns={4}>
        <Field label="Capacity" hint="parcels">
          <NumberInput
            value={value.capacity ?? ''}
            min={0}
            onChange={(capacity) => set({ capacity: capacity === '' ? null : capacity })}
          />
        </Field>
        <Field label="Storage days" hint="before a parcel is overdue">
          <NumberInput
            value={value.storageDays ?? ''}
            min={1}
            onChange={(storageDays) => set({ storageDays: storageDays === '' ? null : storageDays })}
          />
        </Field>
        <Field label="Commission currency">
          <Select
            value={value.commissionCurrency ?? ''}
            placeholder="Choose"
            options={knownCurrencies().map((entry) => ({ value: entry.code, label: entry.code }))}
            onChange={(commissionCurrency) => set({ commissionCurrency: commissionCurrency || null })}
          />
        </Field>
        <Field label="Commission per parcel">
          <MoneyInput
            value={value.commissionPerParcel === null ? '' : String(value.commissionPerParcel)}
            minorUnits={scale}
            min={0}
            onChange={(commissionPerParcel) =>
              set({ commissionPerParcel: commissionPerParcel === '' ? null : commissionPerParcel })
            }
          />
        </Field>
      </FormRow>

      <Field label="Internal note">
        <TextArea
          value={value.adminNote ?? ''}
          onChange={(adminNote) => set({ adminNote: adminNote || null })}
        />
      </Field>
    </>
  );
}

function CreatePoint() {
  const [open, setOpen] = useState(false);
  const [value, setValue] = useState<CreatePickupPointRequest>(BLANK);

  const action = useAction(() => pickupApi.create(value), {
    invalidate: [keys.pickupPoints],
    message: (saved) => saved.message,
    onDone: () => setValue(BLANK),
  });

  return (
    <>
      <DecideButton onClick={() => setOpen(true)}>Open a counter</DecideButton>
      <ActionModal
        open={open}
        width="wide"
        title="Open a counter"
        submitLabel="Open it"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={!value.name || !value.addressStreet || !value.city || !value.countryCode}
      >
        <PointFields value={value} onChange={setValue} requireAddress />
      </ActionModal>
    </>
  );
}

function EditPoint({ point }: { point: PickupPointRow }) {
  const [open, setOpen] = useState(false);
  const [value, setValue] = useState<CreatePickupPointRequest>({
    ...BLANK,
    name: point.name,
    city: point.city ?? '',
    latitude: point.latitude ?? 0,
    longitude: point.longitude ?? 0,
    capacity: point.capacity,
    storageDays: point.storageDays,
    commissionPerParcel: point.commissionPerParcel,
    commissionCurrency: point.commissionCurrency,
    operatorUserId: point.operatorUserId,
    adminNote: point.adminNote,
  });
  const [active, setActive] = useState(point.active);

  const action = useAction(
    () => {
      const { countryCode: _ignored, ...rest } = value;
      return pickupApi.patch(point.id, { ...rest, active });
    },
    { invalidate: [keys.pickupPoints], message: (saved) => saved.message },
  );

  return (
    <>
      <DecideButton variant="ghost" onClick={() => setOpen(true)}>
        Edit
      </DecideButton>
      <ActionModal
        open={open}
        width="wide"
        title={`Edit ${point.name}`}
        description="The country is fixed once a counter exists — a counter that moves country is a different counter."
        submitLabel="Save"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
      >
        <CheckBox
          checked={active}
          onChange={setActive}
          label="Open for new parcels"
          hint="Turning this off is the reversible version of suspending."
        />
        <PointFields value={value} onChange={setValue} requireAddress={false} />
      </ActionModal>
    </>
  );
}

function SuspendPoint({ point }: { point: PickupPointRow }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');

  const action = useAction(() => pickupApi.suspend(point.id, { reason: reason.trim() }), {
    invalidate: [keys.pickupPoints, keys.dashboard],
    message: (saved) => saved.message,
  });

  return (
    <>
      <DecideButton variant="danger" onClick={() => setOpen(true)}>
        Suspend
      </DecideButton>
      <ActionModal
        open={open}
        title={`Stop new parcels going to ${point.name}`}
        description={
          (point.storedParcels ?? 0) > 0
            ? `${point.storedParcels} parcels are on that shelf. Suspending stops more arriving; those still have to be collected or returned.`
            : undefined
        }
        submitLabel="Suspend the counter"
        tone="danger"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={reason.trim().length < 5}
      >
        <Field label="Reason" required>
          <TextArea value={reason} required minLength={5} onChange={setReason} />
        </Field>
      </ActionModal>
    </>
  );
}
