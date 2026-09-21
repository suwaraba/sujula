import { useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { catalogueApi } from '@/api/endpoints/catalogue';
import { ApiError } from '@/api/errors';
import { useIdempotencyKey } from '@/lib/hooks';
import { formatDateTime, formatNumber, humanise } from '@/lib/format';
import type { CatalogueJob } from '@/api/types';
import {
  Badge, Button, Card, EmptyState, Notice, PageHeader, Skeleton, Stat, type Tone,
} from '@/components/ui';
import { CheckField } from '@/components/form';
import { Pagination } from '@/components/Pagination';
import { useToast } from '@/components/Toast';

const ACCEPTED = [
  'text/csv',
  'application/vnd.ms-excel',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
];
const MAX_BYTES = 10 * 1024 * 1024;

const JOB_TONES: Record<string, Tone> = {
  QUEUED: 'info',
  RUNNING: 'info',
  SUCCEEDED: 'ok',
  PARTIAL: 'warn',
  FAILED: 'danger',
};

/** A job that is still moving is worth asking about again. */
function isFinished(status: string): boolean {
  return status === 'SUCCEEDED' || status === 'FAILED' || status === 'PARTIAL';
}

export function BulkCatalogue() {
  const [reference, setReference] = useState<string | null>(null);
  const [errorPage, setErrorPage] = useState(0);
  const [updateExisting, setUpdateExisting] = useState(false);
  const [uploading, setUploading] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const queryClient = useQueryClient();
  const toast = useToast();
  const [key, resetKey] = useIdempotencyKey();

  const template = useQuery({
    queryKey: ['import-template'],
    queryFn: () => catalogueApi.importTemplate(),
    staleTime: 60 * 60 * 1000,
  });

  const job = useQuery({
    queryKey: ['catalogue-job', reference, errorPage],
    queryFn: () => catalogueApi.job(reference!, { page: errorPage, size: 25 }),
    enabled: reference != null,
    // Poll while it is still working, then stop. A finished job does not change.
    refetchInterval: (query) =>
      query.state.data && isFinished(query.state.data.status) ? false : 2000,
  });

  const upload = useMutation({
    mutationFn: async (file: File) => {
      const looksRight =
        ACCEPTED.includes(file.type) || /\.(csv|xlsx|xls)$/i.test(file.name);
      if (!looksRight) {
        throw new ApiError({
          status: 400, path: 'upload',
          message: 'Send a CSV or an Excel file.',
        });
      }
      if (file.size > MAX_BYTES) {
        throw new ApiError({
          status: 413, path: 'upload',
          message: 'That file is larger than 10MB. Split it and import in two goes.',
        });
      }

      const presigned = await catalogueApi.presignImage(file.type || 'text/csv');
      const put = await fetch(presigned.uploadUrl, {
        method: 'PUT',
        body: file,
        headers: { 'Content-Type': file.type || 'text/csv' },
      });
      if (!put.ok) {
        throw new ApiError({
          status: put.status, path: 'storage',
          message: 'The file did not upload. Check your connection and try again.',
        });
      }

      return catalogueApi.bulkImport(
        {
          fileUrl: presigned.publicUrl,
          originalFilename: file.name,
          format: /\.xlsx?$/i.test(file.name) ? 'xlsx' : 'csv',
          updateExisting,
        },
        key,
      );
    },
    onSuccess: (started) => {
      resetKey();
      setErrorPage(0);
      setReference(started.reference);
      toast.success('Reading your spreadsheet. This page follows along.');
      void queryClient.invalidateQueries({ queryKey: ['products'] });
    },
    onError: (error) => {
      resetKey();
      toast.error(error instanceof ApiError ? error.message : 'Could not start the import.');
    },
    onSettled: () => {
      setUploading(false);
      if (fileInput.current) fileInput.current.value = '';
    },
  });

  const exportJob = useMutation({
    mutationFn: (includeArchived: boolean) => catalogueApi.requestExport(includeArchived),
    onSuccess: (started) => {
      setErrorPage(0);
      setReference(started.reference);
      toast.success('Preparing your file.');
    },
    onError: (error) => {
      toast.error(error instanceof ApiError ? error.message : 'Could not start the export.');
    },
  });

  return (
    <div className="page stack stack--loose">
      <PageHeader
        title="Import and export"
        subtitle="A spreadsheet in, a spreadsheet out. Both run in the background."
        actions={<Link to="/products" className="btn btn--secondary">← Products</Link>}
      />

      <Card title="Import listings">
        <div className="stack">
          <Notice tone="info" title="Everything imported arrives as a draft">
            Nothing goes on sale from a spreadsheet. Each row becomes a draft listing you then
            send to be checked, exactly as if you had typed it.
          </Notice>

          {template.isLoading ? (
            <Skeleton height={80} />
          ) : template.data ? (
            <div className="stack stack--tight">
              <div className="field__label">Columns it understands</div>
              <div className="row" style={{ gap: 4, flexWrap: 'wrap' }}>
                {template.data.requiredColumns.map((column) => (
                  <Badge key={column} tone="accent">{column}</Badge>
                ))}
                {template.data.optionalColumns.map((column) => (
                  <Badge key={column}>{column}</Badge>
                ))}
              </div>
              <p className="small muted">
                The green ones are required. Put them in the first row of your sheet, spelled
                exactly like that. Anything else is ignored rather than guessed at.
              </p>
            </div>
          ) : null}

          <CheckField
            label="Update listings whose code already exists"
            hint="Off, a row whose SKU you already use is refused rather than overwriting what is there."
            checked={updateExisting}
            onChange={(event) => setUpdateExisting(event.target.checked)}
          />

          <input
            ref={fileInput}
            type="file"
            accept=".csv,.xlsx,.xls,text/csv"
            className="sr-only"
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (!file) return;
              setUploading(true);
              upload.mutate(file);
            }}
          />

          <Button
            variant="primary"
            onClick={() => fileInput.current?.click()}
            busy={uploading}
          >
            Choose a spreadsheet
          </Button>
        </div>
      </Card>

      <Card title="Export your catalogue">
        <div className="stack">
          <p className="muted">
            Every listing you have, as a file you can edit and import back.
          </p>
          <div className="row">
            <Button
              variant="secondary"
              onClick={() => exportJob.mutate(false)}
              busy={exportJob.isPending}
            >
              Export what is live
            </Button>
            <Button
              variant="ghost"
              onClick={() => exportJob.mutate(true)}
              busy={exportJob.isPending}
            >
              Include archived
            </Button>
          </div>
        </div>
      </Card>

      {reference && (
        <JobPanel
          job={job.data}
          loading={job.isLoading}
          errorPage={errorPage}
          onErrorPage={setErrorPage}
          onDismiss={() => setReference(null)}
        />
      )}
    </div>
  );
}

