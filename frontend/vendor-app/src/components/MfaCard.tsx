import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { authApi } from '@/api/endpoints/auth';
import { ApiError } from '@/api/errors';
import { useMe } from '@/auth/AuthProvider';
import type { MfaActivation, MfaSetup } from '@/api/types';
import { Badge, Button, Card, Notice } from './ui';
import { TextField } from './form';
import { Sheet } from './Sheet';
import { useToast } from './Toast';

/**
 * Turning the second factor on and off.
 *
 * Three calls with three different proofs, and the difference matters:
 * activating takes the authenticator's own code, because that is what proves
 * the seller actually holds the authenticator; turning it off and reissuing the
 * recovery codes take the account password, because those are exactly what
 * somebody who has stolen the open session would want, and a second factor that
 * the session can remove protects nothing.
 *
 * Recovery codes are shown once. They are not stored, not re-fetchable, and
 * this component says so before the sheet closes on them.
 */
export function MfaCard({ onChanged }: { onChanged: () => void }) {
  const me = useMe();
  const [enrolling, setEnrolling] = useState(false);
  const [disabling, setDisabling] = useState(false);
  const [reissuing, setReissuing] = useState(false);

  return (
    <Card
      title="Two-step sign-in"
      actions={
        <Badge tone={me.profile.mfaEnabled ? 'ok' : 'warn'} dot>
          {me.profile.mfaEnabled ? 'On' : 'Off'}
        </Badge>
      }
    >
      <div className="stack">
        {me.profile.mfaEnabled ? (
          <>
            <p className="muted">
              Your authenticator code is asked for when you sign in, and again when you change
              where your money is sent.
            </p>
            <div className="row">
              <Button variant="secondary" onClick={() => setReissuing(true)}>
                New recovery codes
              </Button>
              <Button variant="ghost" onClick={() => setDisabling(true)}>
                Turn it off
              </Button>
            </div>
          </>
        ) : (
          <>
            <Notice tone="warn">
              A password alone protects the account that receives your money. If somebody guesses
              it, they can change where your payouts go.
            </Notice>
            <Button variant="primary" onClick={() => setEnrolling(true)}>
              Turn on two-step sign-in
            </Button>
          </>
        )}
      </div>

      {enrolling && (
        <EnrolSheet
          onClose={() => setEnrolling(false)}
          onDone={() => { setEnrolling(false); onChanged(); }}
        />
      )}

      {reissuing && (
        <ReissueSheet onClose={() => setReissuing(false)} />
      )}

      {disabling && (
        <DisableSheet
          onClose={() => setDisabling(false)}
          onDone={() => { setDisabling(false); onChanged(); }}
        />
      )}
    </Card>
  );
}

function EnrolSheet({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const toast = useToast();
  const [setup, setSetup] = useState<MfaSetup | null>(null);
  const [codes, setCodes] = useState<MfaActivation | null>(null);
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);

  const begin = useMutation({
    mutationFn: () => authApi.mfaSetup(),
    onSuccess: setSetup,
    onError: (cause) =>
      setError(cause instanceof ApiError ? cause.message : 'Could not start enrolment.'),
  });

  const activate = useMutation({
    mutationFn: () => authApi.mfaActivate(code.trim()),
    onSuccess: (result) => {
      setCodes(result);
      toast.success('Two-step sign-in is on.');
    },
    onError: (cause) =>
      setError(cause instanceof ApiError ? cause.message : 'That code was not accepted.'),
  });

  // Start the enrolment as soon as the sheet opens; there is nothing to ask first.
  if (!setup && !begin.isPending && !begin.isError && begin.isIdle) begin.mutate();

  if (codes) {
    return (
      <Sheet
        title="Write these down"
        onClose={() => { onDone(); }}
        footer={
          <Button variant="primary" onClick={onDone}>
            I have written them down
          </Button>
        }
      >
        <div className="stack">
          <Notice tone="warn" title="Shown once, and never again">
            These get you back in if you lose your phone. Each works once. Keep them somewhere
            that is not the phone with the authenticator on it.
          </Notice>
          <div
            className="mono"
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(2, 1fr)',
              gap: 'var(--space-2)',
              padding: 'var(--space-4)',
              background: 'var(--surface-sunken)',
              borderRadius: 'var(--radius)',
              userSelect: 'all',
            }}
          >
            {codes.recoveryCodes.map((recovery) => (
              <span key={recovery}>{recovery}</span>
            ))}
          </div>
          <p className="small muted">{codes.remaining} codes, each usable once.</p>
        </div>
      </Sheet>
    );
  }

  return (
    <Sheet
      title="Turn on two-step sign-in"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={activate.isPending}>Cancel</Button>
          <Button
            variant="primary"
            onClick={() => { setError(null); activate.mutate(); }}
            busy={activate.isPending}
            disabled={!/^\d{6,8}$/.test(code.trim())}
          >
            Confirm
          </Button>
        </>
      }
    >
      <div className="stack">
        {error && <Notice tone="danger">{error}</Notice>}

        <p className="muted">
          Install an authenticator app — Google Authenticator, Authy, or the one built into your
          password manager — then add this account to it.
        </p>

        {setup ? (
          <>
            <div className="stack stack--tight">
              <div className="field__label">Type this into the app</div>
              <div
                className="mono"
                style={{
                  padding: 'var(--space-3)',
                  background: 'var(--surface-sunken)',
                  borderRadius: 'var(--radius)',
                  wordBreak: 'break-all',
                  letterSpacing: '0.08em',
                  userSelect: 'all',
                }}
              >
                {setup.secret}
              </div>
              <p className="small muted">
                Account name: {setup.issuer}. On a phone you can open the authenticator and paste
                this in as a setup key.
              </p>
            </div>

            <TextField
              label="The six digits it shows"
              className="input input--mono"
              value={code}
              onChange={(event) => setCode(event.target.value.replace(/\D/g, '').slice(0, 8))}
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={8}
              autoFocus
              required
            />
          </>
        ) : (
          <p className="muted">Preparing…</p>
        )}
      </div>
    </Sheet>
  );
}

