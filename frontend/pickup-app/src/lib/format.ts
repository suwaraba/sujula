import type { Currency } from '@/api/types';

/**
 * Money, at the currency's own scale.
 *
 * XOF has no minor units, so a formatter that always shows two decimals
 * invents a coin. The scale comes from the currency catalogue the server
 * serves. A counter's commission is small and paid in real money; rounding it
 * to the wrong number of places is a discrepancy somebody has to chase.
 *
 * Nothing here does arithmetic. Every figure shown was decided by the server.
 */
export type CurrencyLookup = (code: string) => Currency | undefined;

export function formatMoney(
  amount: number | null | undefined,
  currencyCode: string | null | undefined,
  lookup: CurrencyLookup,
): string {
  if (amount === null || amount === undefined || Number.isNaN(amount)) return '—';
  const code = (currencyCode ?? '').toUpperCase();
  const currency = code ? lookup(code) : undefined;
  const minorUnits = currency?.minorUnits ?? 2;

  const digits = new Intl.NumberFormat(undefined, {
    minimumFractionDigits: minorUnits,
    maximumFractionDigits: minorUnits,
  }).format(amount);

  return currency?.symbol ? `${currency.symbol}${digits}` : code ? `${digits} ${code}` : digits;
}

export function formatNumber(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  return new Intl.NumberFormat().format(value);
}

/**
 * The application writes `LocalDateTime`, which carries no zone. Treating it as
 * UTC would shift every timestamp by the tablet's offset — a parcel stored at
 * 09:00 in Serrekunda would read differently on a device set to another
 * country, and none of those is what the server meant. Rendered as written.
 */
function parseServerDateTime(value: string | null | undefined): Date | null {
  if (!value) return null;
  const local = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2}))?)?/.exec(value);
  if (local) {
    const [, y, mo, d, h, mi, s] = local;
    return new Date(
      Number(y), Number(mo) - 1, Number(d),
      Number(h ?? '0'), Number(mi ?? '0'), Number(s ?? '0'),
    );
  }
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}

const DATE_TIME = new Intl.DateTimeFormat(undefined, {
  day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit',
});
const DATE_ONLY = new Intl.DateTimeFormat(undefined, {
  day: '2-digit', month: 'short', year: 'numeric',
});

export function formatDateTime(value: string | null | undefined): string {
  const date = parseServerDateTime(value);
  return date ? DATE_TIME.format(date) : '—';
}

export function formatDate(value: string | null | undefined): string {
  const date = parseServerDateTime(value);
  return date ? DATE_ONLY.format(date) : '—';
}

/** "3 days left", "2 days over" — what an operator working the shelf needs. */
export function formatDeadline(daysRemaining: number, overdue: boolean): string {
  if (overdue) {
    const over = Math.abs(daysRemaining);
    return over === 0 ? 'Due back today' : `${over} day${over === 1 ? '' : 's'} over`;
  }
  if (daysRemaining === 0) return 'Last day';
  return `${daysRemaining} day${daysRemaining === 1 ? '' : 's'} left`;
}

/** SCREAMING_SNAKE_CASE as a human reads it. */
export function humanise(value: string | null | undefined): string {
  if (!value) return '—';
  return value
    .toLowerCase()
    .split('_')
    .map((word) => (word ? word[0]!.toUpperCase() + word.slice(1) : word))
    .join(' ');
}

/** Today, as the API's `LocalDate` wants it. */
export function isoToday(): string {
  return new Date().toISOString().slice(0, 10);
}

export function isoDaysAgo(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() - days);
  return date.toISOString().slice(0, 10);
}
