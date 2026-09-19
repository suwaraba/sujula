import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { payouts as payoutsApi } from '@/api/endpoints';
import type { BatchItem, BatchRow } from '@/api/types';
import { ActionModal } from '@/components/ActionModal';
import { DecideButton } from '@/components/Decide';
import { Field, TextArea } from '@/components/forms';
import { Money } from '@/components/Money';
import { EMPTY_STEP_UP, StepUpFields, stepUpBody, type StepUpValue } from '@/components/StepUp';
import { useAuth } from '@/auth/AuthContext';
import {
  Card,
  ErrorBanner,
  KeyValue,
  KeyValueList,
  Loading,
  Muted,
  PageHeader,
  StatusPill,
  Warnings,
} from '@/components/primitives';
import { DateTime } from '@/components/Time';
import { keys, useAction } from '@/hooks';

export function BatchDetailPage() {
  const batchId = Number(useParams().batchId);

  const query = useQuery({
    queryKey: keys.batch(batchId),
    queryFn: () => payoutsApi.batch(batchId),
    enabled: Number.isFinite(batchId),
  });

  if (query.isLoading) return <Loading what="Reading the run" />;
  if (query.error) return <ErrorBanner error={query.error} />;
  if (!query.data) return null;

  const batch = query.data;

  return (
    <>
      <PageHeader
        title={`Payout run ${batch.reference}`}
        description={
          <>
            <StatusPill status={batch.status} /> · {batch.itemCount} transfers in {batch.currency}
          </>
        }
        actions={
          <div className="row-actions">
            <ApproveBatch batch={batch} />
            <CancelBatch batch={batch} />
          </div>
        }
      />

      <Warnings warnings={batch.warnings} />

      <Card title="The run">
        <KeyValueList>
          <KeyValue label="Total">
            <Money amount={batch.total} currency={batch.currency} />
          </KeyValue>
          <KeyValue label="Prepared by">
            {batch.preparedByEmail ?? <Muted>—</Muted>} <DateTime value={batch.preparedAt} />
          </KeyValue>
          <KeyValue label="Released by">
            {batch.approvedByEmail ?? <Muted>nobody yet</Muted>}{' '}
            <DateTime value={batch.approvedAt} />
          </KeyValue>
          {batch.note && <KeyValue label="Note">{batch.note}</KeyValue>}
          {batch.exclusions && <KeyValue label="Left out">{batch.exclusions}</KeyValue>}
        </KeyValueList>
      </Card>

      <Card title="Transfers" subtitle="Each one to a vendor, in the vendor's own currency.">
        <div className="table-scroll">
          <table className="table">
            <thead>
              <tr>
                <th scope="col">Store</th>
                <th scope="col" className="align-right">
                  Amount
                </th>
                <th scope="col">Status</th>
                <th scope="col">Destination</th>
                <th scope="col" className="align-right">
                  Attempts
                </th>
                <th scope="col">Failed because</th>
                <th scope="col" />
              </tr>
            </thead>
            <tbody>
              {batch.items.map((item) => (
                <tr key={item.payoutId}>
                  <td>
                    <strong>{item.storeName}</strong>
                    <br />
                    <Muted>#{item.vendorId}</Muted>
                  </td>
                  <td className="align-right">
                    <Money amount={item.amount} currency={item.currency} />
                  </td>
                  <td>
                    <StatusPill status={item.status} />
                  </td>
                  <td>
                    <Muted>{item.bankAccountSummary ?? '—'}</Muted>
                  </td>
                  <td className="align-right">{item.attempts}</td>
                  <td>{item.failureReason ?? <Muted>—</Muted>}</td>
                  <td>
                    <RetryItem item={item} batchId={batchId} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </>
  );
}

/**
 * Releasing a run.
 *
 * The server refuses a release by the person who prepared it. This mirrors that
 * before the call rather than after the refusal, so the reason is visible on
 * the disabled button rather than arriving as an error.
 */
function ApproveBatch({ batch }: { batch: BatchRow }) {
  const { profile } = useAuth();
  const [open, setOpen] = useState(false);
  const [note, setNote] = useState('');
  const [stepUp, setStepUp] = useState<StepUpValue>(EMPTY_STEP_UP);

  const action = useAction(
    () => payoutsApi.approveBatch(batch.batchId, { note: note.trim() || null, ...stepUpBody(stepUp) }),
    {
      invalidate: [keys.batch(batch.batchId), keys.batches, keys.balances, keys.dashboard],
      message: (saved) => saved.message,
      onDone: () => setStepUp(EMPTY_STEP_UP),
    },
  );

  const ownRun = profile?.id !== undefined && profile.id === batch.preparedByUserId;
  const releasable = batch.status === 'AWAITING_APPROVAL' || batch.status === 'DRAFT';

  return (
    <>
      <DecideButton
        onClick={() => setOpen(true)}
        disabled={ownRun || !releasable}
        title={
          ownRun
            ? 'You assembled this run. Somebody else releases it — that is what the second pair of eyes is for.'
            : !releasable
              ? `A run that is ${batch.status.toLowerCase()} cannot be released.`
              : undefined
        }
      >
        Release the run
      </DecideButton>

      <ActionModal
        open={open}
        title="Release this payout run"
        description="Money leaves the platform when you confirm. Check the total and the currency against what you expected before you do."
        submitLabel="Release it"
        tone="danger"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={!stepUp.password}
      >
        <p className="notice notice-warning">
          <Money amount={batch.total} currency={batch.currency} /> across {batch.itemCount} transfers.
        </p>
        <Field label="Note">
          <TextArea value={note} onChange={setNote} />
        </Field>
        <StepUpFields value={stepUp} onChange={setStepUp} what="Releasing money to vendors" />
      </ActionModal>
    </>
  );
}

function CancelBatch({ batch }: { batch: BatchRow }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');

  const action = useAction(() => payoutsApi.cancelBatch(batch.batchId, { reason: reason.trim() }), {
    invalidate: [keys.batch(batch.batchId), keys.batches, keys.balances],
    message: (saved) => saved.message,
  });

  const abandonable = batch.status === 'DRAFT' || batch.status === 'AWAITING_APPROVAL';

  return (
    <>
      <DecideButton
        variant="ghost"
        onClick={() => setOpen(true)}
        disabled={!abandonable}
        title={abandonable ? undefined : 'A run that has been released cannot be abandoned.'}
      >
        Abandon the run
      </DecideButton>
      <ActionModal
        open={open}
        title="Abandon this run before it is released"
        description="The balances go back to being payable and can be gathered into another run."
        submitLabel="Abandon it"
        tone="danger"
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

function RetryItem({ item, batchId }: { item: BatchItem; batchId: number }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');

  const action = useAction(() => payoutsApi.retryItem(item.payoutId, { reason: reason.trim() }), {
    invalidate: [keys.batch(batchId), keys.batches, keys.dashboard],
    message: (retried) => retried.message,
  });

  if (item.status !== 'FAILED') return null;

  return (
    <>
      <DecideButton variant="ghost" onClick={() => setOpen(true)}>
        Try again
      </DecideButton>
      <ActionModal
        open={open}
        title={`Retry the transfer to ${item.storeName}`}
        description={item.failureReason ?? undefined}
        submitLabel="Try again"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={reason.trim().length < 3}
      >
        <Field
          label="Why it will work this time"
          required
          hint="A retry against an unchanged bank detail fails the same way. Say what changed."
        >
          <TextArea value={reason} onChange={setReason} required minLength={3} />
        </Field>
      </ActionModal>
    </>
  );
}
