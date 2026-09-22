import { useEffect, useState, type FormEvent } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { storeApi } from '@/api/endpoints/store';
import { ApiError } from '@/api/errors';
import { useStore } from '@/store/StoreProvider';
import { humanise } from '@/lib/format';
import type { Store } from '@/api/types';
import { Badge, Button, Card, KeyValue, Notice, PageHeader, Skeleton } from '@/components/ui';
import { CheckField, TextArea, TextField, fieldError } from '@/components/form';
import { useToast } from '@/components/Toast';

export function StoreSettings() {
  const { store, isLoading, storeId } = useStore();
  const queryClient = useQueryClient();
  const toast = useToast();

  const [form, setForm] = useState(() => (store ? fromStore(store) : blank()));
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});

  useEffect(() => {
    if (store) setForm(fromStore(store));
  }, [store]);

  const save = useMutation({
    mutationFn: () =>
      storeApi.update(storeId, {
        storeName: form.storeName.trim(),
        description: form.description.trim(),
        storeEmail: form.storeEmail.trim(),
        storePhone: form.storePhone.trim(),
        website: form.website.trim(),
        returnPolicy: form.returnPolicy.trim(),
        shippingPolicy: form.shippingPolicy.trim(),
        storePolicy: form.storePolicy.trim(),
        ...(form.handlingDays.trim() ? { handlingDays: Number(form.handlingDays) } : {}),
        vacationMode: form.vacationMode,
        vacationMessage: form.vacationMessage.trim(),
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['store'] });
      toast.success('Shop details saved.');
    },
    onError: (cause) => {
      if (cause instanceof ApiError) {
        setError(cause.message);
        setFields(cause.fieldErrors);
      } else {
        setError('Could not save.');
      }
    },
  });

  if (isLoading || !store) {
    return <div className="page stack"><Skeleton height={32} width={200} /><Skeleton height={300} /></div>;
  }

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
        title="Shop details"
        subtitle={<span className="mono small">/{store.storeSlug}</span>}
        actions={
          <Badge tone={store.canTrade ? 'ok' : 'warn'} dot>
            {humanise(store.status)}
          </Badge>
        }
      />

      <form className="stack" onSubmit={onSubmit} noValidate>
        {error && <Notice tone="danger">{error}</Notice>}

        <Card title="How buyers see you">
          <div className="stack">
            <TextField
              label="Shop name" value={form.storeName} onChange={set('storeName')}
              error={fieldError(fields, 'storeName')} maxLength={100}
            />
            <TextArea
              label="About the shop" value={form.description} onChange={set('description')}
              error={fieldError(fields, 'description')} maxLength={2000} rows={4}
            />
            <div className="grid grid--2">
              <TextField
                label="Shop email" type="email" value={form.storeEmail} onChange={set('storeEmail')}
                inputMode="email" autoCapitalize="none" error={fieldError(fields, 'storeEmail')}
              />
              <TextField
                label="Shop phone" type="tel" value={form.storePhone} onChange={set('storePhone')}
                inputMode="tel" error={fieldError(fields, 'storePhone')}
              />
            </div>
            <TextField
              label="Website" type="url" value={form.website} onChange={set('website')}
              inputMode="url" autoCapitalize="none" error={fieldError(fields, 'website')}
            />
          </div>
        </Card>

        <Card title="How you work">
          <div className="stack">
            <TextField
              label="Days to pack an order"
              type="number" inputMode="numeric" min="0" max="60"
              value={form.handlingDays} onChange={set('handlingDays')}
              hint="Buyers see this as a delivery estimate, so be honest rather than optimistic."
              error={fieldError(fields, 'handlingDays')}
            />
            <TextArea
              label="Returns policy" value={form.returnPolicy} onChange={set('returnPolicy')}
              error={fieldError(fields, 'returnPolicy')} maxLength={5000} rows={4}
            />
            <TextArea
              label="Delivery policy" value={form.shippingPolicy} onChange={set('shippingPolicy')}
              error={fieldError(fields, 'shippingPolicy')} maxLength={5000} rows={4}
            />
            <TextArea
              label="Anything else buyers should know" value={form.storePolicy} onChange={set('storePolicy')}
              error={fieldError(fields, 'storePolicy')} maxLength={5000} rows={3}
            />
          </div>
        </Card>

        <Card title="Holiday mode">
          <div className="stack">
            <CheckField
              label="Pause my shop"
              hint="Your listings stay visible but nobody can order. Orders you already have still need packing."
              checked={form.vacationMode}
              onChange={(event) => setForm((c) => ({ ...c, vacationMode: event.target.checked }))}
            />
            {form.vacationMode && (
              <TextField
                label="What buyers are told"
                value={form.vacationMessage} onChange={set('vacationMessage')}
                placeholder="Back on the 14th — thank you for waiting."
                error={fieldError(fields, 'vacationMessage')} maxLength={300}
              />
            )}
          </div>
        </Card>

        <Card title="Registration">
          <KeyValue
            rows={[
              ['You are paid in', <strong key="c">{store.settlementCurrency}</strong>],
              ['Business registration', store.businessRegistrationNumber ?? '—'],
              ['Tax number', store.taxNumber ?? '—'],
              ['Verification', humanise(store.kycStatus)],
            ]}
          />
          <p className="small muted" style={{ marginTop: 'var(--space-3)' }}>
            Your currency was fixed when the shop opened — every listing is priced in it and every
            payout made in it. Changing it would re-denominate your whole catalogue, so support
            has to do it.
          </p>
        </Card>

        <div className="row">
          <Button type="submit" variant="primary" busy={save.isPending}>Save changes</Button>
          <Button type="button" variant="ghost" onClick={() => setForm(fromStore(store))}>
            Undo changes
          </Button>
        </div>
      </form>
    </div>
  );
}

function blank() {
  return {
    storeName: '', description: '', storeEmail: '', storePhone: '', website: '',
    returnPolicy: '', shippingPolicy: '', storePolicy: '', handlingDays: '',
    vacationMode: false, vacationMessage: '',
  };
}

function fromStore(store: Store) {
  return {
    storeName: store.storeName ?? '',
    description: store.description ?? '',
    storeEmail: store.storeEmail ?? '',
    storePhone: store.storePhone ?? '',
    website: store.website ?? '',
    returnPolicy: store.returnPolicy ?? '',
    shippingPolicy: store.shippingPolicy ?? '',
    storePolicy: store.storePolicy ?? '',
    handlingDays: store.handlingDays != null ? String(store.handlingDays) : '',
    vacationMode: store.vacationMode,
    vacationMessage: store.vacationMessage ?? '',
  };
}
