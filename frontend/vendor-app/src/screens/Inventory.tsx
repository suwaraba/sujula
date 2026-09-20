import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { inventoryApi } from '@/api/endpoints/inventory';
import { ApiError } from '@/api/errors';
import { useCurrencies, useDebounced } from '@/lib/hooks';
import { formatDateTime } from '@/lib/format';
import { STOCK_REASONS } from '@/lib/catalogue';
import type { InventoryItem } from '@/api/types';
import { Badge, Button, Card, EmptyState, Notice, PageHeader, SkeletonList } from '@/components/ui';
import { SelectField, TextArea, TextField } from '@/components/form';
import { Sheet } from '@/components/Sheet';
import { Pagination } from '@/components/Pagination';
import { useToast } from '@/components/Toast';

const FILTERS = [
  { label: 'Everything', lowStock: false, outOfStock: false },
  { label: 'Running low', lowStock: true, outOfStock: false },
  { label: 'Out of stock', lowStock: false, outOfStock: true },
] as const;

export function Inventory() {
  const [filter, setFilter] = useState(0);
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState('');
  const [adjusting, setAdjusting] = useState<InventoryItem | null>(null);
  const debouncedSearch = useDebounced(search);
  const { money } = useCurrencies();

  const active = FILTERS[filter]!;

  const inventory = useQuery({
    queryKey: ['inventory', { ...active, search: debouncedSearch, page }],
    queryFn: () =>
      inventoryApi.list({
        ...(active.lowStock ? { lowStock: true } : {}),
        ...(active.outOfStock ? { outOfStock: true } : {}),
        ...(debouncedSearch.trim() ? { search: debouncedSearch.trim() } : {}),
        page,
        size: 25,
      }),
  });

  return (
    <div className="page stack">
      <PageHeader
        title="Stock"
        subtitle="Every change is recorded with a reason, so you can read back what happened."
      />

      <div className="tabs" role="tablist">
        {FILTERS.map((entry, index) => (
          <button
            key={entry.label}
            type="button"
            role="tab"
            aria-selected={index === filter}
            className={index === filter ? 'tabs__item is-active' : 'tabs__item'}
            onClick={() => { setFilter(index); setPage(0); }}
          >
            {entry.label}
            {index === 1 && (inventory.data?.lowStockCount ?? 0) > 0 && (
              <span className="tabs__count">{inventory.data!.lowStockCount}</span>
            )}
            {index === 2 && (inventory.data?.outOfStockCount ?? 0) > 0 && (
              <span className="tabs__count">{inventory.data!.outOfStockCount}</span>
            )}
          </button>
        ))}
      </div>

      <input
        className="input"
        type="search"
        placeholder="Search by name or SKU"
        value={search}
        onChange={(event) => { setSearch(event.target.value); setPage(0); }}
        aria-label="Search stock"
      />

      <Card flush>
        {inventory.isLoading ? (
          <SkeletonList rows={6} />
        ) : (inventory.data?.items.length ?? 0) === 0 ? (
          <EmptyState icon="▤" title="Nothing here">
            {filter === 0
              ? 'Stock appears once you have a listing with a variant.'
              : 'Nothing at this level right now — which is the good answer.'}
          </EmptyState>
        ) : (
          <>
            <div className="list">
              {inventory.data!.items.map((item) => (
                <div key={item.variantId} className="list__item">
                  <div className="list__main">
                    <div className="list__title">{item.productName}</div>
                    <div className="list__meta">
                      {item.variantLabel ?? item.sku ?? '—'} · {money(item.price, item.currency)}
                      {item.lastMovementAt && ` · moved ${formatDateTime(item.lastMovementAt)}`}
                    </div>
                    <div className="row" style={{ gap: 'var(--space-2)', marginTop: 4 }}>
                      {item.outOfStock ? (
                        <Badge tone="danger">Out of stock</Badge>
                      ) : item.lowStock ? (
                        <Badge tone="warn">Running low</Badge>
                      ) : null}
                      {item.allowBackorder && <Badge tone="info">Backorders on</Badge>}
                      {item.serialised && (
                        <Badge tone="accent">
                          {item.sellableUnits ?? 0} handset{item.sellableUnits === 1 ? '' : 's'}
                        </Badge>
                      )}
                      {!item.live && <Badge>Not on sale</Badge>}
                    </div>
                  </div>
                  <div className="list__side">
                    <div className="num" style={{ fontWeight: 700, fontSize: 19 }}>{item.stock}</div>
                    <div className="small muted">in stock</div>
                  </div>
                  <div className="row" style={{ gap: 4, flexWrap: 'nowrap' }}>
                    <Button size="sm" variant="secondary" onClick={() => setAdjusting(item)}>
                      {item.serialised ? 'Details' : 'Change'}
                    </Button>
                    <Link
                      to={`/inventory/${item.variantId}/history`}
                      className="btn btn--ghost btn--sm"
                    >
                      History
                    </Link>
                  </div>
                </div>
              ))}
            </div>
            <Pagination
              page={inventory.data!.page}
              totalPages={inventory.data!.totalPages}
              totalElements={inventory.data!.totalElements}
              onChange={setPage}
              unit="items"
            />
          </>
        )}
      </Card>

      {adjusting && (
        <AdjustSheet item={adjusting} onClose={() => setAdjusting(null)} />
      )}
    </div>
  );
}

