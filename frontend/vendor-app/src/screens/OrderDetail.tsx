import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ordersApi } from '@/api/endpoints/orders';
import { ApiError } from '@/api/errors';
import { useCurrencies, useIdempotencyKey } from '@/lib/hooks';
import { formatDateTime, humanise } from '@/lib/format';
import type { OrderDetail as Order, OrderLine } from '@/api/types';
import {
  Badge, Button, Card, EmptyState, KeyValue, Notice, PageHeader, Skeleton,
} from '@/components/ui';
import { TextArea, TextField } from '@/components/form';
import { Sheet, ConfirmSheet } from '@/components/Sheet';
import { Thumb } from '@/components/Thumb';
import { CustodyChain, OrderStatusBadge } from '@/components/OrderStatus';
import { ReleaseCodePanel } from '@/components/ReleaseCode';
import { useToast } from '@/components/Toast';

export function OrderDetail() {
  const { orderId } = useParams<{ orderId: string }>();
  const id = Number(orderId);
  const queryClient = useQueryClient();
  const toast = useToast();
  const { money } = useCurrencies();

  const [rejecting, setRejecting] = useState(false);
  const [scanning, setScanning] = useState<OrderLine | null>(null);
  const [confirmReady, setConfirmReady] = useState(false);

  const order = useQuery({
    queryKey: ['orders', 'detail', id],
    queryFn: () => ordersApi.get(id),
    enabled: Number.isFinite(id),
    refetchInterval: 60_000,
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['orders'] });
  };

  if (order.isLoading) {
    return (
      <div className="page stack">
        <Skeleton height={32} width={220} />
        <Skeleton height={180} />
        <Skeleton height={240} />
      </div>
    );
  }

  if (order.isError) {
    const notFound = order.error instanceof ApiError && order.error.isNotFound;
    return (
      <div className="page">
        <EmptyState
          icon="∅"
          title={notFound ? 'That order is not here' : 'Could not load this order'}
          action={<Link to="/orders" className="btn btn--secondary">Back to orders</Link>}
        >
          {notFound
            ? 'It may belong to another shop, or it may never have existed.'
            : 'Check your connection and try again.'}
        </EmptyState>
      </div>
    );
  }

  const data = order.data!;

  return (
    <div className="page stack stack--loose">
      <PageHeader
        title={data.orderNumber}
        subtitle={<>Placed {formatDateTime(data.placedAt)}</>}
        actions={<OrderStatusBadge status={data.status} />}
      />

      <OrderActions
        order={data}
        onReject={() => setRejecting(true)}
        onReady={() => setConfirmReady(true)}
        onDone={invalidate}
      />

      {data.status === 'READY_FOR_PICKUP' && <ReleaseCodePanel orderId={data.id} />}

      <div className="grid grid--2">
        <Card title="What to pack" flush>
          <div className="list">
            {data.lines.map((line) => (
              <div key={line.lineId} className="list__item" style={{ alignItems: 'flex-start' }}>
                <Thumb src={line.imageUrl} alt="" />
                <div className="list__main">
                  <div className="list__title" style={{ whiteSpace: 'normal' }}>
                    {line.productName}
                  </div>
                  <div className="list__meta">
                    {line.selectedOptions ?? line.variantSku ?? line.sku ?? '—'}
                  </div>
                  {line.serialised && (
                    <HandsetProgress
                      line={line}
                      // Binding a handset is something you do while the parcel
                      // is still on your bench. Once it is with a driver there
                      // is nothing left to scan, and the server refuses it.
                      canScan={data.status === 'PENDING' || data.status === 'PREPARING'}
                      onScan={() => setScanning(line)}
                    />
                  )}
                </div>
                <div className="list__side">
                  <div style={{ fontWeight: 700, fontSize: 17 }}>×{line.quantity}</div>
                  <div className="small muted num">{money(line.lineTotal, data.currency)}</div>
                </div>
              </div>
            ))}
          </div>
        </Card>

        <div className="stack">
          <Card title="Where it goes">
            {data.shipping ? (
              <div className="stack stack--tight">
                <KeyValue
                  rows={[
                    ['Recipient', data.shipping.recipientName ?? '—'],
                    ['Town', data.shipping.town ?? '—'],
                    ['Country', data.shipping.country ?? '—'],
                    ['How', humanise(data.shipping.deliveryMode)],
                    ...(data.shipping.pickupPointName
                      ? [['Pickup point', data.shipping.pickupPointName] as [string, string]]
                      : []),
                    ...(data.shipping.phoneHint
                      ? [['Phone', <span key="p" className="mono">{data.shipping.phoneHint}</span>] as [string, React.ReactNode]]
                      : []),
                  ]}
                />
                <p className="small muted">
                  You are given a name and a town, not a street. The driver resolves the address
                  by scanning the label.
                </p>
              </div>
            ) : (
              <p className="muted">No delivery details on this order yet.</p>
            )}
          </Card>

          <Card title="What it pays">
            <div className="stack stack--tight">
              <KeyValue
                rows={[
                  ['Goods', money(data.goodsSubtotal, data.currency)],
                  ...(data.discount
                    ? [['Discount', `−${money(data.discount, data.currency)}`] as [string, string]]
                    : []),
                  ['Total', money(data.goodsTotal, data.currency)],
                  [
                    // commissionRate arrives as a percentage (10 = 10%), not a
                    // fraction — see the ledger's own formula.
                    `Commission (${data.commissionRate}%)`,
                    `−${money(data.commission, data.currency)}`,
                  ],
                  [
                    <strong key="p">Your payout</strong>,
                    <strong key="v" className="num">{money(data.payout, data.currency)}</strong>,
                  ],
                ]}
              />
              {data.fx && <FxNote fx={data.fx} />}
              {data.couponCode && (
                <p className="small muted">Buyer used coupon <span className="mono">{data.couponCode}</span>.</p>
              )}
            </div>
          </Card>
        </div>
      </div>

      <Card title="Where the parcel is">
        <CustodyChain
          status={data.status}
          placedAt={data.placedAt}
          acceptedAt={data.acceptedAt}
          readyAt={data.readyAt}
          collectedAt={data.collectedAt}
          recipientName={data.shipping?.recipientName ?? null}
          pickupPointName={data.shipping?.pickupPointName ?? null}
          deliveryMode={data.shipping?.deliveryMode ?? null}
        />
      </Card>

      {data.rejectionReason && (
        <Notice tone="danger" title="You turned this order down">
          {data.rejectionReason}
        </Notice>
      )}

      {rejecting && (
        <RejectSheet
          orderId={data.id}
          onClose={() => setRejecting(false)}
          onDone={() => {
            setRejecting(false);
            invalidate();
            toast.success('Order turned down. A refund has been requested for the buyer.');
          }}
        />
      )}

      {scanning && (
        <ScanSheet
          orderId={data.id}
          line={scanning}
          onClose={() => setScanning(null)}
          onDone={invalidate}
        />
      )}

      {confirmReady && (
        <ReadyConfirm
          order={data}
          onClose={() => setConfirmReady(false)}
          onDone={() => {
            setConfirmReady(false);
            invalidate();
          }}
        />
      )}
    </div>
  );
}

