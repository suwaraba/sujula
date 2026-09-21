import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { rateCards as rateCardsApi } from '@/api/endpoints';
import { DELIVERY_MODES } from '@/api/enums';
import type { RateCardRow } from '@/api/types';
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
import { Money } from '@/components/Money';
import { Pagination } from '@/components/Pagination';
import { Card, Muted, PageHeader, Pill, humanise } from '@/components/primitives';
import { DateOnly, today } from '@/components/Time';
import { currencyInfo, knownCurrencies } from '@/money/currency';
import { keys, useAction, useFilters, usePaging } from '@/hooks';

const DEFAULTS = {
  zoneId: undefined as number | undefined,
  countryCode: undefined as string | undefined,
  mode: undefined as string | undefined,
  activeOnly: undefined as boolean | undefined,
};

export function RateCardsPage() {
  const [filters, setFilters, reset] = useFilters(DEFAULTS);
  const { page, size, setPage, setSize } = usePaging();

  const query = useQuery({
    queryKey: [...keys.rateCards, filters, page, size],
    queryFn: () => rateCardsApi.search({ ...filters, mode: filters.mode as never, page, size }),
  });

  const preview = useQuery({
    queryKey: [...keys.ratePreview, filters.zoneId, filters.countryCode],
    queryFn: () => rateCardsApi.preview({ zoneId: filters.zoneId, countryCode: filters.countryCode }),
  });

  const columns: Column<RateCardRow>[] = [
    {
      key: 'card',
      header: 'Card',
      render: (row) => (
        <>
          <strong>{row.name}</strong>
          <br />
          <Muted>
            {row.zoneCode ?? row.countryCode ?? 'everywhere'} ·{' '}
            {row.mode ? humanise(row.mode) : 'any mode'}
          </Muted>
        </>
      ),
    },
    {
      key: 'window',
      header: 'In force',
      render: (row) => (
        <>
          <DateOnly value={row.effectiveFrom} /> →{' '}
          {row.effectiveUntil ? <DateOnly value={row.effectiveUntil} /> : <Muted>open</Muted>}
          <br />
          {row.inForceToday ? <Pill tone="good">Today</Pill> : <Muted>not today</Muted>}
          {!row.active && <Pill tone="neutral">Closed</Pill>}
        </>
      ),
    },
    {
      key: 'base',
      header: 'Base',
      align: 'right',
      render: (row) => <Money amount={row.baseFee} currency={row.currency} />,
    },
    {
      key: 'distance',
      header: 'Distance',
      align: 'right',
      render: (row) => (
        <>
          <Muted>{row.includedKm ?? 0} km included</Muted>
          <br />
          <Money amount={row.perKm} currency={row.currency} /> <Muted>/km</Muted>
        </>
      ),
    },
    {
      key: 'weight',
      header: 'Weight',
      align: 'right',
      render: (row) => (
        <>
          <Muted>{row.includedKg ?? 0} kg included</Muted>
          <br />
          <Money amount={row.perKg} currency={row.currency} /> <Muted>/kg</Muted>
        </>
      ),
    },
    {
      key: 'bounds',
      header: 'Bounds',
      align: 'right',
      render: (row) => (
        <>
          <Muted>min</Muted> <Money amount={row.minFee} currency={row.currency} />
          <br />
          <Muted>max</Muted>{' '}
          {row.maxFee === null ? <Muted>uncapped</Muted> : <Money amount={row.maxFee} currency={row.currency} />}
          {row.freeAbove !== null && (
            <>
              <br />
              <Muted>free above</Muted> <Money amount={row.freeAbove} currency={row.currency} />
            </>
          )}
        </>
      ),
    },
    { key: 'actions', header: '', render: (row) => <PatchCard card={row} /> },
  ];

  return (
    <>
      <PageHeader
        title="Rate cards"
        description="What carriage costs, and from when. Cards are written forward: a quote already given to a buyer was given at the card in force that day, and a new card does not reach back for it."
        actions={<CreateCard />}
      />

      <FilterBar onReset={reset}>
        <Field label="Zone id">
          <NumberInput
            value={filters.zoneId ?? ''}
            onChange={(zoneId) => setFilters({ zoneId: zoneId === '' ? undefined : zoneId })}
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
        <Field label="Mode">
          <Select
            value={(filters.mode ?? '') as string}
            options={DELIVERY_MODES}
            placeholder="Any mode"
            onChange={(mode) => setFilters({ mode: mode || undefined })}
          />
        </Field>
        <Field label="In force only">
          <TriState value={filters.activeOnly} onChange={(activeOnly) => setFilters({ activeOnly })} />
        </Field>
      </FilterBar>

      {preview.data && preview.data.length > 0 && (
        <Card
          title="What the live cards would charge"
          subtitle="Three sample legs, so a card can be read as money rather than as coefficients."
        >
          <table className="table table-compact">
            <thead>
              <tr>
                <th scope="col">Card</th>
                <th scope="col">From</th>
                <th scope="col" className="align-right">
                  5 km, 1 kg
                </th>
                <th scope="col" className="align-right">
                  20 km, 3 kg
                </th>
                <th scope="col" className="align-right">
                  100 km, 10 kg
                </th>
              </tr>
            </thead>
            <tbody>
              {preview.data.map((line, index) => (
                <tr key={line.cardId ?? index}>
                  <th scope="row">{line.cardName ?? <Muted>default pricing</Muted>}</th>
                  <td>
                    <Muted>{humanise(line.source)}</Muted>
                  </td>
                  <td className="align-right">
                    <Money amount={line.fiveKmOneKg} currency={line.currency} />
                  </td>
                  <td className="align-right">
                    <Money amount={line.twentyKmThreeKg} currency={line.currency} />
                  </td>
                  <td className="align-right">
                    <Money amount={line.hundredKmTenKg} currency={line.currency} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}

      <DataTable
        columns={columns}
        rows={query.data?.content}
        rowKey={(row) => row.id}
        isLoading={query.isFetching}
        error={query.error}
        empty="No rate cards match those filters."
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

function CreateCard() {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const [zoneId, setZoneId] = useState<number | ''>('');
  const [countryCode, setCountryCode] = useState('');
  const [mode, setMode] = useState<string>('');
  const [currency, setCurrency] = useState('');
  const [baseFee, setBaseFee] = useState('');
  const [includedKm, setIncludedKm] = useState('');
  const [perKm, setPerKm] = useState('');
  const [includedKg, setIncludedKg] = useState('');
  const [perKg, setPerKg] = useState('');
  const [minFee, setMinFee] = useState('');
  const [maxFee, setMaxFee] = useState('');
  const [freeAbove, setFreeAbove] = useState('');
  const [effectiveFrom, setEffectiveFrom] = useState(today());
  const [note, setNote] = useState('');

  const action = useAction(
    () =>
      rateCardsApi.create({
        name: name.trim(),
        zoneId: zoneId === '' ? null : zoneId,
        countryCode: countryCode ? countryCode.toUpperCase() : null,
        mode: mode ? (mode as never) : null,
        currency,
        baseFee,
        includedKm: includedKm || null,
        perKm: perKm || null,
        includedKg: includedKg || null,
        perKg: perKg || null,
        minFee: minFee || null,
        maxFee: maxFee || null,
        freeAbove: freeAbove || null,
        effectiveFrom,
        note: note.trim() || null,
      }),
    {
      invalidate: [keys.rateCards, keys.ratePreview],
      message: (saved) =>
        saved.supersededCardId
          ? `${saved.message} Card #${saved.supersededCardId} now ends on ${saved.supersededEndsOn}.`
          : saved.message,
    },
  );

  const scale = currencyInfo(currency)?.minorUnits ?? 2;

  return (
    <>
      <DecideButton onClick={() => setOpen(true)}>Write a card</DecideButton>
      <ActionModal
        open={open}
        width="wide"
        title="Write a rate card, effective from a date"
        description="A card that starts while another is running closes the old one on the day before. Nothing is ever backdated — a quote already given stands."
        submitLabel="Write the card"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={!name.trim() || !currency || !baseFee || !effectiveFrom}
      >
        <FormRow>
          <Field label="Name" required>
            <TextInput value={name} required onChange={setName} />
          </Field>
          <Field label="Effective from" required>
            <input
              className="input"
              type="date"
              required
              value={effectiveFrom}
              onChange={(event) => setEffectiveFrom(event.target.value)}
            />
          </Field>
        </FormRow>

        <FormRow columns={3}>
          <Field label="Zone id" hint="Leave empty to apply to a whole country.">
            <NumberInput value={zoneId} onChange={setZoneId} />
          </Field>
          <Field label="Country" hint="Used when no zone is given.">
            <TextInput value={countryCode} maxLength={2} onChange={setCountryCode} />
          </Field>
          <Field label="Mode" hint="Empty applies to every mode.">
            <Select value={mode} options={DELIVERY_MODES} placeholder="Any mode" onChange={setMode} />
          </Field>
        </FormRow>

        <FormRow>
          <Field
            label="Currency"
            required
            hint="Carriage is priced in one currency and converted for the buyer, like everything else."
          >
            <Select
              value={currency}
              required
              placeholder="Choose"
              options={knownCurrencies().map((entry) => ({ value: entry.code, label: entry.code }))}
              onChange={setCurrency}
            />
          </Field>
          <Field label="Base fee" required>
            <MoneyInput value={baseFee} required minorUnits={scale} min={0} onChange={setBaseFee} />
          </Field>
        </FormRow>

        <FormRow columns={4}>
          <Field label="Included km">
            <NumberInput
              value={includedKm === '' ? '' : Number(includedKm)}
              min={0}
              onChange={(value) => setIncludedKm(value === '' ? '' : String(value))}
            />
          </Field>
          <Field label="Per km beyond">
            <MoneyInput value={perKm} minorUnits={scale} min={0} onChange={setPerKm} />
          </Field>
          <Field label="Included kg">
            <NumberInput
              value={includedKg === '' ? '' : Number(includedKg)}
              min={0}
              step="0.1"
              onChange={(value) => setIncludedKg(value === '' ? '' : String(value))}
            />
          </Field>
          <Field label="Per kg beyond">
            <MoneyInput value={perKg} minorUnits={scale} min={0} onChange={setPerKg} />
          </Field>
        </FormRow>

        <FormRow columns={3}>
          <Field label="Minimum fee">
            <MoneyInput value={minFee} minorUnits={scale} min={0} onChange={setMinFee} />
          </Field>
          <Field label="Maximum fee" hint="Empty is uncapped.">
            <MoneyInput value={maxFee} minorUnits={scale} min={0} onChange={setMaxFee} />
          </Field>
          <Field label="Free above" hint="Basket value with one vendor.">
            <MoneyInput value={freeAbove} minorUnits={scale} min={0} onChange={setFreeAbove} />
          </Field>
        </FormRow>

        <Field label="Note">
          <TextArea value={note} onChange={setNote} />
        </Field>
      </ActionModal>
    </>
  );
}

function PatchCard({ card }: { card: RateCardRow }) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState(card.name);
  const [note, setNote] = useState(card.note ?? '');
  const [effectiveUntil, setEffectiveUntil] = useState(card.effectiveUntil ?? '');
  const [active, setActive] = useState(card.active);

  const action = useAction(
    () =>
      rateCardsApi.patch(card.id, {
        name: name.trim(),
        note: note.trim() || null,
        effectiveUntil: effectiveUntil || null,
        active,
      }),
    { invalidate: [keys.rateCards, keys.ratePreview], message: (saved) => saved.message },
  );

  return (
    <>
      <DecideButton variant="ghost" onClick={() => setOpen(true)}>
        Edit
      </DecideButton>
      <ActionModal
        open={open}
        title={`Edit “${card.name}”`}
        description="A card's numbers cannot be edited once written — write a new one from a date instead. What can change is its name, its note, and when it stops."
        submitLabel="Save"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
      >
        <Field label="Name" required>
          <TextInput value={name} required onChange={setName} />
        </Field>
        <Field label="Close it from" hint="The last day it applies. Leave empty to leave it open.">
          <input
            className="input"
            type="date"
            value={effectiveUntil}
            onChange={(event) => setEffectiveUntil(event.target.value)}
          />
        </Field>
        <CheckBox checked={active} onChange={setActive} label="Active" />
        <Field label="Note">
          <TextArea value={note} onChange={setNote} />
        </Field>
      </ActionModal>
    </>
  );
}
