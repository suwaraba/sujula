import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { catalogueApi } from '@/api/endpoints/catalogue';
import { useCurrencies, useDebounced } from '@/lib/hooks';
import { formatDateTime } from '@/lib/format';
import type { ProductStatus } from '@/api/types';
import { Badge, Card, EmptyState, PageHeader, SkeletonList, type Tone } from '@/components/ui';
import { Pagination } from '@/components/Pagination';
import { Thumb } from '@/components/Thumb';

const TABS: { label: string; status?: ProductStatus }[] = [
  { label: 'All', status: undefined },
  { label: 'On sale', status: 'PUBLISHED' },
  { label: 'Drafts', status: 'DRAFT' },
  { label: 'Being reviewed', status: 'IN_REVIEW' },
  { label: 'Approved', status: 'APPROVED' },
  { label: 'Off sale', status: 'UNPUBLISHED' },
  { label: 'Rejected', status: 'REJECTED' },
];

export const PRODUCT_STATUS_TONE: Record<ProductStatus, Tone> = {
  DRAFT: 'neutral',
  IN_REVIEW: 'info',
  APPROVED: 'accent',
  PUBLISHED: 'ok',
  UNPUBLISHED: 'warn',
  REJECTED: 'danger',
  SUSPENDED: 'danger',
  ARCHIVED: 'neutral',
};

export const PRODUCT_STATUS_LABEL: Record<ProductStatus, string> = {
  DRAFT: 'Draft',
  IN_REVIEW: 'Being reviewed',
  APPROVED: 'Approved — not on sale',
  PUBLISHED: 'On sale',
  UNPUBLISHED: 'Off sale',
  REJECTED: 'Rejected',
  SUSPENDED: 'Suspended',
  ARCHIVED: 'Archived',
};

export function ProductStatusBadge({ status }: { status: ProductStatus }) {
  return <Badge tone={PRODUCT_STATUS_TONE[status] ?? 'neutral'} dot>{PRODUCT_STATUS_LABEL[status] ?? status}</Badge>;
}

export function Products() {
  const [tab, setTab] = useState(0);
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState('');
  const [includeArchived, setIncludeArchived] = useState(false);
  const debouncedSearch = useDebounced(search);
  const { money } = useCurrencies();

  const status = TABS[tab]?.status;

  const products = useQuery({
    queryKey: ['products', { status: status ?? null, search: debouncedSearch, includeArchived, page }],
    queryFn: () =>
      catalogueApi.list({
        ...(status ? { status } : {}),
        ...(debouncedSearch.trim() ? { search: debouncedSearch.trim() } : {}),
        includeArchived,
        page,
        size: 20,
      }),
  });

  return (
    <div className="page stack">
      <PageHeader
        title="Products"
        subtitle="Write it, send it to be checked, then put it on sale."
        actions={<Link to="/products/new" className="btn btn--primary">＋ New listing</Link>}
      />

      <div className="tabs" role="tablist">
        {TABS.map((entry, index) => {
          const count = entry.status ? products.data?.counts?.[entry.status] : undefined;
          return (
            <button
              key={entry.label}
              type="button"
              role="tab"
              aria-selected={index === tab}
              className={index === tab ? 'tabs__item is-active' : 'tabs__item'}
              onClick={() => { setTab(index); setPage(0); }}
            >
              {entry.label}
              {count !== undefined && count > 0 && <span className="tabs__count">{count}</span>}
            </button>
          );
        })}
      </div>

      <div className="row">
        <input
          className="input"
          style={{ flex: 1, minWidth: 200 }}
          type="search"
          placeholder="Search your listings"
          value={search}
          onChange={(event) => { setSearch(event.target.value); setPage(0); }}
          aria-label="Search your listings"
        />
        <label className="row small muted" style={{ gap: 'var(--space-2)', cursor: 'pointer' }}>
          <input
            type="checkbox"
            checked={includeArchived}
            onChange={(event) => { setIncludeArchived(event.target.checked); setPage(0); }}
          />
          Show archived
        </label>
      </div>

      <Card flush>
        {products.isLoading ? (
          <SkeletonList rows={5} />
        ) : (products.data?.items.length ?? 0) === 0 ? (
          <EmptyState
            icon="☰"
            title={debouncedSearch ? 'Nothing matched' : 'No listings yet'}
            action={
              !debouncedSearch && (
                <Link to="/products/new" className="btn btn--primary">Write your first listing</Link>
              )
            }
          >
            {debouncedSearch
              ? 'Try a different word, or clear the search.'
              : 'A listing starts as a draft. Nothing is visible to buyers until it has been checked and you put it on sale.'}
          </EmptyState>
        ) : (
          <>
            <div className="list">
              {products.data!.items.map((product) => (
                <Link key={product.id} to={`/products/${product.id}`} className="list__item">
                  <Thumb src={product.leadImageUrl} alt="" />
                  <div className="list__main">
                    <div className="list__title">{product.name}</div>
                    <div className="list__meta">
                      {product.sku ?? 'no SKU'}
                      {product.variantCount > 0 && ` · ${product.variantCount} variant${product.variantCount === 1 ? '' : 's'}`}
                      {product.imageCount === 0 && ' · no photos'}
                      {' · '}edited {formatDateTime(product.updatedAt)}
                    </div>
                    <div className="row" style={{ gap: 'var(--space-2)', marginTop: 4 }}>
                      <ProductStatusBadge status={product.status} />
                      {product.lowStock && <Badge tone="warn">Low stock</Badge>}
                      {product.stock === 0 && <Badge tone="danger">Out of stock</Badge>}
                    </div>
                    {product.blockedReason && (
                      <div className="small" style={{ color: 'var(--danger)', marginTop: 4 }}>
                        {product.blockedReason}
                      </div>
                    )}
                  </div>
                  <div className="list__side">
                    <div className="num" style={{ fontWeight: 650 }}>
                      {money(product.price, product.currency)}
                    </div>
                    {product.stock !== null && (
                      <div className="small muted num">{product.stock} in stock</div>
                    )}
                  </div>
                  <span className="list__chevron" aria-hidden="true">›</span>
                </Link>
              ))}
            </div>
            <Pagination
              page={products.data!.page}
              totalPages={products.data!.totalPages}
              totalElements={products.data!.totalElements}
              onChange={setPage}
              unit="listings"
            />
          </>
        )}
      </Card>
    </div>
  );
}