/**
 * The rungs a seller can actually climb.
 *
 * Accept, pack, hand over — and cancel while nothing has moved. There is no
 * control here for "shipped" or "delivered", because the server does not accept
 * either from this account: `isVendorSettable()` allows PREPARING,
 * READY_FOR_PICKUP and CANCELLED. Drawing a button for the other two would be
 * drawing a button that 400s.
 */
function OrderActions({
  order, onReject, onReady, onDone,
}: { order: Order; onReject: () => void; onReady: () => void; onDone: () => void }) {
  const toast = useToast();
  const [acceptKey, resetAcceptKey] = useIdempotencyKey();

  const accept = useMutation({
    mutationFn: () => ordersApi.accept(order.id, acceptKey),
    onSuccess: (result) => {
      toast.success(result.message || 'Order accepted.');
      onDone();
    },
    onError: (error) => {
      resetAcceptKey();
      toast.error(error instanceof ApiError ? error.message : 'Could not accept the order.');
    },
  });

  const downloadLabel = useMutation({
    mutationFn: async () => {
      const blob = await ordersApi.label(order.id);
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `${order.orderNumber}-label.pdf`;
      document.body.append(link);
      link.click();
      link.remove();
      // Revoked on the next tick; a click that has not started yet loses the blob.
      setTimeout(() => URL.revokeObjectURL(url), 10_000);
    },
    onError: () => toast.error('Could not fetch the label.'),
  });

  const canCancel = order.allowedNextStatuses?.includes('CANCELLED') ?? false;

  if (order.status === 'PENDING') {
    return (
      <Card>
        <div className="stack">
          <Notice tone="warn" title="This order is waiting on you">
            The buyer has already paid. Accept it to start packing, or turn it down and their
            money is refunded.
          </Notice>
          <div className="row">
            <Button variant="primary" onClick={() => accept.mutate()} busy={accept.isPending}>
              Accept this order
            </Button>
            <Button variant="secondary" onClick={onReject} disabled={accept.isPending}>
              Turn it down
            </Button>
          </div>
        </div>
      </Card>
    );
  }

  if (order.status === 'PREPARING') {
    return (
      <Card>
        <div className="stack">
          {!order.readyToPack && (
            <Notice tone="warn" title="Scan the handsets first">
              A line tracked handset by handset needs every phone bound to it before the parcel
              can be sealed. That is the last moment anyone can say which handset is in the box.
            </Notice>
          )}
          <div className="row">
            <Button variant="primary" onClick={onReady} disabled={!order.readyToPack}>
              Packed — call a driver
            </Button>
            <Button
              variant="secondary"
              onClick={() => downloadLabel.mutate()}
              busy={downloadLabel.isPending}
            >
              Print the label
            </Button>
            {canCancel && (
              <Button variant="ghost" onClick={onReject}>Cancel this order</Button>
            )}
          </div>
        </div>
      </Card>
    );
  }

  if (order.status === 'READY_FOR_PICKUP') {
    return (
      <Card>
        <div className="row">
          <Button
            variant="secondary"
            onClick={() => downloadLabel.mutate()}
            busy={downloadLabel.isPending}
          >
            Print the label
          </Button>
          {canCancel && <Button variant="ghost" onClick={onReject}>Cancel this order</Button>}
        </div>
      </Card>
    );
  }

  if (order.status === 'SHIPPED') {
    return (
      <Notice tone="info" title="The driver has it">
        Nothing more for you to do. It is marked delivered when the recipient reads back the code
        texted to their phone — not by you, and not by the driver alone.
      </Notice>
    );
  }

  if (order.status === 'DELIVERED') {
    return (
      <Notice tone="ok" title="Delivered">
        The recipient proved it with the code sent to their phone. Your payout is released on the
        platform's settlement schedule — see <Link to="/earnings">Earnings</Link>.
      </Notice>
    );
  }

  return null;
}