function JobPanel({
  job, loading, errorPage, onErrorPage, onDismiss,
}: {
  job: CatalogueJob | undefined;
  loading: boolean;
  errorPage: number;
  onErrorPage: (page: number) => void;
  onDismiss: () => void;
}) {
  if (loading || !job) {
    return <Card title="Working"><Skeleton height={100} /></Card>;
  }

  const running = !isFinished(job.status);

  return (
    <Card
      title={job.type === 'EXPORT' ? 'Your export' : 'Your import'}
      actions={
        <div className="row" style={{ gap: 'var(--space-2)' }}>
          <Badge tone={JOB_TONES[job.status] ?? 'neutral'} dot>{humanise(job.status)}</Badge>
          {!running && <Button size="sm" variant="ghost" onClick={onDismiss}>Dismiss</Button>}
        </div>
      }
    >
      <div className="stack">
        {running && (
          <Notice tone="info">
            Still working{job.originalFilename ? ` through ${job.originalFilename}` : ''}. You can
            leave this page — it keeps going, and the reference below brings you back.
          </Notice>
        )}

        {job.failureReason && (
          <Notice tone="danger" title="It could not be read">{job.failureReason}</Notice>
        )}

        <div className="grid grid--3">
          <Stat label="Rows" value={formatNumber(job.totalRows)} />
          <Stat label="Imported" value={formatNumber(job.succeededRows)} />
          <Stat label="Refused" value={formatNumber(job.failedRows)} />
        </div>

        {job.downloadUrl && (
          <div className="stack stack--tight">
            <a className="btn btn--primary" href={job.downloadUrl} download>
              Download the file
            </a>
            {job.downloadExpiresAt && (
              <p className="small muted">
                The link stops working {formatDateTime(job.downloadExpiresAt)}.
              </p>
            )}
          </div>
        )}

        {job.succeededRows > 0 && job.type !== 'EXPORT' && (
          <Notice tone="ok">
            {formatNumber(job.succeededRows)} draft listing
            {job.succeededRows === 1 ? '' : 's'} added.{' '}
            <Link to="/products">Open them</Link> to send them to be checked.
          </Notice>
        )}

        {(job.errors?.length ?? 0) > 0 && (
          <div className="stack stack--tight">
            <div className="field__label">
              Rows that were refused
              {job.errorsTotal > job.errorsShown && (
                <span className="muted"> — {formatNumber(job.errorsTotal)} in total</span>
              )}
            </div>
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr><th>Row</th><th>Column</th><th>What was wrong</th><th>Value</th></tr>
                </thead>
                <tbody>
                  {job.errors!.map((rowError, index) => (
                    <tr key={`${rowError.row}-${rowError.field ?? index}`}>
                      <td className="num">{rowError.row}</td>
                      <td className="mono small">{rowError.field ?? '—'}</td>
                      <td style={{ whiteSpace: 'normal', maxWidth: 320 }}>{rowError.message}</td>
                      <td className="mono small truncate" style={{ maxWidth: 160 }}>
                        {rowError.value ?? '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination
              page={errorPage}
              totalPages={Math.max(1, Math.ceil(job.errorsTotal / 25))}
              totalElements={job.errorsTotal}
              onChange={onErrorPage}
              unit="refused rows"
            />
            <p className="small muted">
              Fix these rows in your sheet and import it again. Rows that went in are not
              duplicated — tick "update listings whose code already exists" to overwrite them.
            </p>
          </div>
        )}

        {!running && job.failedRows === 0 && !job.failureReason && job.type !== 'EXPORT' && (
          <EmptyState icon="✓" title="Every row went in" />
        )}

        <p className="small faint mono">
          {job.reference} · started {formatDateTime(job.createdAt)}
          {job.finishedAt && ` · finished ${formatDateTime(job.finishedAt)}`}
        </p>
      </div>
    </Card>
  );
}
