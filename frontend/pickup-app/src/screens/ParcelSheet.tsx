import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { pickupApi } from '@/api/endpoints';
import { ApiError } from '@/api/errors';
import { useCounter } from '@/counter/CounterProvider';
import { useEventId } from '@/lib/hooks';
import { formatDateTime } from '@/lib/format';
import type { CodeResent, StoredParcel } from '@/api/types';
import { Button, Notice } from '@/components/ui';
import { TextArea } from '@/components/form';
import { ConfirmSheet, Sheet } from '@/components/Sheet';
import { useToast } from '@/components/Toast';
import { ParcelFacts } from './Counter';

/**
 * One parcel, and the two things that can happen to it other than being
 * collected: the code being sent again, or it going back.
 */
export function ParcelSheet({
  parcel, onClose, onRelease,
}: { parcel: StoredParcel; onClose: () => void; onRelease: () => void }) {
  const { pointId, refresh } = useCounter();
  const toast = useToast();
  const [returning, setReturning] = useState(false);
  const [resent, setResent] = useState<CodeResent | null>(null);
  const [resendKey, resetResendKey] = useEventId();

  const resend = useMutation({
    mutationFn: () => pickupApi.resendCode(pointId!, parcel.shipmentId, resendKey),
    onSuccess: setResent,
    onError: (cause) => {
      resetResendKey();
      toast.error(
        cause instanceof ApiError && cause.isRateLimited
          ? 'The code has been sent several times already. Wait a little before sending another.'
          : cause instanceof ApiError
            ? cause.message
            : 'Could not send the code.',
      );
    },
  });

  return (
    <Sheet
      title={parcel.recipientName ?? parcel.reference}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>Close</Button>
          <Button variant="primary" onClick={onRelease}>Hand over</Button>
        </>
      }
    >
      <div className="stack">
        <ParcelFacts parcel={parcel} />

        {parcel.note && <Notice tone="info" title="Note">{parcel.note}</Notice>}

        {resent ? (
          <Notice tone={resent.sent ? 'ok' : 'warn'} title={resent.message}>
            {resent.sent && resent.sentTo && <>Sent to {resent.sentTo}. </>}
            {resent.expiresAt && <>It stops working {formatDateTime(resent.expiresAt)}. </>}
            {resent.requestsRemaining > 0
              ? `${resent.requestsRemaining} more send${resent.requestsRemaining === 1 ? '' : 's'} allowed.`
              : 'No more sends allowed for this parcel.'}
          </Notice>
        ) : (
          <div className="stack stack--tight">
            <Button
              variant="secondary"
              block
              onClick={() => resend.mutate()}
              busy={resend.isPending}
            >
              Send the code again
            </Button>
            <p className="small muted">
              It goes to the buyer, who passes it on — the person collecting may have no account
              and no email of her own. Any earlier code stops working. You are not shown it.
            </p>
          </div>
        )}

        {parcel.overdue ? (
          <Button variant="danger" block onClick={() => setReturning(true)}>
            Send it back
          </Button>
        ) : (
          <Notice tone="info">
            It can go back once the deadline passes on {formatDateTime(parcel.storageDeadline)}.
            Until then somebody may be travelling to collect it, and that is not the counter's
            decision to take.
          </Notice>
        )}
      </div>

      {returning && (
        <ReturnConfirm
          parcel={parcel}
          onClose={() => setReturning(false)}
          onDone={() => {
            setReturning(false);
            refresh();
            onClose();
          }}
        />
      )}
    </Sheet>
  );
}

function ReturnConfirm({
  parcel, onClose, onDone,
}: { parcel: StoredParcel; onClose: () => void; onDone: () => void }) {
  const { pointId } = useCounter();
  const toast = useToast();
  const [note, setNote] = useState('');
  const [eventId, resetEventId] = useEventId();
  const [error, setError] = useState<string | null>(null);

  const send = useMutation({
    mutationFn: () =>
      pickupApi.returnParcel(
        pointId!,
        parcel.shipmentId,
        { ...(note.trim() ? { note: note.trim() } : {}), clientEventId: eventId },
        eventId,
      ),
    onSuccess: (result) => {
      toast.success(
        result.duplicate
          ? 'This parcel was already on its way back.'
          : result.message || 'It is going back.',
      );
      onDone();
    },
    onError: (cause) => {
      resetEventId();
      setError(cause instanceof ApiError ? cause.message : 'Could not send it back.');
    },
  });

  return (
    <ConfirmSheet
      title="Send this parcel back?"
      confirmLabel="Send it back"
      danger
      onConfirm={() => { setError(null); send.mutate(); }}
      onClose={onClose}
      busy={send.isPending}
    >
      <div className="stack">
        {error && <Notice tone="danger">{error}</Notice>}
        <p>
          <strong>{parcel.recipientName ?? parcel.reference}</strong> has been here since{' '}
          {formatDateTime(parcel.storedAt)} and nobody has come for it. It goes back to the seller
          and comes off your shelf.
        </p>
        <TextArea
          label="Note"
          hint="Optional. Anything the seller should know."
          value={note}
          onChange={(event) => setNote(event.target.value)}
          maxLength={400}
          rows={3}
        />
      </div>
    </ConfirmSheet>
  );
}
