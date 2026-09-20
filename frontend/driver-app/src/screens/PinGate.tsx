/**
 * The PIN: set once after signing in, asked for every time the phone has been
 * idle or in a pocket.
 *
 * Why there is a PIN at all, given the driver already typed a password: the
 * password is long, is typed on a small keyboard in the sun, and unlocks a
 * screen showing where people live. Asking for it every five minutes means it
 * gets written on the case. Four digits on a huge keypad is the trade that
 * actually holds — and the refresh token behind it is encrypted with those four
 * digits, so a phone found in a taxi is a phone with nothing readable on it.
 *
 * Five wrong tries wipes the saved session, which is the same rule the backend
 * applies to a handover code. It is not punishment: a four-digit secret is ten
 * thousand guesses to somebody patient and three to somebody who mistyped, and
 * the count is the only thing that tells them apart.
 */

import { useState } from 'react';
import { useSession } from '../auth/session';
import { WrongPinError } from '../auth/vault';
import { Banner, Button } from '../components/ui';
import { CodePad } from '../components/CodePad';
import { useT } from '../i18n';

const LENGTH = 4;

export function SetPin() {
  const { setPin, emailHint } = useSession();
  const t = useT();

  const [first, setFirst] = useState('');
  const [second, setSecond] = useState('');
  const [stage, setStage] = useState<'first' | 'second'>('first');
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const finish = async (confirmation: string) => {
    if (confirmation !== first) {
      setProblem(t('pin.mismatch'));
      setFirst('');
      setSecond('');
      setStage('first');
      return;
    }
    setBusy(true);
    try {
      await setPin(first);
    } catch (failure) {
      setProblem(failure instanceof Error ? failure.message : 'Could not save the PIN.');
      setBusy(false);
    }
  };

  return (
    <div className="screen screen--plain">
      <p className="center muted">{emailHint}</p>
      {problem && <Banner tone="stop">{problem}</Banner>}
      <Banner icon="🔒">{t('pin.why')}</Banner>

      {stage === 'first' ? (
        <CodePad
          key="first"
          label={t('pin.create')}
          value={first}
          onChange={setFirst}
          length={LENGTH}
          secret
          onComplete={() => setStage('second')}
        />
      ) : (
        <CodePad
          key="second"
          label={t('pin.confirm')}
          value={second}
          onChange={setSecond}
          length={LENGTH}
          secret
          onComplete={(value) => void finish(value)}
        />
      )}

      {busy && <p className="center muted">{t('app.saving')}</p>}
    </div>
  );
}

export function Unlock() {
  const { unlock, signOut, emailHint, attemptsRemaining } = useSession();
  const t = useT();

  const [pin, setPinValue] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const attempt = async (value: string) => {
    setBusy(true);
    setProblem(null);
    try {
      await unlock(value);
    } catch (failure) {
      setPinValue('');
      if (failure instanceof WrongPinError) {
        setProblem(
          failure.attemptsRemaining === 0
            ? t('pin.locked')
            : t('pin.wrong', { left: failure.attemptsRemaining }),
        );
      } else {
        setProblem(
          failure instanceof Error
            ? failure.message
            : 'Could not open your session. Sign in again.',
        );
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="screen screen--plain">
      <div className="center" style={{ marginBottom: 12 }}>
        <span style={{ fontSize: 56 }} aria-hidden="true">
          🔒
        </span>
        <p className="muted">{emailHint}</p>
      </div>

      {problem && <Banner tone="stop">{problem}</Banner>}

      <CodePad
        label={t('pin.unlock')}
        value={pin}
        onChange={setPinValue}
        length={LENGTH}
        secret
        error={Boolean(problem)}
        onComplete={(value) => void attempt(value)}
      />

      <p className="center muted" style={{ marginTop: 16 }}>
        {attemptsRemaining} of 5 tries left
      </p>

      <div style={{ marginTop: 20 }}>
        <Button tone="ghost" busy={busy} onClick={() => void signOut({ keepOutbox: true })}>
          Not you? Sign in with a password
        </Button>
      </div>
    </div>
  );
}
