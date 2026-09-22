import { useEffect, useRef, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { Button } from './ui';

/**
 * A modal that behaves like a sheet on a tablet.
 *
 * Escape closes it, focus moves in and is kept there, and the page behind does
 * not scroll — which matters more here than usual, because the thing inside is
 * often a keypad being worked while somebody reads numbers aloud.
 */
export function Sheet({
  title, onClose, children, footer, dismissible = true,
}: {
  title: ReactNode;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
  /** False while a handover is in flight: closing mid-request loses the answer. */
  dismissible?: boolean;
}) {
  const panel = useRef<HTMLDivElement>(null);
  const restore = useRef<Element | null>(null);

  useEffect(() => {
    restore.current = document.activeElement;
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    panel.current
      ?.querySelector<HTMLElement>('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])')
      ?.focus();

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && dismissible) {
        event.stopPropagation();
        onClose();
        return;
      }
      if (event.key !== 'Tab' || !panel.current) return;

      const items = panel.current.querySelectorAll<HTMLElement>(
        'button:not(:disabled), [href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])',
      );
      if (items.length === 0) return;
      const first = items[0]!;
      const last = items[items.length - 1]!;

      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };

    document.addEventListener('keydown', onKeyDown, true);
    return () => {
      document.removeEventListener('keydown', onKeyDown, true);
      document.body.style.overflow = previous;
      (restore.current as HTMLElement | null)?.focus?.();
    };
  }, [onClose, dismissible]);

  return createPortal(
    <div
      className="sheet-backdrop"
      onMouseDown={(event) => {
        if (dismissible && event.target === event.currentTarget) onClose();
      }}
    >
      <div className="sheet" ref={panel} role="dialog" aria-modal="true" aria-labelledby="sheet-title">
        <header className="sheet__head">
          <h2 className="sheet__title" id="sheet-title">{title}</h2>
          {dismissible && (
            <Button variant="ghost" size="sm" onClick={onClose} aria-label="Close">✕</Button>
          )}
        </header>
        <div className="sheet__body">{children}</div>
        {footer && <footer className="sheet__foot">{footer}</footer>}
      </div>
    </div>,
    document.body,
  );
}

/** A confirmation that names what will happen rather than asking "Are you sure?". */
export function ConfirmSheet({
  title, confirmLabel, onConfirm, onClose, busy = false, danger = false, children,
}: {
  title: ReactNode;
  confirmLabel: string;
  onConfirm: () => void;
  onClose: () => void;
  busy?: boolean;
  danger?: boolean;
  children: ReactNode;
}) {
  return (
    <Sheet
      title={title}
      onClose={onClose}
      dismissible={!busy}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={busy}>Cancel</Button>
          <Button variant={danger ? 'danger' : 'primary'} onClick={onConfirm} busy={busy}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      {children}
    </Sheet>
  );
}
