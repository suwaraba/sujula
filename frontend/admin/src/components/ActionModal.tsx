import { useState, type FormEvent, type ReactNode } from 'react';
import { ErrorBanner } from './primitives';
import { Modal } from './Modal';

/**
 * A decision, in a box, with somewhere for the refusal to land.
 *
 * Every write on this surface is audited with a reason, and most of them tell
 * the person they were done to. So the shape is always the same: say what will
 * happen, collect why, submit once, and show the server's own words if it
 * refuses rather than a generic failure.
 */
export function ActionModal({
  open,
  onClose,
  title,
  description,
  submitLabel,
  tone = 'primary',
  onSubmit,
  children,
  width,
  disabled,
}: {
  open: boolean;
  onClose(): void;
  title: ReactNode;
  description?: ReactNode;
  submitLabel: string;
  tone?: 'primary' | 'danger';
  onSubmit(): Promise<unknown>;
  children: ReactNode;
  width?: 'normal' | 'wide';
  disabled?: boolean;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await onSubmit();
      onClose();
    } catch (caught) {
      setError(caught);
    } finally {
      setBusy(false);
    }
  }

  function close() {
    if (busy) return;
    setError(null);
    onClose();
  }

  return (
    <Modal open={open} title={title} description={description} onClose={close} width={width}>
      <form onSubmit={submit} className="action-form">
        <ErrorBanner error={error} />
        {children}
        <div className="form-actions">
          <button type="button" className="button button-ghost" onClick={close} disabled={busy}>
            Cancel
          </button>
          <button
            type="submit"
            className={`button button-${tone}`}
            disabled={busy || disabled}
          >
            {busy ? 'Working…' : submitLabel}
          </button>
        </div>
      </form>
    </Modal>
  );
}
