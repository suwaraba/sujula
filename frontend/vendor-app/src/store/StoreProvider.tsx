import { createContext, useContext, useMemo, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { storeApi } from '@/api/endpoints/store';
import { useMe } from '@/auth/AuthProvider';
import type { Store, StoreAddress } from '@/api/types';

type StoreContextValue = {
  storeId: number;
  store: Store | null;
  isLoading: boolean;
  /** Approved, verified and not suspended — the same predicate checkout uses. */
  canTrade: boolean;
  /** The seller's own currency. Every listing price and every payout is in it. */
  currency: string;
  /**
   * Where a driver collects, which is where every listing's pin comes from.
   * The pickup address when one is set, the store address otherwise — the same
   * fallback `VendorCatalogueServiceImpl` applies when stamping a new product.
   */
  collectionPoint: StoreAddress | null;
  /** A pin nobody confirmed is a pin a driver gets sent to anyway. */
  pinNeedsConfirming: boolean;
  refresh: () => void;
};

const StoreContext = createContext<StoreContextValue | null>(null);

export function StoreProvider({ children }: { children: ReactNode }) {
  const me = useMe();
  const queryClient = useQueryClient();
  const storeId = me.vendorId!;

  const query = useQuery({
    queryKey: ['store', storeId],
    queryFn: () => storeApi.get(storeId),
    staleTime: 60 * 1000,
  });

  const value = useMemo<StoreContextValue>(() => {
    const store = query.data ?? null;
    const collectionPoint = store?.pickupAddress ?? store?.address ?? null;

    return {
      storeId,
      store,
      isLoading: query.isLoading,
      canTrade: store?.canTrade ?? false,
      currency: store?.settlementCurrency ?? me.resolvedCurrency,
      collectionPoint,
      pinNeedsConfirming: collectionPoint?.needsPinConfirmation ?? false,
      refresh: () => void queryClient.invalidateQueries({ queryKey: ['store', storeId] }),
    };
  }, [query.data, query.isLoading, storeId, me.resolvedCurrency, queryClient]);

  return <StoreContext.Provider value={value}>{children}</StoreContext.Provider>;
}

export function useStore(): StoreContextValue {
  const context = useContext(StoreContext);
  if (!context) throw new Error('useStore must be used inside <StoreProvider>');
  return context;
}
