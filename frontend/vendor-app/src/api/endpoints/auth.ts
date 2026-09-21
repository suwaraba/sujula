import { api, request } from '../client';
import { clearTokens, getRefreshToken, storeTokens } from '../tokens';
import type {
  LoginResult, Me, MfaActivation, MfaSetup, Profile, SessionRow, Tokens,
} from '../types';

export const authApi = {
  /**
   * `mfaRequired` is an answer, not an error: the password was right and one
   * more thing is needed. A client that treats it as a failure shows "wrong
   * password" to somebody who typed the right one.
   */
  async login(email: string, password: string, totpCode?: string): Promise<LoginResult> {
    const result = await api.post<LoginResult>(
      '/auth/login',
      { email, password, ...(totpCode ? { totpCode } : {}) },
      { anonymous: true },
    );
    if (result.tokens) await storeTokens(result.tokens);
    return result;
  },

  async register(input: {
    email: string;
    password: string;
    firstName: string;
    lastName: string;
    phone?: string;
  }): Promise<Tokens> {
    const tokens = await api.post<Tokens>('/auth/register', input, { anonymous: true });
    await storeTokens(tokens);
    return tokens;
  },

  /** Ends this device's session server-side, then locally whatever the server said. */
  async logout(): Promise<void> {
    const refreshToken = await getRefreshToken();
    try {
      if (refreshToken) await api.post<void>('/auth/logout', { refreshToken });
    } finally {
      // A failed call must not strand a seller in a half-signed-in app.
      await clearTokens();
    }
  },

  async logoutEverywhere(): Promise<{ message: string }> {
    try {
      return await api.post<{ message: string }>('/auth/logout-all');
    } finally {
      await clearTokens();
    }
  },

  me: () => api.get<Me>('/me'),

  updateProfile: (input: Partial<Pick<Profile,
    'firstName' | 'lastName' | 'phone' | 'preferredCurrency' | 'preferredLanguage' | 'countryCode'
  >>) => api.patch<Profile>('/me', input),

  sessions: () => api.get<SessionRow[]>('/me/sessions'),

  endSession: (sessionId: number) => api.delete<void>(`/me/sessions/${sessionId}`),

  changePassword: (currentPassword: string, newPassword: string) =>
    api.post<{ message: string }>('/auth/password/change', { currentPassword, newPassword }),

  forgotPassword: (email: string) =>
    api.post<void>('/auth/password/forgot', { email }, { anonymous: true }),

  resendVerification: () => api.post<void>('/auth/verify-email/resend', {}),

  requestPhoneCode: (phone: string) => api.post<void>('/auth/verify-phone/request', { phone }),

  confirmPhoneCode: (phone: string, code: string) =>
    api.post<void>('/auth/verify-phone/confirm', { phone, code }),

  // ── Multi-factor ───────────────────────────────────────────────────────────
  //
  // Note which of these takes a code and which takes a password. Activating
  // proves possession of the authenticator, so it takes the code. Turning it
  // off and replacing the recovery codes are the calls an attacker inside the
  // session would want, so they take the password instead — a second factor
  // that can be removed with the session that is already open protects nothing.

  mfaSetup: () => api.post<MfaSetup>('/auth/mfa/setup'),

  mfaActivate: (code: string) => api.post<MfaActivation>('/auth/mfa/activate', { code }),

  mfaRecoveryCodes: (password: string) =>
    api.post<MfaActivation>('/auth/mfa/recovery-codes', { password }),

  mfaDisable: (password: string) => request<void>('/auth/mfa', {
    method: 'DELETE',
    body: { password },
  }),
};
