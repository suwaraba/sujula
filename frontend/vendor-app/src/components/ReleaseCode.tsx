import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { ordersApi } from '@/api/endpoints/orders';
import { ApiError } from '@/api/errors';
import { useIdempotencyKey, useTicker } from '@/lib/hooks';
import { formatDuration, secondsUntil } from '@/lib/format';
import { Button, Card, Notice, Skeleton } from '@/components/ui';
import { ConfirmSheet } from '@/components/Sheet';
import { useToast } from '@/components/Toast';

/**
 * The collection code.
 *
 * The one secret on this surface, and it is handled like one. The server serves
 * it `no-store`, and this component matches that: the code is held in React
 * state for as long as the screen is open and written nowhere else — not to
 * storage, not to the URL, not to a log. React Query is told never to keep it
 * (`gcTime: 0`) so it does not sit in a cache after the seller navigates away.
 *
 * A driver reads it out; the seller checks it matches. Nobody takes the parcel
 * without it.
 */
export function ReleaseCodePanel({ orderId }: { orderId: number }) {
  const toast = useToast();
  const [confirming, setConfirming] = useState(false);
  const [key, resetKey] = useIdempotencyKey();

  const code = useQuery({
    queryKey: ['release-code', orderId],
    queryFn: () => ordersApi.releaseCode(orderId),
    gcTime: 0,
    staleTime: 0,
    refetchOnWindowFocus: false,
  });

  const regenerate = useMutation({
    mutationFn: () => ordersApi.regenerateReleaseCode(orderId, key),
    onSuccess: async () => {
      resetKey();
      setConfirming(false);
      await code.refetch();
      toast.success('A new code has been issued. The old one no longer works.');
    },
    onError: (error) => {
      resetKey();
      setConfirming(false);
      toast.error(
        error instanceof ApiError && error.isRateLimited
          ? 'Too many codes issued recently. Wait a little before asking for another.'
          : error instanceof ApiError
            ? error.message
            : 'Could not issue a new code.',
      );
    },
  });

  // Re-renders once a second so the countdown moves — but only in its last
  // hour, where it is shown to the second. A code good for three days does not
  // need a timer waking the screen 86,400 times.
  const ticking = code.data != null && secondsUntil(code.data.expiresAt) < 3600;
  useTicker(ticking);

  if (code.isLoading) {
    return <Card><Skeleton height={120} /></Card>;
  }

  if (code.isError || !code.data) {
    return (
      <Notice tone="warn" title="Could not show the collection code">
        Reload the order to try again.
      </Notice>
    );
  }

  const remaining = secondsUntil(code.data.expiresAt);
  const expired = remaining <= 0;
  const urgent = remaining > 0 && remaining < 300;

  return (
    <Card>
      <div className="stack">
        <div className="code-panel">
          <div className="code-panel__label">Collection code</div>
          <div className="code-panel__code">{expired ? '——————' : code.data.code}</div>
          <div className={urgent ? 'code-panel__expiry is-urgent' : 'code-panel__expiry'}>
            {expired
              ? 'Expired — issue a new one'
              : `Expires in ${formatDuration(remaining)}`}
          </div>
        </div>

        <Notice tone="info" title="Read this to the driver — do not send it">
          The driver types it in to take the parcel. Anybody holding this code can collect these
          goods, so do not photograph it, message it, or leave it on the counter.
        </Notice>

        <div className="row row--between">
          <span className="small muted">
            Issued {code.data.timesIssued} time{code.data.timesIssued === 1 ? '' : 's'}
          </span>
          <Button variant="secondary" size="sm" onClick={() => setConfirming(true)}>
            {expired ? 'Issue a new code' : 'Somebody saw it — replace it'}
          </Button>
        </div>
      </div>

      {confirming && (
        <ConfirmSheet
          title="Issue a new collection code?"
          confirmLabel="Issue a new code"
          onConfirm={() => regenerate.mutate()}
          onClose={() => setConfirming(false)}
          busy={regenerate.isPending}
        >
          <p>
            The code on screen stops working immediately. If a driver is already on their way with
            the old one, they will not be able to collect until you read them the new one.
          </p>
        </ConfirmSheet>
      )}
    </Card>
  );
}
