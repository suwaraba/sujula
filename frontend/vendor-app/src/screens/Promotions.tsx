import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { promotionsApi, type PromotionInput } from '@/api/endpoints/promotions';
import { ApiError } from '@/api/errors';
import { useStore } from '@/store/StoreProvider';
import { useCurrencies, useIdempotencyKey } from '@/lib/hooks';
import { formatDateTime, formatNumber } from '@/lib/format';
import {
  PROMOTION_STATUS_LABEL, PROMOTION_STATUS_TONE, PROMOTION_TYPES,
  fromLocalInput, promotionFields, toLocalInput,
} from '@/lib/discounts';
import type { Promotion, PromotionStatus, PromotionType } from '@/api/types';
import {
  Badge, Button, Card, EmptyState, Notice, PageHeader, SkeletonList,
} from '@/components/ui';
import { SelectField, TextArea, TextField } from '@/components/form';
import { ConfirmSheet, Sheet } from '@/components/Sheet';
import { ScopePicker, scopeFrom, scopeToIds, type Scope } from '@/components/ScopePicker';
import { Pagination } from '@/components/Pagination';
import { useToast } from '@/components/Toast';

const TABS: { label: string; status?: PromotionStatus }[] = [
  { label: 'All', status: undefined },
  { label: 'On', status: 'ACTIVE' },
  { label: 'Drafts', status: 'DRAFT' },
  { label: 'Paused', status: 'PAUSED' },
  { label: 'Finished', status: 'EXPIRED' },
];

export function Promotions() {
  const [tab, setTab] = useState(0);
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState<Promotion | 'new' | null>(null);
  const [removing, setRemoving] = useState<Promotion | null>(null);
  const queryClient = useQueryClient();
  const toast = useToast();
  const { currency } = useStore();
  const { money } = useCurrencies();

  const status = TABS[tab]?.status;

  const promotions = useQuery({
    queryKey: ['promotions', { status: status ?? null, page }],
    queryFn: () => promotionsApi.list({ ...(status ? { status } : {}), page, size: 20 }),
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['promotions'] });
  };

  const remove = useMutation({
    mutationFn: (promotionId: number) => promotionsApi.remove(promotionId),
    onSuccess: () => {
      setRemoving(null);
      toast.success('Promotion removed.');
      invalidate();
    },
    onError: (error) => {
      setRemoving(null);
      toast.error(error instanceof ApiError ? error.message : 'Could not remove it.');
    },
  });

  return (
    <div className="page stack">
      <PageHeader
        title="Promotions"
        subtitle="A discount that applies itself — a buyer gets it without typing anything."
        actions={
          <Button variant="primary" onClick={() => setEditing('new')}>＋ New promotion</Button>
        }
      />

      <div className="tabs" role="tablist">
        {TABS.map((entry, index) => (
          <button
            key={entry.label}
            type="button"
            role="tab"
            aria-selected={index === tab}
            className={index === tab ? 'tabs__item is-active' : 'tabs__item'}
            onClick={() => { setTab(index); setPage(0); }}
          >
            {entry.label}
          </button>
        ))}
      </div>

      <Card flush>
        {promotions.isLoading ? (
          <SkeletonList rows={4} />
        ) : (promotions.data?.items.length ?? 0) === 0 ? (
          <EmptyState
            icon="◇"
            title="No promotions here"
            action={<Button variant="primary" onClick={() => setEditing('new')}>Draft one</Button>}
          >
            A promotion discounts a basket on its own. For a code somebody has to type, use{' '}
            coupons instead.
          </EmptyState>
        ) : (
          <>
            <div className="list">
              {promotions.data!.items.map((promotion) => (
                <div key={promotion.id} className="list__item">
                  <div className="list__main">
                    <div className="list__title" style={{ whiteSpace: 'normal' }}>
                      {promotion.name}
                    </div>
                    <div className="list__meta">
                      {describe(promotion, (a, c) => money(a, c))}
                      {promotion.storeWide
                        ? ' · your whole shop'
                        : ` · ${(promotion.productIds?.length ?? 0) + (promotion.categoryIds?.length ?? 0)} selected`}
                    </div>
                    <div className="list__meta">
                      {promotion.startsAt ? `From ${formatDateTime(promotion.startsAt)}` : 'No start date'}
                      {promotion.endsAt ? ` to ${formatDateTime(promotion.endsAt)}` : ''}
                      {promotion.timesApplied > 0 && ` · used ${formatNumber(promotion.timesApplied)} times`}
                    </div>
                    <div className="row" style={{ gap: 'var(--space-2)', marginTop: 4 }}>
                      <Badge tone={PROMOTION_STATUS_TONE[promotion.status] ?? 'neutral'} dot>
                        {PROMOTION_STATUS_LABEL[promotion.status] ?? promotion.status}
                      </Badge>
                      {promotion.status === 'ACTIVE' && !promotion.running && (
                        <Badge tone="warn">On, but not running yet</Badge>
                      )}
                    </div>
                    {/*
                      Not a fault. The server explains why a promotion is not
                      currently discounting anything — a draft that was never
                      turned on, a window that has not opened — and that is
                      information, not an error.
                    */}
                    {promotion.blockedReason && !promotion.running && (
                      <div className="small muted" style={{ marginTop: 4 }}>
                        {promotion.blockedReason}
                      </div>
                    )}
                  </div>
                  <div className="row" style={{ gap: 4, flexWrap: 'nowrap' }}>
                    <PromotionActions promotion={promotion} onDone={invalidate} />
                    <Button size="sm" variant="ghost" onClick={() => setEditing(promotion)}>Edit</Button>
                    <Button size="sm" variant="ghost" onClick={() => setRemoving(promotion)}>✕</Button>
                  </div>
                </div>
              ))}
            </div>
            <Pagination
              page={promotions.data!.page}
              totalPages={promotions.data!.totalPages}
              totalElements={promotions.data!.totalElements}
              onChange={setPage}
              unit="promotions"
            />
          </>
        )}
      </Card>

      <Notice tone="info">
        Every figure here is in <strong>{currency}</strong>, your shop's currency. A buyer paying
        in another one sees the discount converted at the rate on the day.
      </Notice>

      {editing && (
        <PromotionSheet
          promotion={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(null)}
          onDone={() => { setEditing(null); invalidate(); }}
        />
      )}

      {removing && (
        <ConfirmSheet
          title="Remove this promotion?"
          confirmLabel="Remove it"
          danger
          onConfirm={() => remove.mutate(removing.id)}
          onClose={() => setRemoving(null)}
          busy={remove.isPending}
        >
          <p>
            <strong>{removing.name}</strong> stops applying to new baskets. Orders already placed
            with it are not affected.
          </p>
        </ConfirmSheet>
      )}
    </div>
  );
}

