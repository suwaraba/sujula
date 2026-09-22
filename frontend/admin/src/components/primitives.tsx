import type { ReactNode } from 'react';
import { ApiError, NetworkError } from '@/api/client';

export function PageHeader({
  title,
  description,
  actions,
}: {
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <header className="page-header">
      <div>
        <h1 className="page-title">{title}</h1>
        {description && <p className="page-description">{description}</p>}
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </header>
  );
}

export function Card({
  title,
  subtitle,
  actions,
  children,
  tone,
}: {
  title?: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  tone?: 'default' | 'warning' | 'danger';
}) {
  return (
    <section className={`card${tone && tone !== 'default' ? ` card-${tone}` : ''}`}>
      {(title || actions) && (
        <div className="card-head">
          <div>
            {title && <h2 className="card-title">{title}</h2>}
            {subtitle && <p className="card-subtitle">{subtitle}</p>}
          </div>
          {actions && <div className="card-actions">{actions}</div>}
        </div>
      )}
      <div className="card-body">{children}</div>
    </section>
  );
}

export function Grid({ children, columns = 2 }: { children: ReactNode; columns?: number }) {
  return (
    <div className="grid" style={{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }}>
      {children}
    </div>
  );
}

export function KeyValue({ label, children }: { label: ReactNode; children: ReactNode }) {
  return (
    <div className="kv">
      <dt className="kv-key">{label}</dt>
      <dd className="kv-value">{children}</dd>
    </div>
  );
}

export function KeyValueList({ children }: { children: ReactNode }) {
  return <dl className="kv-list">{children}</dl>;
}

export function Muted({ children, className }: { children: ReactNode; className?: string }) {
  return <span className={className ? `muted ${className}` : 'muted'}>{children}</span>;
}

export function EmptyState({ children }: { children: ReactNode }) {
  return <p className="empty-state">{children}</p>;
}

export function Loading({ what = 'Loading' }: { what?: string }) {
  return <p className="muted loading">{what}…</p>;
}

/**
 * Turns a thrown error into something an agent can act on.
 *
 * A 403 here is the ordinary answer for support on a decision surface, not a
 * fault, so it is worded as the rule rather than as a failure. A 404 on a row
 * that belongs to somebody else is the right answer too — the server withholds
 * even the existence of the row — so it is never dressed up as a bug.
 */
export function ErrorBanner({ error, what }: { error: unknown; what?: string }) {
  if (!error) return null;

  if (error instanceof NetworkError) {
    return (
      <p className="notice notice-error" role="alert">
        {error.message}
      </p>
    );
  }

  if (error instanceof ApiError) {
    const fields = Object.entries(error.fieldErrors);
    return (
      <div className="notice notice-error" role="alert">
        <p>
          {error.isForbidden
            ? 'Only an administrator can do that. Support can read this screen but not decide on it.'
            : error.message}
        </p>
        {fields.length > 0 && (
          <ul className="notice-fields">
            {fields.map(([field, message]) => (
              <li key={field}>
                <code>{field}</code>: {message}
              </li>
            ))}
          </ul>
        )}
        {error.body?.reference && (
          <p className="notice-reference">
            Quote reference <code>{error.body.reference}</code> when reporting this.
          </p>
        )}
      </div>
    );
  }

  return (
    <p className="notice notice-error" role="alert">
      {what ? `${what} failed.` : 'Something went wrong.'} {(error as Error)?.message}
    </p>
  );
}

export function Pill({
  children,
  tone = 'neutral',
  title,
}: {
  children: ReactNode;
  tone?: 'neutral' | 'good' | 'warn' | 'bad' | 'info';
  title?: string;
}) {
  return (
    <span className={`pill pill-${tone}`} title={title}>
      {children}
    </span>
  );
}

/** Statuses, coloured by what they mean rather than by where they sit in an enum. */
const TONES: Record<string, 'neutral' | 'good' | 'warn' | 'bad' | 'info'> = {
  // Good
  APPROVED: 'good',
  ACTIVE: 'good',
  PUBLISHED: 'good',
  ACCEPTED: 'good',
  DELIVERED: 'good',
  PAID: 'good',
  COMPLETED: 'good',
  SETTLED: 'good',
  SUCCEEDED: 'good',
  READY: 'good',
  RESOLVED: 'good',
  // Bad
  REJECTED: 'bad',
  SUSPENDED: 'bad',
  FAILED: 'bad',
  CANCELLED: 'bad',
  ATTEMPT_FAILED: 'bad',
  ABANDONED: 'bad',
  BAN: 'bad',
  EXPIRED: 'bad',
  // Warn
  PENDING: 'warn',
  PENDING_KYC: 'warn',
  IN_REVIEW: 'warn',
  UNDER_REVIEW: 'warn',
  SUBMITTED: 'warn',
  AWAITING_APPROVAL: 'warn',
  AWAITING_COLLECTION: 'warn',
  ON_HOLD: 'warn',
  OPEN: 'warn',
  RETURNED: 'warn',
  PARTIALLY_REFUNDED: 'warn',
  REFUNDED: 'warn',
  QUEUED: 'warn',
  SUSPENSION: 'warn',
  WARNING: 'warn',
  // Info / in motion
  PROCESSING: 'info',
  PREPARING: 'info',
  IN_TRANSIT: 'info',
  OUT_FOR_DELIVERY: 'info',
  SHIPPED: 'info',
  DRIVER_OFFERED: 'info',
  DRIVER_ASSIGNED: 'info',
  RUNNING: 'info',
  BUILDING: 'info',
  CONFIRMED: 'info',
};

export function StatusPill({ status, title }: { status: string | null | undefined; title?: string }) {
  if (!status) return <Muted>—</Muted>;
  return (
    <Pill tone={TONES[status] ?? 'neutral'} title={title}>
      {humanise(status)}
    </Pill>
  );
}

/** `READY_FOR_PICKUP` → `Ready for pickup`. Enum names are not prose. */
export function humanise(value: string): string {
  const spaced = value.replace(/_/g, ' ').toLowerCase();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

export function Flags({ flags }: { flags: string[] | null | undefined }) {
  if (!flags || flags.length === 0) return null;
  return (
    <span className="flag-row">
      {flags.map((flag) => (
        <Pill key={flag} tone="warn">
          {humanise(flag)}
        </Pill>
      ))}
    </span>
  );
}

export function Warnings({ warnings }: { warnings: string[] | null | undefined }) {
  if (!warnings || warnings.length === 0) return null;
  return (
    <ul className="warning-list">
      {warnings.map((warning, index) => (
        <li key={index}>{warning}</li>
      ))}
    </ul>
  );
}
