import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { reports as reportsApi } from '@/api/endpoints';
import { REPORT_EXPORT_STATUSES, REPORT_TYPES, type ReportType } from '@/api/enums';
import type { ExportRow } from '@/api/types';
import { ActionModal } from '@/components/ActionModal';
import { DataTable, type Column } from '@/components/DataTable';
import { DecideButton } from '@/components/Decide';
import { Field, FilterBar, FormRow, NumberInput, Select, TextInput } from '@/components/forms';
import { Money } from '@/components/Money';
import { Pagination } from '@/components/Pagination';
import { Card, ErrorBanner, Loading, Muted, PageHeader, StatusPill } from '@/components/primitives';
import { DateOnly, DateTime } from '@/components/Time';
import { keys, useAction, useFilters, usePaging } from '@/hooks';

const REVENUE_DEFAULTS = {
  from: undefined as string | undefined,
  to: undefined as string | undefined,
  vendorId: undefined as number | undefined,
};

/**
 * Commission, refunds and FX margin.
 *
 * Per settlement currency, and never rolled into one figure. The FX margin
 * line carries its own currency for the same reason: margin taken converting
 * EUR into GMD is denominated in one of them, and which one it is matters to
 * whoever has to file it.
 */
export function RevenuePage() {
  const [filters, setFilters, reset] = useFilters(REVENUE_DEFAULTS);

  const query = useQuery({
    queryKey: [...keys.revenue, filters],
    queryFn: () => reportsApi.revenue(filters),
  });

  return (
    <>
      <PageHeader
        title="Revenue"
        description="What the platform earned, per settlement currency. Nothing here is converted into a single reporting currency — that conversion is somebody's accounting decision, not this screen's."
        actions={<RequestExport defaultType="REVENUE" />}
      />

      <FilterBar onReset={reset}>
        <Field label="From">
          <TextInput type="date" value={filters.from ?? ''} onChange={(from) => setFilters({ from: from || undefined })} />
        </Field>
        <Field label="To">
          <TextInput type="date" value={filters.to ?? ''} onChange={(to) => setFilters({ to: to || undefined })} />
        </Field>
        <Field label="Vendor id">
          <NumberInput
            value={filters.vendorId ?? ''}
            onChange={(vendorId) => setFilters({ vendorId: vendorId === '' ? undefined : vendorId })}
          />
        </Field>
      </FilterBar>

      <ErrorBanner error={query.error} />
      {query.isLoading && <Loading what="Adding it up" />}

      {query.data && (
        <Card
          title={`${query.data.fromDate} to ${query.data.toDate}`}
          subtitle={query.data.message}
        >
          {query.data.lines.length === 0 ? (
            <Muted>Nothing in that window.</Muted>
          ) : (
            <div className="table-scroll">
              <table className="table">
                <thead>
                  <tr>
                    <th scope="col">Currency</th>
                    <th scope="col" className="align-right">
                      Gross sales
                    </th>
                    <th scope="col" className="align-right">
                      Commission
                    </th>
                    <th scope="col" className="align-right">
                      Commission reversed
                    </th>
                    <th scope="col" className="align-right">
                      Refunds
                    </th>
                    <th scope="col" className="align-right">
                      Net commission
                    </th>
                    <th scope="col" className="align-right">
                      FX margin
                    </th>
                    <th scope="col" className="align-right">
                      Orders
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {query.data.lines.map((line) => (
                    <tr key={line.currency}>
                      <th scope="row">{line.currency}</th>
                      <td className="align-right">
                        <Money amount={line.grossSales} currency={line.currency} />
                      </td>
                      <td className="align-right">
                        <Money amount={line.commission} currency={line.currency} />
                      </td>
                      <td className="align-right">
                        <Money amount={line.commissionReversed} currency={line.currency} />
                      </td>
                      <td className="align-right">
                        <Money amount={line.refunds} currency={line.currency} />
                      </td>
                      <td className="align-right">
                        <strong>
                          <Money amount={line.netCommission} currency={line.currency} />
                        </strong>
                      </td>
                      <td className="align-right">
                        <Money amount={line.fxMargin} currency={line.fxMarginCurrency} />
                      </td>
                      <td className="align-right">{line.orderCount.toLocaleString()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      )}
    </>
  );
}

const EXPORT_DEFAULTS = {
  requestedBy: undefined as number | undefined,
  status: undefined as string | undefined,
};

export function ExportsPage() {
  const [filters, setFilters, reset] = useFilters(EXPORT_DEFAULTS);
  const { page, size, setPage, setSize } = usePaging();

  const query = useQuery({
    queryKey: [...keys.exports, filters, page, size],
    queryFn: () => reportsApi.exports({ ...filters, status: filters.status as never, page, size }),
    refetchInterval: 15_000,
  });

  const columns: Column<ExportRow>[] = [
    {
      key: 'reference',
      header: 'Export',
      render: (row) => (
        <>
          <strong className="mono">{row.reference}</strong>
          <br />
          <Muted>
            {row.type} · {row.format ?? 'csv'}
          </Muted>
        </>
      ),
    },
    { key: 'status', header: 'Status', render: (row) => <StatusPill status={row.status} /> },
    {
      key: 'who',
      header: 'Asked for by',
      render: (row) => (
        <>
          {row.requestedByEmail}
          <br />
          <Muted>
            <DateTime value={row.createdAt} />
          </Muted>
        </>
      ),
    },
    {
      key: 'window',
      header: 'Window',
      render: (row) => (
        <>
          <DateOnly value={row.fromDate} /> → <DateOnly value={row.toDate} />
          {row.currency && <Muted> · {row.currency}</Muted>}
          {row.vendorId && <Muted> · vendor #{row.vendorId}</Muted>}
        </>
      ),
    },
    {
      key: 'rows',
      header: 'Rows',
      align: 'right',
      render: (row) => (row.rowCount === null ? <Muted>—</Muted> : row.rowCount.toLocaleString()),
    },
    {
      key: 'result',
      header: 'File',
      render: (row) =>
        row.resultUrl ? (
          <>
            <a className="button button-ghost" href={row.resultUrl} target="_blank" rel="noreferrer">
              Download
            </a>
            {row.resultExpiresAt && (
              <>
                <br />
                <Muted>
                  expires <DateTime value={row.resultExpiresAt} />
                </Muted>
              </>
            )}
          </>
        ) : row.failureReason ? (
          <span className="overdue">{row.failureReason}</span>
        ) : (
          <Muted>being built</Muted>
        ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Exports"
        description="Who has exported what. These files carry names, addresses and bank details, so the links expire and who asked for each one is on the record."
        actions={<RequestExport />}
      />

      <FilterBar onReset={reset}>
        <Field label="Status">
          <Select
            value={(filters.status ?? '') as string}
            options={REPORT_EXPORT_STATUSES}
            placeholder="Any status"
            onChange={(status) => setFilters({ status: status || undefined })}
          />
        </Field>
        <Field label="Asked for by" hint="user id">
          <NumberInput
            value={filters.requestedBy ?? ''}
            onChange={(requestedBy) =>
              setFilters({ requestedBy: requestedBy === '' ? undefined : requestedBy })
            }
          />
        </Field>
      </FilterBar>

      <DataTable
        columns={columns}
        rows={query.data?.content}
        rowKey={(row) => row.exportId}
        isLoading={query.isFetching}
        error={query.error}
        empty="Nothing has been exported."
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

function RequestExport({ defaultType }: { defaultType?: ReportType }) {
  const [open, setOpen] = useState(false);
  const [type, setType] = useState<string>(defaultType ?? '');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [currency, setCurrency] = useState('');
  const [vendorId, setVendorId] = useState<number | ''>('');
  const [format, setFormat] = useState('csv');

  const action = useAction(
    () =>
      reportsApi.requestExport(type as ReportType, {
        type: type as ReportType,
        fromDate: fromDate || null,
        toDate: toDate || null,
        currency: currency ? currency.toUpperCase() : null,
        vendorId: vendorId === '' ? null : vendorId,
        format,
      }),
    {
      invalidate: [keys.exports],
      message: (queued) =>
        `${queued.message} ${queued.remainingInWindow} more exports allowed in this window.`,
    },
  );

  return (
    <>
      <DecideButton variant="secondary" onClick={() => setOpen(true)}>
        Ask for a finance file
      </DecideButton>
      <ActionModal
        open={open}
        title="Ask for a finance file"
        description="Built in the background. The link that comes back is short-lived, because the file carries bank details and home addresses."
        submitLabel="Queue it"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={!type}
      >
        <FormRow>
          <Field label="Report" required>
            <Select value={type} required options={REPORT_TYPES} placeholder="Choose a report" onChange={setType} />
          </Field>
          <Field label="Format">
            <Select
              value={format}
              options={[
                { value: 'csv', label: 'CSV' },
                { value: 'xlsx', label: 'Excel' },
              ]}
              onChange={(next) => setFormat(next || 'csv')}
            />
          </Field>
        </FormRow>

        <FormRow>
          <Field label="From">
            <input className="input" type="date" value={fromDate} onChange={(e) => setFromDate(e.target.value)} />
          </Field>
          <Field label="To">
            <input className="input" type="date" value={toDate} onChange={(e) => setToDate(e.target.value)} />
          </Field>
        </FormRow>

        <FormRow>
          <Field label="Currency" hint="Optional. Narrows the file to one settlement currency.">
            <TextInput value={currency} maxLength={3} onChange={setCurrency} />
          </Field>
          <Field label="Vendor id" hint="Optional.">
            <NumberInput value={vendorId} onChange={setVendorId} />
          </Field>
        </FormRow>
      </ActionModal>
    </>
  );
}
