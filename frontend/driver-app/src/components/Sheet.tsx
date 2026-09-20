/**
 * A panel that comes up from the bottom of the screen.
 *
 * Everything that asks the driver for something — a code, a reason, a
 * confirmation — arrives this way, for one reason: it puts the content in the
 * bottom half of the phone, which is the half a thumb reaches while the other
 * hand is holding a box.
 */

import { useEffect, useRef, type ReactNode } from 'react';

interface Props {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  /** Suppresses close-on-backdrop for a step that must not be dismissed by a stray tap. */
  insistent?: boolean;
}

export function Sheet({ open, onClose, title, children, insistent = false }: Props) {
  const panel = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!open) return undefined;

    const previous = document.activeElement as HTMLElement | null;
    panel.current?.focus();

    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !insistent) onClose();
    };
    document.addEventListener('keydown', onKey);

    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = overflow;
      previous?.focus?.();
    };
  }, [open, onClose, insistent]);

  if (!open) return null;

  return (
    <div
      className="sheet-backdrop"
      onClick={(event) => {
        if (!insistent && event.target === event.currentTarget) onClose();
      }}
    >
      <div
        className="sheet"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        tabIndex={-1}
        ref={panel}
      >
        <div className="sheet__grip" aria-hidden="true" />
        <h2>{title}</h2>
        {children}
      </div>
    </div>
  );
}
