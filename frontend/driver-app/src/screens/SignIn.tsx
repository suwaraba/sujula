/**
 * Signing in.
 *
 * Kept to two fields and one button. The multi-factor step appears only when
 * the backend says it is needed — `mfaRequired` is a distinct answer from a
 * wrong password, and showing "that password is wrong" to somebody whose
 * password was right is how a driver ends up locked out of their own round.
 */

import { useState, type FormEvent } from 'react';
import { useSession } from '../auth/session';
import { ApiError } from '../api/http';
import { auth } from '../api/endpoints';
import { Banner, Button, Field } from '../components/ui';
import { useT } from '../i18n';
import { LanguagePicker } from './parts/LanguagePicker';

export function SignIn() {
  const { signIn } = useSession();
  const t = useT();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [totpCode, setTotpCode] = useState('');
  const [needsMfa, setNeedsMfa] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [sentReset, setSentReset] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setProblem(null);
    setBusy(true);
    try {
      const result = await signIn(email, password, totpCode || undefined);
      if (result.mfaRequired) {
        setNeedsMfa(true);
        setProblem(null);
      }
    } catch (failure) {
      setProblem(
        failure instanceof ApiError
          ? failure.message
          : 'Could not reach Sujula. Check your connection and try again.',
      );
    } finally {
      setBusy(false);
    }
  };

  const forgot = async () => {
    if (!email) {
      setProblem('Type your email first.');
      return;
    }
    await auth.forgotPassword(email).catch(() => undefined);
    // Always the same answer, whether or not the address is known: a form that
    // says "no such account" is a form that tells a stranger who works here.
    setSentReset(true);
  };

  return (
    <div className="screen screen--plain">
      <div className="center" style={{ marginBottom: 24 }}>
        <img src="/icons/icon.svg" alt="" width={72} height={72} />
        <h1>{t('app.name')}</h1>
      </div>

      {problem && <Banner tone="stop">{problem}</Banner>}
      {sentReset && (
        <Banner tone="go">
          If that address has an account, a link to set a new password is on its way to it.
        </Banner>
      )}

      <form onSubmit={submit}>
        <Field label={t('login.email')}>
          <input
            className="field__input"
            type="email"
            inputMode="email"
            autoComplete="username"
            autoCapitalize="off"
            autoCorrect="off"
            required
            value={email}
            onChange={(event) => setEmail(event.target.value)}
          />
        </Field>

        <Field label={t('login.password')}>
          <input
            className="field__input"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
        </Field>

        {needsMfa && (
          <Field label={t('login.mfa')}>
            <input
              className="field__input"
              type="text"
              inputMode="numeric"
              pattern="[0-9 ]*"
              autoComplete="one-time-code"
              maxLength={8}
              value={totpCode}
              onChange={(event) => setTotpCode(event.target.value)}
            />
          </Field>
        )}

        <Button tone="brand" major busy={busy} type="submit" icon="→">
          {t('login.submit')}
        </Button>
      </form>

      <div className="stack" style={{ marginTop: 20 }}>
        <Button tone="ghost" onClick={() => void forgot()}>
          {t('login.forgot')}
        </Button>
        <LanguagePicker />
      </div>
    </div>
  );
}
