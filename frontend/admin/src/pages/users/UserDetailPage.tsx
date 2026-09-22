import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { users as usersApi } from '@/api/endpoints';
import { MODERATION_REASONS, USER_ROLES } from '@/api/enums';
import type { UserDetail } from '@/api/types';
import { ActionModal } from '@/components/ActionModal';
import { DecideButton } from '@/components/Decide';
import { Field, FormRow, NumberInput, Select, TextArea, TextInput } from '@/components/forms';
import { MoneyList } from '@/components/Money';
import { EMPTY_STEP_UP, StepUpFields, stepUpBody, type StepUpValue } from '@/components/StepUp';
import { useToast } from '@/components/Toast';
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
  StatusPill,
  humanise,
} from '@/components/primitives';
import { DateTime } from '@/components/Time';
import { keys, useAction } from '@/hooks';

export function UserDetailPage() {
  const userId = Number(useParams().userId);

  const query = useQuery({
    queryKey: keys.user(userId),
    queryFn: () => usersApi.detail(userId),
    enabled: Number.isFinite(userId),
  });

  if (query.isLoading) return <Loading what="Reading the account" />;
  if (query.error) return <ErrorBanner error={query.error} />;
  if (!query.data) return null;

  const detail = query.data;
  const { account, activity } = detail;

  return (
    <>
      <PageHeader
        title={account.name ?? account.email}
        description={
          <>
            <Pill tone="neutral">{humanise(account.role)}</Pill> · {account.email} · joined{' '}
            <DateTime value={account.createdAt} />
          </>
        }
        actions={
          <div className="row-actions">
            <EditProfile detail={detail} />
            <Activate detail={detail} />
            <Suspend detail={detail} />
            <Deactivate detail={detail} />
            <ChangeRole detail={detail} />
            <ForceLogout detail={detail} />
            <ResetMfa detail={detail} />
            <Impersonate detail={detail} />
          </div>
        }
      />

      {detail.note && <p className="notice notice-muted">{detail.note}</p>}

      <Grid columns={2}>
        <Card title="Account">
          <KeyValueList>
            <KeyValue label="Phone">{account.phone ?? <Muted>—</Muted>}</KeyValue>
            <KeyValue label="Country">{account.countryCode ?? <Muted>—</Muted>}</KeyValue>
            <KeyValue label="Signing in">
              {account.enabled ? <Pill tone="good">Allowed</Pill> : <Pill tone="bad">Disabled</Pill>}
              {account.lockedUntil && (
                <Pill tone="warn">
                  locked until <DateTime value={account.lockedUntil} />
                </Pill>
              )}
            </KeyValue>
            <KeyValue label="Email verified">{account.emailVerified ? 'yes' : 'no'}</KeyValue>
            <KeyValue label="Second factor">
              {account.mfaEnabled ? <Pill tone="good">On</Pill> : <Pill tone="warn">Off</Pill>}
            </KeyValue>
            <KeyValue label="Last seen">
              <DateTime value={account.lastSeenAt} />
            </KeyValue>
          </KeyValueList>
        </Card>

        <Card
          title="Activity"
          subtitle="Money is per currency and never summed — 400 EUR and 12,000 GMD is not 12,400 of anything."
        >
          <KeyValueList>
            <KeyValue label="Orders">
              {activity.orders} ({activity.ordersCancelled} cancelled)
            </KeyValue>
            <KeyValue label="Spent">
              <MoneyList
                entries={activity.spendByCurrency.map((spend) => ({
                  currency: spend.currency,
                  amount: spend.total,
                  label: `across ${spend.orders} orders`,
                }))}
                empty="nothing"
              />
            </KeyValue>
            <KeyValue label="Returns and disputes">
              {activity.returns} returns, {activity.disputes} disputes
            </KeyValue>
            <KeyValue label="Reviews">
              {activity.reviewsWritten} written, {activity.reviewsReported} reported
            </KeyValue>
            {activity.productsListed > 0 && (
              <KeyValue label="Listings">{activity.productsListed}</KeyValue>
            )}
            {activity.deliveriesCompleted > 0 && (
              <KeyValue label="Deliveries completed">{activity.deliveriesCompleted}</KeyValue>
            )}
            <KeyValue label="First and last order">
              <DateTime value={activity.firstOrderAt} /> → <DateTime value={activity.lastOrderAt} />
            </KeyValue>
          </KeyValueList>
        </Card>
      </Grid>

      {detail.vendor && (
        <Card title="Store">
          <KeyValueList>
            <KeyValue label="Name">
              <Link to={`/stores?q=${encodeURIComponent(detail.vendor.storeName)}`}>
                {detail.vendor.storeName}
              </Link>
            </KeyValue>
            <KeyValue label="Status">
              <StatusPill status={detail.vendor.status} />
            </KeyValue>
            <KeyValue label="Settles in">{detail.vendor.settlementCurrency}</KeyValue>
            <KeyValue label="Last changed">
              <DateTime value={detail.vendor.lastChangedAt} />
            </KeyValue>
          </KeyValueList>
        </Card>
      )}

      <Card
        title="Sanctions"
        subtitle="Why this account is, or was, held out. Nothing locks an account except a row here."
      >
        {detail.sanctions.length === 0 ? (
          <Muted>Never sanctioned.</Muted>
        ) : (
          <table className="table table-compact">
            <thead>
              <tr>
                <th scope="col">Type</th>
                <th scope="col">Reason</th>
                <th scope="col">Issued</th>
                <th scope="col">Expires</th>
                <th scope="col">Lifted</th>
                <th scope="col" />
              </tr>
            </thead>
            <tbody>
              {detail.sanctions.map((sanction) => (
                <tr key={sanction.id}>
                  <td>
                    <StatusPill status={sanction.type} />
                    {sanction.restrictedPermission && (
                      <>
                        <br />
                        <Muted>
                          <code>{sanction.restrictedPermission}</code>
                        </Muted>
                      </>
                    )}
                  </td>
                  <td>
                    {sanction.reasonText ?? (sanction.reason ? humanise(sanction.reason) : '—')}
                  </td>
                  <td>
                    <DateTime value={sanction.issuedAt} />
                    <br />
                    <Muted>{sanction.issuedBy}</Muted>
                  </td>
                  <td>
                    <DateTime value={sanction.expiresAt} />
                  </td>
                  <td>
                    {sanction.liftedAt ? (
                      <>
                        <DateTime value={sanction.liftedAt} />
                        <br />
                        <Muted>
                          {sanction.liftedBy} — {sanction.liftedReason}
                        </Muted>
                      </>
                    ) : (
                      <Muted>—</Muted>
                    )}
                  </td>
                  <td>{sanction.active && <Pill tone="bad">In force</Pill>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <Card title="Devices signed in">
        {detail.sessions.length === 0 ? (
          <Muted>No open sessions.</Muted>
        ) : (
          <table className="table table-compact">
            <thead>
              <tr>
                <th scope="col">Device</th>
                <th scope="col">From</th>
                <th scope="col">Opened</th>
                <th scope="col">Last seen</th>
                <th scope="col">Ended</th>
              </tr>
            </thead>
            <tbody>
              {detail.sessions.map((session) => (
                <tr key={session.id}>
                  <td>
                    {session.deviceLabel ?? <Muted>unnamed</Muted>}
                    {session.impersonatedBy && (
                      <>
                        {' '}
                        <Pill tone="warn" title="This session was opened by a member of staff acting as this person.">
                          impersonated by {session.impersonatedBy}
                        </Pill>
                      </>
                    )}
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
                    {session.revokedAt ? (
                      <>
                        <DateTime value={session.revokedAt} />
                        <br />
                        <Muted>{session.revokedReason}</Muted>
                      </>
                    ) : (
                      <Pill tone="good">Open</Pill>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      {detail.openCases.length > 0 && (
        <Card title="Open cases" tone="warning">
          <table className="table table-compact">
            <thead>
              <tr>
                <th scope="col">Case</th>
                <th scope="col">Reason</th>
                <th scope="col">About</th>
                <th scope="col">Due</th>
              </tr>
            </thead>
            <tbody>
              {detail.openCases.map((openCase) => (
                <tr key={openCase.id} className={openCase.overdue ? 'row-overdue' : undefined}>
                  <td>
                    <Link to={`/moderation/cases?status=OPEN`} className="mono">
                      {openCase.reference}
                    </Link>
                  </td>
                  <td>{humanise(openCase.reason)}</td>
                  <td>{openCase.subjectLabel ?? <Muted>—</Muted>}</td>
                  <td>
                    <DateTime value={openCase.dueBy} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
    </>
  );
}

const INVALIDATE = (userId: number) => [keys.user(userId), keys.users];

function EditProfile({ detail }: { detail: UserDetail }) {
  const [open, setOpen] = useState(false);
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [phone, setPhone] = useState('');
  const [countryCode, setCountryCode] = useState('');
  const [preferredCurrency, setPreferredCurrency] = useState('');
  const [preferredLanguage, setPreferredLanguage] = useState('');
  const [reason, setReason] = useState('');

  const action = useAction(
    () =>
      usersApi.patch(detail.account.id, {
        firstName: firstName.trim() || null,
        lastName: lastName.trim() || null,
        phone: phone.trim() || null,
        countryCode: countryCode ? countryCode.toUpperCase() : null,
        preferredCurrency: preferredCurrency ? preferredCurrency.toUpperCase() : null,
        preferredLanguage: preferredLanguage.trim() || null,
        reason: reason.trim(),
      }),
    { invalidate: INVALIDATE(detail.account.id), message: () => 'Profile updated.' },
  );

  return (
    <>
      <DecideButton variant="ghost" onClick={() => setOpen(true)}>
        Edit profile
      </DecideButton>
      <ActionModal
        open={open}
        width="wide"
        title="Edit a profile on somebody's behalf"
        description="Leave a field empty to keep what is there. Changing the phone number clears its verification — carrying that across would hand somebody a verified number they do not hold."
        submitLabel="Save"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={reason.trim().length < 5}
      >
        <FormRow>
          <Field label="First name">
            <TextInput value={firstName} onChange={setFirstName} placeholder={detail.account.name ?? ''} />
          </Field>
          <Field label="Last name">
            <TextInput value={lastName} onChange={setLastName} />
          </Field>
        </FormRow>
        <FormRow>
          <Field label="Phone" hint="Changing this clears its verification.">
            <TextInput value={phone} onChange={setPhone} placeholder={detail.account.phone ?? ''} />
          </Field>
          <Field label="Country">
            <TextInput value={countryCode} maxLength={2} onChange={setCountryCode} />
          </Field>
        </FormRow>
        <FormRow>
          <Field label="Preferred currency" hint="What they are shown prices in.">
            <TextInput value={preferredCurrency} maxLength={3} onChange={setPreferredCurrency} />
          </Field>
          <Field label="Preferred language">
            <TextInput value={preferredLanguage} onChange={setPreferredLanguage} placeholder="en" />
          </Field>
        </FormRow>
        <Field label="Why" required>
          <TextArea value={reason} required minLength={5} onChange={setReason} />
        </Field>
      </ActionModal>
    </>
  );
}

function Activate({ detail }: { detail: UserDetail }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');

  const action = useAction(() => usersApi.activate(detail.account.id, { reason: reason.trim() }), {
    invalidate: INVALIDATE(detail.account.id),
    message: (changed) => changed.message,
  });

  return (
    <>
      <DecideButton variant="secondary" onClick={() => setOpen(true)}>
        Lift what is holding them out
      </DecideButton>
      <ActionModal
        open={open}
        title="Lift whatever is holding this account out"
        description="Lifts the sanctions and re-enables sign-in. An account that was not held out answers plainly rather than failing, so two agents answering the same complaint do not both see an error."
        submitLabel="Lift it"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={reason.trim().length < 5}
      >
        <Field label="Reason" required>
          <TextArea value={reason} required minLength={5} onChange={setReason} />
        </Field>
      </ActionModal>
    </>
  );
}

function Suspend({ detail }: { detail: UserDetail }) {
  const [open, setOpen] = useState(false);
  const [days, setDays] = useState<number | ''>(7);
  const [reason, setReason] = useState('');
  const [category, setCategory] = useState<string>('');

  const action = useAction(
    () =>
      usersApi.suspend(detail.account.id, {
        days: Number(days),
        reason: reason.trim(),
        category: category as never,
      }),
    { invalidate: INVALIDATE(detail.account.id), message: (changed) => changed.message },
  );

  return (
    <>
      <DecideButton variant="ghost" onClick={() => setOpen(true)}>
        Suspend
      </DecideButton>
      <ActionModal
        open={open}
        title="Shut this account for a number of days"
        description="A duration, not a date: you think in 'a week', and a date sent from a browser is one that can be off by a timezone nobody converted. It ends by itself."
        submitLabel="Suspend"
        tone="danger"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={days === '' || !category || reason.trim().length < 5}
      >
        <FormRow>
          <Field label="For how many days" required>
            <NumberInput value={days} required min={1} max={365} onChange={setDays} />
          </Field>
          <Field label="Category" required>
            <Select value={category} required options={MODERATION_REASONS} placeholder="Choose" onChange={setCategory} />
          </Field>
        </FormRow>
        <Field label="Reason" required hint="The person is told this.">
          <TextArea value={reason} required minLength={5} onChange={setReason} />
        </Field>
      </ActionModal>
    </>
  );
}

function Deactivate({ detail }: { detail: UserDetail }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [category, setCategory] = useState<string>('');

  const action = useAction(
    () =>
      usersApi.deactivate(detail.account.id, { reason: reason.trim(), category: category as never }),
    { invalidate: INVALIDATE(detail.account.id), message: (changed) => changed.message },
  );

  return (
    <>
      <DecideButton variant="danger" onClick={() => setOpen(true)}>
        Shut indefinitely
      </DecideButton>
      <ActionModal
        open={open}
        title="Shut this account indefinitely"
        description="Every open session ends with it — otherwise the lock only takes effect the next time they are asked to sign in, which for a phone app is never."
        submitLabel="Shut it"
        tone="danger"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={!category || reason.trim().length < 5}
      >
        <Field label="Category" required>
          <Select value={category} required options={MODERATION_REASONS} placeholder="Choose" onChange={setCategory} />
        </Field>
        <Field label="Reason" required hint="The person is told this.">
          <TextArea value={reason} required minLength={5} rows={4} onChange={setReason} />
        </Field>
      </ActionModal>
    </>
  );
}

function ChangeRole({ detail }: { detail: UserDetail }) {
  const [open, setOpen] = useState(false);
  const [role, setRole] = useState<string>('');
  const [reason, setReason] = useState('');
  const [scopeCountry, setScopeCountry] = useState('');

  const action = useAction(
    () =>
      usersApi.changeRole(detail.account.id, {
        role: role as never,
        reason: reason.trim(),
        scopeCountry: scopeCountry ? scopeCountry.toUpperCase() : null,
      }),
    {
      invalidate: INVALIDATE(detail.account.id),
      message: (changed) => changed.message,
    },
  );

  const runsAStore = Boolean(detail.vendor);

  return (
    <>
      <DecideButton
        variant="ghost"
        onClick={() => setOpen(true)}
        disabled={runsAStore}
        title={
          runsAStore
            ? 'This account runs a store. Suspend the store instead — that has a cascade a role change does not.'
            : undefined
        }
      >
        Change role
      </DecideButton>
      <ActionModal
        open={open}
        title="Change what kind of actor this account is"
        description="Every session ends with it. A role lives in the access token, so a demoted user with a live token is still what they were until it expires — and that is the direction that matters."
        submitLabel="Change the role"
        tone="danger"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={!role || reason.trim().length < 5}
      >
        <p className="notice notice-muted">
          Currently <strong>{humanise(detail.account.role)}</strong>.
        </p>
        <FormRow>
          <Field label="New role" required>
            <Select value={role} required options={USER_ROLES} placeholder="Choose" onChange={setRole} />
          </Field>
          <Field label="Scope to a country" hint="Optional, for staff roles.">
            <TextInput value={scopeCountry} maxLength={2} onChange={setScopeCountry} />
          </Field>
        </FormRow>
        <Field label="Reason" required>
          <TextArea value={reason} required minLength={5} onChange={setReason} />
        </Field>
      </ActionModal>
    </>
  );
}

function ForceLogout({ detail }: { detail: UserDetail }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');

  const action = useAction(() => usersApi.forceLogout(detail.account.id, { reason: reason.trim() }), {
    invalidate: INVALIDATE(detail.account.id),
    message: (ended) => ended.message,
  });

  return (
    <>
      <DecideButton variant="ghost" onClick={() => setOpen(true)}>
        End every session
      </DecideButton>
      <ActionModal
        open={open}
        title="End every session this account has open"
        description="What to do first when somebody reports their account taken over: it costs them one sign-in and costs whoever has their token everything."
        submitLabel="End them"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={reason.trim().length < 5}
      >
        <Field label="Reason" required>
          <TextArea value={reason} required minLength={5} onChange={setReason} />
        </Field>
      </ActionModal>
    </>
  );
}

/**
 * Clearing somebody's second factor.
 *
 * The endpoint an attacker who has reached an admin account wants most,
 * because it turns one takeover into a takeover of anybody. Step-up guarded on
 * the server; the warning here says why rather than just that.
 */
function ResetMfa({ detail }: { detail: UserDetail }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [stepUp, setStepUp] = useState<StepUpValue>(EMPTY_STEP_UP);

  const action = useAction(
    () => usersApi.resetMfa(detail.account.id, { reason: reason.trim(), ...stepUpBody(stepUp) }),
    {
      invalidate: INVALIDATE(detail.account.id),
      message: (reset) => reset.message,
      onDone: () => setStepUp(EMPTY_STEP_UP),
    },
  );

  return (
    <>
      <DecideButton
        variant="danger"
        onClick={() => setOpen(true)}
        disabled={!detail.account.mfaEnabled}
        title={detail.account.mfaEnabled ? undefined : 'This account has no second factor to clear.'}
      >
        Clear second factor
      </DecideButton>
      <ActionModal
        open={open}
        title="Clear this account's second factor"
        description="Their sessions go with it, or the account spends a month with no second factor and a live token."
        submitLabel="Clear it"
        tone="danger"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={reason.trim().length < 5 || !stepUp.password}
      >
        <p className="notice notice-warning">
          Satisfy yourself that the person asking is the person who owns the account. This is what
          somebody who has taken over an administrator's session wants most.
        </p>
        <Field label="How you established who they are" required>
          <TextArea
            value={reason}
            required
            minLength={5}
            rows={4}
            onChange={setReason}
            placeholder="Called back on the number on file; confirmed last two orders and delivery address."
          />
        </Field>
        <StepUpFields value={stepUp} onChange={setStepUp} what="Clearing somebody's second factor" />
      </ActionModal>
    </>
  );
}

/**
 * Opening a short session as somebody else.
 *
 * The most invasive thing this surface can do. The token it returns is
 * deliberately not stored: it is shown once, for pasting into a client that can
 * use it, so this console never quietly becomes somebody else.
 */
function Impersonate({ detail }: { detail: UserDetail }) {
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [reference, setReference] = useState('');
  const [minutes, setMinutes] = useState<number | ''>(15);
  const [opened, setOpened] = useState<{ token: string; expiresAt: string; warning: string } | null>(
    null,
  );

  const action = useAction(
    () =>
      usersApi.impersonate(detail.account.id, {
        reason: reason.trim(),
        reference: reference.trim() || null,
        minutes: minutes === '' ? null : minutes,
      }),
    {
      invalidate: INVALIDATE(detail.account.id),
      onDone: (result) =>
        setOpened({ token: result.accessToken, expiresAt: result.expiresAt, warning: result.warning }),
    },
  );

  const staff = detail.account.role === 'ADMIN' || detail.account.role === 'SUPPORT';

  return (
    <>
      <DecideButton
        variant="ghost"
        onClick={() => setOpen(true)}
        disabled={staff}
        title={staff ? 'Impersonating another member of staff is refused.' : undefined}
      >
        Act as this person
      </DecideButton>

      <ActionModal
        open={open}
        title="Open a short session as this person"
        description="It reads their messages, their addresses and their orders. Time-boxed, not extendable, flagged in the token so every client shows a banner, visible in their own device list, and audited with your reason."
        submitLabel="Open the session"
        tone="danger"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={reason.trim().length < 10}
      >
        <p className="notice notice-warning">
          The difference between support work and snooping is the reason you write here, and somebody
          will read it.
        </p>
        <FormRow>
          <Field label="Ticket or case reference">
            <TextInput value={reference} onChange={setReference} />
          </Field>
          <Field label="For how many minutes">
            <NumberInput value={minutes} min={1} max={60} onChange={setMinutes} />
          </Field>
        </FormRow>
        <Field label="Why you need to be them" required>
          <TextArea
            value={reason}
            required
            minLength={10}
            rows={4}
            onChange={setReason}
            placeholder="Customer reports checkout failing at the delivery step and cannot screenshot it; reproducing against their own basket."
          />
        </Field>
      </ActionModal>

      <ActionModal
        open={opened !== null}
        title="Session opened"
        description="Copy this token into the client you are reproducing the problem in. It is not kept here, and this console stays signed in as you."
        submitLabel="Done"
        onClose={() => {
          setOpened(null);
          setReason('');
          setReference('');
        }}
        onSubmit={async () => undefined}
      >
        {opened && (
          <>
            <p className="notice notice-warning">{opened.warning}</p>
            <Field label="Access token" hint={`Expires ${opened.expiresAt}.`}>
              <TextArea
                value={opened.token}
                rows={4}
                readOnly
                onChange={() => undefined}
                onFocus={(event) => event.currentTarget.select()}
              />
            </Field>
            <button
              type="button"
              className="button button-ghost"
              onClick={async () => {
                await navigator.clipboard?.writeText(opened.token);
                toast.ok('Token copied.');
              }}
            >
              Copy the token
            </button>
          </>
        )}
      </ActionModal>
    </>
  );
}
