import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { legacyPayments, orders as ordersApi } from '@/api/endpoints';
import { VENDOR_ORDER_STATUSES } from '@/api/enums';
import type { ParcelView, VendorOrderView } from '@/api/types';
import { ActionModal } from '@/components/ActionModal';
import { DecideButton } from '@/components/Decide';
import { CheckBox, Field, FormRow, MoneyInput, Select, TextArea, TextInput } from '@/components/forms';
import { FxPair, Money } from '@/components/Money';
import {
  Card,
  ErrorBanner,
  Grid,
  KeyValue,
  KeyValueList,
  Loading,
  Muted,
  PageHeader,
  Pill,
  StatusPill,
  Warnings,
  humanise,
} from '@/components/primitives';
import { DateTime } from '@/components/Time';
import { currencyInfo } from '@/money/currency';
import { keys, useAction } from '@/hooks';

export function OrderDetailPage() {
  const orderId = Number(useParams().orderId);

  const query = useQuery({
    queryKey: keys.order(orderId),
    queryFn: () => ordersApi.detail(orderId),
    enabled: Number.isFinite(orderId),
  });

  if (query.isLoading) return <Loading what="Reading the order" />;
  if (query.error) return <ErrorBanner error={query.error} />;
  if (!query.data) return null;

  const { summary, slices, ledger, parcels, warnings } = query.data;

  return (
    <>
      <PageHeader
        title={`Order ${summary.orderNumber}`}
        description={
          <>
            Placed <DateTime value={summary.placedAt} /> · <StatusPill status={summary.status} />
          </>
        }
        actions={<CancelOrder orderId={orderId} slices={slices} />}
      />

      <Warnings warnings={warnings} />

      <Grid columns={2}>
        <Card title="Who paid" subtitle="The payer context.">
          <KeyValueList>
            <KeyValue label="Buyer">{summary.buyerName ?? <Muted>—</Muted>}</KeyValue>
            <KeyValue label="Email">{summary.buyerEmail ?? <Muted>—</Muted>}</KeyValue>
            <KeyValue label="Charged">
              <Money amount={summary.total} currency={summary.currency} />
            </KeyValue>
            <KeyValue label="Payment">
              <StatusPill status={summary.paymentStatus} />
            </KeyValue>
          </KeyValueList>
        </Card>

        <Card
          title="Where it goes"
          subtitle="The delivery context. A different question, and routinely a different country."
        >
          <KeyValueList>
            <KeyValue label="City">{summary.destinationCity ?? <Muted>—</Muted>}</KeyValue>
            <KeyValue label="Country">{summary.destinationCountry ?? <Muted>—</Muted>}</KeyValue>
            <KeyValue label="Parcels">{parcels.length}</KeyValue>
            <KeyValue label="Vendors">{summary.vendorOrders}</KeyValue>
          </KeyValueList>
        </Card>
      </Grid>

      <Card
        title="Sub-orders"
        subtitle="One per vendor. Each ships, cancels, refunds and pays out on its own — a partial refund is a refund of one of these, never a proportion of the order."
      >
        {slices.length === 0 ? (
          <Muted>No sub-orders.</Muted>
        ) : (
          slices.map((slice) => <SliceCard key={slice.id} orderId={orderId} slice={slice} />)
        )}
      </Card>

      <Card title="Parcels">
        {parcels.length === 0 ? <Muted>Nothing has been dispatched.</Muted> : parcels.map((parcel) => <ParcelCard key={parcel.id} parcel={parcel} />)}
      </Card>

      <Card
        title="Ledger"
        subtitle="Every entry in the currency it was written in. Nothing here is summed across currencies."
      >
        {ledger.length === 0 ? (
          <Muted>No entries.</Muted>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th scope="col">When</th>
                <th scope="col">Type</th>
                <th scope="col">Store</th>
                <th scope="col">Description</th>
                <th scope="col" className="align-right">
                  Amount
                </th>
                <th scope="col">Available from</th>
              </tr>
            </thead>
            <tbody>
              {ledger.map((line) => (
                <tr key={line.id}>
                  <td>
                    <DateTime value={line.occurredAt} />
                  </td>
                  <td>
                    <Pill tone="neutral">{humanise(line.type)}</Pill>
                  </td>
                  <td>{line.storeName ?? <Muted>—</Muted>}</td>
                  <td>
                    {line.description ?? <Muted>—</Muted>}
                    {line.reference && (
                      <>
                        <br />
                        <Muted>
                          <code>{line.reference}</code>
                        </Muted>
                      </>
                    )}
                  </td>
                  <td className="align-right">
                    <Money amount={line.amount} currency={line.currency} signed />
                  </td>
                  <td>
                    <DateTime value={line.availableFrom} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <PaymentOperations orderId={orderId} />
    </>
  );
}

function SliceCard({ orderId, slice }: { orderId: number; slice: VendorOrderView }) {
  return (
    <div className="slice">
      <div className="slice-head">
        <div>
          <strong>{slice.storeName}</strong> <Muted>#{slice.id}</Muted>{' '}
          <StatusPill status={slice.status} />
          {slice.disputeFrozenAt && (
            <Pill tone="bad" title="Money on this sub-order is frozen pending a dispute.">
              Frozen
            </Pill>
          )}
        </div>
        <ForceStatus orderId={orderId} slice={slice} />
      </div>

      <FxPair
        display={slice.total}
        displayCurrency={slice.currency}
        native={slice.totalNative}
        nativeCurrency={slice.nativeCurrency}
        rate={slice.fxRate}
        rateAt={slice.fxRateAt}
      />

      <KeyValueList>
        <KeyValue label="Accepted">
          <DateTime value={slice.acceptedAt} />
        </KeyValue>
        <KeyValue label="Ready">
          <DateTime value={slice.readyAt} />
        </KeyValue>
        <KeyValue label="Cancelled">
          <DateTime value={slice.cancelledAt} />
        </KeyValue>
        {slice.rejectionReason && <KeyValue label="Rejected because">{slice.rejectionReason}</KeyValue>}
      </KeyValueList>
    </div>
  );
}

function ParcelCard({ parcel }: { parcel: ParcelView }) {
  return (
    <div className="parcel">
      <div className="parcel-head">
        <div>
          <strong className="mono">{parcel.reference}</strong> <StatusPill status={parcel.status} />
          {parcel.trackingCode && (
            <Muted>
              {' '}
              tracking <code>{parcel.trackingCode}</code>
            </Muted>
          )}
        </div>
        <Link className="button button-ghost" to={`/shipments/${parcel.id}/custody-chain`}>
          Custody chain
        </Link>
      </div>

      <KeyValueList>
        <KeyValue label="Store">{parcel.storeName ?? <Muted>—</Muted>}</KeyValue>
        <KeyValue label="Destination city">{parcel.destinationCity ?? <Muted>—</Muted>}</KeyValue>
        <KeyValue label="Failed attempts">{parcel.failedAttempts}</KeyValue>
        {parcel.heldAtPickupPoint && (
          <KeyValue label="Held at">
            {parcel.heldAtPickupPoint}
            {parcel.shelfCode && <Muted> shelf {parcel.shelfCode}</Muted>}
          </KeyValue>
        )}
        <KeyValue label="Collected">
          <DateTime value={parcel.collectedAt} />
        </KeyValue>
        <KeyValue label="Delivered">
          <DateTime value={parcel.deliveredAt} />
        </KeyValue>
      </KeyValueList>

      {parcel.legs.length > 0 && (
        <table className="table table-compact">
          <thead>
            <tr>
              <th scope="col">#</th>
              <th scope="col">Leg</th>
              <th scope="col">Status</th>
              <th scope="col">Driver</th>
              <th scope="col">From → to</th>
              <th scope="col">Accepted</th>
              <th scope="col">Completed</th>
              <th scope="col" className="align-right">
                Earning
              </th>
            </tr>
          </thead>
          <tbody>
            {parcel.legs.map((leg) => (
              <tr key={leg.id}>
                <td>{leg.sequence}</td>
                <td>{humanise(leg.legType)}</td>
                <td>
                  <StatusPill status={leg.status} />
                </td>
                <td>
                  {leg.driverName ?? <Muted>nobody</Muted>}
                  {leg.driverPhone && (
                    <>
                      <br />
                      <Muted>{leg.driverPhone}</Muted>
                    </>
                  )}
                </td>
                <td>
                  {leg.from ?? '—'} → {leg.to ?? '—'}
                </td>
                <td>
                  <DateTime value={leg.acceptedAt} />
                </td>
                <td>
                  <DateTime value={leg.completedAt} />
                </td>
                <td className="align-right">
                  <Money amount={leg.earning} currency={leg.earningCurrency} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function ForceStatus({ orderId, slice }: { orderId: number; slice: VendorOrderView }) {
  const [open, setOpen] = useState(false);
  const [status, setStatus] = useState<string>('');
  const [note, setNote] = useState('');

  const action = useAction(
    (body: { status: string; note: string }) =>
      ordersApi.forceStatus(orderId, slice.id, {
        status: body.status as never,
        note: body.note,
      }),
    {
      invalidate: [keys.order(orderId), keys.orders, keys.dashboard],
      message: (forced) =>
        forced.warning ?? `Status forced from ${humanise(forced.from)} to ${humanise(forced.to)}.`,
    },
  );

  return (
    <>
      <DecideButton variant="ghost" onClick={() => setOpen(true)}>
        Break glass: force status
      </DecideButton>

      <ActionModal
        open={open}
        title="Set a status by hand"
        description="This writes a status without the event that would normally produce it. The custody chain will not have the evidence, and the note you leave is the only record of why. Use it when something outside the system has already happened."
        submitLabel="Force the status"
        tone="danger"
        onClose={() => setOpen(false)}
        onSubmit={async () => {
          await action.mutateAsync({ status, note: note.trim() });
          setStatus('');
          setNote('');
        }}
        disabled={!status || note.trim().length < 5}
      >
        <p className="notice notice-warning">
          Currently <strong>{humanise(slice.status)}</strong> for {slice.storeName}.
        </p>
        <Field label="New status" required>
          <Select
            value={status}
            options={VENDOR_ORDER_STATUSES}
            placeholder="Choose a status"
            onChange={(next) => setStatus(next)}
          />
        </Field>
        <Field label="What actually happened" required hint="This is the only record there will be.">
          <TextArea value={note} onChange={setNote} required minLength={5} />
        </Field>
      </ActionModal>
    </>
  );
}

function CancelOrder({ orderId, slices }: { orderId: number; slices: VendorOrderView[] }) {
  const [open, setOpen] = useState(false);
  const [vendorOrderId, setVendorOrderId] = useState<string>('');
  const [reason, setReason] = useState('');
  const [refund, setRefund] = useState(true);

  const action = useAction(
    () =>
      ordersApi.forceCancel(orderId, {
        vendorOrderId: vendorOrderId ? Number(vendorOrderId) : null,
        reason: reason.trim(),
        refund,
      }),
    {
      invalidate: [keys.order(orderId), keys.orders, keys.payments, keys.dashboard],
      message: (cancelled) => cancelled.message,
    },
  );

  return (
    <>
      <DecideButton variant="danger" onClick={() => setOpen(true)}>
        Cancel
      </DecideButton>

      <ActionModal
        open={open}
        title="Cancel over the top of whatever it was doing"
        description="One vendor's sub-order, or all of them. Cancelling one leaves the others untouched — that is what a sub-order is for."
        submitLabel="Cancel it"
        tone="danger"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={reason.trim().length < 5}
      >
        <Field label="What to cancel" required>
          <Select
            value={vendorOrderId}
            placeholder="Every sub-order on this order"
            options={slices.map((slice) => ({
              value: String(slice.id),
              label: `${slice.storeName} — #${slice.id} (${humanise(slice.status)})`,
            }))}
            onChange={(value) => setVendorOrderId(value)}
          />
        </Field>

        <CheckBox
          checked={refund}
          onChange={setRefund}
          label="Refund what was taken"
          hint="Refunds are per sub-order and go back in the currency the buyer was charged, at the rate the order was priced at."
        />

        <Field label="Reason" required hint="The buyer and the vendors are told this.">
          <TextArea value={reason} onChange={setReason} required minLength={5} />
        </Field>
      </ActionModal>
    </>
  );
}

/**
 * The older payment surface, for payments the platform is still waiting on.
 *
 * `/admin/payments` refunds money already taken. These three are the other
 * half: a bank transfer that arrived and has to be matched, one that never
 * will, and one the provider failed.
 */
function PaymentOperations({ orderId }: { orderId: number }) {
  const [openAction, setOpenAction] = useState<null | 'confirm' | 'cancel' | 'fail'>(null);
  const [amountReceived, setAmountReceived] = useState('');
  const [collectionReference, setCollectionReference] = useState('');
  const [note, setNote] = useState('');
  const [reason, setReason] = useState('');

  const query = useQuery({
    queryKey: keys.legacyPayment(orderId),
    queryFn: () => legacyPayments.forOrder(orderId),
    enabled: Number.isFinite(orderId),
    retry: false,
  });

  const invalidate = [keys.legacyPayment(orderId), keys.order(orderId), keys.payments];

  const confirm = useAction(
    () =>
      legacyPayments.confirmTransfer(orderId, {
        amountReceived: amountReceived === '' ? null : amountReceived,
        collectionReference: collectionReference.trim() || null,
        note: note.trim() || null,
      }),
    { invalidate, message: () => 'Transfer confirmed.' },
  );
  const cancel = useAction(() => legacyPayments.cancel(orderId, reason.trim()), {
    invalidate,
    message: () => 'Payment cancelled.',
  });
  const fail = useAction(() => legacyPayments.markFailed(orderId, reason.trim()), {
    invalidate,
    message: () => 'Payment marked failed.',
  });

  const payment = query.data;
  const scale = currencyInfo(payment?.currency)?.minorUnits ?? 2;

  return (
    <Card
      title="Payment operations"
      subtitle="Matching a bank transfer, or closing one that will never arrive. Refunds are on the payment screen, per vendor."
    >
      {query.error ? (
        <ErrorBanner error={query.error} />
      ) : !payment ? (
        <Muted>No payment recorded against this order.</Muted>
      ) : (
        <>
          <KeyValueList>
            <KeyValue label="Status">
              <StatusPill status={payment.status} />
            </KeyValue>
            <KeyValue label="Method">{humanise(payment.method)}</KeyValue>
            <KeyValue label="Amount">
              <Money amount={payment.amount} currency={payment.currency} />
            </KeyValue>
            <KeyValue label="Refunded">
              <Money amount={payment.amountRefunded} currency={payment.currency} />
            </KeyValue>
            <KeyValue label="Provider reference">
              {payment.transactionId ? <code>{payment.transactionId}</code> : <Muted>—</Muted>}
            </KeyValue>
            {payment.failureReason && <KeyValue label="Failed because">{payment.failureReason}</KeyValue>}
          </KeyValueList>

          <div className="form-actions form-actions-left">
            <DecideButton variant="secondary" onClick={() => setOpenAction('confirm')}>
              Confirm a bank transfer
            </DecideButton>
            <DecideButton variant="ghost" onClick={() => setOpenAction('cancel')}>
              Cancel the payment
            </DecideButton>
            <DecideButton variant="ghost" onClick={() => setOpenAction('fail')}>
              Mark it failed
            </DecideButton>
          </div>
        </>
      )}

      <ActionModal
        open={openAction === 'confirm'}
        title="Confirm a bank transfer arrived"
        description="Record what actually landed in the account, with the bank's own reference. The amount is what was received, not what was expected."
        submitLabel="Confirm it"
        onClose={() => setOpenAction(null)}
        onSubmit={() => confirm.mutateAsync()}
      >
        <FormRow>
          <Field label={`Amount received (${payment?.currency ?? ''})`}>
            <MoneyInput value={amountReceived} minorUnits={scale} onChange={setAmountReceived} />
          </Field>
          <Field label="Bank reference">
            <TextInput value={collectionReference} onChange={setCollectionReference} />
          </Field>
        </FormRow>
        <Field label="Note">
          <TextArea value={note} onChange={setNote} />
        </Field>
      </ActionModal>

      <ActionModal
        open={openAction === 'cancel' || openAction === 'fail'}
        title={openAction === 'cancel' ? 'Cancel the payment' : 'Mark the payment failed'}
        submitLabel={openAction === 'cancel' ? 'Cancel it' : 'Mark it failed'}
        tone="danger"
        onClose={() => setOpenAction(null)}
        onSubmit={() => (openAction === 'cancel' ? cancel.mutateAsync() : fail.mutateAsync())}
        disabled={reason.trim().length < 3}
      >
        <Field label="Reason" required>
          <TextArea value={reason} onChange={setReason} required minLength={3} />
        </Field>
      </ActionModal>
    </Card>
  );
}
