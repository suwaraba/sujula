import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { shipments as shipmentsApi } from '@/api/endpoints';
import { CUSTODY_EVENT_TYPES } from '@/api/enums';
import type { CustodyEventView } from '@/api/types';
import { ActionModal } from '@/components/ActionModal';
import { DecideButton } from '@/components/Decide';
import { Field, FormRow, NumberInput, Select, TextArea, TextInput } from '@/components/forms';
import { EMPTY_STEP_UP, StepUpFields, stepUpBody, type StepUpValue } from '@/components/StepUp';
import {
  Card,
  EmptyState,
  ErrorBanner,
  KeyValue,
  KeyValueList,
  Loading,
  Muted,
  PageHeader,
  Pill,
  StatusPill,
  humanise,
} from '@/components/primitives';
import { DateTime } from '@/components/Time';
import { keys, useAction } from '@/hooks';

/**
 * The chain, with its evidence.
 *
 * Status is not shown here as a thing that was set. Each link is an event with
 * what proved it — a code the receiving party read out, a signature, a
 * photograph, a position — and the status is what follows. An event with no
 * evidence, or one recorded by an override, is marked as such and stays marked:
 * a chain that hides its own hole is worse than one that shows it.
 */
export function CustodyChainPage() {
  const shipmentId = Number(useParams().shipmentId);

  const query = useQuery({
    queryKey: keys.custody(shipmentId),
    queryFn: () => shipmentsApi.custodyChain(shipmentId),
    enabled: Number.isFinite(shipmentId),
  });

  if (query.isLoading) return <Loading what="Reading the chain" />;
  if (query.error) return <ErrorBanner error={query.error} />;
  if (!query.data) return null;

  const chain = query.data;

  return (
    <>
      <PageHeader
        title={`Parcel ${chain.reference}`}
        description={
          <>
            <StatusPill status={chain.status} /> {chain.note && <Muted>· {chain.note}</Muted>}
          </>
        }
        actions={
          <div className="row-actions">
            <UnassignDriver shipmentId={shipmentId} />
            <ReassignDriver shipmentId={shipmentId} />
            <OverrideHandoff shipmentId={shipmentId} />
          </div>
        }
      />

      <Card
        title="Custody chain"
        subtitle="Vendor → driver → pickup point → recipient. Every transfer below is an event somebody produced evidence for."
      >
        {chain.events.length === 0 ? (
          <EmptyState>Nothing has happened to this parcel yet.</EmptyState>
        ) : (
          <ol className="chain">
            {chain.events.map((event) => (
              <ChainEvent key={event.id} event={event} />
            ))}
          </ol>
        )}
      </Card>
    </>
  );
}

function ChainEvent({ event }: { event: CustodyEventView }) {
  return (
    <li className={`chain-event${event.overridden ? ' chain-event-overridden' : ''}`}>
      <div className="chain-head">
        <strong>{humanise(event.type)}</strong>
        <DateTime value={event.occurredAt} />
        {event.overridden && (
          <Pill tone="bad" title="Recorded by an administrator, not proven by the ordinary handover.">
            Overridden
          </Pill>
        )}
        {event.capturedOffline && (
          <Pill tone="warn" title="Captured on a device with no connection and uploaded later.">
            Captured offline
          </Pill>
        )}
      </div>

      <div className="chain-evidence">
        <Pill tone={event.codePresented ? 'good' : 'warn'}>
          {event.codePresented ? 'Code presented' : 'No code presented'}
        </Pill>
        {event.photoUrl && (
          <a className="button button-ghost" href={event.photoUrl} target="_blank" rel="noreferrer">
            Photograph
          </a>
        )}
        {event.signatureUrl && (
          <a className="button button-ghost" href={event.signatureUrl} target="_blank" rel="noreferrer">
            Signature
          </a>
        )}
        {event.lat !== null && event.lng !== null && (
          <Pill tone={event.withinGeofence ? 'good' : 'bad'}>
            {event.withinGeofence ? 'Within the expected place' : 'Outside the expected place'}
            {event.metresFromExpected !== null && ` — ${Number(event.metresFromExpected).toFixed(0)} m away`}
          </Pill>
        )}
      </div>

      <KeyValueList>
        <KeyValue label="Recorded by">
          {event.recordedBy ?? <Muted>—</Muted>}
          {event.recordedByRole && <Muted> ({humanise(event.recordedByRole)})</Muted>}
        </KeyValue>
        <KeyValue label="Recorded at">
          <DateTime value={event.recordedAt} />
        </KeyValue>
        {event.reasonCode && <KeyValue label="Reason">{humanise(event.reasonCode)}</KeyValue>}
        {event.lat !== null && event.lng !== null && (
          <KeyValue label="Position">
            <span className="mono">
              {event.lat.toFixed(5)}, {event.lng.toFixed(5)}
            </span>
            {event.accuracyMetres !== null && (
              <Muted> ±{Number(event.accuracyMetres).toFixed(0)} m</Muted>
            )}
          </KeyValue>
        )}
        {event.note && <KeyValue label="Note">{event.note}</KeyValue>}
      </KeyValueList>
    </li>
  );
}

