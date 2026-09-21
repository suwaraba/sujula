import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { pickupApi } from '@/api/endpoints';
import { useSession } from './session';
import { LoadingScreen } from '@/components/ui';

/**
 * Who gets in, and where everybody else is sent.
 *
 * The question is not "what role does this account have" but "does this account
 * run a counter", and the only thing that answers it is `/pickup/points`. A role
 * can be granted and revoked; an account whose role says PICKUP_OPERATOR but
 * which runs no counter has nothing to show, and one that runs a counter can
 * work it whatever the role string says.
 *
 * A counter that is still PENDING gets through deliberately. The operator needs
 * to read where the application has got to, and the server refuses every
 * custody action until the counter is approved — which is the right place for
 * that rule, not a hidden screen.
 */
export function RequireOperator({ children }: { children: ReactNode }) {
  const { state } = useSession();
  const location = useLocation();

  const points = useQuery({
    queryKey: ['my-points'],
    queryFn: () => pickupApi.myPoints(),
    enabled: state.phase === 'authenticated',
    retry: false,
    staleTime: 60_000,
  });

  if (state.phase === 'loading') return <LoadingScreen label="Opening the counter" />;

  if (state.phase === 'anonymous') {
    return <Navigate to="/sign-in" replace state={{ from: location.pathname + location.search }} />;
  }

  if (points.isLoading) return <LoadingScreen label="Opening the counter" />;

  // A failed lookup is not a missing counter. Sending somebody to the
  // application form because the wifi dropped would be a bad answer to a
  // question that was never asked.
  if (points.isError) {
    return <Navigate to="/sign-in" replace state={{ from: location.pathname }} />;
  }

  if ((points.data?.points.length ?? 0) === 0) return <Navigate to="/apply" replace />;

  return <>{children}</>;
}

/** For the sign-in screen: an operator already through should not see it. */
export function RequireAnonymous({ children }: { children: ReactNode }) {
  const { state } = useSession();
  if (state.phase === 'loading') return <LoadingScreen />;
  if (state.phase === 'authenticated') return <Navigate to="/" replace />;
  return <>{children}</>;
}

/** The application form needs an account but must not need a counter. */
export function RequireAccount({ children }: { children: ReactNode }) {
  const { state } = useSession();
  const location = useLocation();
  if (state.phase === 'loading') return <LoadingScreen />;
  if (state.phase === 'anonymous') {
    return <Navigate to="/sign-in" replace state={{ from: location.pathname }} />;
  }
  return <>{children}</>;
}
