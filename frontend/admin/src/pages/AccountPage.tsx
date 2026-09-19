import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { auth as authApi, me as meApi } from '@/api/endpoints';
import { ActionModal } from '@/components/ActionModal';
import { Field, TextInput } from '@/components/forms';
import { useToast } from '@/components/Toast';
import { useAuth } from '@/auth/AuthContext';
import {
  Card,
  ErrorBanner,
  Grid,
  KeyValue,
  KeyValueList,
  Loading,
  Muted,
  PageHeader,
  Pill,
  humanise,
} from '@/components/primitives';
import { DateTime } from '@/components/Time';

/**
 * The operator's own account.
 *
 * This exists because of the step-up endpoints. Six operations on this console
 * — a refund, a payout run assembled and released, a dispute decided,
 * somebody's second factor cleared, an unproven handover recorded — ask for the
 * administrator's authenticator code. A console that demands a code and gives
 * nobody a way to enrol one is a console where every administrator is working
 * with a password alone.
 */
export function AccountPage() {
  const { profile, refreshMe, signOut } = useAuth();

  const permissions = useQuery({ queryKey: ['me', 'permissions'], queryFn: meApi.permissions });
  const sessions = useQuery({ queryKey: ['me', 'sessions'], queryFn: meApi.sessions });

  if (!profile) return <Loading what="Reading your account" />;

  return (
    <>
      <PageHeader
        title="Your account"
        description="Everything you do on this console is recorded against this account. Treat its second factor as the thing standing between a stolen laptop and the money on these screens."
      />

      <Grid columns={2}>
        <Card title="Who you are">
          <KeyValueList>
            <KeyValue label="Name">{profile.fullName ?? <Muted>—</Muted>}</KeyValue>
            <KeyValue label="Email">{profile.email}</KeyValue>
            <KeyValue label="Role">
              <Pill tone="info">{humanise(profile.role)}</Pill>
            </KeyValue>
            <KeyValue label="Second factor">
              {profile.mfaEnabled ? <Pill tone="good">On</Pill> : <Pill tone="bad">Off</Pill>}
            </KeyValue>
            <KeyValue label="Joined">
              <DateTime value={profile.createdAt} />
            </KeyValue>
          </KeyValueList>

          <div className="form-actions form-actions-left">
            <ChangePassword />
            {profile.mfaEnabled ? (
              <>
                <RegenerateRecoveryCodes />
                <DisableMfa onDone={refreshMe} />
              </>
            ) : (
              <EnableMfa onDone={refreshMe} />
            )}
          </div>

          {!profile.mfaEnabled && (
            <p className="notice notice-warning">
              Turn on a second factor. Without one you cannot be asked for a code, so every step-up
              on this console falls back to a password — and a password is what an attacker already
              has when they are reading this screen.
            </p>
          )}
        </Card>

        <Card
          title="What you may do"
          subtitle="Resolved by the server. The console renders against this rather than against your role, so a button you can see is one the API will accept."
        >
          <ErrorBanner error={permissions.error} />
          {permissions.data && (
            <div className="flag-row">
              {permissions.data.permissions.map((permission) => (
                <Pill key={permission} tone="neutral">
                  {permission}
                </Pill>
              ))}
            </div>
          )}
        </Card>
      </Grid>

      <Card
        title="Your devices"
        subtitle="Anything here you do not recognise should be signed out now and reported."
        actions={
          <button
            type="button"
            className="button button-ghost"
            onClick={() => signOut(true)}
          >
            Sign out everywhere
          </button>
        }
      >
        <ErrorBanner error={sessions.error} />
        {sessions.isLoading && <Loading />}
        {sessions.data && (
          <table className="table table-compact">
            <thead>
              <tr>
                <th scope="col">Device</th>
                <th scope="col">From</th>
                <th scope="col">Opened</th>
                <th scope="col">Last seen</th>
                <th scope="col" />
              </tr>
            </thead>
            <tbody>
              {sessions.data.map((session) => (
                <tr key={session.id}>
                  <td>
                    {session.deviceLabel ?? <Muted>unnamed</Muted>}
                    {session.current && <Pill tone="info">this one</Pill>}
                    {!session.active && <Pill tone="neutral">ended</Pill>}
                    <br />
                    <Muted>{session.userAgent ?? ''}</Muted>
                  </td>
                  <td>
                    <span className="mono">{session.ipAddress ?? '—'}</span>{' '}
                    <Muted>{session.countryCode}</Muted>
                  </td>
                  <td>
                    <DateTime value={session.createdAt} />
                  </td>
                  <td>
                    <DateTime value={session.lastSeenAt} />
                  </td>
                  <td>
                    <RevokeSession
                      sessionId={session.id}
                      disabled={session.current || !session.active}
                      onDone={() => sessions.refetch()}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </>
  );
}

function ChangePassword() {
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirm, setConfirm] = useState('');

  const mismatch = confirm.length > 0 && confirm !== newPassword;

  return (
    <>
      <button type="button" className="button button-ghost" onClick={() => setOpen(true)}>
        Change your password
      </button>
      <ActionModal
        open={open}
        title="Change your password"
        description="Every other device signed in as you is signed out. That is the point: changing a password because you think somebody has it, and leaving their session open, changes nothing."
        submitLabel="Change it"
        onClose={() => setOpen(false)}
        disabled={!currentPassword || !newPassword || mismatch}
        onSubmit={async () => {
          await authApi.changePassword({ currentPassword, newPassword, keepOtherSessions: false });
          toast.ok('Password changed. Your other devices have been signed out.');
          setCurrentPassword('');
          setNewPassword('');
          setConfirm('');
        }}
      >
        <Field label="Your current password" required>
          <TextInput
            type="password"
            autoComplete="current-password"
            required
            value={currentPassword}
            onChange={setCurrentPassword}
          />
        </Field>
        <Field label="New password" required>
          <TextInput
            type="password"
            autoComplete="new-password"
            required
            value={newPassword}
            onChange={setNewPassword}
          />
        </Field>
        <Field label="New password again" required error={mismatch ? 'These do not match.' : null}>
          <TextInput
            type="password"
            autoComplete="new-password"
            required
            value={confirm}
            onChange={setConfirm}
          />
        </Field>
      </ActionModal>
    </>
  );
}

/**
 * Enrolling an authenticator.
 *
 * Two steps, as the server has them: `setup` mints a secret, `activate` proves
 * you stored it. The recovery codes come back once and are never retrievable
 * again — so they are shown with that said plainly, rather than in a dialog
 * somebody dismisses.
 */
function EnableMfa({ onDone }: { onDone(): void }) {
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [setup, setSetup] = useState<{ secret: string; provisioningUri: string } | null>(null);
  const [code, setCode] = useState('');
  const [recoveryCodes, setRecoveryCodes] = useState<string[] | null>(null);
  const [error, setError] = useState<unknown>(null);

  async function begin() {
    setError(null);
    try {
      const started = await authApi.mfaSetup();
      setSetup(started);
      setOpen(true);
    } catch (caught) {
      setError(caught);
      setOpen(true);
    }
  }

  return (
    <>
      <button type="button" className="button button-primary" onClick={begin}>
        Turn on a second factor
      </button>

      <ActionModal
        open={open && recoveryCodes === null}
        title="Turn on a second factor"
        description="Add the secret below to your authenticator, then type the code it shows."
        submitLabel="Turn it on"
        onClose={() => {
          setOpen(false);
          setSetup(null);
          setCode('');
        }}
        disabled={code.trim().length < 6}
        onSubmit={async () => {
          const activated = await authApi.mfaActivate(code.trim());
          setRecoveryCodes(activated.recoveryCodes);
          onDone();
        }}
      >
        <ErrorBanner error={error} />
        {setup && (
          <>
            <Field label="Secret" hint="Type this into your authenticator if it cannot scan.">
              <TextInput value={setup.secret} readOnly onChange={() => undefined} />
            </Field>
            <Field label="Provisioning URI">
              <TextInput value={setup.provisioningUri} readOnly onChange={() => undefined} />
            </Field>
            <Field label="The code your authenticator shows" required>
              <TextInput
                inputMode="numeric"
                maxLength={6}
                required
                value={code}
                onChange={setCode}
              />
            </Field>
          </>
        )}
      </ActionModal>

      <ActionModal
        open={recoveryCodes !== null}
        title="Your recovery codes"
        description="Write these down somewhere that is not this machine. They are shown once — the server keeps only hashes, so nobody can read them back to you."
        submitLabel="I have stored them"
        onClose={() => {
          setRecoveryCodes(null);
          setOpen(false);
          setSetup(null);
          setCode('');
        }}
        onSubmit={async () => undefined}
      >
        <pre className="code-block">{(recoveryCodes ?? []).join('\n')}</pre>
        <button
          type="button"
          className="button button-ghost"
          onClick={async () => {
            await navigator.clipboard?.writeText((recoveryCodes ?? []).join('\n'));
            toast.ok('Recovery codes copied.');
          }}
        >
          Copy them
        </button>
      </ActionModal>
    </>
  );
}

function RegenerateRecoveryCodes() {
  const [open, setOpen] = useState(false);
  const [password, setPassword] = useState('');
  const [codes, setCodes] = useState<string[] | null>(null);

  return (
    <>
      <button type="button" className="button button-ghost" onClick={() => setOpen(true)}>
        Replace your recovery codes
      </button>
      <ActionModal
        open={open && codes === null}
        title="Replace your recovery codes"
        description="The codes you have now stop working."
        submitLabel="Replace them"
        onClose={() => {
          setOpen(false);
          setPassword('');
        }}
        disabled={!password}
        onSubmit={async () => {
          const result = await authApi.regenerateRecoveryCodes(password);
          setCodes(result.recoveryCodes);
          setPassword('');
        }}
      >
        <Field label="Your password" required>
          <TextInput type="password" required value={password} onChange={setPassword} />
        </Field>
      </ActionModal>

      <ActionModal
        open={codes !== null}
        title="Your new recovery codes"
        description="Shown once. The old ones no longer work."
        submitLabel="I have stored them"
        onClose={() => {
          setCodes(null);
          setOpen(false);
        }}
        onSubmit={async () => undefined}
      >
        <pre className="code-block">{(codes ?? []).join('\n')}</pre>
      </ActionModal>
    </>
  );
}

function DisableMfa({ onDone }: { onDone(): void }) {
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [password, setPassword] = useState('');

  return (
    <>
      <button type="button" className="button button-ghost" onClick={() => setOpen(true)}>
        Turn off the second factor
      </button>
      <ActionModal
        open={open}
        title="Turn off your second factor"
        description="Think about what this account can do before you do this. It refunds payments, releases payout runs and can read every customer's details."
        submitLabel="Turn it off"
        tone="danger"
        onClose={() => {
          setOpen(false);
          setPassword('');
        }}
        disabled={!password}
        onSubmit={async () => {
          await authApi.mfaDisable(password);
          toast.ok('Second factor turned off.');
          setPassword('');
          onDone();
        }}
      >
        <Field label="Your password" required>
          <TextInput type="password" required value={password} onChange={setPassword} />
        </Field>
      </ActionModal>
    </>
  );
}

function RevokeSession({
  sessionId,
  disabled,
  onDone,
}: {
  sessionId: number;
  disabled: boolean;
  onDone(): void;
}) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);

  return (
    <button
      type="button"
      className="button button-ghost"
      disabled={disabled || busy}
      title={disabled ? 'This is the session you are using, or it has already ended.' : undefined}
      onClick={async () => {
        setBusy(true);
        try {
          await meApi.revokeSession(sessionId);
          toast.ok('That device has been signed out.');
          onDone();
        } catch {
          toast.bad('Could not sign that device out.');
        } finally {
          setBusy(false);
        }
      }}
    >
      Sign it out
    </button>
  );
}
