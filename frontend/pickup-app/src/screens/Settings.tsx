import { useEffect, useState, type FormEvent } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { pickupApi } from '@/api/endpoints';
import { ApiError } from '@/api/errors';
import { useCounter } from '@/counter/CounterProvider';
import { useSession } from '@/auth/session';
import { useCurrencies } from '@/lib/hooks';
import { formatDateTime, humanise } from '@/lib/format';
import type { OperatorPoint } from '@/api/types';
import { Badge, Button, Card, KeyValue, Notice, PageHeader, Skeleton } from '@/components/ui';
import { SelectField, TextArea, TextField, fieldError } from '@/components/form';
import { ConfirmSheet } from '@/components/Sheet';
import { useToast } from '@/components/Toast';

const CLOSE_FOR = [
  { label: 'The rest of today', hours: 8 },
  { label: 'Until tomorrow', hours: 24 },
  { label: 'Three days', hours: 72 },
  { label: 'A week', hours: 168 },
];

export function Settings() {
  const { point, points, choosePoint } = useCounter();
  const { signOut } = useSession();
  const { money } = useCurrencies();
  const queryClient = useQueryClient();
  const toast = useToast();

  const [form, setForm] = useState(() => (point ? fromPoint(point) : blank()));
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});
  const [closing, setClosing] = useState(false);

  useEffect(() => {
    if (point) setForm(fromPoint(point));
  }, [point]);

  const save = useMutation({
    mutationFn: () =>
      pickupApi.updatePoint(point!.id, {
        name: form.name.trim(),
        openingHours: form.openingHours.trim(),
        ...(form.capacity.trim() ? { capacity: Number(form.capacity) } : {}),
        ...(form.storageDays.trim() ? { storageDays: Number(form.storageDays) } : {}),
        contactPhone: form.contactPhone.trim(),
        contactEmail: form.contactEmail.trim(),
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['my-points'] });
      toast.success('Counter saved.');
    },
    onError: (cause) => {
      if (cause instanceof ApiError) {
        setError(cause.message);
        setFields(cause.fieldErrors);
      } else {
        setError('Could not save.');
      }
    },
  });

  const reopen = useMutation({
    mutationFn: () => pickupApi.updatePoint(point!.id, { closedUntil: null }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['my-points'] });
      toast.success('The counter is open again.');
    },
    onError: () => toast.error('Could not reopen the counter.'),
  });

  if (!point) {
    return <div className="page stack"><Skeleton height={32} width={220} /><Skeleton height={300} /></div>;
  }

  const set = (name: keyof typeof form) => (event: { target: { value: string } }) =>
    setForm((current) => ({ ...current, [name]: event.target.value }));

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setFields({});
    save.mutate();
  }

  const closed = point.closedUntil != null && new Date(point.closedUntil.slice(0, 19)).getTime() > Date.now();

  return (
    <div className="page stack">
      <PageHeader
        title="This counter"
        subtitle={[point.addressStreet, point.city].filter(Boolean).join(', ')}
        actions={<Badge tone={point.status === 'APPROVED' || point.status === 'ACTIVE' ? 'ok' : 'warn'} dot>
          {humanise(point.status)}
        </Badge>}
      />

      {points.length > 1 && (
        <SelectField
          label="Which counter you are working"
          value={String(point.id)}
          onChange={(event) => choosePoint(Number(event.target.value))}
          hint="Remembered on this tablet."
        >
          {points.map((candidate) => (
            <option key={candidate.id} value={candidate.id}>
              {candidate.name} — {candidate.city ?? ''}
            </option>
          ))}
        </SelectField>
      )}

      {closed ? (
        <Card title="Closed">
          <div className="stack">
            <Notice tone="warn">
              Shut until {formatDateTime(point.closedUntil)}.
              {point.closureReason ? ` ${point.closureReason}` : ''} Parcels already on the shelf
              can still be handed over.
            </Notice>
            <Button variant="primary" onClick={() => reopen.mutate()} busy={reopen.isPending}>
              Open the counter again
            </Button>
          </div>
        </Card>
      ) : (
        <Card title="Closing for a while">
          <div className="stack">
            <p className="muted">
              Stops new parcels being sent here. What you already hold stays — the people waiting
              on those did not choose the closure.
            </p>
            <Button variant="secondary" onClick={() => setClosing(true)}>Close the counter</Button>
          </div>
        </Card>
      )}

      <form className="stack" onSubmit={onSubmit} noValidate>
        {error && <Notice tone="danger">{error}</Notice>}

        <Card title="How the counter runs">
          <div className="stack">
            <TextField
              label="Name" hint="What shoppers see when they choose where to collect."
              value={form.name} onChange={set('name')}
              error={fieldError(fields, 'name')} maxLength={150}
            />
            <TextArea
              label="Opening hours" hint="Free text. Drivers and shoppers both read this."
              placeholder="Mon–Sat 08:00–20:00, Sun closed"
              value={form.openingHours} onChange={set('openingHours')}
              error={fieldError(fields, 'openingHours')} maxLength={500} rows={2}
            />
            <div className="grid grid--2">
              <TextField
                label="How many parcels you can hold"
                hint={`${point.storedParcels ?? 0} on the shelf now. It cannot be set below that.`}
                type="number" inputMode="numeric" min="1" max="5000"
                value={form.capacity} onChange={set('capacity')}
                error={fieldError(fields, 'capacity')}
              />
              <TextField
                label="Days before a parcel goes back"
                hint="Between 1 and 90. Anything longer is storage, not pickup."
                type="number" inputMode="numeric" min="1" max="90"
                value={form.storageDays} onChange={set('storageDays')}
                error={fieldError(fields, 'storageDays')}
              />
            </div>
          </div>
        </Card>

        <Card title="How drivers reach you">
          <div className="grid grid--2">
            <TextField
              label="Phone" type="tel" inputMode="tel"
              value={form.contactPhone} onChange={set('contactPhone')}
              error={fieldError(fields, 'contactPhone')} maxLength={30}
            />
            <TextField
              label="Email" type="email" inputMode="email" autoCapitalize="none"
              value={form.contactEmail} onChange={set('contactEmail')}
              error={fieldError(fields, 'contactEmail')} maxLength={200}
            />
          </div>
        </Card>

        <Card title="What you are paid">
          <KeyValue
            rows={[
              ['Per parcel', money(point.commissionPerParcel, point.commissionCurrency)],
              ['Where it is', [point.addressStreet, point.city, point.country].filter(Boolean).join(', ')],
              ['Pin', point.lat != null && point.lng != null
                ? <span key="p" className="mono">{point.lat.toFixed(6)}, {point.lng.toFixed(6)}</span>
                : '—'],
              ['Running since', formatDateTime(point.createdAt)],
            ]}
          />
          <p className="small muted" style={{ marginTop: 'var(--space-3)' }}>
            The address and the pin were set when the counter was approved. Contact support to
            move it — a driver navigates to that pin.
          </p>
        </Card>

        <Button type="submit" variant="primary" busy={save.isPending}>Save changes</Button>
      </form>

      <Card title="This tablet">
        <Button variant="secondary" block onClick={() => void signOut()}>Sign out</Button>
      </Card>

      {closing && (
        <CloseSheet pointId={point.id} onClose={() => setClosing(false)} />
      )}
    </div>
  );
}

