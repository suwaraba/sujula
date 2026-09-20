import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from './AuthProvider';
import { LoadingScreen } from '@/components/ui';

/**
 * Who gets in, and where everybody else is sent.
 *
 * The gate reads `me.vendorId`, not the role string. `vendorId` is present when
 * this account has a store and absent when it does not, which is the question
 * being asked — a role name can be granted, revoked or renamed, and an account
 * whose role says VENDOR but whose store was never created has nothing to show.
 *
 * Three outcomes:
 *   not signed in        → the sign-in screen
 *   signed in, no store  → the application form
 *   signed in, has store → through, whatever the store's standing
 *
 * The last one is deliberate. A seller awaiting verification still needs to
 * reach the verification screen, and a suspended seller still needs to read why
 * and see what they are owed. What they cannot do is trade, and that is
 * enforced by the server on every call — not by hiding the screen.
 */
export function RequireVendor({ children }: { children: React.ReactNode }) {
  const { state } = useAuth();
  const location = useLocation();

  if (state.phase === 'loading') return <LoadingScreen label="Opening your shop" />;

  if (state.phase === 'anonymous') {
    return <Navigate to="/sign-in" replace state={{ from: location.pathname + location.search }} />;
  }

  if (state.me.vendorId == null) return <Navigate to="/apply" replace />;

  return <>{children}</>;
}

/** For the sign-in and application screens: somebody already through should not see them. */
export function RequireAnonymous({ children }: { children: React.ReactNode }) {
  const { state } = useAuth();

  if (state.phase === 'loading') return <LoadingScreen />;
  if (state.phase === 'authenticated' && state.me.vendorId != null) {
    return <Navigate to="/" replace />;
  }
  return <>{children}</>;
}

/** The application form needs an account but must not need a store. */
export function RequireAccount({ children }: { children: React.ReactNode }) {
  const { state } = useAuth();
  const location = useLocation();

  if (state.phase === 'loading') return <LoadingScreen />;
  if (state.phase === 'anonymous') {
    return <Navigate to="/sign-in" replace state={{ from: location.pathname }} />;
  }
  return <>{children}</>;
}
