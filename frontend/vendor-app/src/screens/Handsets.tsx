import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { inventoryApi } from '@/api/endpoints/inventory';
import { catalogueApi } from '@/api/endpoints/catalogue';
import { ApiError } from '@/api/errors';
import { useCurrencies, useIdempotencyKey } from '@/lib/hooks';
import { formatDate, formatDateTime, humanise } from '@/lib/format';
import { imeiCheckDigitValid } from '@/lib/catalogue';
import type { ImeiGrade, ImeiStatus, ImeiUnit } from '@/api/types';
import {
  Badge, Button, Card, EmptyState, Notice, PageHeader, SkeletonList, type Tone,
} from '@/components/ui';
import { SelectField, TextArea, TextField } from '@/components/form';
import { Sheet } from '@/components/Sheet';
import { Pagination } from '@/components/Pagination';
import { useToast } from '@/components/Toast';

const GRADES: { value: ImeiGrade; label: string }[] = [
  { value: 'NEW', label: 'New — sealed' },
  { value: 'A_GRADE', label: 'A — like new' },
  { value: 'B_GRADE', label: 'B — light marks' },
  { value: 'C_GRADE', label: 'C — visible wear' },
  { value: 'FOR_PARTS', label: 'For parts' },
];

function gradeLabel(grade: ImeiGrade | null): string {
  if (!grade) return '';
  return GRADES.find((entry) => entry.value === grade)?.label ?? humanise(grade);
}

const STATUS_TONES: Record<string, Tone> = {
  IN_STOCK: 'ok',
  RESERVED: 'warn',
  SOLD: 'neutral',
  RETURNED: 'info',
  LOST: 'danger',
  WRITTEN_OFF: 'danger',
};

const STATUSES: { value: ImeiStatus; label: string }[] = [
  { value: 'IN_STOCK', label: 'On the shelf' },
  { value: 'RESERVED', label: 'Reserved' },
  { value: 'SOLD', label: 'Sold' },
  { value: 'RETURNED', label: 'Returned' },
  { value: 'LOST', label: 'Lost' },
];

/**
 * Handsets, one row per physical phone.
 *
 * This is what makes the fulfilment desk's "scan a handset" work: a phone has
 * to be registered and on the shelf before it can be bound to an order line.
 * A serialised variant's stock *is* the count of these — which is why the stock
 * screen refuses a typed number for one and sends the seller here.
 */