function HandsetProgress({
  line, canScan, onScan,
}: { line: OrderLine; canScan: boolean; onScan: () => void }) {
  const assigned = line.assignedImeis?.length ?? 0;
  const outstanding = line.handsetsOutstanding ?? 0;
  // The server reports what is still owed rather than what is required, so the
  // total is derived rather than read — and stays right if a line is edited.
  const required = assigned + outstanding;
  const complete = outstanding <= 0;

  return (
    <div className="stack stack--tight" style={{ marginTop: 'var(--space-2)' }}>
      <div className="row" style={{ gap: 'var(--space-2)' }}>
        <Badge tone={complete ? 'ok' : 'warn'}>
          {assigned} of {required} handset{required === 1 ? '' : 's'} scanned
        </Badge>
        {!complete && canScan && (
          <Button size="sm" variant="secondary" onClick={onScan}>Scan a handset</Button>
        )}
      </div>
      {line.assignedImeis && line.assignedImeis.length > 0 && (
        <div className="small mono muted">{line.assignedImeis.join(' · ')}</div>
      )}
    </div>
  );
}

/**
 * How the buyer's currency became this seller's, and when.
 *
 * Shown because a payout figure without its rate is a number nobody can
 * reconstruct once the rate table has moved on. The rate was snapshotted when
 * the order was placed and is never recomputed (C2).
 */
function FxNote({ fx }: { fx: NonNullable<Order['fx']> }) {
  return (
    <div className="notice notice--info">
      <span className="notice__icon" aria-hidden="true">⇄</span>
      <div className="notice__body small">
        <div className="notice__title">Converted from {fx.paidIn}</div>
        1 {fx.settledIn} = <span className="mono">{fx.rate}</span> {fx.paidIn}, taken{' '}
        {formatDateTime(fx.rateAt)}
        {fx.source ? ` (${fx.source.toLowerCase()})` : ''}. This rate is fixed to this order and
        is not recalculated.
      </div>
    </div>
  );
}

