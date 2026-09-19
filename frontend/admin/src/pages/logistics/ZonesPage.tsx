import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { zones as zonesApi } from '@/api/endpoints';
import type { ZoneRow } from '@/api/types';
import { ActionModal } from '@/components/ActionModal';
import { DataTable, type Column } from '@/components/DataTable';
import { DecideButton } from '@/components/Decide';
import { CheckBox, Field, FilterBar, FormRow, NumberInput, TextArea, TextInput, TriState } from '@/components/forms';
import { CountrySelect } from '@/components/CountrySelect';
import { Pagination } from '@/components/Pagination';
import {
  Card,
  ErrorBanner,
  KeyValue,
  KeyValueList,
  Loading,
  Muted,
  PageHeader,
  Pill,
} from '@/components/primitives';
import { DateTime } from '@/components/Time';
import { keys, useAction, useFilters, usePaging } from '@/hooks';

const DEFAULTS = {
  q: undefined as string | undefined,
  countryCode: undefined as string | undefined,
  active: undefined as boolean | undefined,
};

/**
 * Zones decide serviceability, which is a question about the delivery address
 * and nothing else. Nothing on this screen knows or cares where a buyer pays
 * from.
 */
export function ZonesPage() {
  const navigate = useNavigate();
  const [filters, setFilters, reset] = useFilters(DEFAULTS);
  const { page, size, setPage, setSize } = usePaging();

  const query = useQuery({
    queryKey: [...keys.zones, filters, page, size],
    queryFn: () => zonesApi.search({ ...filters, page, size }),
  });

  const columns: Column<ZoneRow>[] = [
    {
      key: 'zone',
      header: 'Zone',
      render: (row) => (
        <>
          <strong className="mono">{row.code}</strong>
          <br />
          <Muted>{row.name}</Muted>
        </>
      ),
    },
    { key: 'country', header: 'Country', render: (row) => row.countryCode },
    {
      key: 'serviceable',
      header: 'Serviceable',
      render: (row) => (
        <>
          {row.serviceable ? <Pill tone="good">Yes</Pill> : <Pill tone="bad">No</Pill>}
          {row.unserviceableReason && (
            <>
              <br />
              <Muted>{row.unserviceableReason}</Muted>
            </>
          )}
        </>
      ),
    },
    { key: 'priority', header: 'Priority', align: 'right', render: (row) => row.priority },
    {
      key: 'shape',
      header: 'Shape',
      align: 'right',
      render: (row) => (
        <>
          {row.vertexCount} points
          <br />
          <Muted>
            {row.minLatitude?.toFixed(3)}…{row.maxLatitude?.toFixed(3)}
          </Muted>
        </>
      ),
    },
    {
      key: 'uses',
      header: 'Used by',
      align: 'right',
      render: (row) => (
        <>
          {row.rateCards} rate cards
          <br />
          <Muted>{row.drivers} drivers</Muted>
        </>
      ),
    },
    {
      key: 'active',
      header: 'Active',
      render: (row) => (row.active ? <Pill tone="good">Live</Pill> : <Pill tone="neutral">Off</Pill>),
    },
    { key: 'updated', header: 'Changed', render: (row) => <DateTime value={row.updatedAt} /> },
  ];

  return (
    <>
      <PageHeader
        title="Zones"
        description="Where the platform will deliver, and what carriage costs there. A zone answers a question about the delivery address — never about where the buyer is."
        actions={<CreateZone />}
      />

      <FilterBar onReset={reset}>
        <Field label="Search">
          <TextInput value={filters.q ?? ''} onChange={(q) => setFilters({ q: q || undefined })} />
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
        <Field label="Active">
          <TriState value={filters.active} onChange={(active) => setFilters({ active })} />
        </Field>
      </FilterBar>

      <DataTable
        columns={columns}
        rows={query.data?.content}
        rowKey={(row) => row.id}
        isLoading={query.isFetching}
        error={query.error}
        empty="No zones match those filters."
        onRowClick={(row) => navigate(`/zones/${row.id}`)}
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

export function ZoneDetailPage() {
  const zoneId = Number(useParams().zoneId);

  const query = useQuery({
    queryKey: keys.zone(zoneId),
    queryFn: () => zonesApi.detail(zoneId),
    enabled: Number.isFinite(zoneId),
  });

  if (query.isLoading) return <Loading what="Reading the zone" />;
  if (query.error) return <ErrorBanner error={query.error} />;
  if (!query.data) return null;

  const { zone, geometry } = query.data;

  return (
    <>
      <PageHeader
        title={`${zone.code} — ${zone.name}`}
        description={zone.description ?? undefined}
        actions={<EditZone zone={zone} geometry={geometry} />}
      />

      <Card title="Zone">
        <KeyValueList>
          <KeyValue label="Country">{zone.countryCode}</KeyValue>
          <KeyValue label="Serviceable">
            {zone.serviceable ? <Pill tone="good">Yes</Pill> : <Pill tone="bad">No</Pill>}
            {zone.unserviceableReason && <Muted> — {zone.unserviceableReason}</Muted>}
          </KeyValue>
          <KeyValue label="Priority" >
            {zone.priority} <Muted>(higher wins where zones overlap)</Muted>
          </KeyValue>
          <KeyValue label="Bounding box">
            <span className="mono">
              {zone.minLatitude?.toFixed(5)}, {zone.minLongitude?.toFixed(5)} →{' '}
              {zone.maxLatitude?.toFixed(5)}, {zone.maxLongitude?.toFixed(5)}
            </span>
          </KeyValue>
          <KeyValue label="Vertices">{zone.vertexCount}</KeyValue>
          <KeyValue label="Used by">
            {zone.rateCards} rate cards, {zone.drivers} drivers
          </KeyValue>
          <KeyValue label="Last changed">
            <DateTime value={zone.updatedAt} />
            {zone.lastEditedByUserId && <Muted> by user #{zone.lastEditedByUserId}</Muted>}
          </KeyValue>
        </KeyValueList>
      </Card>

      <Card
        title="GeoJSON"
        subtitle="Exactly as it was uploaded. Shown rather than redrawn, because a shape this console re-rendered is a shape nobody could compare against the file it came from."
      >
        <pre className="code-block">{format(geometry)}</pre>
      </Card>
    </>
  );
}

function format(geometry: string): string {
  try {
    return JSON.stringify(JSON.parse(geometry), null, 2);
  } catch {
    return geometry;
  }
}

function CreateZone() {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [countryCode, setCountryCode] = useState('');
  const [geometry, setGeometry] = useState('');
  const [serviceable, setServiceable] = useState(true);
  const [unserviceableReason, setUnserviceableReason] = useState('');
  const [priority, setPriority] = useState<number | ''>(0);

  const action = useAction(
    () =>
      zonesApi.create({
        code: code.trim().toUpperCase(),
        name: name.trim(),
        description: description.trim() || null,
        countryCode: countryCode.toUpperCase(),
        geometry: geometry.trim(),
        serviceable,
        unserviceableReason: serviceable ? null : unserviceableReason.trim() || null,
        priority: priority === '' ? null : priority,
      }),
    {
      invalidate: [keys.zones],
      message: (saved) =>
        `${saved.message} ${saved.vertexCount} points across ${saved.polygonCount} polygons; ${saved.zonesLive} zones live.`,
      onDone: (saved) => navigate(`/zones/${saved.id}`),
    },
  );

  return (
    <>
      <DecideButton onClick={() => setOpen(true)}>Draw a zone</DecideButton>
      <ActionModal
        open={open}
        width="wide"
        title="Draw a zone"
        description="Paste the GeoJSON. It is stored exactly as given, and the serviceability cache is reloaded when it is saved."
        submitLabel="Create the zone"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={!code.trim() || !name.trim() || !countryCode || !geometry.trim()}
      >
        <FormRow columns={3}>
          <Field label="Code" required hint="Short and stable — drivers and rate cards refer to it.">
            <TextInput value={code} required onChange={setCode} placeholder="BJL-CENTRAL" />
          </Field>
          <Field label="Name" required>
            <TextInput value={name} required onChange={setName} />
          </Field>
          <Field label="Country" required hint="Where deliveries in this zone are made.">
            <CountrySelect value={countryCode} purpose="ship" required onChange={setCountryCode} />
          </Field>
        </FormRow>

        <Field label="Description">
          <TextInput value={description} onChange={setDescription} />
        </Field>

        <FormRow>
          <Field label="Priority" hint="Higher wins where zones overlap.">
            <NumberInput value={priority} onChange={setPriority} />
          </Field>
          <div>
            <CheckBox
              checked={serviceable}
              onChange={setServiceable}
              label="Deliverable"
              hint="Turn off for a zone that is mapped but not served — the shape still matters for quoting and for saying why."
            />
          </div>
        </FormRow>

        {!serviceable && (
          <Field label="Why it is not served" hint="Shown to a buyer whose delivery address falls in it.">
            <TextInput value={unserviceableReason} onChange={setUnserviceableReason} />
          </Field>
        )}

        <Field label="GeoJSON" required>
          <TextArea
            value={geometry}
            required
            rows={10}
            onChange={setGeometry}
            placeholder='{"type":"Polygon","coordinates":[[[-16.70,13.44],…]]}'
          />
        </Field>
      </ActionModal>
    </>
  );
}

function EditZone({ zone, geometry }: { zone: ZoneRow; geometry: string }) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState(zone.name);
  const [description, setDescription] = useState(zone.description ?? '');
  const [shape, setShape] = useState(geometry);
  const [serviceable, setServiceable] = useState(zone.serviceable);
  const [unserviceableReason, setUnserviceableReason] = useState(zone.unserviceableReason ?? '');
  const [priority, setPriority] = useState<number | ''>(zone.priority);
  const [active, setActive] = useState(zone.active);

  const action = useAction(
    () =>
      zonesApi.patch(zone.id, {
        name: name.trim(),
        description: description.trim() || null,
        geometry: shape.trim() === geometry.trim() ? null : shape.trim(),
        serviceable,
        unserviceableReason: serviceable ? null : unserviceableReason.trim() || null,
        priority: priority === '' ? null : priority,
        active,
      }),
    {
      invalidate: [keys.zone(zone.id), keys.zones, keys.rateCards],
      message: (saved) => `${saved.message} ${saved.zonesLive} zones live.`,
    },
  );

  return (
    <>
      <DecideButton onClick={() => setOpen(true)}>Edit the zone</DecideButton>
      <ActionModal
        open={open}
        width="wide"
        title={`Edit ${zone.code}`}
        description="Saving reloads the serviceability cache. A zone used by rate cards changes what carriage is quoted at the moment it is saved."
        submitLabel="Save the zone"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
      >
        {zone.rateCards > 0 && (
          <p className="notice notice-warning">
            {zone.rateCards} rate {zone.rateCards === 1 ? 'card is' : 'cards are'} written against
            this zone, and {zone.drivers} {zone.drivers === 1 ? 'driver works' : 'drivers work'} in
            it.
          </p>
        )}

        <FormRow>
          <Field label="Name" required>
            <TextInput value={name} required onChange={setName} />
          </Field>
          <Field label="Priority">
            <NumberInput value={priority} onChange={setPriority} />
          </Field>
        </FormRow>

        <Field label="Description">
          <TextInput value={description} onChange={setDescription} />
        </Field>

        <CheckBox checked={active} onChange={setActive} label="Active" />
        <CheckBox
          checked={serviceable}
          onChange={setServiceable}
          label="Deliverable"
          hint="Turning this off stops the platform offering delivery to addresses inside it."
        />

        {!serviceable && (
          <Field label="Why it is not served">
            <TextInput value={unserviceableReason} onChange={setUnserviceableReason} />
          </Field>
        )}

        <Field label="GeoJSON" hint="Leave as it is to keep the shape unchanged.">
          <TextArea value={shape} rows={12} onChange={setShape} />
        </Field>
      </ActionModal>
    </>
  );
}
