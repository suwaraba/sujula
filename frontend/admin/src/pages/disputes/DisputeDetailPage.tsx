import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { disputes as disputesApi } from '@/api/endpoints';
import { DISPUTE_OUTCOMES } from '@/api/enums';
import type { DisputeRow } from '@/api/types';
import { ActionModal } from '@/components/ActionModal';
import { DecideButton } from '@/components/Decide';
import { CheckBox, Field, FormRow, MoneyInput, NumberInput, Select, TextArea, TextInput } from '@/components/forms';
import { FxPair, Money } from '@/components/Money';
import { EMPTY_STEP_UP, StepUpFields, stepUpBody, type StepUpValue } from '@/components/StepUp';
import {
  Card,
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
import { DateTime, Hours, nowLocalInput, toLocalDateTime } from '@/components/Time';
import { currencyInfo } from '@/money/currency';
import { keys, useAction } from '@/hooks';

export function DisputeDetailPage() {
  const disputeId = Number(useParams().disputeId);

  const query = useQuery({
    queryKey: keys.dispute(disputeId),
    queryFn: () => disputesApi.detail(disputeId),
    enabled: Number.isFinite(disputeId),
  });

  if (query.isLoading) return <Loading what="Reading the dispute" />;
  if (query.error) return <ErrorBanner error={query.error} />;
  if (!query.data) return null;

  const dispute = query.data;
  const open = dispute.status === 'OPEN' || dispute.status === 'UNDER_REVIEW';

  return (
    <>
      <PageHeader
        title={`Dispute ${dispute.reference}`}
        description={
          <>
            <StatusPill status={dispute.status} /> · {humanise(dispute.reason)} · raised{' '}
            <DateTime value={dispute.raisedAt} /> by {dispute.raisedByName ?? 'the buyer'}
          </>
        }
        actions={
          <div className="row-actions">
            <AssignDispute dispute={dispute} />
            <AddNote dispute={dispute} />
            <RequestCallback dispute={dispute} />
            <ResolveDispute dispute={dispute} disabled={!open} />
          </div>
        }
      />

      <Card
        title="What is at stake"
        subtitle="The money frozen is the vendor's, in the vendor's own currency. What the buyer paid is a different number, joined to it by a rate that was fixed when the order was placed."
      >
        <FxPair
          display={dispute.amount}
          displayCurrency={dispute.currency}
          native={dispute.amountNative}
          nativeCurrency={dispute.nativeCurrency}
          displayLabel="Buyer paid"
          nativeLabel="Frozen on the vendor's line"
        />

        <KeyValueList>
          <KeyValue label="Store">
            {dispute.storeName} <Muted>#{dispute.vendorId}</Muted>
          </KeyValue>
          <KeyValue label="Order">
            <Link to={`/orders/${dispute.orderId}`}>{dispute.orderNumber}</Link>{' '}
            <Muted>sub-order #{dispute.vendorOrderId}</Muted>
          </KeyValue>
          <KeyValue label="Money frozen">
            {dispute.moneyFrozen ? (
              <>
                <Pill tone="bad">Frozen</Pill> <DateTime value={dispute.frozenAt} />
              </>
            ) : (
              <Pill tone="neutral">Not frozen</Pill>
            )}
          </KeyValue>
          <KeyValue label="Assigned to">
            {dispute.assignedToEmail ?? <Muted>nobody</Muted>}{' '}
            <Muted>
              <DateTime value={dispute.assignedAt} />
            </Muted>
          </KeyValue>
          <KeyValue label="Deadline">
            <DateTime value={dispute.dueBy} />{' '}
            <Muted>
              (<Hours value={dispute.hoursRemaining} overdue={dispute.overdue} /> left)
            </Muted>
          </KeyValue>
          <KeyValue label="Evidence">
            {dispute.evidenceCount} pieces · {dispute.messageCount} messages
          </KeyValue>
          {dispute.callbackOutstanding && (
            <KeyValue label="Callback">
              <Pill tone="warn">Somebody still owes a telephone call</Pill>{' '}
              <Link to="/callbacks">open the callback queue</Link>
            </KeyValue>
          )}
          {dispute.outcome && (
            <KeyValue label="Decided">
              <Pill tone="info">{humanise(dispute.outcome)}</Pill>{' '}
              <DateTime value={dispute.resolvedAt} />
            </KeyValue>
          )}
        </KeyValueList>
      </Card>
    </>
  );
}

function AssignDispute({ dispute }: { dispute: DisputeRow }) {
  const [open, setOpen] = useState(false);
  const [assigneeUserId, setAssigneeUserId] = useState<number | ''>('');
  const [note, setNote] = useState('');

  const action = useAction(
    () =>
      disputesApi.assign(dispute.disputeId, {
        assigneeUserId: assigneeUserId === '' ? null : assigneeUserId,
        note: note.trim() || null,
      }),
    {
      invalidate: [keys.dispute(dispute.disputeId), keys.disputes, keys.dashboard],
      message: () => 'Dispute assigned.',
    },
  );

  return (
    <>
      <DecideButton variant="ghost" onClick={() => setOpen(true)}>
        Assign
      </DecideButton>
      <ActionModal
        open={open}
        title="Take this dispute, or give it to somebody"
        description="Leave the id empty to take it yourself."
        submitLabel="Assign it"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
      >
        <Field label="Assign to" hint="A staff user id. Empty takes it yourself.">
          <NumberInput value={assigneeUserId} onChange={setAssigneeUserId} />
        </Field>
        <Field label="Note">
          <TextArea value={note} onChange={setNote} />
        </Field>
      </ActionModal>
    </>
  );
}

function AddNote({ dispute }: { dispute: DisputeRow }) {
  const [open, setOpen] = useState(false);
  const [body, setBody] = useState('');

  const action = useAction(() => disputesApi.addNote(dispute.disputeId, { body: body.trim() }), {
    invalidate: [keys.dispute(dispute.disputeId)],
    message: () => 'Note added for the next agent.',
    onDone: () => setBody(''),
  });

  return (
    <>
      <DecideButton variant="ghost" onClick={() => setOpen(true)}>
        Leave a note
      </DecideButton>
      <ActionModal
        open={open}
        title="A note for the next agent"
        description="Internal. The buyer and the vendor never see it — write what you would want to read at the start of a shift, not what you would say to either of them."
        submitLabel="Add the note"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={body.trim().length < 3}
      >
        <Field label="Note" required>
          <TextArea value={body} rows={5} required minLength={3} onChange={setBody} />
        </Field>
      </ActionModal>
    </>
  );
}

/**
 * Arranging for somebody to be telephoned.
 *
 * A phone number and a language, because the person on the other end may have
 * no account, no app and no email — and may not read the language the platform
 * writes in. That is the same case the recipient's SMS code exists for.
 */
function RequestCallback({ dispute }: { dispute: DisputeRow }) {
  const [open, setOpen] = useState(false);
  const [phone, setPhone] = useState('');
  const [contactName, setContactName] = useState('');
  const [preferredLanguage, setPreferredLanguage] = useState('');
  const [reason, setReason] = useState('');
  const [callBy, setCallBy] = useState(nowLocalInput());

  const action = useAction(
    () =>
      disputesApi.requestCallback(dispute.disputeId, {
        phone: phone.trim(),
        contactName: contactName.trim() || null,
        preferredLanguage: preferredLanguage.trim() || null,
        reason: reason.trim(),
        callBy: toLocalDateTime(callBy),
      }),
    {
      invalidate: [keys.dispute(dispute.disputeId), keys.callbacks, keys.dashboard],
      message: () => 'Callback requested.',
    },
  );

  return (
    <>
      <DecideButton variant="ghost" onClick={() => setOpen(true)}>
        Ask for a telephone call
      </DecideButton>
      <ActionModal
        open={open}
        title="Arrange for somebody to be telephoned"
        description="For a party who cannot be reached any other way. The number may belong to somebody with no account at all — a recipient, a relative — and that is the case this is for."
        submitLabel="Request the call"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={!phone.trim() || reason.trim().length < 5}
      >
        <FormRow>
          <Field label="Telephone number" required>
            <TextInput value={phone} required onChange={setPhone} placeholder="+220…" />
          </Field>
          <Field label="Who to ask for">
            <TextInput value={contactName} onChange={setContactName} />
          </Field>
        </FormRow>

        <FormRow>
          <Field label="Language they speak" hint="So whoever calls can find somebody who does.">
            <TextInput value={preferredLanguage} onChange={setPreferredLanguage} placeholder="wo, fr, en" />
          </Field>
          <Field label="Call by" required>
            <input
              className="input"
              type="datetime-local"
              required
              value={callBy}
              onChange={(event) => setCallBy(event.target.value)}
            />
          </Field>
        </FormRow>

        <Field label="What to ask them" required>
          <TextArea value={reason} required minLength={5} onChange={setReason} />
        </Field>
      </ActionModal>
    </>
  );
}

function ResolveDispute({ dispute, disabled }: { dispute: DisputeRow; disabled: boolean }) {
  const [open, setOpen] = useState(false);
  const [outcome, setOutcome] = useState<string>('');
  const [awarded, setAwarded] = useState('');
  const [requireReturn, setRequireReturn] = useState(false);
  const [resolutionNote, setResolutionNote] = useState('');
  const [stepUp, setStepUp] = useState<StepUpValue>(EMPTY_STEP_UP);

  const action = useAction(
    () =>
      disputesApi.resolve(dispute.disputeId, {
        outcome: outcome as never,
        awardedToBuyerNative: outcome === 'SPLIT' ? awarded : null,
        requireReturn,
        resolutionNote: resolutionNote.trim(),
        ...stepUpBody(stepUp),
      }),
    {
      invalidate: [keys.dispute(dispute.disputeId), keys.disputes, keys.ledger, keys.dashboard],
      message: (decided) => decided.message,
      onDone: () => setStepUp(EMPTY_STEP_UP),
    },
  );

  const scale = currencyInfo(dispute.nativeCurrency)?.minorUnits ?? 2;

  return (
    <>
      <DecideButton
        variant="danger"
        onClick={() => setOpen(true)}
        disabled={disabled}
        title={disabled ? 'This dispute has already been decided.' : undefined}
      >
        Decide it
      </DecideButton>

      <ActionModal
        open={open}
        width="wide"
        title={`Decide ${dispute.reference}`}
        description="Deciding moves the money that follows. The buyer and the vendor are both told, and the ledger entries are written under your name."
        submitLabel="Decide it"
        tone="danger"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={!outcome || resolutionNote.trim().length < 10 || !stepUp.password}
      >
        <p className="notice notice-muted">
          Frozen on the vendor's line:{' '}
          <Money amount={dispute.amountNative} currency={dispute.nativeCurrency} />. The buyer paid{' '}
          <Money amount={dispute.amount} currency={dispute.currency} /> at the rate this order was
          snapshotted at.
        </p>

        <Field label="Outcome" required>
          <Select
            value={outcome}
            required
            options={[
              { value: 'FOR_BUYER', label: 'For the buyer — the whole amount goes back' },
              { value: 'FOR_VENDOR', label: 'For the vendor — the money is released to them' },
              { value: 'SPLIT', label: 'Split — say how much goes back' },
              { value: 'NO_DECISION', label: 'No decision — leave it as it stands' },
            ].filter((option) => DISPUTE_OUTCOMES.includes(option.value as never))}
            placeholder="Choose an outcome"
            onChange={setOutcome}
          />
        </Field>

        {outcome === 'SPLIT' && (
          <Field
            label={`Amount to the buyer, in ${dispute.nativeCurrency}`}
            required
            hint="In the vendor's own currency, because that is the money being divided. The buyer's refund is derived from it at the order's own rate."
          >
            <MoneyInput value={awarded} required minorUnits={scale} min={0} onChange={setAwarded} />
          </Field>
        )}

        <CheckBox
          checked={requireReturn}
          onChange={setRequireReturn}
          label="The buyer must send the goods back first"
          hint="Only where the goods are worth returning. A return the recipient cannot afford to post is a decision against them in practice."
        />

        <Field
          label="Why you decided this"
          required
          hint="Both parties read this. At least a sentence."
        >
          <TextArea value={resolutionNote} rows={4} required minLength={10} onChange={setResolutionNote} />
        </Field>

        <StepUpFields value={stepUp} onChange={setStepUp} what="Deciding a dispute and moving the money" />
      </ActionModal>
    </>
  );
}
