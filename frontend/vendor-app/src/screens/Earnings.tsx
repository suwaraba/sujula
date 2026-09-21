import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { moneyApi } from '@/api/endpoints/money';
import { ApiError } from '@/api/errors';
import { useCurrencies, useIdempotencyKey } from '@/lib/hooks';
import { formatDate, formatDateTime, humanise } from '@/lib/format';
import { useStore } from '@/store/StoreProvider';
import type { CurrencyBalance } from '@/api/types';
import {
  Badge, Button, Card, EmptyState, Notice, PageHeader, Skeleton, SkeletonList, Stat, type Tone,
} from '@/components/ui';
import { SelectField, TextArea } from '@/components/form';
import { Sheet } from '@/components/Sheet';
import { Pagination } from '@/components/Pagination';
import { useToast } from '@/components/Toast';

const TABS = ['Balance', 'Transactions', 'Payouts'] as const;

export function Earnings() {
  const [tab, setTab] = useState<(typeof TABS)[number]>('Balance');

  return (
    <div className="page stack">
      <PageHeader
        title="Earnings"
        subtitle="In your own currency, at the rate each order was struck at."
      />

      <div className="tabs" role="tablist">
        {TABS.map((name) => (
          <button
            key={name}
            type="button"
            role="tab"
            aria-selected={tab === name}
            className={tab === name ? 'tabs__item is-active' : 'tabs__item'}
            onClick={() => setTab(name)}
          >
            {name}
          </button>
        ))}
      </div>

      {tab === 'Balance' && <BalancePanel />}
      {tab === 'Transactions' && <TransactionsPanel />}
      {tab === 'Payouts' && <PayoutsPanel />}
    </div>
  );
}

/**
 * What is owed, per currency.
 *
 * A list rather than a total, and deliberately so. A seller who lists in GMD
 * and has been paid for an order placed in EUR holds two balances, and adding
 * them would mean picking a rate — which would be a number nobody could
 * reconstruct later and which nobody actually owes anybody.
 */
function BalancePanel() {
  const { money } = useCurrencies();
  const { store } = useStore();
  const [requesting, setRequesting] = useState<CurrencyBalance | null>(null);

  const balance = useQuery({ queryKey: ['balance'], queryFn: () => moneyApi.balance() });

  if (balance.isLoading) {
    return <div className="grid grid--2"><Skeleton height={160} /><Skeleton height={160} /></div>;
  }

  const rows = balance.data?.byCurrency ?? [];

  if (rows.length === 0) {
    return (
      <Card>
        <EmptyState icon="◫" title="Nothing yet">
          Money appears here once an order you have sent is delivered and released.
        </EmptyState>
      </Card>
    );
  }

  return (
    <div className="stack">
      {balance.data?.note && <Notice tone="info">{balance.data.note}</Notice>}

      {!store?.payoutDestination && (
        <Notice tone="warn" title="No payout account">
          We have nowhere to send your money.{' '}
          <a href="/store/payouts">Add a bank account or mobile money number</a>.
        </Notice>
      )}

      <div className="grid grid--2">
        {rows.map((row) => (
          <Card
            key={row.currency}
            title={`Balance in ${row.currency}`}
            footer={
              <Button
                variant="primary"
                size="sm"
                onClick={() => setRequesting(row)}
                disabled={row.available <= 0 || !store?.payoutDestination}
              >
                Ask to be paid
              </Button>
            }
          >
            <div className="stack stack--tight">
              <Stat
                label="Available now"
                value={money(row.available, row.currency)}
                note="Delivered, released, and yours"
              />
              <div className="grid grid--2">
                <Stat label="Held until delivery" value={money(row.onHold, row.currency)} />
                <Stat label="Pending" value={money(row.pending, row.currency)} />
              </div>
              {row.atRisk > 0 && (
                <Notice tone="warn">
                  {money(row.atRisk, row.currency)} is frozen against an open dispute.
                </Notice>
              )}
            </div>
          </Card>
        ))}
      </div>

      <p className="small muted">
        Read as at {formatDateTime(balance.data?.asAt)}.
      </p>

      {requesting && (
        <RequestPayoutSheet balance={requesting} onClose={() => setRequesting(null)} />
      )}
    </div>
  );
}

function RequestPayoutSheet({
  balance, onClose,
}: { balance: CurrencyBalance; onClose: () => void }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const { money } = useCurrencies();
  const { store } = useStore();
  const [note, setNote] = useState('');
  const [key, resetKey] = useIdempotencyKey();
  const [error, setError] = useState<string | null>(null);

  const request = useMutation({
    mutationFn: () =>
      moneyApi.requestPayout(
        { currency: balance.currency, ...(note.trim() ? { note: note.trim() } : {}) },
        key,
      ),
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: ['balance'] });
      void queryClient.invalidateQueries({ queryKey: ['payouts'] });
      toast.success(result.message || `Requested ${money(result.amount, result.currency)}.`);
      onClose();
    },
    onError: (cause) => {
      resetKey();
      setError(cause instanceof ApiError ? cause.message : 'Could not request the payout.');
    },
  });

  const destination = store?.payoutDestination;

  return (
    <Sheet
      title={`Ask to be paid ${balance.currency}`}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={request.isPending}>Cancel</Button>
          <Button variant="primary" onClick={() => request.mutate()} busy={request.isPending}>
            Request it
          </Button>
        </>
      }
    >
      <div className="stack">
        {error && <Notice tone="danger">{error}</Notice>}

        <Stat label="Available" value={money(balance.available, balance.currency)} />

        {destination ? (
          <Notice tone="info" title="Going to">
            {destination.accountType === 'MOBILE_MONEY'
              ? `${destination.mobileMoneyProvider ?? 'Mobile money'} ending ${destination.mobileMoneyLast4 ?? '••••'}`
              : `${destination.bankName ?? 'Bank account'} ending ${destination.accountNumberLast4 ?? destination.ibanLast4 ?? '••••'}`}
            {' — '}{destination.accountHolderName}
          </Notice>
        ) : (
          <Notice tone="danger">No payout account is set up.</Notice>
        )}

        <p className="muted small">
          A request is reviewed before the money moves. You are paid in {balance.currency}, which
          is your shop's own currency — no conversion happens at this point.
        </p>

        <TextArea
          label="Note" hint="Optional." value={note}
          onChange={(event) => setNote(event.target.value)} maxLength={300} rows={2}
        />
      </div>
    </Sheet>
  );
}

