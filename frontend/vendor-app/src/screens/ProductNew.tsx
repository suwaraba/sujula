import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import { catalogueApi } from '@/api/endpoints/catalogue';
import { ApiError } from '@/api/errors';
import { useStore } from '@/store/StoreProvider';
import { useCategories, useCountries, useCurrencies, useIdempotencyKey } from '@/lib/hooks';
import { Button, Card, Notice, PageHeader } from '@/components/ui';
import { SelectField, TextArea, TextField, CheckField, fieldError } from '@/components/form';
import { CollectionPointNote } from '@/components/CollectionPointNote';
import { useToast } from '@/components/Toast';
import { CONDITIONS, DELIVERY_SCOPES, flattenCategories } from '@/lib/catalogue';

export function ProductNew() {
  const navigate = useNavigate();
  const toast = useToast();
  const { currency, canTrade } = useStore();
  const { step } = useCurrencies();
  const categories = useCategories();
  const countries = useCountries();
  const [key, resetKey] = useIdempotencyKey();

  const [form, setForm] = useState({
    name: '',
    shortDescription: '',
    description: '',
    price: '',
    compareAtPrice: '',
    sku: '',
    stock: '0',
    lowStockThreshold: '3',
    allowBackorder: false,
    categoryId: '',
    condition: 'NEW',
    deliveryScope: '',
    weightKg: '',
    dimensions: '',
    country: '',
  });

  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});

  const set = (name: keyof typeof form) => (event: { target: { value: string } }) =>
    setForm((current) => ({ ...current, [name]: event.target.value }));

  const create = useMutation({
    mutationFn: () =>
      catalogueApi.create(
        {
          name: form.name.trim(),
          ...(form.shortDescription.trim() ? { shortDescription: form.shortDescription.trim() } : {}),
          ...(form.description.trim() ? { description: form.description.trim() } : {}),
          price: Number(form.price),
          ...(form.compareAtPrice.trim() ? { compareAtPrice: Number(form.compareAtPrice) } : {}),
          ...(form.sku.trim() ? { sku: form.sku.trim() } : {}),
          ...(form.stock.trim() ? { stock: Number(form.stock) } : {}),
          ...(form.lowStockThreshold.trim() ? { lowStockThreshold: Number(form.lowStockThreshold) } : {}),
          allowBackorder: form.allowBackorder,
          ...(form.categoryId ? { categoryId: Number(form.categoryId) } : {}),
          condition: form.condition as never,
          ...(form.deliveryScope ? { deliveryScope: form.deliveryScope } : {}),
          ...(form.weightKg.trim() ? { weightKg: Number(form.weightKg) } : {}),
          ...(form.dimensions.trim() ? { dimensions: form.dimensions.trim() } : {}),
          ...(form.country ? { country: form.country } : {}),
        },
        key,
      ),
    onSuccess: (product) => {
      toast.success('Saved as a draft. Send it to be checked when it is ready.');
      navigate(`/products/${product.id}`, { replace: true });
    },
    onError: (cause) => {
      resetKey();
      if (cause instanceof ApiError) {
        setError(cause.message);
        setFields(cause.fieldErrors);
      } else {
        setError('Could not save the listing.');
      }
    },
  });

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setFields({});
    create.mutate();
  }

  return (
    <div className="page stack">
      <PageHeader
        title="New listing"
        subtitle="Saved as a draft. Nothing reaches buyers until it has been checked and you put it on sale."
      />

      {!canTrade && (
        <Notice tone="warn" title="Your shop is not verified yet">
          You can write listings now. They go on sale once verification is accepted.
        </Notice>
      )}

      <form className="stack" onSubmit={onSubmit} noValidate>
        {error && <Notice tone="danger">{error}</Notice>}

        <Card title="What you are selling">
          <div className="stack">
            <TextField
              label="Name" value={form.name} onChange={set('name')}
              hint="What a buyer searching would type."
              error={fieldError(fields, 'name')} maxLength={200} required
            />
            <TextField
              label="One-line summary" value={form.shortDescription} onChange={set('shortDescription')}
              error={fieldError(fields, 'shortDescription')} maxLength={500}
            />
            <TextArea
              label="Full description" value={form.description} onChange={set('description')}
              error={fieldError(fields, 'description')} maxLength={20000} rows={6}
            />
            <div className="grid grid--2">
              <SelectField
                label="Category" value={form.categoryId} onChange={set('categoryId')}
                error={fieldError(fields, 'categoryId')}
              >
                <option value="">Choose a category</option>
                {flattenCategories(categories.data?.categories).map(({ id, label }) => (
                  <option key={id} value={id}>{label}</option>
                ))}
              </SelectField>
              <SelectField
                label="Condition" value={form.condition} onChange={set('condition')}
                error={fieldError(fields, 'condition')}
              >
                {CONDITIONS.map(({ value, label }) => (
                  <option key={value} value={value}>{label}</option>
                ))}
              </SelectField>
            </div>
          </div>
        </Card>

        <Card title="Price and stock">
          <div className="stack">
            <Notice tone="info">
              Prices are in <strong>{currency}</strong> — your shop's currency, which is also what
              you are paid in. A buyer paying in another currency is converted at the rate on the
              day, and that rate is recorded on their order.
            </Notice>

            <div className="grid grid--2">
              <TextField
                label={`Price (${currency})`} type="number" inputMode="decimal"
                step={step(currency)} min="0"
                value={form.price} onChange={set('price')}
                error={fieldError(fields, 'price')} required
              />
              <TextField
                label={`Was (${currency})`} type="number" inputMode="decimal"
                step={step(currency)} min="0"
                value={form.compareAtPrice} onChange={set('compareAtPrice')}
                hint="Shown struck through. Leave blank if it is not on offer."
                error={fieldError(fields, 'compareAtPrice')}
              />
            </div>

            <div className="grid grid--3">
              <TextField
                label="Your code (SKU)" value={form.sku} onChange={set('sku')}
                hint="Generated if you leave it blank."
                error={fieldError(fields, 'sku')} maxLength={60}
              />
              <TextField
                label="In stock" type="number" inputMode="numeric" min="0"
                value={form.stock} onChange={set('stock')}
                error={fieldError(fields, 'stock')}
              />
              <TextField
                label="Warn me at" type="number" inputMode="numeric" min="0"
                value={form.lowStockThreshold} onChange={set('lowStockThreshold')}
                error={fieldError(fields, 'lowStockThreshold')}
              />
            </div>

            <CheckField
              label="Accept orders when out of stock"
              hint="Only if you can reliably restock — a buyer has already paid."
              checked={form.allowBackorder}
              onChange={(event) => setForm((c) => ({ ...c, allowBackorder: event.target.checked }))}
            />
          </div>
        </Card>

        <Card title="Where it ships from, and how">
          <div className="stack">
            <CollectionPointNote />

            <div className="grid grid--2">
              <SelectField
                label="Country of origin" value={form.country} onChange={set('country')}
                hint="Left blank, your shop's country is used."
                error={fieldError(fields, 'country')}
              >
                <option value="">Use my shop's country</option>
                {(countries.data?.countries ?? []).map((country) => (
                  <option key={country.code} value={country.code}>{country.name}</option>
                ))}
              </SelectField>
              <SelectField
                label="How far it can go" value={form.deliveryScope} onChange={set('deliveryScope')}
                error={fieldError(fields, 'deliveryScope')}
              >
                <option value="">Use the platform default</option>
                {DELIVERY_SCOPES.map(({ value, label }) => (
                  <option key={value} value={value}>{label}</option>
                ))}
              </SelectField>
            </div>

            <div className="grid grid--2">
              <TextField
                label="Weight (kg)" type="number" inputMode="decimal" step="0.01" min="0"
                value={form.weightKg} onChange={set('weightKg')}
                hint="Half of what delivery costs is decided by this."
                error={fieldError(fields, 'weightKg')}
              />
              <TextField
                label="Size" value={form.dimensions} onChange={set('dimensions')}
                placeholder="20 × 12 × 6 cm"
                error={fieldError(fields, 'dimensions')} maxLength={60}
              />
            </div>
          </div>
        </Card>

        <div className="row">
          <Button type="submit" variant="primary" busy={create.isPending}>
            Save as draft
          </Button>
          <Button type="button" variant="ghost" onClick={() => navigate('/products')}>
            Cancel
          </Button>
        </div>
      </form>
    </div>
  );
}

