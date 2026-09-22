import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { storeApi } from '@/api/endpoints/store';
import { ApiError } from '@/api/errors';
import { useStore } from '@/store/StoreProvider';
import { useIdempotencyKey } from '@/lib/hooks';
import { formatDateTime, humanise } from '@/lib/format';
import type { StaffMember } from '@/api/types';
import {
  Badge, Button, Card, EmptyState, Notice, PageHeader, SkeletonList, type Tone,
} from '@/components/ui';
import { TextField } from '@/components/form';
import { ConfirmSheet, Sheet } from '@/components/Sheet';
import { useToast } from '@/components/Toast';

/**
 * Who else can work in this shop, and what they may do.
 *
 * Permissions are granted as a whole set, never partially — the server replaces
 * the set outright — and an invitation with none named gets least privilege
 * rather than everything. "I just added them quickly" should not be a way to
 * lose a catalogue.
 */
const PERMISSIONS: { value: string; label: string; description: string }[] = [
  {
    value: 'ORDERS_VIEW',
    label: 'See orders',
    description: 'Read the queue and what each order contains. Cannot act on them.',
  },
  {
    value: 'ORDERS_FULFIL',
    label: 'Pack and hand over orders',
    description: 'Accept, pack, scan handsets and see the collection code a driver must present.',
  },
  {
    value: 'CATALOGUE_MANAGE',
    label: 'Manage listings and stock',
    description: 'Write, edit, publish and archive listings; adjust stock.',
  },
  {
    value: 'FINANCE_VIEW',
    label: 'See the money',
    description: 'Balances, transactions and payouts. Cannot request a payout or change the account.',
  },
  {
    value: 'STORE_PROFILE_MANAGE',
    label: 'Change shop details',
    description: 'Shop profile, policies, collection point and opening hours.',
  },
  {
    value: 'STAFF_MANAGE',
    label: 'Manage staff',
    description: 'Invite people and change what they may do. Grant this sparingly.',
  },
];

const STATUS_TONES: Record<string, Tone> = {
  ACTIVE: 'ok',
  INVITED: 'warn',
  REVOKED: 'neutral',
  EXPIRED: 'neutral',
};

export function StoreStaff() {
  const { storeId } = useStore();
  const [inviting, setInviting] = useState(false);
  const [editing, setEditing] = useState<StaffMember | null>(null);
  const [removing, setRemoving] = useState<StaffMember | null>(null);
  const queryClient = useQueryClient();
  const toast = useToast();

  const staff = useQuery({
    queryKey: ['staff', storeId],
    queryFn: () => storeApi.staff(storeId),
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['staff'] });
    void queryClient.invalidateQueries({ queryKey: ['store'] });
  };

  const remove = useMutation({
    mutationFn: (userId: number) => storeApi.removeStaff(storeId, userId),
    onSuccess: () => {
      setRemoving(null);
      toast.success('Removed from the shop.');
      invalidate();
    },
    onError: (error) => {
      setRemoving(null);
      toast.error(error instanceof ApiError ? error.message : 'Could not remove them.');
    },
  });

  const members = staff.data?.members ?? [];
  const atLimit = staff.data ? members.length >= staff.data.limit : false;

  return (
    <div className="page stack">
      <PageHeader
        title="Staff"
        subtitle="People who can work in your shop, and exactly what each of them may do."
        actions={
          <Button variant="primary" onClick={() => setInviting(true)} disabled={atLimit}>
            ＋ Invite somebody
          </Button>
        }
      />

      {atLimit && (
        <Notice tone="warn">
          You have reached the limit of {staff.data!.limit} people for this shop. Remove somebody
          before inviting another.
        </Notice>
      )}

      <Card flush>
        {staff.isLoading ? (
          <SkeletonList rows={3} />
        ) : members.length === 0 ? (
          <EmptyState
            icon="⊞"
            title="It is just you"
            action={<Button variant="primary" onClick={() => setInviting(true)}>Invite somebody</Button>}
          >
            Invite whoever helps you pack, and give them only what they need.
          </EmptyState>
        ) : (
          <div className="list">
            {members.map((member) => (
              <div key={member.id ?? `owner:${member.email}`} className="list__item">
                <div className="list__main">
                  <div className="list__title">
                    {member.displayName || member.email}
                    {member.owner && <Badge tone="accent"> Owner</Badge>}
                  </div>
                  <div className="list__meta truncate">{member.email}</div>
                  <div className="row" style={{ gap: 4, marginTop: 4, flexWrap: 'wrap' }}>
                    {member.owner ? (
                      <Badge tone="accent">Everything</Badge>
                    ) : member.permissions.length === 0 ? (
                      <Badge>No permissions</Badge>
                    ) : (
                      member.permissions.map((permission) => (
                        <Badge key={permission}>
                          {PERMISSIONS.find((p) => p.value === permission)?.label ?? humanise(permission)}
                        </Badge>
                      ))
                    )}
                  </div>
                  {member.status === 'INVITED' && (
                    <div className="small muted" style={{ marginTop: 4 }}>
                      {member.inviteExpiresAt
                        ? `Invitation expires ${formatDateTime(member.inviteExpiresAt)}`
                        : 'Invitation sent'}
                      {member.userId == null &&
                        ' — they have no account yet, so what they may do can only be changed once they accept.'}
                    </div>
                  )}
                </div>
                <div className="list__side">
                  <Badge tone={STATUS_TONES[member.status] ?? 'neutral'} dot>
                    {humanise(member.status)}
                  </Badge>
                </div>
                {!member.owner && (
                  member.userId != null ? (
                    <div className="row" style={{ gap: 4, flexWrap: 'nowrap' }}>
                      <Button size="sm" variant="ghost" onClick={() => setEditing(member)}>Edit</Button>
                      <Button size="sm" variant="ghost" onClick={() => setRemoving(member)}>✕</Button>
                    </div>
                  ) : (
                    <span className="small faint nowrap">Not joined yet</span>
                  )
                )}
              </div>
            ))}
          </div>
        )}
      </Card>

      {inviting && (
        <InviteSheet
          storeId={storeId}
          onClose={() => setInviting(false)}
          onDone={() => { setInviting(false); invalidate(); }}
        />
      )}

      {editing && editing.userId != null && (
        <PermissionsSheet
          storeId={storeId}
          member={editing as StaffMember & { userId: number }}
          onClose={() => setEditing(null)}
          onDone={() => { setEditing(null); invalidate(); }}
        />
      )}

      {removing && (
        <ConfirmSheet
          title="Remove from the shop?"
          confirmLabel="Remove them"
          danger
          onConfirm={() => {
            if (removing.userId != null) remove.mutate(removing.userId);
          }}
          onClose={() => setRemoving(null)}
          busy={remove.isPending}
        >
          <p>
            <strong>{removing.displayName || removing.email}</strong> loses access immediately.
            Anything they already did stays recorded against their name.
          </p>
        </ConfirmSheet>
      )}
    </div>
  );
}