function CloseSheet({ pointId, onClose }: { pointId: number; onClose: () => void }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const [hours, setHours] = useState(8);
  const [reason, setReason] = useState('');

  const close = useMutation({
    mutationFn: () => {
      // The API takes a LocalDateTime, which carries no zone — so the moment is
      // built from the tablet's own clock and sent without one, which is what
      // the server stores and compares against.
      const until = new Date(Date.now() + hours * 3600_000);
      const pad = (n: number) => String(n).padStart(2, '0');
      const local =
        `${until.getFullYear()}-${pad(until.getMonth() + 1)}-${pad(until.getDate())}` +
        `T${pad(until.getHours())}:${pad(until.getMinutes())}:00`;
      return pickupApi.updatePoint(pointId, {
        closedUntil: local,
        ...(reason.trim() ? { closureReason: reason.trim() } : {}),
      });
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['my-points'] });
      toast.success('The counter is closed. Parcels already here can still be collected.');
      onClose();
    },
    onError: (cause) =>
      toast.error(cause instanceof ApiError ? cause.message : 'Could not close the counter.'),
  });

  return (
    <ConfirmSheet
      title="Close the counter"
      confirmLabel="Close it"
      onConfirm={() => close.mutate()}
      onClose={onClose}
      busy={close.isPending}
    >
      <div className="stack">
        <SelectField
          label="For how long"
          value={String(hours)}
          onChange={(event) => setHours(Number(event.target.value))}
        >
          {CLOSE_FOR.map((entry) => (
            <option key={entry.hours} value={entry.hours}>{entry.label}</option>
          ))}
        </SelectField>

        <TextField
          label="Why" hint="Shown to shoppers looking for somewhere to collect."
          placeholder="Closed for a funeral"
          value={reason} onChange={(event) => setReason(event.target.value)}
          maxLength={300}
        />

        <Notice tone="info">
          You keep everything already on the shelf and can still hand it over. Only new parcels
          stop.
        </Notice>
      </div>
    </ConfirmSheet>
  );
}

function blank() {
  return { name: '', openingHours: '', capacity: '', storageDays: '', contactPhone: '', contactEmail: '' };
}

function fromPoint(point: OperatorPoint) {
  return {
    name: point.name ?? '',
    openingHours: point.openingHours ?? '',
    capacity: point.capacity != null ? String(point.capacity) : '',
    storageDays: point.storageDays != null ? String(point.storageDays) : '',
    contactPhone: point.contactPhone ?? '',
    contactEmail: point.contactEmail ?? '',
  };
}