function TransactionsPanel() {
  const [page, setPage] = useState(0);
  const [currency, setCurrency] = useState('');
  const { money } = useCurrencies();

  const balance = useQuery({ queryKey: ['balance'], queryFn: () => moneyApi.balance() });

  const transactions = useQuery({
    queryKey: ['transactions', { currency, page }],
    queryFn: () => moneyApi.transactions({ ...(currency ? { currency } : {}), page, size: 30 }),
  });

  return (
    <div className="stack">
      {(balance.data?.byCurrency.length ?? 0) > 1 && (
        <SelectField
          label="Currency"
          value={currency}
          onChange={(event) => { setCurrency(event.target.value); setPage(0); }}
        >
          <option value="">All currencies</option>
          {balance.data!.byCurrency.map((row) => (
            <option key={row.currency} value={row.currency}>{row.currency}</option>
          ))}
        </SelectField>
      )}

      <Card flush>
        {transactions.isLoading ? (
          <SkeletonList rows={6} />
        ) : (transactions.data?.entries.length ?? 0) === 0 ? (
          <EmptyState icon="◫" title="No entries yet" />
        ) : (
          <>
            <div className="list">
              {transactions.data!.entries.map((entry) => (
                <div key={entry.id} className="list__item">
                  <div className="list__main">
                    <div className="list__title" style={{ whiteSpace: 'normal' }}>
                      {entry.description ?? humanise(entry.type)}
                    </div>
                    <div className="list__meta">
                      {formatDateTime(entry.occurredAt)}
                      {entry.orderNumber && ` · ${entry.orderNumber}`}
                    </div>
                    <div className="row" style={{ gap: 'var(--space-2)', marginTop: 4 }}>
                      {entry.heldInEscrow && (
                        <Badge tone="warn">
                          Held{entry.availableFrom ? ` until ${formatDate(entry.availableFrom)}` : ''}
                        </Badge>
                      )}
                      {entry.fx && (
                        <Badge tone="info">
                          from {entry.fx.paidIn} at {entry.fx.rate}
                        </Badge>
                      )}
                    </div>
                  </div>
                  <div className="list__side">
                    <div
                      className="num"
                      style={{ fontWeight: 650, color: entry.amount >= 0 ? 'var(--ok)' : 'var(--danger)' }}
                    >
                      {entry.amount >= 0 ? '+' : ''}{money(entry.amount, entry.currency)}
                    </div>
                  </div>
                </div>
              ))}
            </div>
            <Pagination
              page={transactions.data!.page}
              totalPages={transactions.data!.totalPages}
              totalElements={transactions.data!.totalEntries}
              onChange={setPage}
              unit="entries"
            />
          </>
        )}
      </Card>

      {transactions.data?.note && <p className="small muted">{transactions.data.note}</p>}
    </div>
  );
}

const PAYOUT_TONES: Record<string, Tone> = {
  REQUESTED: 'warn',
  APPROVED: 'info',
  PROCESSING: 'info',
  PAID: 'ok',
  FAILED: 'danger',
  CANCELLED: 'neutral',
};

function PayoutsPanel() {
  const [page, setPage] = useState(0);
  const { money } = useCurrencies();

  const payouts = useQuery({
    queryKey: ['payouts', page],
    queryFn: () => moneyApi.payouts({ page, size: 25 }),
  });

  return (
    <Card flush>
      {payouts.isLoading ? (
        <SkeletonList rows={5} />
      ) : (payouts.data?.payouts.length ?? 0) === 0 ? (
        <EmptyState icon="◱" title="No payouts yet">
          When you have an available balance, ask to be paid from the Balance tab.
        </EmptyState>
      ) : (
        <>
          <div className="list">
            {payouts.data!.payouts.map((payout) => (
              <div key={payout.id} className="list__item">
                <div className="list__main">
                  <div className="list__title mono">{payout.reference}</div>
                  <div className="list__meta">
                    Asked {formatDateTime(payout.requestedAt)}
                    {payout.processedAt && ` · paid ${formatDateTime(payout.processedAt)}`}
                    {payout.period && ` · ${payout.period}`}
                  </div>
                  {payout.failureReason && (
                    <div className="small" style={{ color: 'var(--danger)', marginTop: 4 }}>
                      {payout.failureReason}
                    </div>
                  )}
                </div>
                <div className="list__side">
                  <div className="num" style={{ fontWeight: 650 }}>
                    {money(payout.amount, payout.currency)}
                  </div>
                  <Badge tone={PAYOUT_TONES[payout.status] ?? 'neutral'}>
                    {humanise(payout.status)}
                  </Badge>
                </div>
              </div>
            ))}
          </div>
          <Pagination
            page={payouts.data!.page}
            totalPages={payouts.data!.totalPages}
            totalElements={payouts.data!.totalPayouts}
            onChange={setPage}
            unit="payouts"
          />
        </>
      )}
    </Card>
  );
}
