import { useEffect, useRef, type ReactNode } from 'react';

export function Modal({
  open,
  title,
  description,
  onClose,
  children,
  width = 'normal',
}: {
  open: boolean;
  title: ReactNode;
  description?: ReactNode;
  onClose(): void;
  children: ReactNode;
  width?: 'normal' | 'wide';
}) {
  const dialogRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    dialogRef.current?.querySelector<HTMLElement>('input, select, textarea, button')?.focus();
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;

  return (
    <div className="modal-backdrop" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <div
        className={`modal modal-${width}`}
        role="dialog"
        aria-modal="true"
        aria-label={typeof title === 'string' ? title : undefined}
        ref={dialogRef}
      >
        <header className="modal-head">
          <h2 className="modal-title">{title}</h2>
          <button type="button" className="icon-button" onClick={onClose} aria-label="Close">
            ×
          </button>
        </header>
        {description && <p className="modal-description">{description}</p>}
        <div className="modal-body">{children}</div>
      </div>
    </div>
  );
}
