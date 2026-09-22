import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useMutation, useQueryClient, type QueryKey } from '@tanstack/react-query';
import { useToast } from '@/components/Toast';

/**
 * Filters that live in the URL.
 *
 * An agent who finds something in a queue has to be able to send somebody else
 * to it. Keeping the filters in the query string means a pasted link reopens
 * the same screen, and the back button means what it says.
 */
export function useFilters<F extends Record<string, string | number | boolean | undefined>>(
  defaults: F,
): [F, (patch: Partial<F>) => void, () => void] {
  const [params, setParams] = useSearchParams();

  const value = useMemo(() => {
    const result = { ...defaults } as F;
    for (const key of Object.keys(defaults) as (keyof F)[]) {
      const raw = params.get(String(key));
      if (raw === null) continue;
      const fallback = defaults[key];
      if (typeof fallback === 'number') {
        (result[key] as unknown) = raw === '' ? undefined : Number(raw);
      } else if (typeof fallback === 'boolean' || fallback === undefined) {
        (result[key] as unknown) =
          raw === 'true' ? true : raw === 'false' ? false : raw === '' ? undefined : raw;
      } else {
        (result[key] as unknown) = raw;
      }
    }
    return result;
    // `params` is the only thing that actually varies.
  }, [params, defaults]);

  const update = useCallback(
    (patch: Partial<F>) => {
      const next = new URLSearchParams(params);
      for (const [key, item] of Object.entries(patch)) {
        if (item === undefined || item === '' || item === null) next.delete(key);
        else next.set(key, String(item));
      }
      // Any filter change resets to the first page; leaving page 4 in place
      // after narrowing a search shows an empty screen that looks like no
      // results.
      if (!('page' in patch)) next.delete('page');
      setParams(next, { replace: true });
    },
    [params, setParams],
  );

  const reset = useCallback(() => setParams(new URLSearchParams(), { replace: true }), [setParams]);

  return [value, update, reset];
}

/** Page and size, read from the same place the filters are. */
export function usePaging(defaultSize = 20) {
  const [params, setParams] = useSearchParams();
  const page = Number(params.get('page') ?? 0) || 0;
  const size = Number(params.get('size') ?? defaultSize) || defaultSize;

  const setPage = useCallback(
    (next: number) => {
      const updated = new URLSearchParams(params);
      updated.set('page', String(Math.max(0, next)));
      setParams(updated, { replace: true });
    },
    [params, setParams],
  );

  const setSize = useCallback(
    (next: number) => {
      const updated = new URLSearchParams(params);
      updated.set('size', String(next));
      updated.delete('page');
      setParams(updated, { replace: true });
    },
    [params, setParams],
  );

  return { page, size, setPage, setSize };
}

/**
 * A write, with the two things every write on this surface needs: the queues it
 * touched are refetched, and the person is told in the server's own words what
 * happened.
 *
 * The message comes from the response rather than being written here. The
 * server says "3 products suspended, payouts held" because it knows; a client
 * that writes its own "Saved" hides the cascade.
 */
export function useAction<Input, Output>(
  run: (input: Input) => Promise<Output>,
  options: {
    invalidate?: QueryKey[];
    message?: (output: Output) => string;
    onDone?: (output: Output) => void;
  } = {},
) {
  const queryClient = useQueryClient();
  const toast = useToast();

  return useMutation({
    mutationFn: run,
    onSuccess: (output) => {
      options.invalidate?.forEach((key) => queryClient.invalidateQueries({ queryKey: key }));
      const message = options.message?.(output);
      if (message) toast.ok(message);
      options.onDone?.(output);
    },
  });
}

/** Query keys, in one place so an invalidation cannot miss a screen. */
export const keys = {
  dashboard: ['dashboard'] as const,
  orders: ['orders'] as const,
  order: (id: number) => ['orders', id] as const,
  shipments: ['shipments'] as const,
  unassigned: ['shipments', 'unassigned'] as const,
  custody: (id: number) => ['shipments', id, 'custody'] as const,
  payments: ['payments'] as const,
  payment: (id: number) => ['payments', id] as const,
  ledger: ['ledger'] as const,
  reconciliation: ['ledger', 'reconciliation'] as const,
  balances: ['balances'] as const,
  batches: ['payouts'] as const,
  batch: (id: number) => ['payouts', id] as const,
  fxRates: ['fx', 'rates'] as const,
  fxSpread: ['fx', 'spread'] as const,
  revenue: ['reports', 'revenue'] as const,
  exports: ['reports', 'exports'] as const,
  disputes: ['disputes'] as const,
  dispute: (id: number) => ['disputes', id] as const,
  callbacks: ['callbacks'] as const,
  stores: ['stores'] as const,
  kyc: ['kyc'] as const,
  products: ['products'] as const,
  reviews: ['reviews'] as const,
  cases: ['cases'] as const,
  users: ['users'] as const,
  user: (id: number) => ['users', id] as const,
  drivers: ['drivers'] as const,
  pickupPoints: ['pickup-points'] as const,
  zones: ['zones'] as const,
  zone: (id: number) => ['zones', id] as const,
  rateCards: ['rate-cards'] as const,
  ratePreview: ['rate-cards', 'preview'] as const,
  flags: ['feature-flags'] as const,
  jobs: ['jobs'] as const,
  jobHistory: ['jobs', 'history'] as const,
  audit: ['audit'] as const,
  currencies: ['reference', 'currencies'] as const,
  countries: ['reference', 'countries'] as const,
  legacyPayment: (orderId: number) => ['legacy-payment', orderId] as const,
};
