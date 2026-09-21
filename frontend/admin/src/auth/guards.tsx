import type { ReactNode } from 'react';
import { useAuth } from './AuthContext';
import { LoginPage } from './LoginPage';

/**
 * Nothing renders until the session is known to be a staff session.
 *
 * This is a rendering gate, not an authorisation one: every endpoint behind it
 * enforces its own `@PreAuthorize` and its own ownership check. What it buys is
 * that the console never flashes a queue at somebody who is about to be
 * refused it.
 */
export function RequireStaff({ children }: { children: ReactNode }) {
  const { status } = useAuth();

  if (status === 'loading') {
    return (
      <div className="login-shell">
        <p className="muted">Restoring your session…</p>
      </div>
    );
  }
  if (status === 'signed-out') return <LoginPage />;
  return <>{children}</>;
}
