import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { authApi } from '@/api/endpoints/auth';
import { Button, Card, Notice } from '@/components/ui';
import { TextField } from '@/components/form';

export function ForgotPassword() {
  const [email, setEmail] = useState('');
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      await authApi.forgotPassword(email.trim());
    } catch {
      // Deliberately swallowed. Whether an address is registered is not
      // something this screen should be able to tell anybody who asks.
    } finally {
      setBusy(false);
      setSent(true);
    }
  }

  return (
    <div className="auth-screen">
      <div className="auth-card">
        <div className="auth-card__brand">
          <div className="auth-card__mark" aria-hidden="true">S</div>
          <h1>Reset your password</h1>
        </div>

        <Card>
          {sent ? (
            <div className="stack">
              <Notice tone="ok" title="Check your email">
                If that address has an account, a reset link is on its way. The link expires
                shortly, so use it soon.
              </Notice>
              <Link to="/sign-in" className="btn btn--secondary btn--block">Back to sign in</Link>
            </div>
          ) : (
            <form className="stack" onSubmit={onSubmit} noValidate>
              <TextField
                label="Email" type="email" value={email}
                onChange={(e) => setEmail(e.target.value)}
                autoComplete="username" inputMode="email" autoCapitalize="none" required
              />
              <Button type="submit" variant="primary" block busy={busy}>Send reset link</Button>
              <Link to="/sign-in" className="btn btn--ghost btn--block">Back to sign in</Link>
            </form>
          )}
        </Card>
      </div>
    </div>
  );
}
