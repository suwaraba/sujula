import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { products as productsApi } from '@/api/endpoints';
import { MODERATION_REASONS, PRODUCT_STATUSES } from '@/api/enums';
import type { ProductRow } from '@/api/types';
import { ActionModal } from '@/components/ActionModal';
import { DataTable, type Column } from '@/components/DataTable';
import { DecideButton } from '@/components/Decide';
import { CheckBox, Field, FilterBar, FormRow, MoneyInput, NumberInput, Select, TextArea, TextInput } from '@/components/forms';
import { Money } from '@/components/Money';
import { Pagination } from '@/components/Pagination';
import { Muted, PageHeader, StatusPill } from '@/components/primitives';
import { DateTime } from '@/components/Time';
import { currencyInfo, knownCurrencies } from '@/money/currency';
import { keys, useAction, useFilters, usePaging } from '@/hooks';

const DEFAULTS = {
  status: undefined as string | undefined,
  vendorId: undefined as number | undefined,
};

export function ProductsPage() {
  const [filters, setFilters, reset] = useFilters(DEFAULTS);
  const { page, size, setPage, setSize } = usePaging();

  const query = useQuery({
    queryKey: [...keys.products, filters, page, size],
    queryFn: () => productsApi.queue({ ...filters, status: filters.status as never, page, size }),
  });

  const columns: Column<ProductRow>[] = [
    {
      key: 'product',
      header: 'Listing',
      render: (row) => (
        <div className="listing-cell">
          {row.primaryImageUrl && <img src={row.primaryImageUrl} alt="" className="thumb" />}
          <div>
            <strong>{row.name}</strong>
            <br />
            <Muted>#{row.id}</Muted>
          </div>
        </div>
      ),
    },
    { key: 'status', header: 'Status', render: (row) => <StatusPill status={row.status} /> },
    {
      key: 'store',
      header: 'Store',
      render: (row) => (
        <>
          {row.storeName}
          <br />
          <Muted>#{row.vendorId}</Muted>
        </>
      ),
    },
    {
      key: 'price',
      header: 'Listed at',
      align: 'right',
      render: (row) => (
        <>
          <Money amount={row.price} currency={row.currency} />
          <br />
          <Muted>the vendor's own currency</Muted>
        </>
      ),
    },
    { key: 'stock', header: 'Stock', align: 'right', render: (row) => row.stock ?? <Muted>—</Muted> },
    { key: 'submitted', header: 'Submitted', render: (row) => <DateTime value={row.submittedAt} /> },
    {
      key: 'cases',
      header: 'Cases',
      align: 'right',
      render: (row) => (row.openCases > 0 ? <span className="overdue">{row.openCases}</span> : 0),
    },
    {
      key: 'actions',
      header: '',
      render: (row) => (
        <div className="row-actions">
          <ApproveProduct product={row} />
          <RejectProduct product={row} />
          <SuspendProduct product={row} />
          <EditProduct product={row} />
        </div>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Listings"
        description="Listings waiting for review. The price shown is the vendor's own currency — what a buyer sees is that figure converted at the rate live when they look, which is not this screen's business."
        actions={<CreateProduct />}
      />

      <FilterBar onReset={reset}>
        <Field label="Status">
          <Select
            value={(filters.status ?? '') as string}
            options={PRODUCT_STATUSES}
            placeholder="Any status"
            onChange={(status) => setFilters({ status: status || undefined })}
          />
        </Field>
        <Field label="Vendor id">
          <NumberInput
            value={filters.vendorId ?? ''}
            onChange={(vendorId) => setFilters({ vendorId: vendorId === '' ? undefined : vendorId })}
          />
        </Field>
      </FilterBar>

      <DataTable
        columns={columns}
        rows={query.data?.content}
        rowKey={(row) => row.id}
        isLoading={query.isFetching}
        error={query.error}
        empty="No listings match those filters."
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

const INVALIDATE = [keys.products, keys.cases, keys.dashboard];

function ApproveProduct({ product }: { product: ProductRow }) {
  const [open, setOpen] = useState(false);
  const [note, setNote] = useState('');
  const action = useAction(() => productsApi.approve(product.id, { note: note.trim() || null }), {
    invalidate: INVALIDATE,
    message: (decision) => decision.message,
  });

  return (
    <>
      <DecideButton variant="secondary" onClick={() => setOpen(true)}>
        Publish
      </DecideButton>
      <ActionModal
        open={open}
        title={`Publish “${product.name}”`}
        submitLabel="Publish"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
      >
        <Field label="Note" hint="Internal.">
          <TextArea value={note} onChange={setNote} />
        </Field>
      </ActionModal>
    </>
  );
}

function RejectProduct({ product }: { product: ProductRow }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState<string>('');
  const [detail, setDetail] = useState('');
  const action = useAction(
    () => productsApi.reject(product.id, { reason: reason as never, detail: detail.trim() }),
    { invalidate: INVALIDATE, message: (decision) => decision.message },
  );

  return (
    <>
      <DecideButton variant="ghost" onClick={() => setOpen(true)}>
        Refuse
      </DecideButton>
      <ActionModal
        open={open}
        title={`Refuse “${product.name}”`}
        description="A reason code so the pattern is countable, and words so the seller knows what to change."
        submitLabel="Refuse it"
        tone="danger"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={!reason || detail.trim().length < 5}
      >
        <Field label="Reason" required>
          <Select value={reason} required options={MODERATION_REASONS} placeholder="Choose" onChange={setReason} />
        </Field>
        <Field label="What the seller has to change" required>
          <TextArea value={detail} required minLength={5} rows={4} onChange={setDetail} />
        </Field>
      </ActionModal>
    </>
  );
}

function SuspendProduct({ product }: { product: ProductRow }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState<string>('');
  const [detail, setDetail] = useState('');
  const [openCase, setOpenCase] = useState(false);

  const action = useAction(
    () =>
      productsApi.suspend(product.id, {
        reason: reason as never,
        detail: detail.trim(),
        openCaseAgainstSeller: openCase,
      }),
    { invalidate: INVALIDATE, message: (decision) => decision.message },
  );

  return (
    <>
      <DecideButton variant="danger" onClick={() => setOpen(true)}>
        Take down
      </DecideButton>
      <ActionModal
        open={open}
        title={`Take “${product.name}” down`}
        description="For a listing that broke a rule, rather than one that simply is not ready."
        submitLabel="Take it down"
        tone="danger"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={!reason || detail.trim().length < 5}
      >
        <Field label="Rule broken" required>
          <Select value={reason} required options={MODERATION_REASONS} placeholder="Choose" onChange={setReason} />
        </Field>
        <CheckBox
          checked={openCase}
          onChange={setOpenCase}
          label="Open a case against the seller as well"
          hint="Do this where the seller is responsible rather than mistaken. The case is what makes a second offence visible."
        />
        <Field label="Detail" required>
          <TextArea value={detail} required minLength={5} rows={4} onChange={setDetail} />
        </Field>
      </ActionModal>
    </>
  );
}

function EditProduct({ product }: { product: ProductRow }) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState(product.name);
  const [description, setDescription] = useState('');
  const [price, setPrice] = useState('');
  const [stock, setStock] = useState<number | ''>('');
  const [reason, setReason] = useState('');

  const action = useAction(
    () =>
      productsApi.patch(product.id, {
        name: name.trim() === product.name ? null : name.trim(),
        description: description.trim() || null,
        price: price === '' ? null : price,
        stock: stock === '' ? null : stock,
        reason: reason.trim(),
      }),
    { invalidate: INVALIDATE, message: (decision) => decision.message },
  );

  const scale = currencyInfo(product.currency)?.minorUnits ?? 2;

  return (
    <>
      <DecideButton variant="ghost" onClick={() => setOpen(true)}>
        Edit
      </DecideButton>
      <ActionModal
        open={open}
        width="wide"
        title={`Edit somebody else's listing`}
        description="This is the seller's listing, not yours. Edit it where they cannot — a typo that makes a phone unfindable — and leave a reason they will read."
        submitLabel="Save the edit"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={reason.trim().length < 5}
      >
        <Field label="Name">
          <TextInput value={name} onChange={setName} />
        </Field>
        <Field label="Description" hint="Leave empty to keep what is there.">
          <TextArea value={description} rows={4} onChange={setDescription} />
        </Field>
        <FormRow>
          <Field label={`Price (${product.currency})`} hint="The vendor's own currency.">
            <MoneyInput value={price} minorUnits={scale} min={0} onChange={setPrice} />
          </Field>
          <Field label="Stock">
            <NumberInput value={stock} min={0} onChange={setStock} />
          </Field>
        </FormRow>
        <Field label="Why you are editing it" required>
          <TextArea value={reason} required minLength={5} onChange={setReason} />
        </Field>
      </ActionModal>
    </>
  );
}

/**
 * Listing something on a seller's behalf.
 *
 * The price currency is the vendor's own, and the form says so: a listing
 * priced in the administrator's currency would be a listing the vendor is paid
 * out of at a rate nobody agreed to.
 */
function CreateProduct() {
  const [open, setOpen] = useState(false);
  const [vendorId, setVendorId] = useState<number | ''>('');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [price, setPrice] = useState('');
  const [priceCurrency, setPriceCurrency] = useState('');
  const [stock, setStock] = useState<number | ''>('');
  const [categoryId, setCategoryId] = useState<number | ''>('');
  const [reason, setReason] = useState('');

  const action = useAction(
    () =>
      productsApi.create({
        vendorId: Number(vendorId),
        name: name.trim(),
        description: description.trim(),
        price,
        priceCurrency,
        stock: Number(stock),
        categoryId: Number(categoryId),
        reason: reason.trim(),
      }),
    { invalidate: INVALIDATE, message: (decision) => decision.message },
  );

  const scale = currencyInfo(priceCurrency)?.minorUnits ?? 2;

  return (
    <>
      <DecideButton variant="secondary" onClick={() => setOpen(true)}>
        List on a seller's behalf
      </DecideButton>
      <ActionModal
        open={open}
        width="wide"
        title="List something on a seller's behalf"
        description="For a seller who cannot do it themselves. It is their listing and their money — priced in their currency, paid out in their currency."
        submitLabel="Create the listing"
        onClose={() => setOpen(false)}
        onSubmit={() => action.mutateAsync()}
        disabled={!vendorId || !name.trim() || !priceCurrency || reason.trim().length < 5}
      >
        <FormRow>
          <Field label="Vendor id" required>
            <NumberInput value={vendorId} required min={1} onChange={setVendorId} />
          </Field>
          <Field label="Category id" required>
            <NumberInput value={categoryId} required min={1} onChange={setCategoryId} />
          </Field>
        </FormRow>

        <Field label="Name" required>
          <TextInput value={name} required onChange={setName} />
        </Field>
        <Field label="Description" required>
          <TextArea value={description} required rows={4} onChange={setDescription} />
        </Field>

        <FormRow columns={3}>
          <Field
            label="Currency"
            required
            hint="The vendor's own. This is also what they will be paid in."
          >
            <Select
              value={priceCurrency}
              required
              placeholder="Choose"
              options={knownCurrencies().map((entry) => ({
                value: entry.code,
                label: `${entry.code} — ${entry.name}`,
              }))}
              onChange={setPriceCurrency}
            />
          </Field>
          <Field label="Price" required>
            <MoneyInput value={price} required minorUnits={scale} min={0} onChange={setPrice} />
          </Field>
          <Field label="Stock" required>
            <NumberInput value={stock} required min={0} onChange={setStock} />
          </Field>
        </FormRow>

        <Field label="Why you are listing this for them" required>
          <TextArea value={reason} required minLength={5} onChange={setReason} />
        </Field>
      </ActionModal>
    </>
  );
}
