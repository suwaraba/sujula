/**
 * The small pieces every screen is built from.
 *
 * The rule they all obey: colour is never the only thing carrying meaning.
 * Every state has an icon and a word beside it. This screen is read in direct
 * sunlight, through a scratched protector, by someone who may not read the
 * language — and roughly one man in twelve cannot tell the amber from the
 * green whatever the light is like.
 */

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ButtonHTMLAttributes,
  type ReactNode,
} from 'react';

// ── Buttons ──────────────────────────────────────────────────────────────────

type Tone = 'go' | 'stop' | 'brand' | 'plain' | 'ghost';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  tone?: Tone;
  /** The full-width, thumb-height variant that ends a step. */
  major?: boolean;
  icon?: ReactNode;
  busy?: boolean;
}

const TONE_CLASS: Record<Tone, string> = {
  go: 'button--go',
  stop: 'button--stop',
  brand: 'button--brand',
  plain: '',
  ghost: 'button--ghost',
};

export function Button({
  tone = 'plain',
  major = false,
  icon,
  busy = false,
  children,
  className = '',
  disabled,
  ...rest
}: ButtonProps) {
  return (
    <button
      type="button"
      className={`button ${TONE_CLASS[tone]} ${major ? 'button--major' : ''} ${className}`.trim()}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      {...rest}
    >
      {icon && (
        <span className="button__icon" aria-hidden="true">
          {busy ? '…' : icon}
        </span>
      )}
      <span>{children}</span>
    </button>
  );
}

/**
 * The action that finishes what the driver is doing.
 *
 * Pinned to the bottom of the viewport rather than placed in the flow: this
 * phone is held in one hand because the other one has a parcel in it, and the
 * bottom third of the screen is the only part a thumb reaches.
 */
export function PrimaryAction({
  children,
  alone = false,
}: {
  children: ReactNode;
  alone?: boolean;
}) {
  return <div className={`primary-slot ${alone ? 'primary-slot--alone' : ''}`}>{children}</div>;
}

// ── Surfaces ─────────────────────────────────────────────────────────────────

export function Card({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <div className={`card ${className}`.trim()}>{children}</div>;
}

export function Pill({
  tone = 'plain',
  icon,
  children,
}: {
  tone?: 'go' | 'warn' | 'stop' | 'plain';
  icon?: ReactNode;
  children: ReactNode;
}) {
  return (
    <span className={`pill ${tone === 'plain' ? '' : `pill--${tone}`}`.trim()}>
      {icon && <span aria-hidden="true">{icon}</span>}
      {children}
    </span>
  );
}

export function Banner({
  tone = 'plain',
  icon,
  children,
}: {
  tone?: 'go' | 'warn' | 'stop' | 'plain';
  icon?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className={`banner ${tone === 'plain' ? '' : `banner--${tone}`}`.trim()} role="status">
      <span className="banner__icon" aria-hidden="true">
        {icon ?? (tone === 'stop' ? '⚠️' : tone === 'go' ? '✅' : 'ℹ️')}
      </span>
      <div>{children}</div>
    </div>
  );
}

export function Spinner({ label }: { label?: string }) {
  return (
    <div role="status" aria-live="polite">
      <div className="spinner" />
      <span className="visually-hidden">{label ?? 'Loading'}</span>
    </div>
  );
}

export function Empty({ icon, children }: { icon: string; children: ReactNode }) {
  return (
    <div className="empty">
      <span className="empty__icon" aria-hidden="true">
        {icon}
      </span>
      {children}
    </div>
  );
}

// ── Fields ───────────────────────────────────────────────────────────────────

export function Field({
  label,
  error,
  hint,
  children,
}: {
  label: string;
  error?: string | undefined;
  hint?: string;
  children: ReactNode;
}) {
  return (
    <div className="field">
      <label className="field__label">
        {label}
        {hint && <span className="muted"> — {hint}</span>}
      </label>
      {children}
      {error && (
        <p className="field__error" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}

// ── Toasts ───────────────────────────────────────────────────────────────────

type ToastTone = 'go' | 'stop' | 'plain';

const ToastContext = createContext<((message: string, tone?: ToastTone) => void) | null>(null);

/**
 * One line of feedback, spoken as well as shown.
 *
 * `aria-live` is not decoration here: a driver recording a handover is looking
 * at a parcel and a person, not at the phone, and a screen reader or a
 * voice-over user gets the confirmation without having to look down.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState<{ message: string; tone: ToastTone } | null>(null);
  const timer = useRef<number | undefined>(undefined);

  const show = useCallback((message: string, tone: ToastTone = 'plain') => {
    setToast({ message, tone });
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setToast(null), 4500);
  }, []);

  useEffect(() => () => window.clearTimeout(timer.current), []);

  const value = useMemo(() => show, [show]);

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div aria-live="polite" aria-atomic="true">
        {toast && (
          <div className={`toast ${toast.tone === 'plain' ? '' : `toast--${toast.tone}`}`.trim()}>
            {toast.message}
          </div>
        )}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast() {
  const show = useContext(ToastContext);
  if (!show) throw new Error('useToast must be used inside a ToastProvider');
  return show;
}
