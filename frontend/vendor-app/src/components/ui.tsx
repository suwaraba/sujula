import type { ButtonHTMLAttributes, ReactNode } from 'react';

// ── Buttons ─────────────────────────────────────────────────────────────────

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'secondary' | 'danger' | 'ghost' | 'plain';
  size?: 'md' | 'sm';
  block?: boolean;
  busy?: boolean;
};

export function Button({
  variant = 'plain', size = 'md', block = false, busy = false,
  className, children, disabled, ...rest
}: ButtonProps) {
  const classes = [
    'btn',
    variant !== 'plain' ? `btn--${variant}` : '',
    size === 'sm' ? 'btn--sm' : '',
    block ? 'btn--block' : '',
    className ?? '',
  ].filter(Boolean).join(' ');

  return (
    <button className={classes} disabled={disabled || busy} {...rest}>
      {busy && <span className="spinner" aria-hidden="true" />}
      {children}
    </button>
  );
}

// ── Surfaces ────────────────────────────────────────────────────────────────

export function Card({
  title, actions, children, footer, flush = false,
}: {
  title?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  flush?: boolean;
}) {
  return (
    <section className="card">
      {(title || actions) && (
        <header className="card__head">
          {title && <div className="card__title">{title}</div>}
          {actions}
        </header>
      )}
      <div className={flush ? 'card__body card__body--flush' : 'card__body'}>{children}</div>
      {footer && <footer className="card__foot">{footer}</footer>}
    </section>
  );
}

export function PageHeader({
  title, subtitle, actions,
}: { title: ReactNode; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <header className="page__head">
      <div className="page__title">
        <h1>{title}</h1>
        {subtitle && <div className="page__subtitle">{subtitle}</div>}
      </div>
      {actions && <div className="page__actions">{actions}</div>}
    </header>
  );
}

// ── Status ──────────────────────────────────────────────────────────────────

export type Tone = 'neutral' | 'ok' | 'warn' | 'danger' | 'info' | 'accent';

export function Badge({ tone = 'neutral', dot = false, children }: {
  tone?: Tone; dot?: boolean; children: ReactNode;
}) {
  return (
    <span className={tone === 'neutral' ? 'badge' : `badge badge--${tone}`}>
      {dot && <span className="badge__dot" aria-hidden="true" />}
      {children}
    </span>
  );
}

export function Notice({
  tone = 'info', title, children, icon,
}: { tone?: Exclude<Tone, 'neutral' | 'accent'>; title?: ReactNode; children?: ReactNode; icon?: string }) {
  const defaultIcon = { info: 'ℹ', ok: '✓', warn: '⚠', danger: '⚠' }[tone];
  return (
    <div className={`notice notice--${tone}`} role={tone === 'danger' ? 'alert' : undefined}>
      <span className="notice__icon" aria-hidden="true">{icon ?? defaultIcon}</span>
      <div className="notice__body">
        {title && <div className="notice__title">{title}</div>}
        {children}
      </div>
    </div>
  );
}

export function EmptyState({
  icon = '∅', title, children, action,
}: { icon?: string; title: ReactNode; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="empty">
      <div className="empty__icon" aria-hidden="true">{icon}</div>
      <div className="empty__title">{title}</div>
      {children && <p>{children}</p>}
      {action && <div style={{ marginTop: 'var(--space-4)' }}>{action}</div>}
    </div>
  );
}

export function Stat({
  label, value, note, delta,
}: { label: ReactNode; value: ReactNode; note?: ReactNode; delta?: number | null }) {
  return (
    <div className="stat">
      <div className="stat__label">{label}</div>
      <div className="stat__value">{value}</div>
      {delta !== null && delta !== undefined && Number.isFinite(delta) && (
        <div className={`stat__delta ${delta >= 0 ? 'stat__delta--up' : 'stat__delta--down'}`}>
          {delta >= 0 ? '▲' : '▼'} {Math.abs(delta).toFixed(1)}%
        </div>
      )}
      {note && <div className="stat__note">{note}</div>}
    </div>
  );
}

export function KeyValue({ rows }: { rows: [ReactNode, ReactNode][] }) {
  return (
    <dl className="kv">
      {rows.map(([key, value], index) => (
        <div key={index} style={{ display: 'contents' }}>
          <dt className="kv__key">{key}</dt>
          <dd className="kv__value" style={{ margin: 0 }}>{value}</dd>
        </div>
      ))}
    </dl>
  );
}

// ── Loading ─────────────────────────────────────────────────────────────────

export function Skeleton({ height = 16, width = '100%', radius }: {
  height?: number | string; width?: number | string; radius?: number;
}) {
  return (
    <div
      className="skeleton"
      style={{ height, width, ...(radius ? { borderRadius: radius } : {}) }}
      aria-hidden="true"
    />
  );
}

export function SkeletonList({ rows = 4 }: { rows?: number }) {
  return (
    <div className="stack stack--tight" style={{ padding: 'var(--space-4)' }}>
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} height={52} radius={8} />
      ))}
    </div>
  );
}

export function LoadingScreen({ label = 'Loading' }: { label?: string }) {
  return (
    <div className="auth-screen">
      <div className="center muted">
        <div className="spinner" style={{ margin: '0 auto var(--space-3)' }} aria-hidden="true" />
        <div>{label}…</div>
      </div>
    </div>
  );
}
