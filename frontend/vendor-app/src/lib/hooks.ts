import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { referenceApi } from '@/api/endpoints/reference';
import type { Currency } from '@/api/types';
import { amountStep, formatMoney } from './format';

/**
 * The currency catalogue, which is the authority on how many decimal places an
 * amount has. Cached for the session: it changes when the platform adds a
 * currency, not while a seller is packing a box.
 */
export function useCurrencies() {
  const query = useQuery({
    queryKey: ['currencies'],
    queryFn: () => referenceApi.currencies(),
    staleTime: 60 * 60 * 1000,
    gcTime: 24 * 60 * 60 * 1000,
  });

  const byCode = useMemo(() => {
    const map = new Map<string, Currency>();
    for (const currency of query.data?.currencies ?? []) map.set(currency.code, currency);
    return map;
  }, [query.data]);

  const lookup = useCallback((code: string) => byCode.get(code.toUpperCase()), [byCode]);

  return {
    ...query,
    lookup,
    /** Formats at the currency's real scale — no minor units for XOF. */
    money: useCallback(
      (amount: number | null | undefined, code: string | null | undefined, withCode = false) =>
        formatMoney(amount, code, lookup, { withCode }),
      [lookup],
    ),
    step: useCallback((code: string | null | undefined) => amountStep(code, lookup), [lookup]),
    settlementCurrencies: useMemo(
      () => (query.data?.currencies ?? []).filter((c) => c.settlement),
      [query.data],
    ),
  };
}

export function useCountries() {
  return useQuery({
    queryKey: ['countries'],
    queryFn: () => referenceApi.countries(),
    staleTime: 60 * 60 * 1000,
  });
}

export function useCategories() {
  return useQuery({
    queryKey: ['categories'],
    queryFn: () => referenceApi.categories(),
    staleTime: 30 * 60 * 1000,
  });
}

/** Ticks once a second while `active`. Drives the release-code countdown and nothing else. */
export function useTicker(active: boolean): number {
  const [tick, setTick] = useState(0);
  useEffect(() => {
    if (!active) return;
    const id = setInterval(() => setTick((t) => t + 1), 1000);
    return () => clearInterval(id);
  }, [active]);
  return tick;
}

/** Debounces a value — for a search box that would otherwise query on every keystroke. */
export function useDebounced<T>(value: T, delayMs = 350): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(id);
  }, [value, delayMs]);
  return debounced;
}

/**
 * A stable idempotency key for one attempt at one action.
 *
 * Minted once per mount and reset only when the action succeeds, so a seller
 * who taps "Mark ready" twice on a bad connection issues one release code
 * rather than two — the second call is recognised as the same action and
 * returns the same answer.
 */
export function useIdempotencyKey(): [string, () => void] {
  const mint = () =>
    globalThis.crypto?.randomUUID?.() ??
    `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 14)}`;

  const [key, setKey] = useState(mint);
  const reset = useCallback(() => setKey(mint()), []);
  return [key, reset];
}

/** True after the first render — to skip an effect that should not run on mount. */
export function useIsMounted(): () => boolean {
  const mounted = useRef(false);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  return useCallback(() => mounted.current, []);
}