function ReissueSheet({ onClose }: { onClose: () => void }) {
  const [password, setPassword] = useState('');
  const [codes, setCodes] = useState<MfaActivation | null>(null);
  const [error, setError] = useState<string | null>(null);

  const reissue = useMutation({
    mutationFn: () => authApi.mfaRecoveryCodes(password),
    onSuccess: (result) => {
      setPassword('');
      setCodes(result);
    },
    onError: (cause) => {
      setPassword('');
      setError(cause instanceof ApiError ? cause.message : 'Could not replace the codes.');
    },
  });

  if (codes) {
    return (
      <Sheet
        title="Your new recovery codes"
        onClose={onClose}
        footer={<Button variant="primary" onClick={onClose}>I have written them down</Button>}
      >
        <div className="stack">
          <Notice tone="warn" title="The old codes no longer work">
            Shown once. Each of these works once.
          </Notice>
          <div
            className="mono"
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(2, 1fr)',
              gap: 'var(--space-2)',
              padding: 'var(--space-4)',
              background: 'var(--surface-sunken)',
              borderRadius: 'var(--radius)',
              userSelect: 'all',
            }}
          >
            {codes.recoveryCodes.map((recovery) => <span key={recovery}>{recovery}</span>)}
          </div>
        </div>
      </Sheet>
    );
  }

  return (
    <Sheet
      title="Replace your recovery codes"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={reissue.isPending}>Cancel</Button>
          <Button
            variant="primary" onClick={() => { setError(null); reissue.mutate(); }}
            busy={reissue.isPending} disabled={!password}
          >
            Replace them
          </Button>
        </>
      }
    >
      <div className="stack">
        {error && <Notice tone="danger">{error}</Notice>}
        <p className="muted">
          The codes you have now stop working. Your password is asked for because somebody who had
          your open session would otherwise be able to issue themselves a way back in.
        </p>
        <TextField
          label="Your password" type="password" value={password}
          onChange={(event) => setPassword(event.target.value)}
          autoComplete="current-password" required autoFocus
        />
      </div>
    </Sheet>
  );
}

function DisableSheet({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const toast = useToast();
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);

  const disable = useMutation({
    mutationFn: () => authApi.mfaDisable(password),
    onSuccess: () => {
      toast.success('Two-step sign-in is off.');
      onDone();
    },
    onError: (cause) => {
      setPassword('');
      setError(cause instanceof ApiError ? cause.message : 'Could not turn it off.');
    },
  });

  return (
    <Sheet
      title="Turn off two-step sign-in"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={disable.isPending}>Keep it on</Button>
          <Button
            variant="danger" onClick={() => { setError(null); disable.mutate(); }}
            busy={disable.isPending} disabled={!password}
          >
            Turn it off
          </Button>
        </>
      }
    >
      <div className="stack">
        {error && <Notice tone="danger">{error}</Notice>}
        <Notice tone="warn">
          Your password alone will then protect the account that decides where your money is sent.
        </Notice>
        <TextField
          label="Your password" type="password" value={password}
          onChange={(event) => setPassword(event.target.value)}
          autoComplete="current-password" required autoFocus
        />
      </div>
    </Sheet>
  );
}
