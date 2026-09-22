import type { IsoDate, IsoDateTime } from '@/api/types';
import { Muted } from './primitives';

/**
 * `LocalDateTime` on the wire carries no zone, so it is rendered as written
 * rather than shifted into the reader's own. A dispute deadline that moves by
 * an hour depending on who opens the screen is a deadline two agents disagree
 * about.
 */
function parse(value: string | null | undefined): Date | null {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

const DATE_TIME = new Intl.DateTimeFormat('en-GB', {
  year: 'numeric',
  month: 'short',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
});

const DATE_ONLY = new Intl.DateTimeFormat('en-GB', {
  year: 'numeric',
  month: 'short',
  day: '2-digit',
});

export function DateTime({ value }: { value: IsoDateTime | null | undefined }) {
  const date = parse(value);
  if (!date) return <Muted>—</Muted>;
  return (
    <time className="timestamp" dateTime={value ?? undefined} title={value ?? undefined}>
      {DATE_TIME.format(date)}
    </time>
  );
}

export function DateOnly({ value }: { value: IsoDate | null | undefined }) {
  const date = parse(value);
  if (!date) return <Muted>—</Muted>;
  return (
    <time className="timestamp" dateTime={value ?? undefined}>
      {DATE_ONLY.format(date)}
    </time>
  );
}

/** "3 days ago" — for queue ages, where the exact minute is not the point. */
export function Ago({ value }: { value: IsoDateTime | null | undefined }) {
  const date = parse(value);
  if (!date) return <Muted>—</Muted>;

  const seconds = Math.round((Date.now() - date.getTime()) / 1000);
  const relative = new Intl.RelativeTimeFormat('en-GB', { numeric: 'auto' });
  const units: [Intl.RelativeTimeFormatUnit, number][] = [
    ['year', 31_536_000],
    ['month', 2_592_000],
    ['day', 86_400],
    ['hour', 3_600],
    ['minute', 60],
  ];
  for (const [unit, size] of units) {
    if (Math.abs(seconds) >= size) {
      return (
        <time className="timestamp" dateTime={value ?? undefined} title={value ?? undefined}>
          {relative.format(-Math.round(seconds / size), unit)}
        </time>
      );
    }
  }
  return <time className="timestamp">just now</time>;
}

export function Hours({ value, overdue }: { value: number | null | undefined; overdue?: boolean }) {
  if (value === null || value === undefined) return <Muted>—</Muted>;
  const label = value >= 48 ? `${Math.round(value / 24)}d` : `${Math.round(value)}h`;
  return <span className={overdue ? 'overdue' : undefined}>{label}</span>;
}

/** Today, as `yyyy-MM-dd`, for date inputs that default to now. */
export function today(): string {
  return new Date().toISOString().slice(0, 10);
}

/** Now, as the `datetime-local` input wants it. */
export function nowLocalInput(): string {
  const now = new Date();
  const offset = now.getTimezoneOffset() * 60_000;
  return new Date(now.getTime() - offset).toISOString().slice(0, 16);
}

/** A `datetime-local` value as `LocalDateTime` — seconds included. */
export function toLocalDateTime(value: string): string {
  return value.length === 16 ? `${value}:00` : value;
}
