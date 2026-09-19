import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { useAuth } from '@/auth/AuthContext';

/**
 * Why support cannot press this. One sentence, the same one everywhere.
 *
 * The server draws the line at `StaffCaller.decider`: support reads every
 * queue on this surface and decides nothing on it. Rendering the button and
 * disabling it — rather than hiding it — is deliberate. An agent who cannot see
 * the action does not know it exists and asks nobody; an agent who can see it
 * greyed out knows exactly what to escalate.
 */
export const SUPPORT_CANNOT_DECIDE =
  'Support can read this but not decide it. Ask an administrator.';

export function useCanDecide(): boolean {
  return useAuth().canDecide;
}

export function DecideButton({
  children,
  variant = 'primary',
  ...rest
}: {
  children: ReactNode;
  variant?: 'primary' | 'danger' | 'ghost' | 'secondary';
} & ButtonHTMLAttributes<HTMLButtonElement>) {
  const canDecide = useCanDecide();
  return (
    <button
      type="button"
      className={`button button-${variant}`}
      {...rest}
      disabled={rest.disabled || !canDecide}
      title={canDecide ? rest.title : SUPPORT_CANNOT_DECIDE}
      aria-disabled={rest.disabled || !canDecide}
    >
      {children}
    </button>
  );
}

/** Wraps a whole panel of decisions rather than each button in it. */
export function DeciderOnly({ children }: { children: ReactNode }) {
  const canDecide = useCanDecide();
  if (!canDecide) {
    return <p className="notice notice-muted">{SUPPORT_CANNOT_DECIDE}</p>;
  }
  return <>{children}</>;
}
