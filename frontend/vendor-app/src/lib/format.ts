import type { Currency } from '@/api/types';

/**
 * Money, at the currency's own scale.
 *
 * XOF has no minor units — 1250.50 CFA is not an amount that exists — so a
 * formatter that always shows two decimals invents a coin. The scale comes from
 * the currency catalogue the server serves, never from a guess and never from
 * the browser's locale data, which disagrees with the catalogue for some of the
 * currencies this marketplace actually prices in.
 *
 * Nothing here rounds for arithmetic. Every figure shown was decided by the
 * server; this only decides how many digits to draw.
 */
export type CurrencyLookup = (code: string) => Currency | undefined;

export function formatMoney(
  amount: number | null | undefined,
  currencyCode: string | null | undefined,
  lookup: CurrencyLookup,
  options: { withCode?: boolean } = {},
): string {
  if (amount === null || amount === undefined || Number.isNaN(amount)) return '—';
  const code = (currencyCode ?? '').toUpperCase();
  const currency = code ? lookup(code) : undefined;
  const minorUnits = currency?.minorUnits ?? 2;

  const digits = new Intl.NumberFormat(undefined, {
    minimumFractionDigits: minorUnits,
    maximumFractionDigits: minorUnits,
  }).format(amount);

  const symbol = currency?.symbol;
  if (symbol && !options.withCode) return `${symbol}${digits}`;
  return code ? `${digits} ${code}` : digits;
}

/** A currency's scale, for an input's `step` so a seller cannot type a coin that does not exist. */
export function amountStep(currencyCode: string | null | undefined, lookup: CurrencyLookup): string {
  const currency = currencyCode ? lookup(currencyCode.toUpperCase()) : undefined;
  const minorUnits = currency?.minorUnits ?? 2;
  return minorUnits === 0 ? '1' : `0.${'0'.repeat(minorUnits - 1)}1`;
}

export function formatNumber(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  return new Intl.NumberFormat().format(value);
}

export function formatPercent(value: number | null | undefined, digits = 1): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  return `${value.toFixed(digits)}%`;
}

/**
 * The application writes `LocalDateTime`, which carries no zone. Treating it as
 * UTC would shift every timestamp by the viewer's offset — an order placed at
 * 09:00 in Banjul would read 10:00 in Madrid and 04:00 in New York, and none of
 * those is what the server meant. It is rendered as written.
 */
function parseServerDateTime(value: string | null | undefined): Date | null {
  if (!value) return null;
  const local = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?/.exec(value);
  if (local) {
    const [, y, mo, d, h, mi, s] = local;
    return new Date(
      Number(y), Number(mo) - 1, Number(d),
      Number(h), Number(mi), Number(s ?? '0'),
    );
  }
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}

const DATE_TIME = new Intl.DateTimeFormat(undefined, {
  day: '2-digit', month: 'short', year: 'numeric',
  hour: '2-digit', minute: '2-digit',
});

const DATE_ONLY = new Intl.DateTimeFormat(undefined, {
  day: '2-digit', month: 'short', year: 'numeric',
});

const TIME_ONLY = new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit' });

export function formatDateTime(value: string | null | undefined): string {
  const date = parseServerDateTime(value);
  return date ? DATE_TIME.format(date) : '—';
}

export function formatDate(value: string | null | undefined): string {
  const date = parseServerDateTime(value);
  return date ? DATE_ONLY.format(date) : '—';
}

export function formatTime(value: string | null | undefined): string {
  const date = parseServerDateTime(value);
  return date ? TIME_ONLY.format(date) : '—';
}

/** "3 minutes ago", "in 2 hours" — for a code that expires while a seller watches. */
export function formatRelative(value: string | null | undefined): string {
  const date = parseServerDateTime(value);
  if (!date) return '—';
  const deltaSeconds = Math.round((date.getTime() - Date.now()) / 1000);
  const abs = Math.abs(deltaSeconds);

  const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });
  if (abs < 60) return rtf.format(deltaSeconds, 'second');
  if (abs < 3600) return rtf.format(Math.round(deltaSeconds / 60), 'minute');
  if (abs < 86400) return rtf.format(Math.round(deltaSeconds / 3600), 'hour');
  return rtf.format(Math.round(deltaSeconds / 86400), 'day');
}

/** Seconds until an instant, floored at zero. Drives the release-code countdown. */
export function secondsUntil(value: string | null | undefined): number {
  const date = parseServerDateTime(value);
  if (!date) return 0;
  return Math.max(0, Math.round((date.getTime() - Date.now()) / 1000));
}

/**
 * A countdown, at the granularity that is actually useful.
 *
 * A release code can be good for three days, and "43191:04" is not a time
 * anybody reads. Under an hour it ticks in minutes and seconds, because that
 * is when a seller is watching it; above that it says days and hours.
 */
export function formatDuration(totalSeconds: number): string {
  if (totalSeconds < 3600) {
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    return `${minutes}:${String(seconds).padStart(2, '0')}`;
  }

  const hours = Math.floor(totalSeconds / 3600);
  if (hours < 24) {
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    return `${hours}h ${minutes}m`;
  }

  const days = Math.floor(hours / 24);
  return `${days} day${days === 1 ? '' : 's'} ${hours % 24}h`;
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
