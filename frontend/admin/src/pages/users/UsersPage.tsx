import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { users as usersApi } from '@/api/endpoints';
import { USER_ROLES } from '@/api/enums';
import type { UserRow } from '@/api/types';
import { ActionModal } from '@/components/ActionModal';
import { DataTable, type Column } from '@/components/DataTable';
import { DecideButton } from '@/components/Decide';
import { Field, FilterBar, FormRow, Select, TextArea, TextInput, TriState } from '@/components/forms';
import { CountrySelect } from '@/components/CountrySelect';
import { Pagination } from '@/components/Pagination';
import { Muted, PageHeader, Pill, humanise } from '@/components/primitives';
import { DateTime } from '@/components/Time';
import { keys, useAction, useFilters, usePaging } from '@/hooks';

const DEFAULTS = {
  q: undefined as string | undefined,
  role: undefined as string | undefined,
  country: undefined as string | undefined,
  blocked: undefined as boolean | undefined,
  lockedOut: undefined as boolean | undefined,
};

export function UsersPage() {
  const navigate = useNavigate();
  const [filters, setFilters, reset] = useFilters(DEFAULTS);
  const { page, size, setPage, setSize } = usePaging();

  const query = useQuery({
    queryKey: [...keys.users, filters, page, size],
    queryFn: () => usersApi.search({ ...filters, role: filters.role as never, page, size }),
  });

  const columns: Column<UserRow>[] = [
    {
      key: 'who',
      header: 'Account',
      render: (row) => (
        <>
          <strong>{row.name ?? row.email}</strong>
          <br />
          <Muted>
            {row.email} · #{row.id}
          </Muted>
        </>
      ),
    },
    { key: 'role', header: 'Role', render: (row) => <Pill tone="neutral">{humanise(row.role)}</Pill> },
    { key: 'phone', header: 'Phone', render: (row) => row.phone ?? <Muted>—</Muted> },
    { key: 'country', header: 'Country', render: (row) => row.countryCode ?? <Muted>—</Muted> },
    {
      key: 'state',
      header: 'State',
      render: (row) => (
        <>
          {!row.enabled && <Pill tone="bad">Disabled</Pill>}
          {row.blocked && <Pill tone="bad">Blocked</Pill>}
          {row.lockedUntil && (
            <Pill tone="warn" title={row.lockedBy ? `Locked by ${row.lockedBy}` : undefined}>
              Locked until <DateTime value={row.lockedUntil} />
            </Pill>
          )}
          {row.enabled && !row.blocked && !row.lockedUntil && <Pill tone="good">Open</Pill>}
        </>
      ),
    },
    {
      key: 'security',
      header: 'Security',
      render: (row) => (
        <>
          {row.emailVerified ? <Muted>email verified</Muted> : <Pill tone="warn">email unverified</Pill>}
          <br />
          {row.mfaEnabled ? <Pill tone="good">MFA on</Pill> : <Muted>no second factor</Muted>}
        </>
      ),
    },
    { key: 'lastSeen', header: 'Last seen', render: (row) => <DateTime value={row.lastSeenAt} /> },
  ];

  return (
    <>
      <PageHeader
        title="Accounts"
        description="The text match is deliberately narrow — name, email and phone. Nothing on this surface sets a flag on an account by hand: locks come from sanctions, so every account that cannot sign in has a row saying who decided that and why."
        actions={<CreateUser />}
      />

      <FilterBar onReset={reset}>
        <Field label="Search">
          <TextInput
            value={filters.q ?? ''}
            placeholder="Name, email or phone"
            onChange={(q) => setFilters({ q: q || undefined })}
          />
        </Field>
        <Field label="Role">
          <Select
            value={(filters.role ?? '') as string}
            options={USER_ROLES}
            placeholder="Any role"
            onChange={(role) => setFilters({ role: role || undefined })}
          />
        </Field>
        <Field label="Country">
          <TextInput
            value={filters.country ?? ''}
            maxLength={2}
            onChange={(country) => setFilters({ country: country ? country.toUpperCase() : undefined })}
          />
        </Field>
        <Field label="Blocked">
          <TriState value={filters.blocked} onChange={(blocked) => setFilters({ blocked })} />
        </Field>
        <Field label="Locked out" hint="Read from the sanction rows, not a cached flag.">
          <TriState value={filters.lockedOut} onChange={(lockedOut) => setFilters({ lockedOut })} />
        </Field>
      </FilterBar>

      <DataTable
        columns={columns}
        rows={query.data?.content}
        rowKey={(row) => row.id}
        isLoading={query.isFetching}
        error={query.error}
        empty="No accounts match those filters."
        onRowClick={(row) => navigate(`/users/${row.id}`)}
      />

      {query.data && (
        <Pagination
          page={query.data.page}
          size={query.data.size}
          totalElements={query.data.totalElements}
          totalPages={query.data.totalPages}
          last={query.data.last}
          onPage={setPage}
          onSize={setSize}
        />
      )}
    </>
  );
}

/**
 * Creating an actor by hand.
 *
 * No password field, deliberately. The server mints one and sends a link: a
 * password an administrator chose is a password an administrator knows, and
 * this console is not where anybody's credentials are set.
 */
function CreateUser() {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [email, setEmail] = useState('');
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [phone, setPhone] = useState('');
  const [role, setRole] = useState<string>('');
  const [countryCode, setCountryCode] = useState('');
  const [reason, setReason] = useState('');

  const action = useAction(
    () =>
      usersApi.create({
        email: email.trim(),
        firstName: firstName.trim(),
        lastName: lastName.trim(),
        phone: phone.trim() || null,
        role: role as never,
        countryCode: countryCode ? countryCode.toUpperCase() : null,
        reason: reason.trim(),
      }),
    {
      invalidate: [keys.users],
      message: (created) => created.message,
      onDone: (created) => navigate(`/users/${created.id}`),
    },
  );

  return (
    <>
      <DecideButton onClick={() => setOpen(true)}>Create an account</DecideButton>
      <ActionModal
        open={open}
        width="wide"
        title="Create an actor by hand"
        description="For what self-registration cannot serve: a driver who signed up at a desk, a counter operator with no email of their own. No password is set here — one is minted and they are sent a link to choose their own."
        submitLabel="Create the account"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={!email.trim() || !role || reason.trim().length < 5}
      >
        <FormRow>
          <Field label="Email" required>
            <TextInput type="email" value={email} required onChange={setEmail} />
          </Field>
          <Field label="Role" required>
            <Select value={role} required options={USER_ROLES} placeholder="Choose a role" onChange={setRole} />
          </Field>
        </FormRow>
        <FormRow>
          <Field label="First name" required>
            <TextInput value={firstName} required onChange={setFirstName} />
          </Field>
          <Field label="Last name" required>
            <TextInput value={lastName} required onChange={setLastName} />
          </Field>
        </FormRow>
        <FormRow>
          <Field label="Phone">
            <TextInput value={phone} onChange={setPhone} placeholder="+220…" />
          </Field>
          <Field label="Country">
            <CountrySelect
              value={countryCode}
              purpose="any"
              placeholder="Not stated"
              onChange={setCountryCode}
            />
          </Field>
        </FormRow>
        <Field label="Why this account is being created" required>
          <TextArea value={reason} required minLength={5} onChange={setReason} />
        </Field>
      </ActionModal>
    </>
  );
}
