import {
  createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode,
} from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { authApi } from '@/api/endpoints';
import { onSessionEnded, refreshAccessToken } from '@/api/client';
import { getRefreshToken } from '@/api/tokens';
import type { Me } from '@/api/types';

type SessionState =
  | { phase: 'loading' }
  | { phase: 'anonymous'; notice: string | null }
  | { phase: 'authenticated'; me: Me };

type SessionValue = {
  state: SessionState;
  me: Me | null;
  signIn: (email: string, password: string, totpCode?: string) => Promise<'ok' | 'mfa-required'>;
  signOut: () => Promise<void>;
  reload: () => Promise<void>;
};

const SessionContext = createContext<SessionValue | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
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

  // Restoring on start-up is always a refresh: the access token was never
  // written down, so there is nothing else to restore from.
  useEffect(() => {
    let cancelled = false;

    (async () => {
      if (!getRefreshToken()) {
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

  // The client ends the session on its own when a refresh is refused; this is
  // how the screen finds out, rather than every query having to notice a 401.
  useEffect(
    () =>
      onSessionEnded((reason) => {
        queryClient.clear();
        setState({
          phase: 'anonymous',
          notice:
            reason === 'revoked'
              ? 'This counter was signed out. If that was not you, change the password.'
              : 'The session has ended. Sign in again.',
        });
      }),
    [queryClient],
  );

  const signIn = useCallback<SessionValue['signIn']>(
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

  const reload = useCallback(async () => {
    await loadMe();
  }, [loadMe]);

  const value = useMemo<SessionValue>(
    () => ({
      state,
      me: state.phase === 'authenticated' ? state.me : null,
      signIn,
      signOut,
      reload,
    }),
    [state, signIn, signOut, reload],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionValue {
  const context = useContext(SessionContext);
  if (!context) throw new Error('useSession must be used inside <SessionProvider>');
  return context;
}

export function useMe(): Me {
  const { me } = useSession();
  if (!me) throw new Error('useMe used outside an authenticated route');
  return me;
}
