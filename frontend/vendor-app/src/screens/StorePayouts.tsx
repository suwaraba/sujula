import { useState, type FormEvent } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { storeApi } from '@/api/endpoints/store';
import { ApiError } from '@/api/errors';
import { useStore } from '@/store/StoreProvider';
import { useMe } from '@/auth/AuthProvider';
import { useIdempotencyKey } from '@/lib/hooks';
import { formatDateTime } from '@/lib/format';
import { Badge, Button, Card, KeyValue, Notice, PageHeader, Skeleton } from '@/components/ui';
import { SelectField, TextField, fieldError } from '@/components/form';
import { useToast } from '@/components/Toast';

/**
 * Where the money goes.
 *
 * The single most valuable thing an attacker inside a seller's account can
 * change, which is why the server asks for the password again — and for an
 * authenticator code when the account has one. A bearer token only proves
 * somebody held a credential an hour ago; this asks whether they hold it now.
 *
 * Nothing already saved is ever shown back in full. The server returns last
 * four digits, and this screen displays exactly that.
 */
export function StorePayouts() {
  const { store, storeId, isLoading, currency } = useStore();
  const me = useMe();
  const queryClient = useQueryClient();
  const toast = useToast();
  const [key, resetKey] = useIdempotencyKey();

  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState({
    accountType: 'MOBILE_MONEY',
    accountHolderName: '',
    bankName: '',
    accountNumber: '',
    routingNumber: '',
    iban: '',
    swiftCode: '',
    mobileMoneyPhone: '',
    mobileMoneyProvider: '',
    password: '',
    totpCode: '',
  });
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});

  const save = useMutation({
    mutationFn: () =>
      storeApi.putBankAccount(
        storeId,
        {
          accountType: form.accountType,
          accountHolderName: form.accountHolderName.trim(),
          ...(form.bankName.trim() ? { bankName: form.bankName.trim() } : {}),
          ...(form.accountNumber.trim() ? { accountNumber: form.accountNumber.trim() } : {}),
          ...(form.routingNumber.trim() ? { routingNumber: form.routingNumber.trim() } : {}),
          ...(form.iban.trim() ? { iban: form.iban.trim() } : {}),
          ...(form.swiftCode.trim() ? { swiftCode: form.swiftCode.trim() } : {}),
          ...(form.mobileMoneyPhone.trim() ? { mobileMoneyPhone: form.mobileMoneyPhone.trim() } : {}),
          ...(form.mobileMoneyProvider.trim() ? { mobileMoneyProvider: form.mobileMoneyProvider.trim() } : {}),
          password: form.password,
          ...(form.totpCode.trim() ? { totpCode: form.totpCode.trim() } : {}),
        },
        key,
      ),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['store'] });
      toast.success('Payout account saved.');
      setEditing(false);
      // The password and code are dropped the moment they are no longer needed.
      setForm((current) => ({ ...current, password: '', totpCode: '' }));
    },
    onError: (cause) => {
      resetKey();
      setForm((current) => ({ ...current, password: '', totpCode: '' }));
      if (cause instanceof ApiError) {
        setError(cause.message);
        setFields(cause.fieldErrors);
      } else {
        setError('Could not save the payout account.');
      }
    },
  });

  if (isLoading || !store) {
    return <div className="page stack"><Skeleton height={32} width={200} /><Skeleton height={260} /></div>;
  }

  const destination = store.payoutDestination;
  const mobileMoney = form.accountType === 'MOBILE_MONEY';

  const set = (name: keyof typeof form) => (event: { target: { value: string } }) =>
    setForm((current) => ({ ...current, [name]: event.target.value }));

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setFields({});
    save.mutate();
  }

  return (
    <div className="page stack">
      <PageHeader
        title="Payout account"
        subtitle={`Where your ${currency} is sent.`}
      />

      {destination ? (
        <Card
          title="Current account"
          actions={
            !editing && (
              <Button variant="secondary" size="sm" onClick={() => setEditing(true)}>Change</Button>
            )
          }
        >
          <div className="stack stack--tight">
            <KeyValue
              rows={[
                ['Type', destination.accountType === 'MOBILE_MONEY' ? 'Mobile money' : 'Bank account'],
                ['In the name of', destination.accountHolderName],
                ...(destination.accountType === 'MOBILE_MONEY'
                  ? [
                      ['Provider', destination.mobileMoneyProvider ?? '—'] as [string, string],
                      ['Number', `•••• ${destination.mobileMoneyLast4 ?? '••••'}`] as [string, string],
                    ]
                  : [
                      ['Bank', destination.bankName ?? '—'] as [string, string],
                      [
                        'Account',
                        `•••• ${destination.accountNumberLast4 ?? destination.ibanLast4 ?? '••••'}`,
                      ] as [string, string],
                    ]),
                ['Currency', destination.currency ?? currency],
                [
                  'Status',
                  <Badge key="v" tone={destination.verified ? 'ok' : 'warn'} dot>
                    {destination.verified ? 'Verified' : 'Not verified yet'}
                  </Badge>,
                ],
                ['Last changed', formatDateTime(destination.lastChangedAt)],
              ]}
            />
          </div>
        </Card>
      ) : (
        <Notice tone="warn" title="No payout account yet">
          We have nowhere to send your money. Add an account below — you can do this before you
          have earned anything.
        </Notice>
      )}

      {(editing || !destination) && (
        <form className="stack" onSubmit={onSubmit} noValidate>
          {error && <Notice tone="danger">{error}</Notice>}

          <Card title={destination ? 'New payout account' : 'Add a payout account'}>
            <div className="stack">
              <SelectField
                label="How you want to be paid"
                value={form.accountType}
                onChange={set('accountType')}
                required
              >
                <option value="MOBILE_MONEY">Mobile money</option>
                <option value="BANK">Bank account</option>
              </SelectField>

              <TextField
                label="Name on the account"
                hint="Must match the name on your verification documents, or the transfer is rejected."
                value={form.accountHolderName} onChange={set('accountHolderName')}
                error={fieldError(fields, 'accountHolderName')} maxLength={150} required
              />

              {mobileMoney ? (
                <div className="grid grid--2">
                  <TextField
                    label="Provider" placeholder="Wave, Orange Money, Africell…"
                    value={form.mobileMoneyProvider} onChange={set('mobileMoneyProvider')}
                    error={fieldError(fields, 'mobileMoneyProvider')} maxLength={60}
                  />
                  <TextField
                    label="Mobile money number" type="tel" inputMode="tel"
                    value={form.mobileMoneyPhone} onChange={set('mobileMoneyPhone')}
                    error={fieldError(fields, 'mobileMoneyPhone')} maxLength={25}
                  />
                </div>
              ) : (
                <>
                  <TextField
                    label="Bank name" value={form.bankName} onChange={set('bankName')}
                    error={fieldError(fields, 'bankName')} maxLength={150}
                  />
                  <div className="grid grid--2">
                    <TextField
                      label="Account number" value={form.accountNumber} onChange={set('accountNumber')}
                      error={fieldError(fields, 'accountNumber')} maxLength={40}
                      autoComplete="off"
                    />
                    <TextField
                      label="Sort or routing code" value={form.routingNumber} onChange={set('routingNumber')}
                      error={fieldError(fields, 'routingNumber')} maxLength={40}
                      autoComplete="off"
                    />
                  </div>
                  <div className="grid grid--2">
                    <TextField
                      label="IBAN" hint="For an international transfer."
                      value={form.iban} onChange={set('iban')}
                      error={fieldError(fields, 'iban')} maxLength={40} autoComplete="off"
                    />
                    <TextField
                      label="SWIFT / BIC" value={form.swiftCode} onChange={set('swiftCode')}
                      error={fieldError(fields, 'swiftCode')} maxLength={20} autoComplete="off"
                    />
                  </div>
                </>
              )}
            </div>
          </Card>

          <Card title="Confirm it is you">
            <div className="stack">
              <Notice tone="info">
                This is where your money goes, so we ask again even though you are signed in.
              </Notice>

              <TextField
                label="Your password" type="password"
                value={form.password} onChange={set('password')}
                autoComplete="current-password"
                error={fieldError(fields, 'password')} required
              />

              {me.profile.mfaEnabled && (
                <TextField
                  label="Authenticator code"
                  className="input input--mono"
                  value={form.totpCode}
                  onChange={(event) =>
                    setForm((c) => ({ ...c, totpCode: event.target.value.replace(/\D/g, '').slice(0, 6) }))
                  }
                  inputMode="numeric" maxLength={6} autoComplete="one-time-code"
                  error={fieldError(fields, 'totpCode')} required
                />
              )}
            </div>
          </Card>

          <div className="row">
            <Button type="submit" variant="primary" busy={save.isPending}>
              Save payout account
            </Button>
            {destination && (
              <Button
                type="button" variant="ghost"
                onClick={() => {
                  setEditing(false);
                  setError(null);
                  setForm((c) => ({ ...c, password: '', totpCode: '' }));
                }}
              >
                Cancel
              </Button>
            )}
          </div>
        </form>
      )}
    </div>
  );
}
