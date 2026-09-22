import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { ApiError, primeCsrf, setSessionLostHandler } from '@/api/client';
import { auth as authApi, me as meApi } from '@/api/endpoints';
import { canDecide as roleCanDecide, isStaff } from '@/api/enums';
import type { Me, Profile } from '@/api/types';
import { tokenStore } from './tokenStore';

const IDLE_MINUTES = Number(import.meta.env.VITE_IDLE_TIMEOUT_MINUTES ?? 20) || 20;

export interface AuthState {
  status: 'loading' | 'signed-out' | 'signed-in';
  profile: Profile | null;
  me: Me | null;
  /** Mirrors `StaffCaller.decider`: ADMIN decides, SUPPORT reads. */
  canDecide: boolean;
  /** Why the last session ended, so the sign-in page can say so. */
  endedReason: string | null;
  signIn(email: string, password: string, second?: { totpCode?: string; recoveryCode?: string }): Promise<'ok' | 'mfa-required'>;
  signOut(everywhere?: boolean): Promise<void>;
  refreshMe(): Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

export function useAuth(): AuthState {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used inside <AuthProvider>');
  return context;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthState['status']>('loading');
  const [profile, setProfile] = useState<Profile | null>(null);
  const [me, setMe] = useState<Me | null>(null);
  const [endedReason, setEndedReason] = useState<string | null>(null);

  const endSession = useCallback((reason: string | null, broadcast = true) => {
    tokenStore.clear(broadcast);
    setProfile(null);
    setMe(null);
    setEndedReason(reason);
    setStatus('signed-out');
  }, []);

  const loadMe = useCallback(async (): Promise<boolean> => {
    try {
      const loaded = await meApi.get();
      if (!isStaff(loaded.profile.role)) {
        // A customer's or vendor's credentials are valid; they simply have no
        // business here. Say so plainly rather than showing empty queues.
        endSession('That account is not a member of platform staff.');
        return false;
      }
      setMe(loaded);
      setProfile(loaded.profile);
      setStatus('signed-in');
      return true;
    } catch (error) {
      if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
        // The client already tried the refresh token and it did not take, so
        // there is nothing left to restore from.
        endSession('Your session has ended. Sign in again.', false);
        return false;
      }
      throw error;
    }
  }, [endSession]);

  // ── Start-up: prime CSRF, then try to restore a session ────────────────────
  useEffect(() => {
    let cancelled = false;

    (async () => {
      await primeCsrf();
      if (!tokenStore.refreshToken()) {
        if (!cancelled) setStatus('signed-out');
        return;
      }
      try {
        // No access token in memory after a reload, so `/me` 401s and the
        // client's own refresh-then-retry restores the session.
        const ok = await loadMe();
        if (!ok && !cancelled) setStatus('signed-out');
      } catch {
        if (!cancelled) endSession('Could not restore your session.');
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [loadMe, endSession]);

  // ── The client tells us when a refresh failed ──────────────────────────────
  useEffect(() => {
    setSessionLostHandler(() => endSession('Your session expired. Sign in again.'));
    return () => setSessionLostHandler(null);
  }, [endSession]);

  // ── Another tab signed out ─────────────────────────────────────────────────
  useEffect(
    () => tokenStore.onCrossTabLogout(() => endSession('Signed out in another tab.', false)),
    [endSession],
  );

  // ── Idle timeout ───────────────────────────────────────────────────────────
  const idleTimer = useRef<number | null>(null);
  useEffect(() => {
    if (status !== 'signed-in') return;

    const reset = () => {
      if (idleTimer.current) window.clearTimeout(idleTimer.current);
      idleTimer.current = window.setTimeout(
        () => endSession(`Signed out after ${IDLE_MINUTES} minutes without activity.`),
        IDLE_MINUTES * 60_000,
      );
    };

    const events: (keyof WindowEventMap)[] = ['pointerdown', 'keydown', 'wheel', 'focus'];
    events.forEach((event) => window.addEventListener(event, reset, { passive: true }));
    reset();

    return () => {
      events.forEach((event) => window.removeEventListener(event, reset));
      if (idleTimer.current) window.clearTimeout(idleTimer.current);
    };
  }, [status, endSession]);

  const signIn = useCallback<AuthState['signIn']>(
    async (email, password, second) => {
      setEndedReason(null);
      const result = await authApi.login({
        email,
        password,
        totpCode: second?.totpCode,
        recoveryCode: second?.recoveryCode,
        deviceLabel: 'Sujula admin console',
      });

      if (result.mfaRequired || !result.tokens) return 'mfa-required';

      tokenStore.set(result.tokens, result.tokens.user);
      const ok = await loadMe();
      if (!ok) throw new ApiError(403, null, 'That account is not a member of platform staff.');
      return 'ok';
    },
    [loadMe],
  );

  const signOut = useCallback<AuthState['signOut']>(
    async (everywhere = false) => {
      try {
        if (everywhere) await authApi.logoutAll();
        else await authApi.logout(tokenStore.refreshToken());
      } catch {
        // A failed sign-out must still clear the credentials held here; the
        // server-side session expires on its own.
      }
      endSession(null);
    },
    [endSession],
  );

  const refreshMe = useCallback(async () => {
    await loadMe();
  }, [loadMe]);

  const value = useMemo<AuthState>(
    () => ({
      status,
      profile,
      me,
      canDecide: roleCanDecide(profile?.role),
      endedReason,
      signIn,
      signOut,
      refreshMe,
    }),
    [status, profile, me, endedReason, signIn, signOut, refreshMe],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
