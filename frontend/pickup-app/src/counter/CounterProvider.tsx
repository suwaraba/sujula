import {
  createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode,
} from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { pickupApi } from '@/api/endpoints';
import { readStored, writeStored } from '@/lib/storage';
import type { OperatorPoint, Parcels } from '@/api/types';

const LAST_POINT_KEY = 'sujula.counter.lastPointId';

type CounterValue = {
  points: OperatorPoint[];
  point: OperatorPoint | null;
  pointId: number | null;
  choosePoint: (pointId: number) => void;
  parcels: Parcels | undefined;
  parcelsLoading: boolean;
  /** Approved and open: the server refuses custody actions otherwise. */
  canTakeParcels: boolean;
  /** Why it cannot, in words an operator can act on. */
  blockedReason: string | null;
  refresh: () => void;
};

const CounterContext = createContext<CounterValue | null>(null);

export function CounterProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();

  const pointsQuery = useQuery({
    queryKey: ['my-points'],
    queryFn: () => pickupApi.myPoints(),
    staleTime: 60_000,
  });

  const points = useMemo(() => pointsQuery.data?.points ?? [], [pointsQuery.data]);

  // Which counter is open. Remembered per device rather than per account,
  // because the tablet belongs to the shop — it is the same counter every
  // morning, and picking it again daily is a tax on the common case.
  const [chosenId, setChosenId] = useState<number | null>(() => {
    const stored = readStored(LAST_POINT_KEY);
    const parsed = stored ? Number(stored) : NaN;
    return Number.isFinite(parsed) ? parsed : null;
  });

  /**
   * Which counter this tablet is working.
   *
   * The remembered one wins. Falling back to `points[0]` would not do: an
   * operator who runs two counters and has one closed for a funeral would open
   * the app to the closed one and find every button refused. So the fallback
   * prefers a counter that can actually take a parcel right now.
   */
  const point = useMemo(() => {
    const remembered = points.find((candidate) => candidate.id === chosenId);
    if (remembered) return remembered;

    const workable = points.find((candidate) => {
      const approved = candidate.status === 'APPROVED' || candidate.status === 'ACTIVE';
      const closedUntil = candidate.closedUntil
        ? new Date(candidate.closedUntil.slice(0, 19)).getTime()
        : 0;
      return approved && candidate.active && closedUntil <= Date.now();
    });

    return workable ?? points[0] ?? null;
  }, [points, chosenId]);

  useEffect(() => {
    if (point && point.id !== chosenId) setChosenId(point.id);
  }, [point, chosenId]);

  const choosePoint = useCallback((pointId: number) => {
    setChosenId(pointId);
    writeStored(LAST_POINT_KEY, String(pointId));
  }, []);

  const parcelsQuery = useQuery({
    queryKey: ['parcels', point?.id],
    queryFn: () => pickupApi.parcels(point!.id),
    enabled: point != null,
    // A driver can arrive at any moment and a shelf changes under the
    // operator's hands, so this is polled rather than left to go stale.
    refetchInterval: 45_000,
    staleTime: 15_000,
  });

  const value = useMemo<CounterValue>(() => {
    const closedUntil = point?.closedUntil ? new Date(point.closedUntil.slice(0, 19)) : null;
    const closedNow = closedUntil != null && closedUntil.getTime() > Date.now();
    const approved = point?.status === 'APPROVED' || point?.status === 'ACTIVE';

    let blockedReason: string | null = null;
    if (!point) blockedReason = 'No counter is open.';
    else if (!approved) blockedReason = `This counter is ${point.status.replace(/_/g, ' ').toLowerCase()}.`;
    else if (closedNow) blockedReason = point.closureReason ?? 'This counter is closed.';
    else if (!point.active) blockedReason = 'This counter is switched off.';

    return {
      points,
      point,
      pointId: point?.id ?? null,
      choosePoint,
      parcels: parcelsQuery.data,
      parcelsLoading: parcelsQuery.isLoading,
      canTakeParcels: blockedReason == null,
      blockedReason,
      refresh: () => {
        void queryClient.invalidateQueries({ queryKey: ['parcels'] });
        void queryClient.invalidateQueries({ queryKey: ['my-points'] });
      },
    };
  }, [points, point, choosePoint, parcelsQuery.data, parcelsQuery.isLoading, queryClient]);

  return <CounterContext.Provider value={value}>{children}</CounterContext.Provider>;
}

export function useCounter(): CounterValue {
  const context = useContext(CounterContext);
  if (!context) throw new Error('useCounter must be used inside <CounterProvider>');
  return context;
}
