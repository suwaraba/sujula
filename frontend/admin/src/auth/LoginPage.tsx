import { useState, type FormEvent } from 'react';
import { ApiError } from '@/api/client';
import { useAuth } from './AuthContext';

/**
 * Sign-in, including the second factor.
 *
 * The server answers a first, correct password with `{mfaRequired: true}` and
 * no tokens; the same call is then made again with the code. So this form has
 * two shapes and one submit path — which is what keeps the password in memory
 * for exactly as long as the challenge takes and no longer.
 */
export function LoginPage() {
  const { signIn, endedReason } = useAuth();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [totpCode, setTotpCode] = useState('');
  const [recoveryCode, setRecoveryCode] = useState('');
  const [useRecovery, setUseRecovery] = useState(false);
  const [needsSecondFactor, setNeedsSecondFactor] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const second = needsSecondFactor
        ? useRecovery
          ? { recoveryCode: recoveryCode.trim() }
          : { totpCode: totpCode.trim() }
        : undefined;
      const outcome = await signIn(email.trim(), password, second);
      if (outcome === 'mfa-required') {
        setNeedsSecondFactor(true);
        setError(null);
      }
    } catch (caught) {
      setError(
        caught instanceof ApiError
          ? caught.message
          : 'Could not reach the server. Check your connection and try again.',
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-shell">
      <form className="login-card" onSubmit={submit}>
        <h1 className="login-title">Sujula</h1>
        <p className="login-subtitle">Platform administration</p>

        {endedReason && <p className="notice notice-muted">{endedReason}</p>}
        {error && (
          <p className="notice notice-error" role="alert">
            {error}
          </p>
        )}

        <label className="field">
          <span className="field-label">Email</span>
          <input
            className="input"
            type="email"
            autoComplete="username"
            required
            value={email}
            disabled={needsSecondFactor}
            onChange={(event) => setEmail(event.target.value)}
          />
        </label>

        <label className="field">
          <span className="field-label">Password</span>
          <input
            className="input"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            disabled={needsSecondFactor}
            onChange={(event) => setPassword(event.target.value)}
          />
        </label>

        {needsSecondFactor && !useRecovery && (
          <label className="field">
            <span className="field-label">Authenticator code</span>
            <input
              className="input"
              inputMode="numeric"
              autoComplete="one-time-code"
              pattern="[0-9]*"
              maxLength={6}
              required
              autoFocus
              value={totpCode}
              onChange={(event) => setTotpCode(event.target.value)}
            />
          </label>
        )}

        {needsSecondFactor && useRecovery && (
          <label className="field">
            <span className="field-label">Recovery code</span>
            <input
              className="input"
              required
              autoFocus
              value={recoveryCode}
              onChange={(event) => setRecoveryCode(event.target.value)}
            />
          </label>
        )}

        {needsSecondFactor && (
          <button
            type="button"
            className="link-button"
            onClick={() => setUseRecovery((previous) => !previous)}
          >
            {useRecovery ? 'Use my authenticator instead' : 'Use a recovery code instead'}
          </button>
        )}

        <button className="button button-primary button-block" type="submit" disabled={busy}>
          {busy ? 'Signing in…' : needsSecondFactor ? 'Confirm' : 'Sign in'}
        </button>

        <p className="login-footnote">
          This console moves money and can read every customer's details. It signs itself out when
          left unattended, and every action taken here is recorded against your name.
        </p>
      </form>
    </div>
  );
}
