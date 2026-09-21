import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { promotionsApi, type CouponInput } from '@/api/endpoints/promotions';
import { ApiError } from '@/api/errors';
import { useStore } from '@/store/StoreProvider';
import { useCurrencies, useIdempotencyKey } from '@/lib/hooks';
import { formatDateTime, formatNumber } from '@/lib/format';
import { COUPON_TYPES, fromLocalInput, toLocalInput } from '@/lib/discounts';
import type { Coupon, CouponType } from '@/api/types';
import {
  Badge, Button, Card, EmptyState, Notice, PageHeader, SkeletonList,
} from '@/components/ui';
import { SelectField, TextField } from '@/components/form';
import { Sheet } from '@/components/Sheet';
import { Pagination } from '@/components/Pagination';
import { useToast } from '@/components/Toast';

export function Coupons() {
  const [activeOnly, setActiveOnly] = useState(false);
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState<Coupon | 'new' | null>(null);
  const [viewing, setViewing] = useState<Coupon | null>(null);
  const queryClient = useQueryClient();
  const { currency } = useStore();
  const { money } = useCurrencies();

  const coupons = useQuery({
    queryKey: ['coupons', { activeOnly, page }],
    queryFn: () => promotionsApi.coupons({ activeOnly, page, size: 20 }),
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['coupons'] });
  };

  return (
    <div className="page stack">
      <PageHeader
        title="Coupons"
        subtitle="A code somebody has to know. For a discount that applies itself, use promotions."
        actions={<Button variant="primary" onClick={() => setEditing('new')}>＋ New coupon</Button>}
      />

      <label className="row small muted" style={{ gap: 'var(--space-2)', cursor: 'pointer' }}>
        <input
          type="checkbox"
          checked={activeOnly}
          onChange={(event) => { setActiveOnly(event.target.checked); setPage(0); }}
        />
        Only ones a buyer could use right now
      </label>

      <Card flush>
        {coupons.isLoading ? (
          <SkeletonList rows={4} />
        ) : (coupons.data?.items.length ?? 0) === 0 ? (
          <EmptyState
            icon="◷"
            title="No coupons"
            action={<Button variant="primary" onClick={() => setEditing('new')}>Issue one</Button>}
          >
            Give a code to a customer who asks, or put one on a flyer. You choose how many times
            it can be used, and by whom.
          </EmptyState>
        ) : (
          <>
            <div className="list">
              {coupons.data!.items.map((coupon) => (
                <div key={coupon.id} className="list__item">
                  <div className="list__main">
                    <div className="list__title mono" style={{ letterSpacing: '0.06em' }}>
                      {coupon.code}
                    </div>
                    <div className="list__meta">
                      {describeCoupon(coupon, money)}
                      {coupon.minimumOrderAmount
                        ? ` · baskets over ${money(coupon.minimumOrderAmount, coupon.currency ?? currency)}`
                        : ''}
                    </div>
                    <div className="list__meta">
                      {coupon.expiresAt ? `Until ${formatDateTime(coupon.expiresAt)}` : 'No end date'}
                      {coupon.perUserLimit ? ` · ${coupon.perUserLimit} per customer` : ''}
                    </div>
                    <div className="row" style={{ gap: 'var(--space-2)', marginTop: 4 }}>
                      <Badge tone={coupon.redeemable ? 'ok' : coupon.active ? 'warn' : 'neutral'} dot>
                        {coupon.redeemable ? 'Usable now' : coupon.active ? 'Not usable yet' : 'Off'}
                      </Badge>
                      {coupon.blockedReason && <Badge tone="warn">{coupon.blockedReason}</Badge>}
                    </div>
                  </div>
                  <div className="list__side">
                    <div className="num" style={{ fontWeight: 650 }}>
                      {formatNumber(coupon.timesUsed)}
                      {coupon.usageLimit ? ` / ${coupon.usageLimit}` : ''}
                    </div>
                    <div className="small muted">used</div>
                  </div>
                  <div className="row" style={{ gap: 4, flexWrap: 'nowrap' }}>
                    <Button size="sm" variant="ghost" onClick={() => setViewing(coupon)}>Who used it</Button>
                    <Button size="sm" variant="ghost" onClick={() => setEditing(coupon)}>Edit</Button>
                  </div>
                </div>
              ))}
            </div>
            <Pagination
              page={coupons.data!.page}
              totalPages={coupons.data!.totalPages}
              totalElements={coupons.data!.totalElements}
              onChange={setPage}
              unit="coupons"
            />
          </>
        )}
      </Card>

      {editing && (
        <CouponSheet
          coupon={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(null)}
          onDone={() => { setEditing(null); invalidate(); }}
        />
      )}

      {viewing && <RedemptionsSheet coupon={viewing} onClose={() => setViewing(null)} />}
    </div>
  );
}

