import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { authApi } from '@/api/endpoints/auth';
import { useAuth } from '@/auth/AuthProvider';
import { ApiError } from '@/api/errors';
import { Button, Card, Notice } from '@/components/ui';
import { TextField, fieldError } from '@/components/form';

/**
 * Creating an account. This always produces a CUSTOMER — selling is applied for
 * afterwards, and granted by approval. There is no "register as a vendor" here
 * because there is no such thing on the server.
 */
export function Register() {
  const { reload } = useAuth();
  const navigate = useNavigate();

  const [form, setForm] = useState({
    firstName: '', lastName: '', email: '', phone: '', password: '',
  });
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  const set = (key: keyof typeof form) => (event: { target: { value: string } }) =>
    setForm((current) => ({ ...current, [key]: event.target.value }));

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setFields({});
    setBusy(true);
    try {
      await authApi.register({
        email: form.email.trim(),
        password: form.password,
        firstName: form.firstName.trim(),
        lastName: form.lastName.trim(),
        ...(form.phone.trim() ? { phone: form.phone.trim() } : {}),
      });
      await reload();
      navigate('/apply', { replace: true });
    } catch (cause) {
      if (cause instanceof ApiError) {
        setError(cause.message);
        setFields(cause.fieldErrors);
      } else {
        setError('Could not create the account. Try again.');
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth-screen">
      <div className="auth-card">
        <div className="auth-card__brand">
          <div className="auth-card__mark" aria-hidden="true">S</div>
          <h1>Create an account</h1>
          <p className="muted" style={{ marginTop: 'var(--space-2)' }}>
            Then tell us about your shop.
          </p>
        </div>

        <Card>
          <form className="stack" onSubmit={onSubmit} noValidate>
            {error && <Notice tone="danger">{error}</Notice>}

            <div className="grid grid--2">
              <TextField
                label="First name" value={form.firstName} onChange={set('firstName')}
                autoComplete="given-name" error={fieldError(fields, 'firstName')} required
              />
              <TextField
                label="Last name" value={form.lastName} onChange={set('lastName')}
                autoComplete="family-name" error={fieldError(fields, 'lastName')} required
              />
            </div>

            <TextField
              label="Email" type="email" value={form.email} onChange={set('email')}
              autoComplete="email" inputMode="email" autoCapitalize="none" autoCorrect="off"
              error={fieldError(fields, 'email')} required
            />

            <TextField
              label="Phone" type="tel" value={form.phone} onChange={set('phone')}
              autoComplete="tel" inputMode="tel"
              hint="Where we reach you about an order that is waiting."
              error={fieldError(fields, 'phone')}
            />

            <TextField
              label="Password" type="password" value={form.password} onChange={set('password')}
              autoComplete="new-password" error={fieldError(fields, 'password')} required
            />

            <Button type="submit" variant="primary" block busy={busy}>Create account</Button>
          </form>
        </Card>

        <p className="center small muted" style={{ marginTop: 'var(--space-4)' }}>
          Already selling? <Link to="/sign-in">Sign in</Link>
        </p>
      </div>
    </div>
  );
}
