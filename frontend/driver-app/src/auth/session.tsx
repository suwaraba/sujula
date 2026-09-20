/**
 * Who is signed in, and whether the phone is unlocked.
 *
 * The state machine is small and worth reading whole, because every screen in
 * the app is a function of it:
 *
 *   starting ──► signedOut ──login──► settingPin ──► ready
 *       │            ▲                                 │
 *       │            │                             lock│(idle, backgrounded)
 *       ├──► locked ─┴──too many wrong PINs            ▼
 *              ▲──────────────unlock──────────────► locked
 *
 * `locked` exists because of the phone this runs on. It is shared between
 * shifts and left in vehicles, and the screen behind the lock carries
 * recipients' home addresses. Locking is therefore not a preference: the app
 * locks itself after a few idle minutes and whenever it is backgrounded for
 * longer than that, and the refresh token is unreadable until a PIN opens it.
 */

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
import { ApiError, bootstrapCsrf, configureAuth, setAccessToken } from '../api/http';
import { auth as authApi } from '../api/endpoints';
import type { Me, Tokens } from '../api/types';
import * as vault from './vault';
import { wipe } from '../offline/db';

export type SessionState = 'starting' | 'signedOut' | 'locked' | 'settingPin' | 'ready';

interface SessionValue {
  state: SessionState;
  me: Me | null;
  /** The address the lock screen greets, so a driver knows whose phone this is. */
  emailHint: string | null;
  attemptsRemaining: number;

  signIn(email: string, password: string, totpCode?: string): Promise<{ mfaRequired: boolean }>;
  /** Seals the session under a PIN. The step between signing in and working. */
  setPin(pin: string): Promise<void>;
  unlock(pin: string): Promise<void>;
  lock(): void;
  signOut(options?: { keepOutbox?: boolean }): Promise<void>;
  refreshMe(): Promise<void>;
}

const SessionContext = createContext<SessionValue | null>(null);

const LOCK_AFTER_MS =
  Math.max(1, Number(import.meta.env.VITE_LOCK_AFTER_MINUTES ?? 5)) * 60 * 1000;

const DEVICE_LABEL = 'Sujula Driver app';

