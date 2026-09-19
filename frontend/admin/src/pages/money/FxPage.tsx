import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { fx as fxApi } from '@/api/endpoints';
import type { RateRow, SpreadRow } from '@/api/types';
import { ActionModal } from '@/components/ActionModal';
import { DataTable, type Column } from '@/components/DataTable';
import { DecideButton } from '@/components/Decide';
import { Field, FilterBar, FormRow, NumberInput, Select, TextArea, TextInput } from '@/components/forms';
import { Rate } from '@/components/Money';
import { Pagination } from '@/components/Pagination';
import { Card, ErrorBanner, Loading, Muted, PageHeader, Pill } from '@/components/primitives';
import { DateOnly, DateTime, nowLocalInput, toLocalDateTime } from '@/components/Time';
import { formatBasisPoints } from '@/money/currency';
import { knownCurrencies } from '@/money/currency';
import { keys, useAction, useFilters, usePaging } from '@/hooks';

const DEFAULTS = {
  currency: undefined as string | undefined,
  from: undefined as string | undefined,
  to: undefined as string | undefined,
};

/**
 * Rates and the spread that applies to them.
 *
 * Nothing on this screen re-prices an existing order. A rate published today is
 * what tomorrow's orders will be converted at; every order already placed
 * carries the rate it was snapshotted with, and that number never moves again.
 * The history is here because somebody will eventually be asked to explain a
 * figure on an invoice from four months ago.
 */
export function FxPage() {
  const [filters, setFilters, reset] = useFilters(DEFAULTS);
  const { page, size, setPage, setSize } = usePaging();

  const rates = useQuery({
    queryKey: [...keys.fxRates, filters, page, size],
    queryFn: () => fxApi.rates({ ...filters, page, size }),
  });

  const spreads = useQuery({ queryKey: keys.fxSpread, queryFn: fxApi.spreads });

  const columns: Column<RateRow>[] = [
    {
      key: 'pair',
      header: 'Pair',
      render: (row) => (
        <strong className="mono">
          {row.fromCurrency}→{row.toCurrency}
        </strong>
      ),
    },
    { key: 'date', header: 'For', render: (row) => <DateOnly value={row.rateDate} /> },
    { key: 'rate', header: 'Published', align: 'right', render: (row) => <Rate value={row.rate} /> },
    {
      key: 'spread',
      header: 'Spread that day',
      align: 'right',
      render: (row) => <Muted>{formatBasisPoints(row.spreadBasisPoints)}</Muted>,
    },
    {
      key: 'effective',
      header: 'Charged at',
      align: 'right',
      render: (row) => <Rate value={row.rateWithSpread} />,
    },
    { key: 'recorded', header: 'Recorded', render: (row) => <DateTime value={row.recordedAt} /> },
  ];

  return (
    <>
      <PageHeader
        title="FX rates and spread"
        description="Rates are published forward. An order already placed keeps the rate it was snapshotted at — nothing here can change what somebody was charged."
        actions={
          <div className="row-actions">
            <RefreshRates />
            <SetSpread />
          </div>
        }
      />

      <Card title="Spreads" subtitle="Every spread ever set, and which one is live.">
        {spreads.isLoading && <Loading />}
        <ErrorBanner error={spreads.error} />
        {spreads.data && <SpreadTable spreads={spreads.data} />}
      </Card>

      <Card title="Rate history">
        <FilterBar onReset={reset}>
          <Field label="Currency">
            <TextInput
              value={filters.currency ?? ''}
              maxLength={3}
              placeholder="EUR"
              onChange={(currency) =>
                setFilters({ currency: currency ? currency.toUpperCase() : undefined })
              }
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
          rows={rates.data?.content}
          rowKey={(row) => row.id}
          isLoading={rates.isFetching}
          error={rates.error}
          empty="No rates recorded for that filter."
        />

        {rates.data && (
          <Pagination
            page={rates.data.page}
            size={rates.data.size}
            totalElements={rates.data.totalElements}
            totalPages={rates.data.totalPages}
            last={rates.data.last}
            onPage={setPage}
            onSize={setSize}
          />
        )}
      </Card>
    </>
  );
}

