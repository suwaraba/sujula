import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { payments as paymentsApi } from '@/api/endpoints';
import type { PaymentRow, SliceRow } from '@/api/types';
import { ActionModal } from '@/components/ActionModal';
import { DecideButton } from '@/components/Decide';
import { CheckBox, Field, MoneyInput, TextArea } from '@/components/forms';
import { FxPair, Money } from '@/components/Money';
import { EMPTY_STEP_UP, StepUpFields, stepUpBody, type StepUpValue } from '@/components/StepUp';
import {
  Card,
  ErrorBanner,
  Flags,
  Grid,
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
import { refundNeedsStepUp, REFUND_STEP_UP_ABOVE } from '@/api/policy';
import { currencyInfo } from '@/money/currency';
import { keys, useAction } from '@/hooks';

export function PaymentDetailPage() {
  const paymentId = Number(useParams().paymentId);

  const query = useQuery({
    queryKey: keys.payment(paymentId),
    queryFn: () => paymentsApi.detail(paymentId),
    enabled: Number.isFinite(paymentId),
  });

  if (query.isLoading) return <Loading what="Reading the payment" />;
  if (query.error) return <ErrorBanner error={query.error} />;
  if (!query.data) return null;

  const payment = query.data;

  return (
    <>
      <PageHeader
        title={`Payment on order ${payment.orderNumber}`}
        description={
          <>
            <StatusPill status={payment.status} /> · {humanise(payment.method)} ·{' '}
            <Link to={`/orders/${payment.orderId}`}>open the order</Link>
          </>
        }
      />

      <Flags flags={payment.flags} />

      <Grid columns={2}>
        <Card title="Who paid" subtitle="The payer context.">
          <KeyValueList>
            <KeyValue label="Buyer">{payment.buyerName ?? <Muted>—</Muted>}</KeyValue>
            <KeyValue label="Email">{payment.buyerEmail ?? <Muted>—</Muted>}</KeyValue>
            <KeyValue label="Paid from">{payment.payerCountry ?? <Muted>—</Muted>}</KeyValue>
            <KeyValue label="Charged">
              <Money amount={payment.amount} currency={payment.currency} />
            </KeyValue>
            <KeyValue label="Refunded so far">
              <Money amount={payment.amountRefunded} currency={payment.currency} />
            </KeyValue>
            <KeyValue label="Still refundable">
              <Money amount={payment.refundable} currency={payment.currency} />
            </KeyValue>
          </KeyValueList>
        </Card>

        <Card title="Where the goods go" subtitle="The delivery context. A separate question.">
          <KeyValueList>
            <KeyValue label="Destination">{payment.destinationCountry ?? <Muted>—</Muted>}</KeyValue>
            <KeyValue label="Provider reference">
              {payment.transactionId ? <code>{payment.transactionId}</code> : <Muted>—</Muted>}
            </KeyValue>
            <KeyValue label="Our reference">
              {payment.reference ? <code>{payment.reference}</code> : <Muted>—</Muted>}
            </KeyValue>
            <KeyValue label="Paid at">
              <DateTime value={payment.paidAt} />
            </KeyValue>
            <KeyValue label="Created">
              <DateTime value={payment.createdAt} />
            </KeyValue>
          </KeyValueList>
        </Card>
      </Grid>

      <Card
        title="Sub-orders"
        subtitle="A refund is a refund of one of these. The vendor is owed in their own currency, at the rate snapshotted when the order was placed — so refunding by the vendor's amount and refunding by the buyer's are two different instructions, and both are offered."
      >
        {payment.slices.map((slice) => (
          <SlicePanel key={slice.vendorOrderId} payment={payment} slice={slice} />
        ))}
      </Card>
    </>
  );
}

function SlicePanel({ payment, slice }: { payment: PaymentRow; slice: SliceRow }) {
  return (
    <div className="slice">
      <div className="slice-head">
        <div>
          <strong>{slice.storeName}</strong> <Muted>sub-order #{slice.vendorOrderId}</Muted>{' '}
          <StatusPill status={slice.status} />
          {slice.disputeFrozen && (
            <Pill tone="bad" title="Frozen pending a dispute. Money cannot move until it is decided.">
              Frozen by a dispute
            </Pill>
          )}
          {slice.escrowReleased && <Pill tone="good">Escrow released</Pill>}
        </div>
        <RefundSlice payment={payment} slice={slice} />
      </div>

      <FxPair
        display={slice.total}
        displayCurrency={slice.displayCurrency}
        native={slice.totalNative}
        nativeCurrency={slice.nativeCurrency}
        rate={slice.fxRate}
        rateAt={slice.fxRateAt}
      />

      <KeyValueList>
        <KeyValue label="Already refunded to this vendor's line">
          <Money amount={slice.alreadyRefundedNative} currency={slice.nativeCurrency} />
        </KeyValue>
      </KeyValueList>
    </div>
  );
}

function RefundSlice({ payment, slice }: { payment: PaymentRow; slice: SliceRow }) {
  const [open, setOpen] = useState(false);
  const [full, setFull] = useState(true);
  const [basis, setBasis] = useState<'display' | 'native'>('display');
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');
  const [stepUp, setStepUp] = useState<StepUpValue>(EMPTY_STEP_UP);

  const action = useAction(
    () =>
      paymentsApi.refund(payment.paymentId, {
        vendorOrderId: slice.vendorOrderId,
        amount: full ? null : basis === 'display' ? amount : null,
        amountNative: full ? null : basis === 'native' ? amount : null,
        reason: reason.trim(),
        ...stepUpBody(stepUp),
      }),
    {
      invalidate: [keys.payment(payment.paymentId), keys.payments, keys.ledger, keys.dashboard],
      message: (made) => made.message,
      onDone: () => {
        setStepUp(EMPTY_STEP_UP);
        setAmount('');
        setReason('');
      },
    },
  );

  const currency = basis === 'display' ? slice.displayCurrency : slice.nativeCurrency;
  const scale = currencyInfo(currency)?.minorUnits ?? 2;

  // The server asks for the password again only above its own threshold, so
  // this form does too. Asking for it on every twenty-euro refund is how a
  // step-up prompt becomes something people type through without reading.
  //
  // For a partial refund entered in the vendor's currency the display amount is
  // unknown here — converting it would be the client second-guessing a
  // snapshotted rate — so `refundNeedsStepUp` answers yes and the box appears.
  const displayAmount = full ? slice.total : basis === 'display' ? amount : null;
  const needsStepUp = refundNeedsStepUp(displayAmount);

  return (
    <>
      <DecideButton variant="danger" onClick={() => setOpen(true)} disabled={slice.disputeFrozen}
        title={slice.disputeFrozen ? 'Money on this sub-order is frozen pending a dispute.' : undefined}>
        Refund this vendor's part
      </DecideButton>

      <ActionModal
        open={open}
        width="wide"
        title={`Refund ${slice.storeName}'s sub-order`}
        description="Only this vendor's line. The other vendors on this payment are untouched."
        submitLabel="Refund it"
        tone="danger"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={(!full && !amount) || reason.trim().length < 5}
      >
        <FxPair
          display={slice.total}
          displayCurrency={slice.displayCurrency}
          native={slice.totalNative}
          nativeCurrency={slice.nativeCurrency}
          rate={slice.fxRate}
          rateAt={slice.fxRateAt}
        />

        <CheckBox
          checked={full}
          onChange={setFull}
          label="Refund the whole sub-order"
          hint="Leave this on unless the buyer is getting back part of what they paid this one vendor."
        />

        {!full && (
          <>
            <fieldset className="radio-row">
              <legend>Which currency the amount is in</legend>
              <label className="radio">
                <input
                  type="radio"
                  checked={basis === 'display'}
                  onChange={() => setBasis('display')}
                />
                <span>
                  {slice.displayCurrency} — what the buyer gets back
                </span>
              </label>
              <label className="radio">
                <input type="radio" checked={basis === 'native'} onChange={() => setBasis('native')} />
                <span>
                  {slice.nativeCurrency} — what comes off the vendor's line
                </span>
              </label>
            </fieldset>
            <p className="field-hint">
              The other figure is derived by the server at the rate this order was snapshotted at,
              not at today's rate. That is why you choose which one you mean.
            </p>

            <Field label={`Amount in ${currency}`} required>
              <MoneyInput value={amount} minorUnits={scale} min={0} required onChange={setAmount} />
            </Field>
          </>
        )}

        <Field label="Reason" required hint="The buyer and the vendor are both told this.">
          <TextArea value={reason} required minLength={5} onChange={setReason} />
        </Field>

        {!needsStepUp && (
          <p className="field-hint">
            Refunds over {REFUND_STEP_UP_ABOVE.toLocaleString()} ask for your password again. This
            one is below that.
          </p>
        )}

        {needsStepUp && (
          <StepUpFields value={stepUp} onChange={setStepUp} what="Sending money back to a buyer" />
        )}
      </ActionModal>
    </>
  );
}
