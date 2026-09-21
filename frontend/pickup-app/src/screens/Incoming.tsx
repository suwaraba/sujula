import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { pickupApi } from '@/api/endpoints';
import { ApiError } from '@/api/errors';
import { useCounter } from '@/counter/CounterProvider';
import { useCurrencies, useEventId } from '@/lib/hooks';
import { humanise } from '@/lib/format';
import type { IncomingParcel, ParcelAccepted, RejectReason } from '@/api/types';
import {
  Badge, Button, Card, EmptyState, Notice, PageHeader, SkeletonList,
} from '@/components/ui';
import { SelectField, TextArea, TextField } from '@/components/form';
import { CodePad } from '@/components/CodePad';
import { Sheet } from '@/components/Sheet';
import { useToast } from '@/components/Toast';

/**
 * Parcels on their way here.
 *
 * None of these rows carries a recipient's name, and that is the server's
 * choice rather than an omission on this screen: the parcel is not here yet,
 * and a counter has no business holding a name for something it may turn away
 * at the door.
 */
export function Incoming() {
  const { parcels, parcelsLoading, canTakeParcels, blockedReason } = useCounter();
  const [accepting, setAccepting] = useState<IncomingParcel | null>(null);
  const [rejecting, setRejecting] = useState<IncomingParcel | null>(null);

  const incoming = parcels?.incoming ?? [];

  return (
    <div className="page stack">
      <PageHeader
        title="Coming in"
        subtitle="A driver brings these. Check their code before anything goes on the shelf."
      />

      {!canTakeParcels && blockedReason && (
        <Notice tone="warn" title="Nothing can be taken in">
          {blockedReason} A driver arriving now should be turned away and told why.
        </Notice>
      )}

      <Card flush>
        {parcelsLoading ? (
          <SkeletonList rows={3} />
        ) : incoming.length === 0 ? (
          <EmptyState icon="↓" title="Nothing on its way">
            Parcels appear here once a driver has been given them for this counter.
          </EmptyState>
        ) : (
          <div className="list">
            {incoming.map((parcel) => (
              <div key={parcel.shipmentId} className="list__item">
                <div className="list__main">
                  <div className="list__title mono">{parcel.reference}</div>
                  <div className="list__meta">
                    {parcel.fromStore ?? 'Unknown shop'}
                    {parcel.parcelCount > 1 && ` · ${parcel.parcelCount} parcels`}
                    {parcel.expectedFrom && ` · expected ${parcel.expectedFrom}`}
                  </div>
                  <div style={{ marginTop: 4 }}>
                    <Badge tone="info" dot>{humanise(parcel.status)}</Badge>
                  </div>
                </div>
                <div className="row" style={{ gap: 'var(--space-2)', flexWrap: 'nowrap' }}>
                  <Button
                    variant="primary"
                    onClick={() => setAccepting(parcel)}
                    disabled={!canTakeParcels}
                  >
                    Take it in
                  </Button>
                  <Button variant="ghost" size="sm" onClick={() => setRejecting(parcel)}>
                    Turn away
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      {accepting && <AcceptSheet parcel={accepting} onClose={() => setAccepting(null)} />}
      {rejecting && <RejectSheet parcel={rejecting} onClose={() => setRejecting(null)} />}
    </div>
  );
}

/**
 * Taking a parcel in.
 *
 * The operator verifies the *driver's* code — the receiving party checking the
 * giving party, which is the only thing a code can prove. It is the same shape
 * as every other link in the chain, from the opposite side.
 */
function AcceptSheet({ parcel, onClose }: { parcel: IncomingParcel; onClose: () => void }) {
  const { pointId, refresh } = useCounter();
  const { money } = useCurrencies();
  const [eventId, resetEventId] = useEventId();

  const [code, setCode] = useState('');
  const [shelfCode, setShelfCode] = useState('');
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<ParcelAccepted | null>(null);

  const accept = useMutation({
    mutationFn: () =>
      pickupApi.accept(
        pointId!,
        parcel.shipmentId,
        {
          code,
          ...(shelfCode.trim() ? { shelfCode: shelfCode.trim().toUpperCase() } : {}),
          ...(note.trim() ? { note: note.trim() } : {}),
          clientEventId: eventId,
        },
        eventId,
      ),
    onSuccess: (result) => {
      setDone(result);
      refresh();
    },
    onError: (cause) => {
      setCode('');
      resetEventId();
      setError(cause instanceof ApiError ? cause.message : 'That did not go through. Try again.');
    },
  });

  if (done) {
    return (
      <Sheet
        title={done.duplicate ? 'Already taken in' : 'On the shelf'}
        onClose={onClose}
        footer={<Button variant="primary" onClick={onClose}>Done</Button>}
      >
        <div className="stack">
          <Notice tone={done.duplicate ? 'info' : 'ok'} title={done.message}>
            {done.duplicate && 'This parcel had already been accepted — nothing was done twice.'}
          </Notice>

          {done.shelfCode && (
            <div className="center">
              <div className="field__label">Put it on</div>
              <div
                className="shelf"
                style={{ margin: 'var(--space-3) auto', minWidth: 140, height: 84, fontSize: 34 }}
              >
                {done.shelfCode}
              </div>
            </div>
          )}

          <p className="muted center">
            {done.storedCount} of {done.capacity} shelves used
            {done.commission != null && <> · you earn {money(done.commission, done.commissionCurrency)}</>}
          </p>
        </div>
      </Sheet>
    );
  }

  return (
    <Sheet
      title="Take this parcel in"
      onClose={onClose}
      dismissible={!accept.isPending}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={accept.isPending}>Cancel</Button>
          <Button
            variant="primary"
            onClick={() => { setError(null); accept.mutate(); }}
            busy={accept.isPending}
            disabled={!/^\d{4,8}$/.test(code)}
          >
            Take it in
          </Button>
        </>
      }
    >
      <div className="stack">
        <div className="row row--between">
          <span className="mono">{parcel.reference}</span>
          <span className="muted">{parcel.fromStore ?? ''}</span>
        </div>

        {error && <Notice tone="danger">{error}</Notice>}

        <CodePad
          label="The driver's code"
          value={code}
          onChange={setCode}
          error={error != null}
          disabled={accept.isPending}
        />
        <p className="small muted center">
          Ask the driver to read out their handover code.
        </p>

        <TextField
          label="Shelf"
          hint="Leave blank and the counter picks one for you."
          value={shelfCode}
          onChange={(event) => setShelfCode(event.target.value.toUpperCase().slice(0, 12))}
          autoComplete="off"
          maxLength={12}
          className="input mono"
          disabled={accept.isPending}
        />

        <TextArea
          label="Note"
          hint="Optional. The state it arrived in, anything unusual."
          value={note}
          onChange={(event) => setNote(event.target.value)}
          maxLength={300}
          rows={2}
          disabled={accept.isPending}
        />
      </div>
    </Sheet>
  );
}

const REASONS: { value: RejectReason; label: string; blurb: string }[] = [
  { value: 'DAMAGED', label: 'It arrived damaged', blurb: 'Goes back with what you record here as evidence.' },
  { value: 'OVER_CAPACITY', label: 'No room on the shelf', blurb: 'The driver takes it to another counter.' },
  { value: 'WRONG_PARCEL', label: 'Not for this counter', blurb: 'Not what the manifest said, or not addressed here.' },
  { value: 'TOO_LARGE', label: 'Too big or too heavy', blurb: 'More than this counter can hold.' },
  { value: 'CLOSING', label: 'We are closing', blurb: 'Cannot take it today.' },
];

/**
 * Turning one away.
 *
 * Custody does not move: the driver still has the parcel in their hands, which
 * is why the server records this as a failed attempt rather than the end of the
 * chain. Somebody stays accountable for it, and the screen says so — an
 * operator who thinks they have handed the problem on will stop watching it.
 */
function RejectSheet({ parcel, onClose }: { parcel: IncomingParcel; onClose: () => void }) {
  const { pointId, refresh } = useCounter();
  const toast = useToast();
  const [eventId, resetEventId] = useEventId();

  const [reason, setReason] = useState<RejectReason>('DAMAGED');
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(null);

  const reject = useMutation({
    mutationFn: () =>
      pickupApi.reject(
        pointId!,
        parcel.shipmentId,
        { reason, ...(note.trim() ? { note: note.trim() } : {}), clientEventId: eventId },
        eventId,
      ),
    onSuccess: (result) => {
      toast.success(
        result.duplicate ? 'This parcel had already been turned away.' : result.message || 'Turned away.',
      );
      refresh();
      onClose();
    },
    onError: (cause) => {
      resetEventId();
      setError(cause instanceof ApiError ? cause.message : 'Could not record that.');
    },
  });

  const chosen = REASONS.find((entry) => entry.value === reason);

  return (
    <Sheet
      title="Turn this parcel away"
      onClose={onClose}
      dismissible={!reject.isPending}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={reject.isPending}>Cancel</Button>
          <Button
            variant="danger"
            onClick={() => { setError(null); reject.mutate(); }}
            busy={reject.isPending}
          >
            Turn it away
          </Button>
        </>
      }
    >
      <div className="stack">
        {error && <Notice tone="danger">{error}</Notice>}

        <Notice tone="info" title="The driver keeps it">
          Nothing changes hands. This is recorded as an attempt that did not work, so the parcel
          stays somebody's responsibility rather than falling between the two of you.
        </Notice>

        <SelectField
          label="Why"
          value={reason}
          onChange={(event) => setReason(event.target.value as RejectReason)}
          hint={chosen?.blurb}
          required
          disabled={reject.isPending}
        >
          {REASONS.map(({ value, label }) => (
            <option key={value} value={value}>{label}</option>
          ))}
        </SelectField>

        <TextArea
          label="What you saw"
          hint={reason === 'DAMAGED'
            ? 'Describe the damage. This is the record of what it looked like when it arrived.'
            : 'Optional.'}
          value={note}
          onChange={(event) => setNote(event.target.value)}
          maxLength={400}
          rows={3}
          disabled={reject.isPending}
        />
      </div>
    </Sheet>
  );
}
