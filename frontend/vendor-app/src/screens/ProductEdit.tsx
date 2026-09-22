import { useEffect, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { catalogueApi } from '@/api/endpoints/catalogue';
import { ApiError } from '@/api/errors';
import { useCategories, useCountries, useCurrencies, useIdempotencyKey } from '@/lib/hooks';
import { formatDateTime } from '@/lib/format';
import type { ProductDetail } from '@/api/types';
import {
  Button, Card, EmptyState, KeyValue, Notice, PageHeader, Skeleton,
} from '@/components/ui';
import { CheckField, SelectField, TextArea, TextField, fieldError } from '@/components/form';
import { ConfirmSheet, Sheet } from '@/components/Sheet';
import { CollectionPointNote } from '@/components/CollectionPointNote';
import { useToast } from '@/components/Toast';
import { CONDITIONS, DELIVERY_SCOPES, flattenCategories } from '@/lib/catalogue';
import { ProductStatusBadge } from './Products';
import { ProductMediaPanel } from './ProductMedia';
import { ProductVariantsPanel } from './ProductVariants';

const TABS = ['Details', 'Photos', 'Variants'] as const;

export function ProductEdit() {
  const { productId } = useParams<{ productId: string }>();
  const id = Number(productId);
  const [tab, setTab] = useState<(typeof TABS)[number]>('Details');

  const product = useQuery({
    queryKey: ['products', 'detail', id],
    queryFn: () => catalogueApi.get(id),
    enabled: Number.isFinite(id),
  });

  if (product.isLoading) {
    return (
      <div className="page stack">
        <Skeleton height={32} width={260} />
        <Skeleton height={200} />
        <Skeleton height={320} />
      </div>
    );
  }

  if (product.isError || !product.data) {
    const notFound = product.error instanceof ApiError && product.error.isNotFound;
    return (
      <div className="page">
        <EmptyState
          icon="∅"
          title={notFound ? 'That listing is not here' : 'Could not load this listing'}
          action={<Link to="/products" className="btn btn--secondary">Back to products</Link>}
        >
          {notFound ? 'It may belong to another shop, or it may have been removed.' : 'Try again.'}
        </EmptyState>
      </div>
    );
  }

  const data = product.data;

  return (
    <div className="page stack">
      <PageHeader
        title={data.name}
        subtitle={
          <>
            <ProductStatusBadge status={data.status} />{' '}
            <span className="muted">· edited {formatDateTime(data.updatedAt)}</span>
          </>
        }
      />

      <LifecyclePanel product={data} />

      <div className="tabs" role="tablist">
        {TABS.map((name) => (
          <button
            key={name}
            type="button"
            role="tab"
            aria-selected={tab === name}
            className={tab === name ? 'tabs__item is-active' : 'tabs__item'}
            onClick={() => setTab(name)}
          >
            {name}
            {name === 'Photos' && (
              <span className="tabs__count">{data.media.length}</span>
            )}
            {name === 'Variants' && data.variants.length > 0 && (
              <span className="tabs__count">{data.variants.length}</span>
            )}
          </button>
        ))}
      </div>

      {tab === 'Details' && <DetailsForm product={data} />}
      {tab === 'Photos' && <ProductMediaPanel product={data} />}
      {tab === 'Variants' && <ProductVariantsPanel product={data} />}
    </div>
  );
}

/**
 * The four verbs, and which of them this listing can take right now.
 *
 * Write → send for review → approved → on sale. The server decides which are
 * available (`moderation.canSubmit`, `canPublish`, `editable`) and this panel
 * draws only those — a seller who could name the status could name PUBLISHED,
 * and moderation would be something you opt into.
 */
function LifecyclePanel({ product }: { product: ProductDetail }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const [archiving, setArchiving] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [submitKey, resetSubmitKey] = useIdempotencyKey();
  const [publishKey, resetPublishKey] = useIdempotencyKey();

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['products'] });
  };

  const publish = useMutation({
    mutationFn: () => catalogueApi.publish(product.id, publishKey),
    onSuccess: (result) => {
      toast.success(result.message || 'It is on sale.');
      invalidate();
    },
    onError: (error) => {
      resetPublishKey();
      toast.error(error instanceof ApiError ? error.message : 'Could not put it on sale.');
    },
  });

  const unpublish = useMutation({
    mutationFn: () => catalogueApi.unpublish(product.id),
    onSuccess: (result) => {
      toast.success(result.message || 'Taken off sale.');
      invalidate();
    },
    onError: (error) => {
      toast.error(error instanceof ApiError ? error.message : 'Could not take it off sale.');
    },
  });

  const archive = useMutation({
    mutationFn: () => catalogueApi.archive(product.id),
    onSuccess: (result) => {
      setArchiving(false);
      toast.success(result.message || 'Archived.');
      invalidate();
    },
    onError: (error) => {
      setArchiving(false);
      toast.error(error instanceof ApiError ? error.message : 'Could not archive it.');
    },
  });

  const { moderation } = product;

  // The server refuses a review without these three, and says so in a 400. A
  // seller should not have to discover that by being refused — the same rules
  // are stated here, before the button is pressed.
  const missing: string[] = [];
  if (!(product.price > 0)) missing.push('Give it a price.');
  if (product.categoryId == null) missing.push('Choose a category.');
  if (!product.description?.trim()) {
    missing.push(
      'Write a description. Buyers here are often thousands of miles from the goods, ' +
      'and this is all they have to go on.',
    );
  }

  return (
    <Card>
      <div className="stack">
        {product.status === 'IN_REVIEW' && (
          <Notice tone="info" title="Being checked">
            Sent {formatDateTime(moderation.submittedAt)}. It cannot be edited until a decision is
            made.
          </Notice>
        )}

        {product.status === 'REJECTED' && (
          <Notice tone="danger" title="This listing was turned down">
            {moderation.reason ?? 'No reason was given. Edit the listing and send it again.'}
          </Notice>
        )}

        {product.status === 'SUSPENDED' && (
          <Notice tone="danger" title="This listing is suspended">
            {moderation.reason ?? 'Contact support to find out why.'}
          </Notice>
        )}

        {product.status === 'APPROVED' && (
          <Notice tone="ok" title="Approved — but not on sale yet">
            It has been checked. Put it on sale when you are ready.
          </Notice>
        )}

        {moderation.editsNeedReview && moderation.editable && (
          <Notice tone="warn" title="Editing this sends it back to be checked">
            Changing the name, description, photos or category takes it off sale until it has been
            looked at again. Price and stock do not.
          </Notice>
        )}

        {moderation.canSubmit && missing.length > 0 && (
          <Notice tone="warn" title="Not ready to be checked yet">
            <ul style={{ margin: 'var(--space-2) 0 0', paddingLeft: '1.2em' }}>
              {missing.map((item) => <li key={item}>{item}</li>)}
            </ul>
          </Notice>
        )}

        {moderation.canSubmit && missing.length === 0 && product.media.length === 0 && (
          <Notice tone="warn" title="No photos">
            You can send it without one, but a listing with no photo rarely sells.
          </Notice>
        )}

        <div className="row">
          {moderation.canSubmit && (
            <Button
              variant="primary"
              onClick={() => setSubmitting(true)}
              disabled={missing.length > 0}
            >
              Send to be checked
            </Button>
          )}
          {moderation.canPublish && (
            <Button variant="primary" onClick={() => publish.mutate()} busy={publish.isPending}>
              Put it on sale
            </Button>
          )}
          {product.live && (
            <Button variant="secondary" onClick={() => unpublish.mutate()} busy={unpublish.isPending}>
              Take it off sale
            </Button>
          )}
          {product.status !== 'ARCHIVED' && (
            <Button variant="ghost" onClick={() => setArchiving(true)}>Archive</Button>
          )}
        </div>
      </div>

      {archiving && (
        <ConfirmSheet
          title="Archive this listing?"
          confirmLabel="Archive it"
          danger
          onConfirm={() => archive.mutate()}
          onClose={() => setArchiving(false)}
          busy={archive.isPending}
        >
          <p>
            It comes off sale and out of your catalogue. Orders already placed for it are not
            affected. An archived listing cannot be brought back.
          </p>
        </ConfirmSheet>
      )}

      {submitting && (
        <SubmitSheet
          productId={product.id}
          idempotencyKey={submitKey}
          onResetKey={resetSubmitKey}
          onClose={() => setSubmitting(false)}
          onDone={() => {
            setSubmitting(false);
            invalidate();
          }}
        />
      )}
    </Card>
  );
}

