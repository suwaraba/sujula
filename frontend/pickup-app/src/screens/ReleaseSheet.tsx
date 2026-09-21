import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { pickupApi } from '@/api/endpoints';
import { ApiError } from '@/api/errors';
import { useCounter } from '@/counter/CounterProvider';
import { useEventId } from '@/lib/hooks';
import type { CodeResent, ParcelReleased, StoredParcel } from '@/api/types';
import { Badge, Button, Notice } from '@/components/ui';
import { TextField, TextArea } from '@/components/form';
import { CodePad } from '@/components/CodePad';
import { Sheet } from '@/components/Sheet';

/**
 * Handing a parcel to the person it is for.
 *
 * The end of the custody chain, and the thing that releases the seller's money.
 * Two checks, and the server requires both: the code proves they were told it by
 * whoever sent the parcel, and the name is what the operator writes down after
 * looking at the person in front of them. A code alone would let anybody who
 * overheard it collect; a name alone, anybody who read the label.
 *
 * The operator is never shown the code. This screen only ever receives one —
 * typed in from what somebody reads aloud. There is no reveal, no hint, and
 * nothing here that compares the entered digits locally: the server decides,
 * because a client that could tell a right code from a wrong one is a client
 * that can be asked until it says yes.
 */
export function ReleaseSheet({
  parcel, onClose,
}: { parcel: StoredParcel; onClose: () => void }) {
  const { pointId, refresh } = useCounter();
  const [eventId, resetEventId] = useEventId();

  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [identity, setIdentity] = useState('');
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<ParcelReleased | null>(null);
  const [sent, setSent] = useState<CodeResent | null>(null);
  const [resendKey, resetResendKey] = useEventId();

  /**
   * Somebody can arrive without ever having been sent a code — the buyer never
   * passed it on, or no code was issued at all, which the server says plainly
   * when a release is tried. Without a way out of that, the operator is left
   * with a person at the counter, a parcel on the shelf and a refusal.
   */
  const resend = useMutation({
    mutationFn: () => pickupApi.resendCode(pointId!, parcel.shipmentId, resendKey),
    onSuccess: (result) => {
      setSent(result);
      setError(null);
      setCode('');
    },
    onError: (cause) => {
      resetResendKey();
      setError(
        cause instanceof ApiError && cause.isRateLimited
          ? 'The code has been sent several times already. Wait a little before sending another.'
          : cause instanceof ApiError
            ? cause.message
            : 'Could not send the code.',
      );
    },
  });

  const release = useMutation({
    mutationFn: () =>
      pickupApi.release(
        pointId!,
        parcel.shipmentId,
        {
          code,
          collectedByName: name.trim(),
          ...(identity.trim() ? { identityShown: identity.trim() } : {}),
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
      // A refused code is the ordinary case, not a crash: somebody misheard a
      // digit. The code is cleared so the next attempt starts from empty, and
      // the event id is reset so the retry is a new event rather than a replay
      // of the failed one.
      setCode('');
      resetEventId();
      setError(
        cause instanceof ApiError
          ? cause.message
          : 'That did not go through. Try again.',
      );
    },
  });

  const ready = /^\d{4,8}$/.test(code) && name.trim().length > 0;

  if (done) {
    return (
      <Sheet
        title={done.duplicate ? 'Already handed over' : 'Handed over'}
        onClose={onClose}
        footer={<Button variant="primary" onClick={onClose}>Done</Button>}
      >
        <div className="stack">
          <Notice tone={done.duplicate ? 'info' : 'ok'} title={done.message}>
            {done.duplicate
              ? 'This parcel had already been released — nothing was done twice.'
              : `Given to ${done.releasedTo}.`}
          </Notice>

          {!done.nameMatched && (
            <Notice tone="warn" title="The name did not match the parcel">
              It has been recorded as written, against the name on the label. Worth a word with
              the customer if something looked wrong.
            </Notice>
          )}

          <p className="muted">
            {done.storedCount} parcel{done.storedCount === 1 ? '' : 's'} still on the shelf.
          </p>
        </div>
      </Sheet>
    );
  }

  return (
    <Sheet
      title={`Hand over to ${parcel.recipientName ?? 'the recipient'}`}
      onClose={onClose}
      dismissible={!release.isPending}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={release.isPending}>
            Cancel
          </Button>
          <Button
            variant="primary"
            onClick={() => { setError(null); release.mutate(); }}
            busy={release.isPending}
            disabled={!ready}
          >
            Hand it over
          </Button>
        </>
      }
    >
      <div className="stack">
        <div className="row row--between">
          <span className="mono">{parcel.reference}</span>
          {parcel.shelfCode && <Badge tone="accent">Shelf {parcel.shelfCode}</Badge>}
        </div>

        {error && <Notice tone="danger">{error}</Notice>}

        {sent && (
          <Notice tone="ok" title={sent.message}>
            {sent.sentTo && <>Sent to {sent.sentTo}. </>}
            They should have it in a moment. Any earlier code has stopped working.
          </Notice>
        )}

        <CodePad
          label="The code they read out"
          value={code}
          onChange={setCode}
          error={error != null}
          disabled={release.isPending || resend.isPending}
        />
        <p className="small muted center">
          Ask them to read it from their phone. You are not shown it.
        </p>

        <TextField
          label="Who is collecting"
          hint="Write down the name of the person actually standing there, even when it is not the name on the parcel."
          value={name}
          onChange={(event) => setName(event.target.value)}
          autoComplete="off"
          maxLength={200}
          required
          disabled={release.isPending}
        />

        {/*
          Bordered rather than ghost: on a counter tablet a control that does
          not look like one is a control nobody presses, and this is the way
          out of the commonest dead end — somebody at the counter who was never
          sent a code.
        */}
        <Button
          variant="secondary"
          block
          onClick={() => resend.mutate()}
          busy={resend.isPending}
          disabled={release.isPending}
        >
          They have no code — send one
        </Button>
        <p className="small muted center" style={{ marginTop: 'calc(var(--space-3) * -1)' }}>
          It goes to the buyer, who passes it on. The person collecting may have no account of
          her own.
        </p>

        <TextField
          label="ID shown"
          hint="Optional. The number from whatever they showed you."
          value={identity}
          onChange={(event) => setIdentity(event.target.value)}
          autoComplete="off"
          maxLength={60}
          disabled={release.isPending}
        />

        <TextArea
          label="Note"
          hint="Optional. Anything worth recording about this handover."
          value={note}
          onChange={(event) => setNote(event.target.value)}
          maxLength={300}
          rows={2}
          disabled={release.isPending}
        />
      </div>
    </Sheet>
  );
}
