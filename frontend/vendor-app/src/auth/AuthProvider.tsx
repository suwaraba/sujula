import {
  createContext, useCallback, useContext, useEffect, useMemo, useRef, useState,
  type ReactNode,
} from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { authApi } from '@/api/endpoints/auth';
import { onSessionEnded, refreshAccessToken } from '@/api/client';
import { getRefreshToken } from '@/api/tokens';
import type { Me } from '@/api/types';

type SessionState =
  | { phase: 'loading' }
  | { phase: 'anonymous'; notice: string | null }
  | { phase: 'authenticated'; me: Me };

type AuthContextValue = {
  state: SessionState;
  me: Me | null;
  /** Signed in, and this account sells. `vendorId` is the gate, not the role string. */
  isVendor: boolean;
  /** Approved and in good enough standing to actually trade right now. */
  signIn: (email: string, password: string, totpCode?: string) => Promise<'ok' | 'mfa-required'>;
  signOut: () => Promise<void>;
  signOutEverywhere: () => Promise<void>;
  /** Re-reads `/me`. Called after applying to sell, so the gate re-routes without a reload. */
  reload: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<SessionState>({ phase: 'loading' });
  const queryClient = useQueryClient();
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const loadMe = useCallback(async () => {
    const me = await authApi.me();
    if (mounted.current) setState({ phase: 'authenticated', me });
  }, []);

  // Restoring a session on start-up. The access token was never written down,
  // so this is always a refresh — and if there is no refresh token, there is
  // nothing to restore and the sign-in screen is the correct first paint.
  useEffect(() => {
    let cancelled = false;

    (async () => {
      const refreshToken = await getRefreshToken();
      if (!refreshToken) {
        if (!cancelled) setState({ phase: 'anonymous', notice: null });
        return;
      }

      const refreshed = await refreshAccessToken();
      if (cancelled) return;

      if (!refreshed) {
        setState({ phase: 'anonymous', notice: null });
        return;
      }

      try {
        await loadMe();
      } catch {
        if (!cancelled) setState({ phase: 'anonymous', notice: null });
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [loadMe]);

  // The client ends the session on its own when a refresh is refused. This is
  // how the UI finds out, rather than every screen having to notice a 401.
  useEffect(
    () =>
      onSessionEnded((reason) => {
        queryClient.clear();
        setState({
          phase: 'anonymous',
          notice:
            reason === 'revoked'
              ? 'You were signed out. If that was not you, change your password.'
              : 'Your session has ended. Sign in again.',
        });
      }),
    [queryClient],
  );

  const signIn = useCallback<AuthContextValue['signIn']>(
    async (email, password, totpCode) => {
      const result = await authApi.login(email, password, totpCode);
      if (result.mfaRequired || !result.tokens) return 'mfa-required';
      await loadMe();
      return 'ok';
    },
    [loadMe],
  );

  const signOut = useCallback(async () => {
    await authApi.logout();
    queryClient.clear();
    setState({ phase: 'anonymous', notice: null });
  }, [queryClient]);

  const signOutEverywhere = useCallback(async () => {
    await authApi.logoutEverywhere();
    queryClient.clear();
    setState({ phase: 'anonymous', notice: 'Signed out on every device.' });
  }, [queryClient]);

  const reload = useCallback(async () => {
    await loadMe();
  }, [loadMe]);

  const value = useMemo<AuthContextValue>(
    () => ({
      state,
      me: state.phase === 'authenticated' ? state.me : null,
      isVendor: state.phase === 'authenticated' && state.me.vendorId != null,
      signIn,
      signOut,
      signOutEverywhere,
      reload,
    }),
    [state, signIn, signOut, signOutEverywhere, reload],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used inside <AuthProvider>');
  return context;
}

/** The signed-in account, or a throw. For screens behind the gate, where it cannot be null. */
export function useMe(): Me {
  const { me } = useAuth();
  if (!me) throw new Error('useMe used outside an authenticated route');
  return me;
}