export function Handsets() {
  const [status, setStatus] = useState<string>('');
  const [page, setPage] = useState(0);
  const [registering, setRegistering] = useState(false);
  const [editing, setEditing] = useState<ImeiUnit | null>(null);
  const queryClient = useQueryClient();

  const units = useQuery({
    queryKey: ['imei-units', { status, page }],
    queryFn: () =>
      inventoryApi.imeiUnits({ ...(status ? { status: status as ImeiStatus } : {}), page, size: 25 }),
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['imei-units'] });
    void queryClient.invalidateQueries({ queryKey: ['inventory'] });
  };

  return (
    <div className="page stack">
      <PageHeader
        title="Handsets"
        subtitle="One row per phone. A phone must be here, and on the shelf, before it can go in a parcel."
        actions={
          <Button variant="primary" onClick={() => setRegistering(true)}>＋ Register handsets</Button>
        }
      />

      <div className="row">
        <SelectField
          label={<span className="sr-only">Filter by state</span>}
          value={status}
          onChange={(event) => { setStatus(event.target.value); setPage(0); }}
        >
          <option value="">Every handset</option>
          {STATUSES.map(({ value, label }) => (
            <option key={value} value={value}>{label}</option>
          ))}
        </SelectField>
        {units.data && (
          <span className="small muted">
            {units.data.sellable} on the shelf and sellable
          </span>
        )}
      </div>

      <Card flush>
        {units.isLoading ? (
          <SkeletonList rows={5} />
        ) : (units.data?.items.length ?? 0) === 0 ? (
          <EmptyState
            icon="▤"
            title="No handsets registered"
            action={
              <Button variant="primary" onClick={() => setRegistering(true)}>
                Register the first ones
              </Button>
            }
          >
            Register each phone against the listing it belongs to. Its IMEI is what binds it to an
            order, so anyone can say afterwards which handset went in which box.
          </EmptyState>
        ) : (
          <>
            <div className="list">
              {units.data!.items.map((unit) => (
                <div key={unit.id} className="list__item">
                  <div className="list__main">
                    <div className="list__title mono">{unit.imei}</div>
                    <div className="list__meta" style={{ whiteSpace: 'normal' }}>
                      {unit.productName ?? 'no listing'}
                      {unit.variantLabel ? ` · ${unit.variantLabel}` : ''}
                      {unit.batteryHealth != null ? ` · battery ${unit.batteryHealth}%` : ''}
                    </div>
                    <div className="list__meta">
                      Registered {formatDateTime(unit.registeredAt)}
                      {unit.soldOnOrderNumber && ` · sold on ${unit.soldOnOrderNumber}`}
                      {unit.warrantyExpiresOn && ` · warranty to ${formatDate(unit.warrantyExpiresOn)}`}
                    </div>
                    <div className="row" style={{ gap: 'var(--space-2)', marginTop: 4 }}>
                      <Badge tone={STATUS_TONES[unit.status] ?? 'neutral'} dot>
                        {humanise(unit.status)}
                      </Badge>
                      {unit.grade && <Badge>{gradeLabel(unit.grade)}</Badge>}
                    </div>
                  </div>
                  <Button size="sm" variant="ghost" onClick={() => setEditing(unit)}>Edit</Button>
                </div>
              ))}
            </div>
            <Pagination
              page={units.data!.page}
              totalPages={units.data!.totalPages}
              totalElements={units.data!.totalElements}
              onChange={setPage}
              unit="handsets"
            />
          </>
        )}
      </Card>

      <Notice tone="info">
        A listing tracked this way has no typed stock level — its stock is however many handsets
        here are on the shelf. See <Link to="/inventory">Stock</Link>.
      </Notice>

      {registering && (
        <RegisterSheet
          onClose={() => setRegistering(false)}
          onDone={() => { setRegistering(false); invalidate(); }}
        />
      )}

      {editing && (
        <EditUnitSheet
          unit={editing}
          onClose={() => setEditing(null)}
          onDone={() => { setEditing(null); invalidate(); }}
        />
      )}
    </div>
  );
}

