import type { Decimal, IsoDateTime } from '@/api/types';
import { formatCommission, formatMoney, formatRate } from '@/money/currency';
import { Muted } from './primitives';
import { DateTime } from './Time';

/** One amount in one currency, at that currency's own scale. */
export function Money({
  amount,
  currency,
  signed,
  className,
}: {
  amount: Decimal | null | undefined;
  currency: string | null | undefined;
  signed?: boolean;
  className?: string;
}) {
  const negative = Number(amount) < 0;
  return (
    <span className={`money${negative ? ' money-negative' : ''}${className ? ` ${className}` : ''}`}>
      {formatMoney(amount, currency, { signed })}
    </span>
  );
}

/**
 * Several currencies, side by side and never added up.
 *
 * 400 EUR and 12,000 GMD is not 12,400 of anything. The server never sums
 * across currencies and neither does this: the list is the total.
 */
export function MoneyList({
  entries,
  empty = '—',
}: {
  entries: { currency: string; amount: Decimal; label?: string }[];
  empty?: string;
}) {
  if (entries.length === 0) return <Muted>{empty}</Muted>;
  return (
    <ul className="money-list">
      {entries.map((entry) => (
        <li key={`${entry.currency}-${entry.label ?? ''}`}>
          <Money amount={entry.amount} currency={entry.currency} />
          {entry.label && <span className="money-list-label">{entry.label}</span>}
        </li>
      ))}
    </ul>
  );
}

/**
 * The two currencies of an order, with the rate that joined them.
 *
 * The rate and the moment it was taken travel with the pair, because a
 * converted figure without them is a figure nobody can explain later — and the
 * server will never recompute it, so this is the only record there is.
 */
export function FxPair({
  display,
  displayCurrency,
  native,
  nativeCurrency,
  rate,
  rateAt,
  displayLabel = 'Buyer paid',
  nativeLabel = 'Vendor is owed',
}: {
  display: Decimal | null | undefined;
  displayCurrency: string | null | undefined;
  native: Decimal | null | undefined;
  nativeCurrency: string | null | undefined;
  rate?: Decimal | null;
  rateAt?: IsoDateTime | null;
  displayLabel?: string;
  nativeLabel?: string;
}) {
  const sameCurrency =
    Boolean(displayCurrency) &&
    displayCurrency?.toUpperCase() === nativeCurrency?.toUpperCase();

  return (
    <div className="fx-pair">
      <div className="fx-side">
        <span className="fx-label">{displayLabel}</span>
        <Money amount={display} currency={displayCurrency} />
      </div>
      <div className="fx-side">
        <span className="fx-label">{nativeLabel}</span>
        <Money amount={native} currency={nativeCurrency} />
      </div>
      <div className="fx-side fx-rate">
        <span className="fx-label">Rate, snapshotted</span>
        {rate ? (
          <span>
            {formatRate(rate)}{' '}
            <Muted>
              {displayCurrency}→{nativeCurrency}
            </Muted>
            {rateAt && (
              <>
                {' '}
                <Muted>
                  at <DateTime value={rateAt} />
                </Muted>
              </>
            )}
          </span>
        ) : sameCurrency ? (
          <Muted>same currency — no conversion</Muted>
        ) : (
          // Two currencies and no rate on the record. Saying "no conversion"
          // here would be a plain lie, and the wrong kind: somebody reading a
          // GBP figure beside a GMD one needs to know a rate was applied and
          // that this screen is not the one carrying it.
          <Muted>
            converted at a rate this record does not carry — the sub-order holds it
          </Muted>
        )}
      </div>
    </div>
  );
}

export function Rate({ value }: { value: Decimal | null | undefined }) {
  return <span className="mono">{formatRate(value)}</span>;
}

export function Commission({ value }: { value: Decimal | null | undefined }) {
  return <span className="mono">{formatCommission(value)}</span>;
}
