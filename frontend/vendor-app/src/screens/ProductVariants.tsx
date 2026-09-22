import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { catalogueApi } from '@/api/endpoints/catalogue';
import { ApiError } from '@/api/errors';
import { useCurrencies, useIdempotencyKey } from '@/lib/hooks';
import type { ProductDetail, ProductVariant } from '@/api/types';
import { Badge, Button, Card, EmptyState, Notice } from '@/components/ui';
import { SelectField, TextField } from '@/components/form';
import { ConfirmSheet, Sheet } from '@/components/Sheet';
import { useToast } from '@/components/Toast';

/**
 * Variants: one row per combination a buyer can actually pick.
 *
 * A variant names exactly one value per option the listing defines — a variant
 * that names two colours, or no colour, is not a variant of anything, and the
 * server refuses it. The form below therefore offers one select per option and
 * requires all of them.
 */
export function ProductVariantsPanel({ product }: { product: ProductDetail }) {
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<ProductVariant | null>(null);
  const [removing, setRemoving] = useState<ProductVariant | null>(null);
  const { money } = useCurrencies();
  const queryClient = useQueryClient();
  const toast = useToast();

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['products'] });
  };

  const remove = useMutation({
    mutationFn: (variantId: number) => catalogueApi.deleteVariant(product.id, variantId),
    onSuccess: () => {
      setRemoving(null);
      toast.success('Variant removed.');
      invalidate();
    },
    onError: (error) => {
      setRemoving(null);
      toast.error(error instanceof ApiError ? error.message : 'Could not remove the variant.');
    },
  });

  if (product.options.length === 0) {
    return (
      <Card>
        <EmptyState icon="⊞" title="This listing has no options">
          Variants exist when a listing comes in sizes, colours or capacities. The options
          themselves are set up by the platform for the category you chose — pick a category that
          has them, or sell this as a single item.
        </EmptyState>
      </Card>
    );
  }

  return (
    <div className="stack">
      <Card
        title="Variants"
        actions={
          <Button variant="primary" size="sm" onClick={() => setAdding(true)}>
            ＋ Add variant
          </Button>
        }
        flush
      >
        {product.variants.length === 0 ? (
          <EmptyState
            icon="⊞"
            title="No variants yet"
            action={<Button variant="primary" onClick={() => setAdding(true)}>Add the first one</Button>}
          >
            Each variant is one combination a buyer can choose, with its own stock and, if you
            want, its own price.
          </EmptyState>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Variant</th>
                  <th>SKU</th>
                  <th className="num">Price</th>
                  <th className="num">Stock</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {product.variants.map((variant) => (
                  <tr key={variant.id}>
                    <td>
                      {variant.values.map((v) => v.value).join(' · ') || '—'}
                      {!variant.active && <Badge tone="neutral"> Hidden</Badge>}
                    </td>
                    <td className="mono small">{variant.sku ?? '—'}</td>
                    <td className="num">
                      {money(variant.effectivePrice, product.currency)}
                      {variant.priceOverride == null && (
                        <div className="small faint">listing price</div>
                      )}
                    </td>
                    <td className="num">{variant.stock ?? 0}</td>
                    <td>
                      <div className="row" style={{ gap: 4, flexWrap: 'nowrap', justifyContent: 'flex-end' }}>
                        <Button size="sm" variant="ghost" onClick={() => setEditing(variant)}>Edit</Button>
                        <Button size="sm" variant="ghost" onClick={() => setRemoving(variant)}>✕</Button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Notice tone="info">
        Day-to-day stock changes are easier in <Link to="/inventory">Stock</Link>, which records
        why each movement happened and keeps a history you can read back.
      </Notice>

      {adding && (
        <VariantSheet
          product={product}
          onClose={() => setAdding(false)}
          onDone={() => { setAdding(false); invalidate(); }}
        />
      )}

      {editing && (
        <VariantSheet
          product={product}
          variant={editing}
          onClose={() => setEditing(null)}
          onDone={() => { setEditing(null); invalidate(); }}
        />
      )}

      {removing && (
        <ConfirmSheet
          title="Remove this variant?"
          confirmLabel="Remove it"
          danger
          onConfirm={() => remove.mutate(removing.id)}
          onClose={() => setRemoving(null)}
          busy={remove.isPending}
        >
          <p>
            Buyers can no longer choose{' '}
            <strong>{removing.values.map((v) => v.value).join(' · ')}</strong>. Orders already
            placed for it are not affected.
          </p>
        </ConfirmSheet>
      )}
    </div>
  );
}

function VariantSheet({
  product, variant, onClose, onDone,
}: {
  product: ProductDetail;
  variant?: ProductVariant;
  onClose: () => void;
  onDone: () => void;
}) {
  const editing = variant != null;
  const toast = useToast();
  const { step } = useCurrencies();
  const [key, resetKey] = useIdempotencyKey();

  const [selection, setSelection] = useState<Record<number, string>>(() => {
    const initial: Record<number, string> = {};
    for (const option of product.options) {
      const chosen = variant?.values.find((value) =>
        option.values.some((candidate) => candidate.optionValueId === value.optionValueId),
      );
      initial[option.id] = chosen ? String(chosen.optionValueId) : '';
    }
    return initial;
  });

  const [sku, setSku] = useState(variant?.sku ?? '');
  const [stock, setStock] = useState(variant?.stock != null ? String(variant.stock) : '0');
  const [priceOverride, setPriceOverride] = useState(
    variant?.priceOverride != null ? String(variant.priceOverride) : '',
  );
  const [active, setActive] = useState(variant?.active ?? true);
  const [error, setError] = useState<string | null>(null);

  const optionValueIds = product.options
    .map((option) => Number(selection[option.id]))
    .filter((id) => Number.isFinite(id) && id > 0);

  const complete = optionValueIds.length === product.options.length;

  const save = useMutation({
    mutationFn: () => {
      const body = {
        ...(sku.trim() ? { sku: sku.trim() } : {}),
        ...(stock.trim() ? { stock: Number(stock) } : {}),
        ...(priceOverride.trim() ? { priceOverride: Number(priceOverride) } : {}),
      };
      return editing
        ? catalogueApi.updateVariant(product.id, variant.id, { ...body, active, optionValueIds })
        : catalogueApi.createVariant(product.id, { ...body, optionValueIds }, key);
    },
    onSuccess: () => {
      toast.success(editing ? 'Variant saved.' : 'Variant added.');
      onDone();
    },
    onError: (cause) => {
      if (!editing) resetKey();
      setError(cause instanceof ApiError ? cause.message : 'Could not save the variant.');
    },
  });

  return (
    <Sheet
      title={editing ? 'Edit variant' : 'Add a variant'}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={save.isPending}>Cancel</Button>
          <Button
            variant="primary"
            onClick={() => save.mutate()}
            busy={save.isPending}
            disabled={!complete}
          >
            {editing ? 'Save' : 'Add it'}
          </Button>
        </>
      }
    >
      <div className="stack">
        {error && <Notice tone="danger">{error}</Notice>}

        {product.options.map((option) => (
          <SelectField
            key={option.id}
            label={option.name}
            value={selection[option.id] ?? ''}
            onChange={(event) =>
              setSelection((current) => ({ ...current, [option.id]: event.target.value }))
            }
            required
          >
            <option value="">Choose {option.name.toLowerCase()}</option>
            {option.values.map((value) => (
              <option key={value.optionValueId} value={value.optionValueId}>
                {value.value}
              </option>
            ))}
          </SelectField>
        ))}

        <TextField
          label="SKU" value={sku} onChange={(event) => setSku(event.target.value)}
          hint="Generated if you leave it blank." maxLength={60}
        />

        <div className="grid grid--2">
          <TextField
            label="In stock" type="number" inputMode="numeric" min="0"
            value={stock} onChange={(event) => setStock(event.target.value)}
          />
          <TextField
            label={`Price (${product.currency})`} type="number" inputMode="decimal"
            step={step(product.currency)} min="0"
            value={priceOverride} onChange={(event) => setPriceOverride(event.target.value)}
            hint="Blank uses the listing price."
          />
        </div>

        {editing && (
          <label className="checkbox">
            <input
              type="checkbox"
              checked={active}
              onChange={(event) => setActive(event.target.checked)}
            />
            <span className="checkbox__text">
              Buyers can choose this
              <span className="checkbox__hint">
                Untick to hide it without removing it.
              </span>
            </span>
          </label>
        )}
      </div>
    </Sheet>
  );
}