function UnassignDriver({ shipmentId }: { shipmentId: number }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');

  const action = useAction(() => shipmentsApi.unassign(shipmentId, { reason: reason.trim() }), {
    invalidate: [keys.custody(shipmentId), keys.shipments, keys.unassigned],
    message: (removed) => removed.message,
  });

  return (
    <>
      <DecideButton variant="ghost" onClick={() => setOpen(true)}>
        Take it off the driver
      </DecideButton>
      <ActionModal
        open={open}
        title="Take this parcel back off its driver"
        description="The parcel returns to the unassigned board. Whether the driver's acceptance score is affected depends on why — the server decides that from the reason, so say what actually happened."
        submitLabel="Unassign"
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

function ReassignDriver({ shipmentId }: { shipmentId: number }) {
  const [open, setOpen] = useState(false);
  const [driverId, setDriverId] = useState<number | ''>('');
  const [minutes, setMinutes] = useState<number | ''>(15);
  const [reason, setReason] = useState('');

  const action = useAction(
    () =>
      shipmentsApi.reassign(shipmentId, {
        driverId: Number(driverId),
        reason: reason.trim(),
        acceptanceMinutes: minutes === '' ? null : minutes,
      }),
    {
      invalidate: [keys.custody(shipmentId), keys.shipments, keys.unassigned],
      message: (made) => made.message,
    },
  );

  return (
    <>
      <DecideButton variant="ghost" onClick={() => setOpen(true)}>
        Move it to another driver
      </DecideButton>
      <ActionModal
        open={open}
        title="Move this parcel to a different driver"
        submitLabel="Reassign"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={!driverId || reason.trim().length < 5}
      >
        <FormRow>
          <Field label="Driver id" required>
            <NumberInput value={driverId} required min={1} onChange={setDriverId} />
          </Field>
          <Field label="Offer expires after" hint="minutes">
            <NumberInput value={minutes} min={1} max={240} onChange={setMinutes} />
          </Field>
        </FormRow>
        <Field label="Reason" required>
          <TextArea value={reason} onChange={setReason} required minLength={5} />
        </Field>
      </ActionModal>
    </>
  );
}

/**
 * Recording a handover that could not be proven the ordinary way.
 *
 * The one place on this surface that writes a link into the custody chain
 * without the evidence that normally produces it. It is therefore step-up
 * guarded, attested to a named person, and flagged on the event for good.
 */
function OverrideHandoff({ shipmentId }: { shipmentId: number }) {
  const [open, setOpen] = useState(false);
  const [eventType, setEventType] = useState<string>('');
  const [note, setNote] = useState('');
  const [attestedBy, setAttestedBy] = useState('');
  const [lat, setLat] = useState<number | ''>('');
  const [lng, setLng] = useState<number | ''>('');
  const [stepUp, setStepUp] = useState<StepUpValue>(EMPTY_STEP_UP);

  const action = useAction(
    () =>
      shipmentsApi.overrideHandoff(shipmentId, {
        eventType: eventType as never,
        note: note.trim(),
        attestedBy: attestedBy.trim(),
        lat: lat === '' ? null : lat,
        lng: lng === '' ? null : lng,
        ...stepUpBody(stepUp),
      }),
    {
      invalidate: [keys.custody(shipmentId), keys.shipments],
      message: (overridden) => overridden.warning || 'Handover recorded as an override.',
      onDone: () => setStepUp(EMPTY_STEP_UP),
    },
  );

  return (
    <>
      <DecideButton variant="danger" onClick={() => setOpen(true)}>
        Record an unproven handover
      </DecideButton>
      <ActionModal
        open={open}
        width="wide"
        title="Record a handover that could not be proven"
        description="The custody chain is built from evidence: a code the recipient read out, a signature, a photograph. This writes a link with none of that. The event carries your name and stays marked as an override for as long as the parcel exists."
        submitLabel="Record the override"
        tone="danger"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={!eventType || note.trim().length < 10 || !attestedBy.trim() || !stepUp.password}
      >
        <p className="notice notice-warning">
          Prefer the ordinary path wherever it still exists. The recipient's SMS code works for
          somebody with a phone and nothing else — no account, no app, no email — so "they could not
          log in" is not a reason to use this.
        </p>

        <FormRow>
          <Field label="What happened" required>
            <Select
              value={eventType}
              options={CUSTODY_EVENT_TYPES}
              placeholder="Choose an event"
              onChange={setEventType}
            />
          </Field>
          <Field
            label="Attested by"
            required
            hint="The person who told you this happened, by name. Not you."
          >
            <TextInput value={attestedBy} required onChange={setAttestedBy} />
          </Field>
        </FormRow>

        <FormRow>
          <Field label="Latitude" hint="optional, if you know where it happened">
            <NumberInput value={lat} step="0.00001" min={-90} max={90} onChange={setLat} />
          </Field>
          <Field label="Longitude" hint="optional">
            <NumberInput value={lng} step="0.00001" min={-180} max={180} onChange={setLng} />
          </Field>
        </FormRow>

        <Field
          label="What happened, and why it could not be proven the ordinary way"
          required
          hint="At least a sentence. Somebody will read this in six months to decide who is liable."
        >
          <TextArea value={note} rows={4} required minLength={10} onChange={setNote} />
        </Field>

        <StepUpFields value={stepUp} onChange={setStepUp} what="Recording an unproven handover" />
      </ActionModal>
    </>
  );
}