/** One line saying what this promotion actually takes off. */
function describe(
  promotion: Promotion,
  money: (amount: number | null, currency: string | null) => string,
): string {
  switch (promotion.type) {
    case 'PERCENT':
      return `${promotion.percentOff ?? 0}% off`;
    case 'FIXED':
      return `${money(promotion.amountOff, promotion.currency)} off`;
    case 'BUY_X_GET_Y':
      return promotion.getDiscountPercent
        ? `Buy ${promotion.buyQuantity}, get ${promotion.getQuantity} at ${promotion.getDiscountPercent}% off`
        : `Buy ${promotion.buyQuantity}, get ${promotion.getQuantity} free`;
    case 'FREE_SHIPPING':
      return 'Free delivery';
    case 'BUNDLE':
      return `Bundle at ${money(promotion.bundlePrice, promotion.currency)}`;
    default:
      return '';
  }
}

function PromotionActions({
  promotion, onDone,
}: { promotion: Promotion; onDone: () => void }) {
  const toast = useToast();
  const [key, resetKey] = useIdempotencyKey();
  const [refusal, setRefusal] = useState<string | null>(null);

  const activate = useMutation({
    mutationFn: () => promotionsApi.activate(promotion.id, key),
    onSuccess: (result) => {
      onDone();
      toast.success(result.message || 'Promotion is on.');
    },
    onError: (error) => {
      resetKey();
      // An overlap is refused, not reported, and the server's message names
      // the promotion already discounting those goods. That is too useful to
      // put in a toast that disappears while the seller is reading it.
      if (error instanceof ApiError && error.status === 400) setRefusal(error.message);
      else toast.error(error instanceof ApiError ? error.message : 'Could not turn it on.');
    },
  });

  const pause = useMutation({
    mutationFn: (paused: boolean) => promotionsApi.update(promotion.id, { paused }),
    onSuccess: (_, paused) => {
      toast.success(paused ? 'Paused.' : 'Running again.');
      onDone();
    },
    onError: (error) => {
      toast.error(error instanceof ApiError ? error.message : 'Could not change it.');
    },
  });

  return (
    <>
      {promotion.status === 'DRAFT' && (
        <Button size="sm" variant="primary" onClick={() => activate.mutate()} busy={activate.isPending}>
          Turn on
        </Button>
      )}
      {promotion.status === 'ACTIVE' && (
        <Button size="sm" variant="secondary" onClick={() => pause.mutate(true)} busy={pause.isPending}>
          Pause
        </Button>
      )}
      {promotion.status === 'PAUSED' && (
        <Button size="sm" variant="primary" onClick={() => pause.mutate(false)} busy={pause.isPending}>
          Resume
        </Button>
      )}

      {refusal && (
        <Sheet
          title="It cannot run alongside that"
          onClose={() => setRefusal(null)}
          footer={<Button variant="primary" onClick={() => setRefusal(null)}>Understood</Button>}
        >
          <div className="stack">
            <Notice tone="warn">{refusal}</Notice>
            <p className="muted small">
              Pause or narrow the other promotion, or change what this one applies to, then try
              again.
            </p>
          </div>
        </Sheet>
      )}
    </>
  );
}