function RejectSheet({
  orderId, onClose, onDone,
}: { orderId: number; onClose: () => void; onDone: () => void }) {
  const [reason, setReason] = useState('');
  const [key, resetKey] = useIdempotencyKey();
  const toast = useToast();

  const reject = useMutation({
    mutationFn: () => ordersApi.reject(orderId, reason.trim(), key),
    onSuccess: onDone,
    onError: (error) => {
      resetKey();
      toast.error(error instanceof ApiError ? error.message : 'Could not turn the order down.');
    },
  });

  const tooShort = reason.trim().length < 5;

  return (
    <Sheet
      title="Turn this order down"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={reject.isPending}>Keep it</Button>
          <Button
            variant="danger"
            onClick={() => reject.mutate()}
            busy={reject.isPending}
            disabled={tooShort}
          >
            Turn it down
          </Button>
        </>
      }
    >
      <div className="stack">
        <Notice tone="warn">
          The goods go back on your shelf and a refund is requested for the buyer. Only your lines
          are cancelled — anything they bought from another shop on the same payment is untouched.
        </Notice>
        <TextArea
          label="Why"
          hint="The buyer reads this, and they have already paid. Between 5 and 400 characters."
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          maxLength={400}
          rows={4}
          autoFocus
        />
      </div>
    </Sheet>
  );
}

function ScanSheet({
  orderId, line, onClose, onDone,
}: { orderId: number; line: OrderLine; onClose: () => void; onDone: () => void }) {
  const [imei, setImei] = useState('');
  const [key, resetKey] = useIdempotencyKey();
  const toast = useToast();

  const assign = useMutation({
    mutationFn: () => ordersApi.assignImei(orderId, line.lineId, imei, key),
    onSuccess: (result) => {
      setImei('');
      resetKey();
      onDone();
      toast.success(
        result.lineComplete
          ? 'Every handset on this line is scanned.'
          : `${result.boundToLine} of ${result.requiredForLine} scanned.`,
      );
      if (result.lineComplete) onClose();
    },
    onError: (error) => {
      resetKey();
      toast.error(error instanceof ApiError ? error.message : 'Could not scan that handset.');
    },
  });

  const valid = /^\d{15}$/.test(imei.trim());

  return (
    <Sheet
      title={`Scan a handset onto ${line.productName}`}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={assign.isPending}>Done</Button>
          <Button
            variant="primary"
            onClick={() => assign.mutate()}
            busy={assign.isPending}
            disabled={!valid}
          >
            Bind this handset
          </Button>
        </>
      }
    >
      <div className="stack">
        <p className="muted">
          One phone, one line. A line of two phones takes two scans. The handset must be yours, on
          the shelf, and the model the buyer actually ordered.
        </p>
        <TextField
          label="IMEI"
          hint="Exactly 15 digits. Dial *#06# on the handset to see it."
          className="input input--mono"
          value={imei}
          onChange={(event) => setImei(event.target.value.replace(/\D/g, '').slice(0, 15))}
          inputMode="numeric"
          maxLength={15}
          autoFocus
          onKeyDown={(event) => {
            if (event.key === 'Enter' && valid) assign.mutate();
          }}
        />
      </div>
    </Sheet>
  );
}

function ReadyConfirm({
  order, onClose, onDone,
}: { order: Order; onClose: () => void; onDone: () => void }) {
  const [key, resetKey] = useIdempotencyKey();
  const toast = useToast();

  const ready = useMutation({
    mutationFn: () => ordersApi.ready(order.id, key),
    onSuccess: () => {
      toast.success('A driver has been called. Your collection code is on the order.');
      onDone();
    },
    onError: (error) => {
      resetKey();
      toast.error(error instanceof ApiError ? error.message : 'Could not mark it ready.');
    },
  });

  return (
    <ConfirmSheet
      title="Packed and ready?"
      confirmLabel="Yes, call a driver"
      onConfirm={() => ready.mutate()}
      onClose={onClose}
      busy={ready.isPending}
    >
      <div className="stack">
        <p>
          This calls a driver to <strong>{order.orderNumber}</strong> and issues the collection
          code the driver must present to take the parcel.
        </p>
        <Notice tone="info">
          Nobody can take this parcel without that code — which is why it is shown to you and to
          nobody else.
        </Notice>
      </div>
    </ConfirmSheet>
  );
}