function SubmitSheet({
  productId, idempotencyKey, onResetKey, onClose, onDone,
}: {
  productId: number;
  idempotencyKey: string;
  onResetKey: () => void;
  onClose: () => void;
  onDone: () => void;
}) {
  const [note, setNote] = useState('');
  const toast = useToast();

  const submit = useMutation({
    mutationFn: () => catalogueApi.submitForReview(productId, note.trim() || undefined, idempotencyKey),
    onSuccess: (result) => {
      toast.success(result.message || 'Sent to be checked.');
      onDone();
    },
    onError: (error) => {
      onResetKey();
      toast.error(error instanceof ApiError ? error.message : 'Could not send it.');
    },
  });

  return (
    <Sheet
      title="Send this listing to be checked"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={submit.isPending}>Cancel</Button>
          <Button variant="primary" onClick={() => submit.mutate()} busy={submit.isPending}>
            Send it
          </Button>
        </>
      }
    >
      <div className="stack">
        <p className="muted">
          Somebody reads it and either approves it or tells you what to change. While it is being
          checked you cannot edit it.
        </p>
        <TextArea
          label="Anything the reviewer should know"
          hint="Optional."
          value={note}
          onChange={(event) => setNote(event.target.value)}
          maxLength={500}
          rows={3}
        />
      </div>
    </Sheet>
  );
}