export function SessionProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<SessionState>('starting');
  const [me, setMe] = useState<Me | null>(null);
  const [emailHint, setEmailHint] = useState<string | null>(null);
  const [attempts, setAttempts] = useState(5);

  /**
   * The PIN, held in memory for as long as the app is unlocked.
   *
   * Needed because refresh tokens are single-use: every exchange returns a new
   * one that has to be re-sealed, and re-sealing needs the key. It is a ref
   * rather than state so it never lands in a React DevTools tree, and it is
   * cleared the moment the app locks.
   */
  const pinRef = useRef<string | null>(null);
  const pendingTokens = useRef<Tokens | null>(null);
  const refreshTokenRef = useRef<string | null>(null);

  const forgetEverythingInMemory = useCallback(() => {
    pinRef.current = null;
    refreshTokenRef.current = null;
    pendingTokens.current = null;
    setAccessToken(null);
    setMe(null);
  }, []);

  const lock = useCallback(() => {
    setState((current) => {
      if (current !== 'ready') return current;
      forgetEverythingInMemory();
      return 'locked';
    });
  }, [forgetEverythingInMemory]);

  const applyTokens = useCallback(async (tokens: Tokens) => {
    setAccessToken(tokens.accessToken, tokens.expiresIn);
    refreshTokenRef.current = tokens.refreshToken;
    if (pinRef.current) {
      // Re-seal on every exchange. The token just used is spent; presenting it
      // again is read as theft, and the backend ends the session for it.
      await vault.resealSession(tokens.refreshToken, pinRef.current);
    }
  }, []);

  // The HTTP layer owns retries and single-flighting; it needs a way to mint a
  // new access token and a way to say the session is gone.
  useEffect(() => {
    configureAuth({
      refresh: async () => {
        const token = refreshTokenRef.current;
        if (!token) throw new ApiError(401, { message: 'Signed out' });
        const tokens = await authApi.refresh(token);
        await applyTokens(tokens);
        return tokens;
      },
      onSessionLost: () => {
        forgetEverythingInMemory();
        void vault.hasSavedSession().then((saved) => setState(saved ? 'locked' : 'signedOut'));
      },
    });
  }, [applyTokens, forgetEverythingInMemory]);

  // First paint: the CSRF cookie, and whether this phone remembers anybody.
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      void bootstrapCsrf();
      const [saved, hint, remaining] = await Promise.all([
        vault.hasSavedSession(),
        vault.savedEmailHint(),
        vault.attemptsRemaining(),
      ]);
      if (cancelled) return;
      setEmailHint(hint);
      setAttempts(remaining);
      setState(saved ? 'locked' : 'signedOut');
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // Idle and background locking.
  useEffect(() => {
    if (state !== 'ready') return undefined;

    let timer = window.setTimeout(lock, LOCK_AFTER_MS);
    const restart = () => {
      window.clearTimeout(timer);
      timer = window.setTimeout(lock, LOCK_AFTER_MS);
    };

    let hiddenAt = 0;
    const onVisibility = () => {
      if (document.hidden) {
        hiddenAt = Date.now();
      } else if (hiddenAt && Date.now() - hiddenAt >= LOCK_AFTER_MS) {
        // Backgrounded long enough that the phone may have changed hands.
        lock();
      } else {
        restart();
      }
    };

    const events: Array<keyof WindowEventMap> = ['pointerdown', 'keydown', 'touchstart'];
    events.forEach((event) => window.addEventListener(event, restart, { passive: true }));
    document.addEventListener('visibilitychange', onVisibility);

    return () => {
      window.clearTimeout(timer);
      events.forEach((event) => window.removeEventListener(event, restart));
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [state, lock]);

  const loadMe = useCallback(async () => {
    const profile = await authApi.me();
    setMe(profile);
    return profile;
  }, []);

  const signIn = useCallback<SessionValue['signIn']>(async (email, password, totpCode) => {
    const result = await authApi.login({
      email: email.trim(),
      password,
      ...(totpCode ? { totpCode } : {}),
      deviceLabel: DEVICE_LABEL,
    });

    if (result.mfaRequired || !result.tokens) {
      // Not a failure: the password was right and one more thing is needed.
      return { mfaRequired: true };
    }

    pendingTokens.current = result.tokens;
    setAccessToken(result.tokens.accessToken, result.tokens.expiresIn);
    refreshTokenRef.current = result.tokens.refreshToken;
    setEmailHint(result.tokens.user?.email ?? email.trim());
    setState('settingPin');
    return { mfaRequired: false };
  }, []);

  const setPin = useCallback<SessionValue['setPin']>(
    async (pin) => {
      const tokens = pendingTokens.current;
      if (!tokens) throw new Error('Sign in first.');
      await vault.saveSession(
        tokens.refreshToken,
        pin,
        tokens.user?.email ?? emailHint ?? 'this phone',
      );
      pinRef.current = pin;
      pendingTokens.current = null;
      setAttempts(5);
      await loadMe();
      setState('ready');
    },
    [emailHint, loadMe],
  );

  const unlock = useCallback<SessionValue['unlock']>(
    async (pin) => {
      try {
        const refreshToken = await vault.unlock(pin);
        pinRef.current = pin;
        refreshTokenRef.current = refreshToken;
        // The saved token is the only credential on this phone; exchanging it
        // is what proves it is still good. A vault that opens on a session the
        // server has already ended should send the driver to sign in, not to a
        // home screen that 401s on every tile.
        const tokens = await authApi.refresh(refreshToken);
        await applyTokens(tokens);
        setAttempts(5);
        await loadMe();
        setState('ready');
      } catch (failure) {
        if (failure instanceof vault.WrongPinError) {
          setAttempts(failure.attemptsRemaining);
          if (failure.attemptsRemaining === 0) {
            forgetEverythingInMemory();
            setState('signedOut');
          }
          throw failure;
        }
        if (failure instanceof ApiError && failure.isUnauthorised) {
          // The PIN was right; the session behind it is gone.
          await vault.forgetSession();
          forgetEverythingInMemory();
          setState('signedOut');
        }
        throw failure;
      }
    },
    [applyTokens, forgetEverythingInMemory, loadMe],
  );

  const signOut = useCallback<SessionValue['signOut']>(
    async ({ keepOutbox = false } = {}) => {
      const token = refreshTokenRef.current;
      if (token) {
        // Best effort. A driver signing out in a dead spot is still signed out
        // here, and the session lapses on its own.
        await authApi.logout(token).catch(() => undefined);
      }
      await vault.forgetSession();
      if (!keepOutbox) await wipe();
      navigator.serviceWorker?.controller?.postMessage('PURGE');
      forgetEverythingInMemory();
      setEmailHint(null);
      setState('signedOut');
    },
    [forgetEverythingInMemory],
  );

  const value = useMemo<SessionValue>(
    () => ({
      state,
      me,
      emailHint,
      attemptsRemaining: attempts,
      signIn,
      setPin,
      unlock,
      lock,
      signOut,
      refreshMe: async () => {
        await loadMe();
      },
    }),
    [state, me, emailHint, attempts, signIn, setPin, unlock, lock, signOut, loadMe],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionValue {
  const value = useContext(SessionContext);
  if (!value) throw new Error('useSession must be used inside a SessionProvider');
  return value;
}

/** Whether this account may work the driver surface at all. */
export function isDriverAccount(me: Me | null): boolean {
  return me?.profile.role === 'DELIVERY' || me?.profile.role === 'ADMIN';
}
