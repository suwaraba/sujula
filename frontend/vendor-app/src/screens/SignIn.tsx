import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '@/auth/AuthProvider';
import { ApiError } from '@/api/errors';
import { Button, Card, Notice } from '@/components/ui';
import { TextField } from '@/components/form';

export function SignIn() {
  const { signIn, state } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [totpCode, setTotpCode] = useState('');
  const [needsTotp, setNeedsTotp] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const notice = state.phase === 'anonymous' ? state.notice : null;
  const from = (location.state as { from?: string } | null)?.from ?? '/';

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const result = await signIn(email.trim(), password, needsTotp ? totpCode.trim() : undefined);
      if (result === 'mfa-required') {
        // Not a failure: the password was right and one more thing is needed.
        setNeedsTotp(true);
        setError(null);
      } else {
        navigate(from, { replace: true });
      }
    } catch (cause) {
      setError(
        cause instanceof ApiError
          ? cause.message
          : 'Could not sign you in. Try again.',
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth-screen">
      <div className="auth-card">
        <div className="auth-card__brand">
          <div className="auth-card__mark" aria-hidden="true">S</div>
          <h1>Sujula for sellers</h1>
          <p className="muted" style={{ marginTop: 'var(--space-2)' }}>
            Your shop, your orders, your money.
          </p>
        </div>

        <Card>
          <form className="stack" onSubmit={onSubmit} noValidate>
            {notice && !error && <Notice tone="info">{notice}</Notice>}
            {error && <Notice tone="danger">{error}</Notice>}

            <TextField
              label="Email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              autoComplete="username"
              inputMode="email"
              autoCapitalize="none"
              autoCorrect="off"
              required
              disabled={needsTotp}
            />

            <TextField
              label="Password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              required
              disabled={needsTotp}
            />

            {needsTotp && (
              <TextField
                label="Authenticator code"
                hint="The six digits from your authenticator app."
                className="input input--mono"
                value={totpCode}
                onChange={(e) => setTotpCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                inputMode="numeric"
                autoComplete="one-time-code"
                maxLength={6}
                autoFocus
                required
              />
            )}

            <Button type="submit" variant="primary" block busy={busy}>
              {needsTotp ? 'Confirm code' : 'Sign in'}
            </Button>

            {needsTotp && (
              <Button
                type="button"
                variant="ghost"
                block
                onClick={() => {
                  setNeedsTotp(false);
                  setTotpCode('');
                  setError(null);
                }}
              >
                Use a different account
              </Button>
            )}
          </form>
        </Card>

        <p className="center small muted" style={{ marginTop: 'var(--space-4)' }}>
          <Link to="/forgot-password">Forgotten your password?</Link>
          {' · '}
          <Link to="/register">Create an account</Link>
        </p>
      </div>
    </div>
  );
}