function SpreadTable({ spreads }: { spreads: SpreadRow[] }) {
  if (spreads.length === 0) return <Muted>No spread has ever been set. Rates are passed through.</Muted>;
  return (
    <table className="table table-compact">
      <thead>
        <tr>
          <th scope="col">Pair</th>
          <th scope="col" className="align-right">
            Spread
          </th>
          <th scope="col">From</th>
          <th scope="col">Reason</th>
          <th scope="col" />
        </tr>
      </thead>
      <tbody>
        {spreads.map((spread) => (
          <tr key={spread.id}>
            <th scope="row" className="mono">
              {spread.fromCurrency}→{spread.toCurrency}
            </th>
            <td className="align-right">
              {formatBasisPoints(spread.basisPoints)} <Muted>{spread.asPercentage}</Muted>
            </td>
            <td>
              <DateTime value={spread.effectiveFrom} />
            </td>
            <td>{spread.reason ?? <Muted>—</Muted>}</td>
            <td>{spread.inForceNow && <Pill tone="good">Live</Pill>}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function RefreshRates() {
  const [open, setOpen] = useState(false);
  const [currencies, setCurrencies] = useState('');
  const [note, setNote] = useState('');

  const action = useAction(
    () =>
      fxApi.refresh({
        currencies: currencies.trim()
          ? currencies
              .split(/[\s,]+/)
              .filter(Boolean)
              .map((code) => code.toUpperCase())
          : null,
        note: note.trim() || null,
      }),
    {
      invalidate: [keys.fxRates],
      message: (refreshed) => refreshed.message,
    },
  );

  return (
    <>
      <DecideButton variant="secondary" onClick={() => setOpen(true)}>
        Pull rates now
      </DecideButton>
      <ActionModal
        open={open}
        title="Pull rates now"
        description="Fetches today's rates from the provider. Orders already placed are untouched — this only changes what the next conversion will use."
        submitLabel="Pull them"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
      >
        <Field label="Only these currencies" hint="Codes, separated by spaces. Empty means every pair.">
          <TextInput value={currencies} onChange={setCurrencies} placeholder="EUR GBP XOF" />
        </Field>
        <Field label="Note">
          <TextArea value={note} onChange={setNote} />
        </Field>
      </ActionModal>
    </>
  );
}

function SetSpread() {
  const [open, setOpen] = useState(false);
  const [fromCurrency, setFromCurrency] = useState('');
  const [toCurrency, setToCurrency] = useState('');
  const [basisPoints, setBasisPoints] = useState<number | ''>('');
  const [effectiveFrom, setEffectiveFrom] = useState(nowLocalInput());
  const [reason, setReason] = useState('');

  const action = useAction(
    () =>
      fxApi.setSpread({
        fromCurrency,
        toCurrency,
        basisPoints: Number(basisPoints),
        effectiveFrom: toLocalDateTime(effectiveFrom),
        reason: reason.trim(),
      }),
    {
      invalidate: [keys.fxSpread, keys.fxRates],
      message: (set) => set.message,
    },
  );

  const codes = knownCurrencies().map((entry) => ({
    value: entry.code,
    label: `${entry.code} — ${entry.name}`,
  }));

  return (
    <>
      <DecideButton onClick={() => setOpen(true)}>Set a spread</DecideButton>
      <ActionModal
        open={open}
        title="Set the platform's spread from a moment"
        description="From a moment, never retroactively. Every conversion already made kept the spread that was live when it was made, and changing that would re-price orders nobody can explain."
        submitLabel="Set it"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={!fromCurrency || !toCurrency || basisPoints === '' || reason.trim().length < 5}
      >
        <FormRow>
          <Field label="From currency" required hint="What the buyer is charged in.">
            <Select value={fromCurrency} required options={codes} placeholder="Choose" onChange={setFromCurrency} />
          </Field>
          <Field label="To currency" required hint="What the vendor is paid in.">
            <Select value={toCurrency} required options={codes} placeholder="Choose" onChange={setToCurrency} />
          </Field>
        </FormRow>

        <FormRow>
          <Field label="Spread" required hint="In basis points. 150 is 1.50%.">
            <NumberInput value={basisPoints} required min={0} max={10000} onChange={setBasisPoints} />
          </Field>
          <Field label="Effective from" required>
            <input
              className="input"
              type="datetime-local"
              required
              value={effectiveFrom}
              onChange={(event) => setEffectiveFrom(event.target.value)}
            />
          </Field>
        </FormRow>

        <Field label="Reason" required>
          <TextArea value={reason} required minLength={5} onChange={setReason} />
        </Field>
      </ActionModal>
    </>
  );
}