function RegisterSheet({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const toast = useToast();
  const { step } = useCurrencies();
  const [key, resetKey] = useIdempotencyKey();

  const [productId, setProductId] = useState('');
  const [variantId, setVariantId] = useState('');
  const [imeis, setImeis] = useState('');
  const [grade, setGrade] = useState<string>('');
  const [batteryHealth, setBatteryHealth] = useState('');
  const [costPrice, setCostPrice] = useState('');
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [rejected, setRejected] = useState<{ message: string }[] | null>(null);

  const products = useQuery({
    queryKey: ['products', 'picker', ''],
    queryFn: () => catalogueApi.list({ page: 0, size: 100 }),
  });

  const product = useQuery({
    queryKey: ['products', 'detail', Number(productId)],
    queryFn: () => catalogueApi.get(Number(productId)),
    enabled: productId !== '',
  });

  // One per line is how a seller reading phones off a shelf actually types
  // them, and it survives a paste out of a spreadsheet column.
  const parsed = imeis
    .split(/[\s,]+/)
    .map((value) => value.trim())
    .filter(Boolean);

  // The server refuses an IMEI whose own check digit disagrees with the rest
  // of it. Checking here means a seller pasting forty of them finds the typo
  // before sending the batch rather than after.
  const valid = parsed.filter((imei) => imeiCheckDigitValid(imei));
  const malformed = parsed.filter((imei) => !/^\d{15}$/.test(imei));
  const mistyped = parsed.filter((imei) => /^\d{15}$/.test(imei) && !imeiCheckDigitValid(imei));
  const duplicates = valid.filter((imei, index) => valid.indexOf(imei) !== index);

  const register = useMutation({
    mutationFn: () =>
      inventoryApi.registerImeiUnits(
        {
          productId: Number(productId),
          ...(variantId ? { variantId: Number(variantId) } : {}),
          units: valid.map((imei) => ({
            imei,
            ...(grade ? { grade: grade as ImeiGrade } : {}),
            ...(batteryHealth.trim() ? { batteryHealth: Number(batteryHealth) } : {}),
            ...(costPrice.trim() ? { costPrice: Number(costPrice) } : {}),
            ...(note.trim() ? { note: note.trim() } : {}),
          })),
        },
        key,
      ),
    onSuccess: (result) => {
      // Partial is the normal outcome, not an error: the server registers what
      // it can and names what it would not take. Closing the sheet on a
      // rejection would throw away the only explanation the seller gets.
      if (result.rejected.length > 0) {
        setRejected(result.rejected);
        setImeis(result.rejected.map((row) => extractImei(row.message)).filter(Boolean).join('\n'));
        toast.show(
          `${result.registered} registered, ${result.rejected.length} not.`,
          'warn',
        );
        return;
      }
      toast.success(
        `${result.registered} handset${result.registered === 1 ? '' : 's'} registered.`,
      );
      onDone();
    },
    onError: (cause) => {
      resetKey();
      setError(cause instanceof ApiError ? cause.message : 'Could not register them.');
    },
  });

  const ready =
    productId !== '' &&
    valid.length > 0 &&
    malformed.length === 0 &&
    mistyped.length === 0 &&
    duplicates.length === 0;

  return (
    <Sheet
      title="Register handsets"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={register.isPending}>Cancel</Button>
          <Button
            variant="primary" onClick={() => register.mutate()} busy={register.isPending}
            disabled={!ready}
          >
            Register {valid.length || ''}
          </Button>
        </>
      }
    >
      <div className="stack">
        {error && <Notice tone="danger">{error}</Notice>}

        <SelectField
          label="Which listing"
          value={productId}
          onChange={(event) => { setProductId(event.target.value); setVariantId(''); }}
          required
        >
          <option value="">Choose a listing</option>
          {(products.data?.items ?? []).map((item) => (
            <option key={item.id} value={item.id}>{item.name}</option>
          ))}
        </SelectField>

        {(product.data?.variants.length ?? 0) > 0 && (
          <SelectField
            label="Which variant"
            hint="Which colour and capacity these phones are."
            value={variantId}
            onChange={(event) => setVariantId(event.target.value)}
          >
            <option value="">The listing itself</option>
            {product.data!.variants.map((variant) => (
              <option key={variant.id} value={variant.id}>
                {variant.values.map((v) => v.value).join(' · ') || variant.sku || `#${variant.id}`}
              </option>
            ))}
          </SelectField>
        )}

        <TextArea
          label="IMEIs"
          className="textarea input--mono"
          hint="One per line. Dial *#06# on each handset to see it. You can paste a column from a spreadsheet."
          value={imeis}
          onChange={(event) => setImeis(event.target.value)}
          rows={6}
          required
        />

        {parsed.length > 0 && (
          <div className="row" style={{ gap: 'var(--space-2)' }}>
            <Badge tone={valid.length > 0 ? 'ok' : 'neutral'}>{valid.length} ready</Badge>
            {malformed.length > 0 && <Badge tone="danger">{malformed.length} wrong length</Badge>}
            {mistyped.length > 0 && <Badge tone="danger">{mistyped.length} mistyped</Badge>}
            {duplicates.length > 0 && <Badge tone="danger">{duplicates.length} repeated</Badge>}
          </div>
        )}

        {malformed.length > 0 && (
          <Notice tone="danger" title="These are not the right length">
            <span className="mono small">{malformed.slice(0, 6).join(', ')}</span>
            {malformed.length > 6 && ` and ${malformed.length - 6} more`}. An IMEI is exactly 15
            digits.
          </Notice>
        )}

        {mistyped.length > 0 && (
          <Notice tone="danger" title="One of the digits is wrong">
            <span className="mono small">{mistyped.slice(0, 6).join(', ')}</span>
            {mistyped.length > 6 && ` and ${mistyped.length - 6} more`}. These are 15 digits but
            fail their own check digit, so something was read or typed wrong. Dial *#06# on the
            handset and compare.
          </Notice>
        )}

        {rejected && rejected.length > 0 && (
          <Notice tone="warn" title="The rest went in — these did not">
            <ul style={{ margin: 'var(--space-2) 0 0', paddingLeft: '1.2em' }}>
              {rejected.map((row, index) => (
                <li key={index} className="small">{row.message}</li>
              ))}
            </ul>
          </Notice>
        )}

        {duplicates.length > 0 && (
          <Notice tone="danger" title="The same handset twice">
            <span className="mono small">{[...new Set(duplicates)].join(', ')}</span>
          </Notice>
        )}

        <div className="grid grid--2">
          <SelectField
            label="Condition" value={grade} onChange={(event) => setGrade(event.target.value)}
            hint="Applied to all of them."
          >
            <option value="">Not graded</option>
            {GRADES.map(({ value, label }) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </SelectField>
          <TextField
            label="Battery health (%)" type="number" inputMode="numeric" min="0" max="100"
            value={batteryHealth} onChange={(event) => setBatteryHealth(event.target.value)}
          />
        </div>

        <TextField
          label="What each cost you" type="number" inputMode="decimal" min="0"
          step={step(product.data?.currency)}
          value={costPrice} onChange={(event) => setCostPrice(event.target.value)}
          hint="For your own margin records. Buyers never see it."
        />

        <TextField
          label="Note" value={note} onChange={(event) => setNote(event.target.value)} maxLength={300}
        />
      </div>
    </Sheet>
  );
}

/** Pulls the IMEI back out of a rejection message so it can be re-offered for fixing. */
function extractImei(message: string): string {
  return /'(\d{10,20})'/.exec(message)?.[1] ?? '';
}

function EditUnitSheet({
  unit, onClose, onDone,
}: { unit: ImeiUnit; onClose: () => void; onDone: () => void }) {
  const toast = useToast();
  const [grade, setGrade] = useState<string>(unit.grade ?? '');
  const [status, setStatus] = useState<string>(unit.status);
  const [batteryHealth, setBatteryHealth] = useState(
    unit.batteryHealth != null ? String(unit.batteryHealth) : '',
  );
  const [note, setNote] = useState(unit.note ?? '');
  const [error, setError] = useState<string | null>(null);

  const save = useMutation({
    mutationFn: () =>
      inventoryApi.updateImeiUnit(unit.id, {
        ...(grade ? { grade: grade as ImeiGrade } : {}),
        ...(status !== unit.status ? { status: status as ImeiStatus } : {}),
        ...(batteryHealth.trim() ? { batteryHealth: Number(batteryHealth) } : {}),
        ...(note.trim() ? { note: note.trim() } : {}),
      }),
    onSuccess: () => {
      toast.success('Handset updated.');
      onDone();
    },
    onError: (cause) => {
      setError(cause instanceof ApiError ? cause.message : 'Could not update it.');
    },
  });

  return (
    <Sheet
      title={unit.imei}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={save.isPending}>Cancel</Button>
          <Button variant="primary" onClick={() => save.mutate()} busy={save.isPending}>Save</Button>
        </>
      }
    >
      <div className="stack">
        {error && <Notice tone="danger">{error}</Notice>}

        {unit.soldOnOrderNumber && (
          <Notice tone="info">
            This handset went out on <strong>{unit.soldOnOrderNumber}</strong>
            {unit.soldAt ? ` on ${formatDateTime(unit.soldAt)}` : ''}.
          </Notice>
        )}

        <SelectField
          label="Where it is" value={status} onChange={(event) => setStatus(event.target.value)}
          hint="Moving one off the shelf changes the listing's stock."
        >
          {STATUSES.map(({ value, label }) => (
            <option key={value} value={value}>{label}</option>
          ))}
        </SelectField>

        <div className="grid grid--2">
          <SelectField label="Condition" value={grade} onChange={(event) => setGrade(event.target.value)}>
            <option value="">Not graded</option>
            {GRADES.map(({ value, label }) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </SelectField>
          <TextField
            label="Battery health (%)" type="number" inputMode="numeric" min="0" max="100"
            value={batteryHealth} onChange={(event) => setBatteryHealth(event.target.value)}
          />
        </div>

        <TextArea
          label="Note" value={note} onChange={(event) => setNote(event.target.value)}
          maxLength={300} rows={2}
        />
      </div>
    </Sheet>
  );
}
