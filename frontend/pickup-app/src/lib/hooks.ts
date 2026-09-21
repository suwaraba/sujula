import { useCallback, useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { referenceApi } from '@/api/endpoints';
import { newEventId } from '@/api/client';
import type { Currency } from '@/api/types';
import { formatMoney } from './format';

/** The currency catalogue, which is the authority on how many decimals an amount has. */
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
    money: useCallback(
      (amount: number | null | undefined, code: string | null | undefined) =>
        formatMoney(amount, code, lookup),
      [lookup],
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

/** Whether the tablet thinks it has a connection. */
export function useOnline(): boolean {
  const [online, setOnline] = useState(() =>
    typeof navigator === 'undefined' ? true : navigator.onLine !== false,
  );

  useEffect(() => {
    const up = () => setOnline(true);
    const down = () => setOnline(false);
    window.addEventListener('online', up);
    window.addEventListener('offline', down);
    return () => {
      window.removeEventListener('online', up);
      window.removeEventListener('offline', down);
    };
  }, []);

  return online;
}

/**
 * One id for one attempt at one custody event.
 *
 * Held across retries and reset only once the action has gone through, so a
 * second press — or a retry after the shop's wifi dropped mid-request — is
 * recognised as the same event and comes back marked `duplicate` rather than
 * releasing a parcel twice.
 */
export function useEventId(): [string, () => void] {
  const [id, setId] = useState(newEventId);
  return [id, useCallback(() => setId(newEventId()), [])];
}

/** Debounces a value — for a shelf search that should not query per keystroke. */
export function useDebounced<T>(value: T, delayMs = 250): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}