function describeCoupon(
  coupon: Coupon,
  money: (amount: number | null | undefined, currency: string | null | undefined) => string,
): string {
  switch (coupon.type) {
    case 'PERCENTAGE':
      return `${coupon.value ?? 0}% off`;
    case 'FIXED_AMOUNT':
      return `${money(coupon.value, coupon.currency)} off`;
    case 'FREE_SHIPPING':
      return 'Free delivery';
    default:
      return '';
  }
}

function CouponSheet({
  coupon, onClose, onDone,
}: { coupon?: Coupon; onClose: () => void; onDone: () => void }) {
  const editing = coupon != null;
  const toast = useToast();
  const { currency } = useStore();
  const { step } = useCurrencies();
  const [key, resetKey] = useIdempotencyKey();

  const [form, setForm] = useState({
    code: coupon?.code ?? '',
    description: coupon?.description ?? '',
    type: (coupon?.type ?? 'PERCENTAGE') as CouponType,
    value: coupon?.value != null ? String(coupon.value) : '',
    minimumOrderAmount: coupon?.minimumOrderAmount != null ? String(coupon.minimumOrderAmount) : '',
    maximumDiscountAmount: coupon?.maximumDiscountAmount != null ? String(coupon.maximumDiscountAmount) : '',
    usageLimit: coupon?.usageLimit != null ? String(coupon.usageLimit) : '',
    perUserLimit: coupon?.perUserLimit != null ? String(coupon.perUserLimit) : '1',
    startsAt: toLocalInput(coupon?.startsAt),
    expiresAt: toLocalInput(coupon?.expiresAt),
    active: coupon?.active ?? true,
  });
  const [error, setError] = useState<string | null>(null);

  const set = (name: keyof typeof form) => (event: { target: { value: string } }) =>
    setForm((current) => ({ ...current, [name]: event.target.value }));
  const num = (value: string) => (value.trim() === '' ? undefined : Number(value));

  const needsValue = form.type !== 'FREE_SHIPPING';
  const codeValid = /^[A-Za-z0-9_-]{3,40}$/.test(form.code.trim());

  const save = useMutation({
    mutationFn: () => {
      const shared = {
        ...(form.description.trim() ? { description: form.description.trim() } : {}),
        ...(needsValue ? { value: num(form.value) } : {}),
        ...(num(form.minimumOrderAmount) !== undefined
          ? { minimumOrderAmount: num(form.minimumOrderAmount) } : {}),
        ...(num(form.maximumDiscountAmount) !== undefined
          ? { maximumDiscountAmount: num(form.maximumDiscountAmount) } : {}),
        ...(num(form.usageLimit) !== undefined ? { usageLimit: num(form.usageLimit) } : {}),
        ...(num(form.perUserLimit) !== undefined ? { perUserLimit: num(form.perUserLimit) } : {}),
        ...(fromLocalInput(form.startsAt) ? { startsAt: fromLocalInput(form.startsAt) } : {}),
        ...(fromLocalInput(form.expiresAt) ? { expiresAt: fromLocalInput(form.expiresAt) } : {}),
      };

      // Neither the code nor the type can be changed. Buyers may already be
      // holding the code, and a coupon that quietly becomes a different offer
      // is one nobody can honour a complaint about.
      return editing
        ? promotionsApi.updateCoupon(coupon.id, { ...shared, active: form.active })
        : promotionsApi.createCoupon(
            { ...shared, code: form.code.trim().toUpperCase(), type: form.type } satisfies CouponInput,
            key,
          );
    },
    onSuccess: () => {
      toast.success(editing ? 'Coupon saved.' : 'Coupon issued.');
      onDone();
    },
    onError: (cause) => {
      if (!editing) resetKey();
      setError(cause instanceof ApiError ? cause.message : 'Could not save the coupon.');
    },
  });

  return (
    <Sheet
      title={editing ? `Edit ${coupon.code}` : 'New coupon'}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={save.isPending}>Cancel</Button>
          <Button
            variant="primary" onClick={() => save.mutate()} busy={save.isPending}
            disabled={!editing && !codeValid}
          >
            {editing ? 'Save' : 'Issue it'}
          </Button>
        </>
      }
    >
      <div className="stack">
        {error && <Notice tone="danger">{error}</Notice>}

        {editing ? (
          <Notice tone="info" title="The code cannot be changed">
            <span className="mono">{coupon.code}</span> — customers may already be holding it.
            Turn it off below and issue a new one instead.
          </Notice>
        ) : (
          <TextField
            label="The code"
            className="input input--mono"
            hint="Letters, numbers, hyphens and underscores, 3 to 40 characters. It gets read out over the phone and typed on a small keypad."
            value={form.code}
            onChange={(event) =>
              setForm((c) => ({ ...c, code: event.target.value.toUpperCase().replace(/[^A-Z0-9_-]/g, '') }))
            }
            error={form.code && !codeValid ? 'Between 3 and 40 characters.' : undefined}
            maxLength={40}
            autoCapitalize="characters"
            autoComplete="off"
            required
            autoFocus
          />
        )}

        <SelectField
          label="What it does" value={form.type} onChange={set('type')}
          disabled={editing}
          hint={
            editing
              ? 'Fixed once issued.'
              : COUPON_TYPES.find((t) => t.value === form.type)?.blurb
          }
          required
        >
          {COUPON_TYPES.map(({ value, label }) => (
            <option key={value} value={value}>{label}</option>
          ))}
        </SelectField>

        {needsValue && (
          <TextField
            label={form.type === 'PERCENTAGE' ? 'Percentage off' : `Amount off (${currency})`}
            type="number" inputMode="decimal" min="0.01"
            {...(form.type === 'PERCENTAGE' ? { max: '100', step: '0.01' } : { step: step(currency) })}
            value={form.value} onChange={set('value')} required
          />
        )}

        <div className="grid grid--2">
          <TextField
            label={`Only above (${currency})`} hint="Basket total needed. Optional."
            type="number" inputMode="decimal" min="0" step={step(currency)}
            value={form.minimumOrderAmount} onChange={set('minimumOrderAmount')}
          />
          <TextField
            label={`Never more than (${currency})`} hint="Caps one basket's discount."
            type="number" inputMode="decimal" min="0.01" step={step(currency)}
            value={form.maximumDiscountAmount} onChange={set('maximumDiscountAmount')}
          />
        </div>

        <div className="grid grid--2">
          <TextField
            label="Times it can be used" hint="Across everybody. Blank is unlimited."
            type="number" inputMode="numeric" min="1"
            value={form.usageLimit} onChange={set('usageLimit')}
          />
          <TextField
            label="Times per customer" hint="Blank is unlimited."
            type="number" inputMode="numeric" min="1"
            value={form.perUserLimit} onChange={set('perUserLimit')}
          />
        </div>

        <div className="grid grid--2">
          <TextField
            label="Works from" type="datetime-local"
            value={form.startsAt} onChange={set('startsAt')}
            hint="Blank works immediately."
          />
          <TextField
            label="Expires" type="datetime-local"
            value={form.expiresAt} onChange={set('expiresAt')}
            hint="Blank never expires."
          />
        </div>

        <TextField
          label="Note" hint="Optional, for your own records."
          value={form.description} onChange={set('description')} maxLength={200}
        />

        {editing && (
          <label className="checkbox">
            <input
              type="checkbox"
              checked={form.active}
              onChange={(event) => setForm((c) => ({ ...c, active: event.target.checked }))}
            />
            <span className="checkbox__text">
              Buyers can use this code
              <span className="checkbox__hint">
                Untick to stop it working. Orders already placed with it are not affected.
              </span>
            </span>
          </label>
        )}
      </div>
    </Sheet>
  );
}