function InviteSheet({
  storeId, onClose, onDone,
}: { storeId: number; onClose: () => void; onDone: () => void }) {
  const toast = useToast();
  const [key, resetKey] = useIdempotencyKey();
  const [email, setEmail] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [permissions, setPermissions] = useState<string[]>(['ORDERS_VIEW']);
  const [error, setError] = useState<string | null>(null);

  const invite = useMutation({
    mutationFn: () =>
      storeApi.inviteStaff(
        storeId,
        {
          email: email.trim(),
          ...(displayName.trim() ? { displayName: displayName.trim() } : {}),
          permissions,
        },
        key,
      ),
    onSuccess: () => {
      toast.success('Invitation sent.');
      onDone();
    },
    onError: (cause) => {
      resetKey();
      setError(cause instanceof ApiError ? cause.message : 'Could not send the invitation.');
    },
  });

  return (
    <Sheet
      title="Invite somebody"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={invite.isPending}>Cancel</Button>
          <Button
            variant="primary" onClick={() => invite.mutate()} busy={invite.isPending}
            disabled={!email.trim()}
          >
            Send invitation
          </Button>
        </>
      }
    >
      <div className="stack">
        {error && <Notice tone="danger">{error}</Notice>}
        <TextField
          label="Their email" type="email" value={email}
          onChange={(event) => setEmail(event.target.value)}
          inputMode="email" autoCapitalize="none" required autoFocus
        />
        <TextField
          label="Name" hint="What you call them. Optional."
          value={displayName} onChange={(event) => setDisplayName(event.target.value)}
          maxLength={150}
        />
        <PermissionChecklist value={permissions} onChange={setPermissions} />
      </div>
    </Sheet>
  );
}

function PermissionsSheet({
  storeId, member, onClose, onDone,
}: {
  storeId: number;
  /** Only ever opened for somebody who has joined, so `userId` is present. */
  member: StaffMember & { userId: number };
  onClose: () => void;
  onDone: () => void;
}) {
  const toast = useToast();
  const [permissions, setPermissions] = useState<string[]>(member.permissions);
  const [displayName, setDisplayName] = useState(member.displayName ?? '');
  const [error, setError] = useState<string | null>(null);

  const save = useMutation({
    mutationFn: () =>
      storeApi.updateStaff(storeId, member.userId, {
        permissions,
        ...(displayName.trim() ? { displayName: displayName.trim() } : {}),
      }),
    onSuccess: () => {
      toast.success('Permissions saved.');
      onDone();
    },
    onError: (cause) => {
      setError(cause instanceof ApiError ? cause.message : 'Could not save the permissions.');
    },
  });

  return (
    <Sheet
      title={member.displayName || member.email}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={save.isPending}>Cancel</Button>
          <Button variant="primary" onClick={() => save.mutate()} busy={save.isPending}>Save</Button>
        </>
      }
    >
      <div className="stack">
        {error && <Notice tone="danger">{error}</Notice>}
        <TextField
          label="Name" value={displayName}
          onChange={(event) => setDisplayName(event.target.value)} maxLength={150}
        />
        <PermissionChecklist value={permissions} onChange={setPermissions} />
        <Notice tone="info">
          Saving replaces what they may do with exactly what is ticked here.
        </Notice>
      </div>
    </Sheet>
  );
}

function PermissionChecklist({
  value, onChange,
}: { value: string[]; onChange: (next: string[]) => void }) {
  return (
    <fieldset style={{ border: 'none', margin: 0, padding: 0, minInlineSize: 0 }}>
      <legend className="field__label" style={{ padding: 0 }}>What they may do</legend>
      <div className="stack stack--tight" style={{ marginTop: 'var(--space-2)' }}>
        {PERMISSIONS.map((permission) => (
          <label key={permission.value} className="checkbox">
            <input
              type="checkbox"
              checked={value.includes(permission.value)}
              onChange={(event) =>
                onChange(
                  event.target.checked
                    ? [...value, permission.value]
                    : value.filter((v) => v !== permission.value),
                )
              }
            />
            <span className="checkbox__text">
              {permission.label}
              <span className="checkbox__hint">{permission.description}</span>
            </span>
          </label>
        ))}
      </div>
    </fieldset>
  );
}