function PromotionSheet({
  promotion, onClose, onDone,
}: { promotion?: Promotion; onClose: () => void; onDone: () => void }) {
  const editing = promotion != null;
  const toast = useToast();
  const { currency } = useStore();
  const { step } = useCurrencies();
  const [key, resetKey] = useIdempotencyKey();

  const [form, setForm] = useState({
    name: promotion?.name ?? '',
    description: promotion?.description ?? '',
    type: (promotion?.type ?? 'PERCENT') as PromotionType,
    percentOff: promotion?.percentOff != null ? String(promotion.percentOff) : '',
    amountOff: promotion?.amountOff != null ? String(promotion.amountOff) : '',
    buyQuantity: promotion?.buyQuantity != null ? String(promotion.buyQuantity) : '2',
    getQuantity: promotion?.getQuantity != null ? String(promotion.getQuantity) : '1',
    getDiscountPercent: promotion?.getDiscountPercent != null ? String(promotion.getDiscountPercent) : '',
    bundlePrice: promotion?.bundlePrice != null ? String(promotion.bundlePrice) : '',
    minimumBasket: promotion?.minimumBasket != null ? String(promotion.minimumBasket) : '',
    maximumDiscount: promotion?.maximumDiscount != null ? String(promotion.maximumDiscount) : '',
    startsAt: toLocalInput(promotion?.startsAt),
    endsAt: toLocalInput(promotion?.endsAt),
  });
  const [scope, setScope] = useState<Scope>(() =>
    scopeFrom(promotion?.productIds, promotion?.categoryIds),
  );
  const [error, setError] = useState<string | null>(null);

  const fields = promotionFields(form.type);
  const set = (name: keyof typeof form) => (event: { target: { value: string } }) =>
    setForm((current) => ({ ...current, [name]: event.target.value }));

  const num = (value: string) => (value.trim() === '' ? undefined : Number(value));

  const save = useMutation({
    mutationFn: () => {
      // The shared half. `type` is deliberately not in here: it is required on
      // the way in and absent from the update DTO, because changing what a
      // running promotion does is a different promotion.
      const shared = {
        name: form.name.trim(),
        ...(form.description.trim() ? { description: form.description.trim() } : {}),
        ...(fields.percentOff ? { percentOff: num(form.percentOff) } : {}),
        ...(fields.amountOff ? { amountOff: num(form.amountOff) } : {}),
        ...(fields.quantities
          ? { buyQuantity: num(form.buyQuantity), getQuantity: num(form.getQuantity) }
          : {}),
        ...(fields.getDiscountPercent ? { getDiscountPercent: num(form.getDiscountPercent) } : {}),
        ...(fields.bundlePrice ? { bundlePrice: num(form.bundlePrice) } : {}),
        ...(num(form.minimumBasket) !== undefined ? { minimumBasket: num(form.minimumBasket) } : {}),
        ...(num(form.maximumDiscount) !== undefined ? { maximumDiscount: num(form.maximumDiscount) } : {}),
        ...(fromLocalInput(form.startsAt) ? { startsAt: fromLocalInput(form.startsAt) } : {}),
        ...(fromLocalInput(form.endsAt) ? { endsAt: fromLocalInput(form.endsAt) } : {}),
        ...scopeToIds(scope),
      };

      return editing
        ? promotionsApi.update(promotion.id, shared)
        : promotionsApi.create({ ...shared, type: form.type } satisfies PromotionInput, key);
    },
    onSuccess: () => {
      toast.success(editing ? 'Promotion saved.' : 'Saved as a draft. Turn it on when ready.');
      onDone();
    },
    onError: (cause) => {
      if (!editing) resetKey();
      setError(cause instanceof ApiError ? cause.message : 'Could not save the promotion.');
    },
  });

  return (
    <Sheet
      title={editing ? 'Edit promotion' : 'New promotion'}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={save.isPending}>Cancel</Button>
          <Button
            variant="primary" onClick={() => save.mutate()} busy={save.isPending}
            disabled={!form.name.trim()}
          >
            {editing ? 'Save' : 'Save as draft'}
          </Button>
        </>
      }
    >
      <div className="stack">
        {error && <Notice tone="danger">{error}</Notice>}

        <TextField
          label="What you call it"
          hint="For your own records — buyers see the discount, not this."
          value={form.name} onChange={set('name')} maxLength={150} required autoFocus
        />

        <SelectField
          label="What it does"
          value={form.type}
          onChange={set('type')}
          disabled={editing}
          hint={
            editing
              ? 'Fixed once created. Draft a new promotion to do something different.'
              : PROMOTION_TYPES.find((t) => t.value === form.type)?.blurb
          }
          required
        >
          {PROMOTION_TYPES.map(({ value, label }) => (
            <option key={value} value={value}>{label}</option>
          ))}
        </SelectField>

        {fields.percentOff && (
          <TextField
            label="Percentage off" type="number" inputMode="decimal" min="0.01" max="100" step="0.01"
            value={form.percentOff} onChange={set('percentOff')} required
          />
        )}

        {fields.amountOff && (
          <TextField
            label={`Amount off (${currency})`} type="number" inputMode="decimal"
            min="0.01" step={step(currency)}
            value={form.amountOff} onChange={set('amountOff')} required
          />
        )}

        {fields.quantities && (
          <div className="grid grid--2">
            <TextField
              label="They buy" type="number" inputMode="numeric" min="1"
              value={form.buyQuantity} onChange={set('buyQuantity')} required
            />
            <TextField
              label="They get" type="number" inputMode="numeric" min="1"
              value={form.getQuantity} onChange={set('getQuantity')} required
            />
          </div>
        )}

        {fields.getDiscountPercent && (
          <TextField
            label="Discount on the free ones (%)"
            hint="Leave blank to give them away outright."
            type="number" inputMode="decimal" min="0.01" max="100" step="0.01"
            value={form.getDiscountPercent} onChange={set('getDiscountPercent')}
          />
        )}

        {fields.bundlePrice && (
          <TextField
            label={`Price for the bundle (${currency})`} type="number" inputMode="decimal"
            min="0.01" step={step(currency)}
            value={form.bundlePrice} onChange={set('bundlePrice')} required
          />
        )}

        <div className="grid grid--2">
          <TextField
            label={`Only above (${currency})`} hint="Basket total needed to qualify. Optional."
            type="number" inputMode="decimal" min="0" step={step(currency)}
            value={form.minimumBasket} onChange={set('minimumBasket')}
          />
          <TextField
            label={`Never more than (${currency})`} hint="Caps what one basket can take off."
            type="number" inputMode="decimal" min="0.01" step={step(currency)}
            value={form.maximumDiscount} onChange={set('maximumDiscount')}
          />
        </div>

        <div className="grid grid--2">
          <TextField
            label="Starts" type="datetime-local"
            value={form.startsAt} onChange={set('startsAt')}
            hint="Blank starts it the moment you turn it on."
          />
          <TextField
            label="Ends" type="datetime-local"
            value={form.endsAt} onChange={set('endsAt')}
            hint="Blank runs it until you stop it."
          />
        </div>

        <ScopePicker value={scope} onChange={setScope} />

        <TextArea
          label="Note" hint="Optional, for your own records."
          value={form.description} onChange={set('description')} maxLength={300} rows={2}
        />
      </div>
    </Sheet>
  );
}