function DetailsForm({ product }: { product: ProductDetail }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const { step, money } = useCurrencies();
  const categories = useCategories();
  const countries = useCountries();

  const [form, setForm] = useState(() => fromProduct(product));
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});

  // A refetch that brings back a newer server copy should win over a form the
  // seller has not touched — otherwise a publish elsewhere silently reverts here.
  useEffect(() => {
    setForm(fromProduct(product));
  }, [product]);

  const editable = product.moderation.editable;

  const save = useMutation({
    mutationFn: () =>
      catalogueApi.update(product.id, {
        name: form.name.trim(),
        shortDescription: form.shortDescription.trim(),
        description: form.description.trim(),
        price: Number(form.price),
        ...(form.compareAtPrice.trim() ? { compareAtPrice: Number(form.compareAtPrice) } : {}),
        sku: form.sku.trim(),
        ...(form.stock.trim() ? { stock: Number(form.stock) } : {}),
        ...(form.lowStockThreshold.trim() ? { lowStockThreshold: Number(form.lowStockThreshold) } : {}),
        allowBackorder: form.allowBackorder,
        ...(form.categoryId ? { categoryId: Number(form.categoryId) } : {}),
        condition: form.condition as never,
        ...(form.deliveryScope ? { deliveryScope: form.deliveryScope } : {}),
        ...(form.weightKg.trim() ? { weightKg: Number(form.weightKg) } : {}),
        dimensions: form.dimensions.trim(),
        ...(form.country ? { country: form.country } : {}),
      }),
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: ['products'] });
      // The server says whether the edit knocked it back into moderation, so
      // the seller finds out here rather than noticing their listing went
      // offline some time later.
      toast.success(
        result.needsReview
          ? 'Saved — and sent back to be checked, so it is off sale for now.'
          : result.message || 'Saved.',
      );
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

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setFields({});
    save.mutate();
  }

  const set = (name: keyof typeof form) => (event: { target: { value: string } }) =>
    setForm((current) => ({ ...current, [name]: event.target.value }));

  return (
    <form className="stack" onSubmit={onSubmit} noValidate>
      {error && <Notice tone="danger">{error}</Notice>}
      {!editable && (
        <Notice tone="info">
          This listing cannot be edited in its current state.
        </Notice>
      )}

      <Card title="What you are selling">
        <div className="stack">
          <TextField
            label="Name" value={form.name} onChange={set('name')}
            error={fieldError(fields, 'name')} maxLength={200} disabled={!editable} required
          />
          <TextField
            label="One-line summary" value={form.shortDescription} onChange={set('shortDescription')}
            error={fieldError(fields, 'shortDescription')} maxLength={500} disabled={!editable}
          />
          <TextArea
            label="Full description" value={form.description} onChange={set('description')}
            error={fieldError(fields, 'description')} maxLength={20000} rows={6} disabled={!editable}
          />
          <div className="grid grid--2">
            <SelectField
              label="Category" value={form.categoryId} onChange={set('categoryId')}
              error={fieldError(fields, 'categoryId')} disabled={!editable}
            >
              <option value="">Choose a category</option>
              {flattenCategories(categories.data?.categories).map(({ id, label }) => (
                <option key={id} value={id}>{label}</option>
              ))}
            </SelectField>
            <SelectField
              label="Condition" value={form.condition} onChange={set('condition')}
              error={fieldError(fields, 'condition')} disabled={!editable}
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
          <div className="grid grid--2">
            <TextField
              label={`Price (${product.currency})`} type="number" inputMode="decimal"
              step={step(product.currency)} min="0"
              value={form.price} onChange={set('price')}
              error={fieldError(fields, 'price')} disabled={!editable} required
            />
            <TextField
              label={`Was (${product.currency})`} type="number" inputMode="decimal"
              step={step(product.currency)} min="0"
              value={form.compareAtPrice} onChange={set('compareAtPrice')}
              error={fieldError(fields, 'compareAtPrice')} disabled={!editable}
            />
          </div>

          {product.variants.length > 0 ? (
            <Notice tone="info">
              This listing has variants, and each can set its own price and stock. The figures here
              are the listing's defaults. <strong>Stock is managed per variant</strong> — see the
              Variants tab or <Link to="/inventory">Stock</Link>.
            </Notice>
          ) : (
            <div className="grid grid--3">
              <TextField
                label="Your code (SKU)" value={form.sku} onChange={set('sku')}
                error={fieldError(fields, 'sku')} maxLength={60} disabled={!editable}
              />
              <TextField
                label="In stock" type="number" inputMode="numeric" min="0"
                value={form.stock} onChange={set('stock')}
                error={fieldError(fields, 'stock')} disabled={!editable}
              />
              <TextField
                label="Warn me at" type="number" inputMode="numeric" min="0"
                value={form.lowStockThreshold} onChange={set('lowStockThreshold')}
                error={fieldError(fields, 'lowStockThreshold')} disabled={!editable}
              />
            </div>
          )}

          <CheckField
            label="Accept orders when out of stock"
            checked={form.allowBackorder}
            disabled={!editable}
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
              error={fieldError(fields, 'country')} disabled={!editable}
            >
              <option value="">Use my shop's country</option>
              {(countries.data?.countries ?? []).map((country) => (
                <option key={country.code} value={country.code}>{country.name}</option>
              ))}
            </SelectField>
            <SelectField
              label="How far it can go" value={form.deliveryScope} onChange={set('deliveryScope')}
              error={fieldError(fields, 'deliveryScope')} disabled={!editable}
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
              error={fieldError(fields, 'weightKg')} disabled={!editable}
            />
            <TextField
              label="Size" value={form.dimensions} onChange={set('dimensions')}
              error={fieldError(fields, 'dimensions')} maxLength={60} disabled={!editable}
            />
          </div>
        </div>
      </Card>

      <Card title="How it has done">
        <KeyValue
          rows={[
            ['Sold', product.totalSold ?? 0],
            [
              'Rating',
              product.rating
                ? `${product.rating.toFixed(1)} from ${product.totalReviews ?? 0} review${product.totalReviews === 1 ? '' : 's'}`
                : 'No reviews yet',
            ],
            ['Listed price', money(product.price, product.currency)],
            ['Written', formatDateTime(product.createdAt)],
            ['Web address', <span key="s" className="mono small">/{product.slug}</span>],
          ]}
        />
      </Card>

      {editable && (
        <div className="row">
          <Button type="submit" variant="primary" busy={save.isPending}>Save changes</Button>
          <Button type="button" variant="ghost" onClick={() => setForm(fromProduct(product))}>
            Undo changes
          </Button>
        </div>
      )}
    </form>
  );
}

function fromProduct(product: ProductDetail) {
  return {
    name: product.name,
    shortDescription: product.shortDescription ?? '',
    description: product.description ?? '',
    price: String(product.price ?? ''),
    compareAtPrice: product.compareAtPrice != null ? String(product.compareAtPrice) : '',
    sku: product.sku ?? '',
    stock: product.stock != null ? String(product.stock) : '',
    lowStockThreshold: product.lowStockThreshold != null ? String(product.lowStockThreshold) : '',
    allowBackorder: product.allowBackorder,
    categoryId: product.categoryId != null ? String(product.categoryId) : '',
    condition: product.condition,
    deliveryScope: product.deliveryScope ?? '',
    weightKg: product.weightKg != null ? String(product.weightKg) : '',
    dimensions: product.dimensions ?? '',
    country: product.country ?? '',
  };
}