function RedemptionsSheet({ coupon, onClose }: { coupon: Coupon; onClose: () => void }) {
  const [page, setPage] = useState(0);

  const redemptions = useQuery({
    queryKey: ['coupons', 'redemptions', coupon.id, page],
    queryFn: () => promotionsApi.redemptions(coupon.id, { page, size: 20 }),
  });

  return (
    <Sheet
      title={`Who used ${coupon.code}`}
      onClose={onClose}
      footer={<Button variant="secondary" onClick={onClose}>Close</Button>}
    >
      {redemptions.isLoading ? (
        <SkeletonList rows={4} />
      ) : (redemptions.data?.redemptions.length ?? 0) === 0 ? (
        <EmptyState icon="◷" title="Nobody yet">
          It has not been used since you issued it.
        </EmptyState>
      ) : (
        <div className="stack stack--tight">
          <p className="muted small">
            Used {formatNumber(redemptions.data!.timesUsed)} time
            {redemptions.data!.timesUsed === 1 ? '' : 's'}
            {redemptions.data!.usageLimit ? ` of ${redemptions.data!.usageLimit}` : ''}.
          </p>
          <div className="list">
            {redemptions.data!.redemptions.map((row) => (
              <div key={row.id} className="list__item">
                <div className="list__main">
                  <div className="list__title">{row.customer ?? 'A customer'}</div>
                  <div className="list__meta">{formatDateTime(row.usedAt)}</div>
                </div>
              </div>
            ))}
          </div>
          <Pagination
            page={redemptions.data!.page}
            totalPages={redemptions.data!.totalPages}
            totalElements={redemptions.data!.totalElements}
            onChange={setPage}
            unit="uses"
          />
        </div>
      )}
    </Sheet>
  );
}
