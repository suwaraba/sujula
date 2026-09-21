import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { catalogueApi } from '@/api/endpoints/catalogue';
import { useCategories, useDebounced } from '@/lib/hooks';
import { flattenCategories } from '@/lib/catalogue';
import { Badge, Notice, Skeleton } from './ui';

export type Scope =
  | { kind: 'store' }
  | { kind: 'categories'; categoryIds: number[] }
  | { kind: 'products'; productIds: number[] };

/**
 * What a promotion covers.
 *
 * The server treats "names neither products nor categories" as the whole shop,
 * which is a sensible default and a terrible thing to arrive at by accident —
 * a seller who meant to pick three phones and picked none has just discounted
 * everything. So the three cases are made explicit and the store-wide one says
 * out loud what it means.
 */
export function ScopePicker({ value, onChange }: { value: Scope; onChange: (next: Scope) => void }) {
  const [search, setSearch] = useState('');
  const debounced = useDebounced(search);
  const categories = useCategories();

  const products = useQuery({
    queryKey: ['products', 'picker', debounced],
    queryFn: () =>
      catalogueApi.list({
        ...(debounced.trim() ? { search: debounced.trim() } : {}),
        page: 0,
        size: 50,
      }),
    enabled: value.kind === 'products',
  });

  const toggle = (list: number[], id: number) =>
    list.includes(id) ? list.filter((x) => x !== id) : [...list, id];

  return (
    <fieldset
      className="stack stack--tight"
      style={{ border: 'none', margin: 0, padding: 0, minInlineSize: 0 }}
    >
      <legend className="field__label" style={{ padding: 0 }}>What it applies to</legend>

      <div className="tabs" style={{ marginBottom: 0 }}>
        <button
          type="button"
          className={value.kind === 'store' ? 'tabs__item is-active' : 'tabs__item'}
          onClick={() => onChange({ kind: 'store' })}
        >
          Everything
        </button>
        <button
          type="button"
          className={value.kind === 'categories' ? 'tabs__item is-active' : 'tabs__item'}
          onClick={() => onChange({ kind: 'categories', categoryIds: [] })}
        >
          Categories
        </button>
        <button
          type="button"
          className={value.kind === 'products' ? 'tabs__item is-active' : 'tabs__item'}
          onClick={() => onChange({ kind: 'products', productIds: [] })}
        >
          Chosen listings
        </button>
      </div>

      {value.kind === 'store' && (
        <Notice tone="warn">
          Every listing in your shop, including ones you add later.
        </Notice>
      )}

      {value.kind === 'categories' && (
        <div className="stack stack--tight">
          {categories.isLoading ? (
            <Skeleton height={120} />
          ) : (
            <div style={{ maxHeight: 220, overflowY: 'auto' }}>
              {flattenCategories(categories.data?.categories).map(({ id, label }) => (
                <label key={id} className="checkbox" style={{ minHeight: 36 }}>
                  <input
                    type="checkbox"
                    style={{ marginTop: 8 }}
                    checked={value.categoryIds.includes(id)}
                    onChange={() =>
                      onChange({ kind: 'categories', categoryIds: toggle(value.categoryIds, id) })
                    }
                  />
                  <span className="checkbox__text" style={{ paddingTop: 6 }}>{label}</span>
                </label>
              ))}
            </div>
          )}
          {value.categoryIds.length === 0 && (
            <Notice tone="danger">
              Pick at least one, or this covers your whole shop.
            </Notice>
          )}
        </div>
      )}

      {value.kind === 'products' && (
        <div className="stack stack--tight">
          <input
            className="input"
            type="search"
            placeholder="Search your listings"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            aria-label="Search your listings"
          />
          {products.isLoading ? (
            <Skeleton height={140} />
          ) : (
            <div style={{ maxHeight: 240, overflowY: 'auto' }}>
              {(products.data?.items ?? []).map((product) => (
                <label key={product.id} className="checkbox" style={{ minHeight: 36 }}>
                  <input
                    type="checkbox"
                    style={{ marginTop: 8 }}
                    checked={value.productIds.includes(product.id)}
                    onChange={() =>
                      onChange({ kind: 'products', productIds: toggle(value.productIds, product.id) })
                    }
                  />
                  <span className="checkbox__text" style={{ paddingTop: 6 }}>
                    {product.name}
                    <span className="checkbox__hint">{product.sku ?? 'no SKU'}</span>
                  </span>
                </label>
              ))}
              {(products.data?.items.length ?? 0) === 0 && (
                <p className="muted small">Nothing matched.</p>
              )}
            </div>
          )}
          <div className="row">
            <Badge tone={value.productIds.length > 0 ? 'accent' : 'neutral'}>
              {value.productIds.length} chosen
            </Badge>
            {value.productIds.length === 0 && (
              <span className="small" style={{ color: 'var(--danger)' }}>
                Pick at least one, or this covers your whole shop.
              </span>
            )}
          </div>
        </div>
      )}
    </fieldset>
  );
}

/** The ids a scope sends, which is nothing at all for a store-wide one. */
export function scopeToIds(scope: Scope): { productIds?: number[]; categoryIds?: number[] } {
  if (scope.kind === 'categories' && scope.categoryIds.length > 0) {
    return { categoryIds: scope.categoryIds };
  }
  if (scope.kind === 'products' && scope.productIds.length > 0) {
    return { productIds: scope.productIds };
  }
  return {};
}

/** Reads a saved promotion back into the picker's three cases. */
export function scopeFrom(
  productIds: number[] | null | undefined,
  categoryIds: number[] | null | undefined,
): Scope {
  if (productIds?.length) return { kind: 'products', productIds: [...productIds] };
  if (categoryIds?.length) return { kind: 'categories', categoryIds: [...categoryIds] };
  return { kind: 'store' };
}
