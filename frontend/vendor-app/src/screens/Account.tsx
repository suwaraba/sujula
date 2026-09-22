import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { authApi } from '@/api/endpoints/auth';
import { ApiError } from '@/api/errors';
import { useAuth, useMe } from '@/auth/AuthProvider';
import { formatDateTime } from '@/lib/format';
import { platformName } from '@/lib/platform';
import {
  Badge, Button, Card, KeyValue, Notice, PageHeader, SkeletonList,
} from '@/components/ui';
import { TextField, fieldError } from '@/components/form';
import { ConfirmSheet } from '@/components/Sheet';
import { MfaCard } from '@/components/MfaCard';
import { useToast } from '@/components/Toast';

export function Account() {
  const me = useMe();
  const { signOut, signOutEverywhere, reload } = useAuth();
  const queryClient = useQueryClient();
  const toast = useToast();
  const [signingOutAll, setSigningOutAll] = useState(false);

  const sessions = useQuery({ queryKey: ['sessions'], queryFn: () => authApi.sessions() });

  const endSession = useMutation({
    mutationFn: (sessionId: number) => authApi.endSession(sessionId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['sessions'] });
      toast.success('That device has been signed out.');
    },
    onError: () => toast.error('Could not sign that device out.'),
  });

  const resendVerification = useMutation({
    mutationFn: () => authApi.resendVerification(),
    onSuccess: () => toast.success('Verification email sent.'),
    onError: () => toast.error('Could not send the email.'),
  });

  return (
    <div className="page stack stack--loose">
      <PageHeader title="Your account" subtitle={me.profile.email} />

      {!me.profile.emailVerified && (
        <Notice tone="warn" title="Your email is not confirmed">
          Some things stay locked until it is.{' '}
          <Button
            variant="ghost" size="sm"
            onClick={() => resendVerification.mutate()}
            busy={resendVerification.isPending}
          >
            Send the link again
          </Button>
        </Notice>
      )}

      <Card title="You">
        <KeyValue
          rows={[
            ['Name', me.profile.fullName ?? '—'],
            ['Email', <>
              {me.profile.email}{' '}
              <Badge tone={me.profile.emailVerified ? 'ok' : 'warn'}>
                {me.profile.emailVerified ? 'Confirmed' : 'Not confirmed'}
              </Badge>
            </>],
            ['Phone', <>
              {me.profile.phone ?? '—'}{' '}
              {me.profile.phone && (
                <Badge tone={me.profile.phoneVerified ? 'ok' : 'warn'}>
                  {me.profile.phoneVerified ? 'Confirmed' : 'Not confirmed'}
                </Badge>
              )}
            </>],
            ['Shown to you in', me.resolvedCurrency],
            ['Signed in since', formatDateTime(me.profile.createdAt)],
          ]}
        />
      </Card>

      <PasswordCard onChanged={() => void reload()} />

      <MfaCard onChanged={() => void reload()} />

      <Card title="Where you are signed in" flush>
        {sessions.isLoading ? (
          <SkeletonList rows={2} />
        ) : (sessions.data?.length ?? 0) === 0 ? (
          <div style={{ padding: 'var(--space-4)' }} className="muted">
            Only this device.
          </div>
        ) : (
          <div className="list">
            {sessions.data!.map((session) => (
              <div key={session.id} className="list__item">
                <div className="list__main">
                  <div className="list__title">
                    {session.device ?? session.userAgent ?? 'Unknown device'}
                    {session.current && <Badge tone="accent"> This one</Badge>}
                  </div>
                  <div className="list__meta">
                    {session.ipAddress ?? 'unknown address'} · last seen{' '}
                    {formatDateTime(session.lastSeenAt ?? session.createdAt)}
                  </div>
                </div>
                {!session.current && (
                  <Button
                    size="sm" variant="ghost"
                    onClick={() => endSession.mutate(session.id)}
                    disabled={endSession.isPending}
                  >
                    Sign out
                  </Button>
                )}
              </div>
            ))}
          </div>
        )}
      </Card>

      <Card title="Signing out">
        <div className="stack">
          <div className="row">
            <Button variant="secondary" onClick={() => void signOut()}>
              Sign out of this device
            </Button>
            <Button variant="danger" onClick={() => setSigningOutAll(true)}>
              Sign out everywhere
            </Button>
          </div>
          <p className="small muted">
            Signing out everywhere ends every session on the account, including this one. Do it if
            you think somebody else has your password.
          </p>
        </div>
      </Card>

      <p className="small faint center">
        Sujula for sellers · {platformName()}
      </p>

      {signingOutAll && (
        <ConfirmSheet
          title="Sign out everywhere?"
          confirmLabel="Sign out everywhere"
          danger
          onConfirm={() => void signOutEverywhere()}
          onClose={() => setSigningOutAll(false)}
        >
          <p>
            Every device signed in to this account is signed out, including this one. You will
            need your password to get back in.
          </p>
        </ConfirmSheet>
      )}
    </div>
  );
}

function PasswordCard({ onChanged }: { onChanged: () => void }) {
  const toast = useToast();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});

  const change = useMutation({
    mutationFn: () => authApi.changePassword(current, next),
    onSuccess: (result) => {
      toast.success(result.message || 'Password changed.');
      setCurrent('');
      setNext('');
      setConfirm('');
      onChanged();
    },
    onError: (cause) => {
      setCurrent('');
      if (cause instanceof ApiError) {
        setError(cause.message);
        setFields(cause.fieldErrors);
      } else {
        setError('Could not change the password.');
      }
    },
  });

  const mismatch = next !== '' && confirm !== '' && next !== confirm;

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setFields({});
    if (mismatch) return;
    change.mutate();
  }

  return (
    <Card title="Password">
      <form className="stack" onSubmit={onSubmit} noValidate>
        {error && <Notice tone="danger">{error}</Notice>}
        <TextField
          label="Current password" type="password" value={current}
          onChange={(event) => setCurrent(event.target.value)}
          autoComplete="current-password" error={fieldError(fields, 'currentPassword')} required
        />
        <TextField
          label="New password" type="password" value={next}
          onChange={(event) => setNext(event.target.value)}
          autoComplete="new-password" error={fieldError(fields, 'newPassword')} required
        />
        <TextField
          label="New password again" type="password" value={confirm}
          onChange={(event) => setConfirm(event.target.value)}
          autoComplete="new-password"
          error={mismatch ? 'These two do not match.' : undefined}
          required
        />
        <Button
          type="submit" variant="primary" busy={change.isPending}
          disabled={mismatch || !current || !next}
        >
          Change password
        </Button>
      </form>
    </Card>
  );
}