/**
 * Changing one item's stock.
 *
 * Two ways in — "I counted them" and "this many arrived or left" — because those
 * are two different claims and conflating them loses the difference between a
 * correction and a movement.
 *
 * The `version` read off the row goes back with the change. If somebody else
 * moved the same stock while this sheet was open, the server refuses rather
 * than silently overwriting them, and the seller is told to look again.
 */
function AdjustSheet({ item, onClose }: { item: InventoryItem; onClose: () => void }) {
  const queryClient = useQueryClient();
  const toast = useToast();

  const [mode, setMode] = useState<'set' | 'delta'>('set');
  const [setTo, setSetTo] = useState(String(item.stock));
  const [delta, setDelta] = useState('');
  const [reason, setReason] = useState('RESTOCK');
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(null);

  const adjust = useMutation({
    mutationFn: () =>
      inventoryApi.adjust(item.variantId, {
        ...(mode === 'set' ? { setTo: Number(setTo) } : { delta: Number(delta) }),
        version: item.version,
        reason,
        ...(note.trim() ? { note: note.trim() } : {}),
      }),
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: ['inventory'] });
      void queryClient.invalidateQueries({ queryKey: ['products'] });
      toast.success(
        `${item.productName}: ${result.stockBefore} → ${result.stockAfter}.`,
      );
      onClose();
    },
    onError: (cause) => {
      if (cause instanceof ApiError && cause.isConflict) {
        setError(
          'Somebody else changed this stock while you had this open. Close it, look at the ' +
          'current number, and make the change again.',
        );
      } else {
        setError(cause instanceof ApiError ? cause.message : 'Could not change the stock.');
      }
    },
  });

  const valid =
    mode === 'set'
      ? setTo.trim() !== '' && Number(setTo) >= 0
      : delta.trim() !== '' && Number(delta) !== 0;

  const projected =
    mode === 'set' ? Number(setTo) : item.stock + (Number(delta) || 0);

  return (
    <Sheet
      title={item.productName}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={adjust.isPending}>
            {item.serialised ? 'Close' : 'Cancel'}
          </Button>
          {!item.serialised && (
            <Button
              variant="primary"
              onClick={() => adjust.mutate()}
              busy={adjust.isPending}
              disabled={!valid}
            >
              Save the change
            </Button>
          )}
        </>
      }
    >
      <div className="stack">
        {error && <Notice tone="danger">{error}</Notice>}

        <div className="row row--between">
          <span className="muted">{item.variantLabel ?? item.sku ?? '—'}</span>
          <span className="num" style={{ fontWeight: 700 }}>{item.stock} now</span>
        </div>

        {item.serialised ? (
          <Notice tone="warn" title="This item is counted in handsets">
            Its stock is however many handsets are registered and still on the shelf — there is no
            number to type. Register a handset, sell it, or write it off, and the count follows.
          </Notice>
        ) : null}

        <div className="tabs" style={{ marginBottom: 0 }} hidden={item.serialised}>
          <button
            type="button"
            className={mode === 'set' ? 'tabs__item is-active' : 'tabs__item'}
            onClick={() => setMode('set')}
          >
            I counted them
          </button>
          <button
            type="button"
            className={mode === 'delta' ? 'tabs__item is-active' : 'tabs__item'}
            onClick={() => setMode('delta')}
          >
            Some arrived or left
          </button>
        </div>

        {item.serialised ? null : mode === 'set' ? (
          <TextField
            label="How many there actually are"
            type="number" inputMode="numeric" min="0"
            value={setTo}
            onChange={(event) => setSetTo(event.target.value)}
            autoFocus
          />
        ) : (
          <TextField
            label="Change by"
            hint="A positive number for stock arriving, a negative one for stock leaving."
            type="number" inputMode="numeric"
            value={delta}
            onChange={(event) => setDelta(event.target.value)}
            placeholder="+10 or -3"
            autoFocus
          />
        )}

        {!item.serialised && valid && (
          <Notice tone={projected < 0 ? 'danger' : 'info'}>
            {projected < 0
              ? 'That would take the stock below zero.'
              : <>New stock level: <strong className="num">{projected}</strong></>}
          </Notice>
        )}

        {!item.serialised && (
          <>
            <SelectField
              label="Why" value={reason} onChange={(event) => setReason(event.target.value)} required
            >
              {STOCK_REASONS.map(({ value, label }) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </SelectField>

            <TextArea
              label="Note" hint="Optional — for you, when you read this back in six months."
              value={note} onChange={(event) => setNote(event.target.value)}
              maxLength={300} rows={2}
            />
          </>
        )}
      </div>
    </Sheet>
  );
}
