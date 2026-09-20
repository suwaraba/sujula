/**
 * Server state, and how often it is asked for.
 *
 * There is no websocket on this platform, so "real time" here means polling on
 * a schedule chosen per screen rather than one global interval. That choice is
 * a cost decision as much as a freshness one — every poll is a request on a
 * prepaid bundle the driver paid for, and a radio wake on a battery that may
 * not be charged again until night. So:
 *
 *  - **offers** are polled hard (10s) but only while the driver is on duty and
 *    the app is in the foreground, because an offer expires in under a minute
 *    and a driver who sees it thirty seconds late has already lost it;
 *  - **the parcel in hand** is polled gently (30s), because it changes when the
 *    driver changes it and this app is the thing changing it;
 *  - **earnings and history** are not polled at all. They change when a leg
 *    completes, and the app already knows when that happened.
 *
 * Nothing here is ever persisted by the query cache. These responses carry
 * recipients' addresses and the backend marks every one of them `no-store`; a
 * client-side cache written to disk would undo that on the phone where it
 * matters most.
 */

import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseQueryResult,
} from '@tanstack/react-query';
import { driver } from './endpoints';
import { ApiError } from './http';
import { newId } from '../lib/ids';
import type {
  Assignments,
  DriverProfile,
  Earnings,
  History,
  ShipmentDetail,
} from './types';

export const keys = {
  profile: ['driver', 'profile'] as const,
  assignments: ['driver', 'assignments'] as const,
  shipment: (id: number) => ['driver', 'shipment', id] as const,
  earnings: (from?: string, to?: string) => ['driver', 'earnings', from ?? '', to ?? ''] as const,
  history: (page: number) => ['driver', 'history', page] as const,
};

/**
 * The driver's own record.
 *
 * A 403 here is not an error to retry: it is an account that has applied and is
 * still waiting for dispatch to grant it the delivery role. The screens read
 * that case off the error rather than off a flag, because the backend has no
 * flag for it — `GET /driver/profile` simply is not open to a customer yet.
 */
export function useDriverProfile(enabled = true): UseQueryResult<DriverProfile, ApiError> {
  return useQuery<DriverProfile, ApiError>({
    queryKey: keys.profile,
    queryFn: () => driver.profile(),
    enabled,
    staleTime: 30_000,
    retry: (attempt, error) =>
      attempt < 2 && !(error instanceof ApiError && [401, 403, 404].includes(error.status)),
  });
}

export function useAssignments(online: boolean): UseQueryResult<Assignments, ApiError> {
  return useQuery<Assignments, ApiError>({
    queryKey: keys.assignments,
    queryFn: () => driver.assignments(),
    // Offers lapse in well under a minute. Ten seconds is the difference
    // between a driver taking a job and watching it go to somebody else.
    refetchInterval: online ? 10_000 : false,
    // Not in the background: a phone in a pocket polling every ten seconds is
    // a phone that is flat before the round is over.
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
    staleTime: 5_000,
  });
}

export function useShipment(id: number | null): UseQueryResult<ShipmentDetail, ApiError> {
  return useQuery<ShipmentDetail, ApiError>({
    queryKey: keys.shipment(id ?? -1),
    queryFn: () => driver.shipment(id!),
    enabled: id !== null,
    refetchInterval: 30_000,
    refetchIntervalInBackground: false,
    staleTime: 10_000,
    retry: (attempt, error) =>
      attempt < 2 && !(error instanceof ApiError && [401, 403, 404].includes(error.status)),
  });
}

export function useEarnings(from?: string, to?: string): UseQueryResult<Earnings, ApiError> {
  return useQuery<Earnings, ApiError>({
    queryKey: keys.earnings(from, to),
    queryFn: () => driver.earnings(from, to),
    staleTime: 60_000,
  });
}

export function useHistory(page: number): UseQueryResult<History, ApiError> {
  return useQuery<History, ApiError>({
    queryKey: keys.history(page),
    queryFn: () => driver.history(page, 20),
    staleTime: 60_000,
  });
}

// ── Mutations ────────────────────────────────────────────────────────────────

/**
 * Going on or off duty.
 *
 * Invalidates the offer list on the way out: going online with an empty screen
 * and waiting ten seconds for the first poll reads as "there is no work", which
 * is the wrong thing to tell somebody who has just started their day.
 */
export function useSetAvailability() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (online: boolean) => driver.setAvailability(online),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.profile });
      void client.invalidateQueries({ queryKey: keys.assignments });
    },
  });
}

/**
 * Taking a job.
 *
 * The idempotency key is minted once per attempt and reused if the request is
 * retried, which is what makes a lost response safe: the same key returns the
 * same answer rather than accepting a second leg.
 */
export function useAcceptAssignment() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ legId, key }: { legId: number; key?: string }) =>
      driver.accept(legId, key ?? newId()),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.assignments });
    },
  });
}

export function useDeclineAssignment() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ legId, reason, key }: { legId: number; reason: string; key?: string }) =>
      driver.decline(legId, reason, key ?? newId()),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.assignments });
    },
  });
}

/**
 * Asking for the recipient's code to be sent.
 *
 * The response never contains the code — a driver who could read it could mark
 * a parcel delivered without meeting anybody — so there is nothing to cache and
 * nothing to show but the fact that it went, and to whom, masked.
 */
export function useRequestRecipientCode() {
  return useMutation({
    mutationFn: (shipmentId: number) => driver.requestRecipientCode(shipmentId, newId()),
  });
}

export function useUpdateDriverProfile() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: driver.updateProfile,
    onSuccess: (profile) => {
      client.setQueryData(keys.profile, profile);
    },
  });
}

export function useApply() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ body, key }: { body: Parameters<typeof driver.apply>[0]; key?: string }) =>
      driver.apply(body, key ?? newId()),
    onSuccess: (profile) => {
      client.setQueryData(keys.profile, profile);
    },
  });
}
