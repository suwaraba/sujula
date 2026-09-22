import { api } from '../client';
import type {
  Accepted, ImeiAssigned, OrderDetail, OrderStats, OrderSummary,
  Paged, ReadyResult, Rejected, ReleaseCode, VendorOrderStatus,
} from '../types';

/**
 * The seller's rung of the custody chain.
 *
 * It ends at READY_FOR_PICKUP. SHIPPED is what a driver presenting the release
 * code produces, and DELIVERED is what the recipient proves with the code sent
 * to their phone — neither is a button on this surface, and there is no call
 * here that would let a seller assert either. That is C4: the status is a
 * consequence of evidence, not an input.
 */
export const ordersApi = {
  list: (params: { status?: VendorOrderStatus; page?: number; size?: number }) =>
    api.get<Paged<OrderSummary>>('/vendor/orders', { query: params }),

  stats: () => api.get<OrderStats>('/vendor/orders/stats'),

  get: (vendorOrderId: number) => api.get<OrderDetail>(`/vendor/orders/${vendorOrderId}`),

  /** Idempotent by design: a double-tapped button on one bar of signal is the ordinary case. */
  accept: (vendorOrderId: number, idempotencyKey?: string) =>
    api.post<Accepted>(`/vendor/orders/${vendorOrderId}/accept`, undefined, {
      idempotent: idempotencyKey ?? true,
    }),

  /**
   * Cancels this seller's slice only — another vendor's lines on the same
   * payment are untouched (C3) — returns the goods to the shelf and *requests*
   * the buyer's refund. Requests it: money leaving the platform is an
   * administrator's decision.
   */
  reject: (vendorOrderId: number, reason: string, idempotencyKey?: string) =>
    api.post<Rejected>(`/vendor/orders/${vendorOrderId}/reject`, { reason }, {
      idempotent: idempotencyKey ?? true,
    }),

  /** Refused while a handset-tracked line still has a phone to scan. */
  ready: (vendorOrderId: number, idempotencyKey?: string) =>
    api.post<ReadyResult>(`/vendor/orders/${vendorOrderId}/ready`, undefined, {
      idempotent: idempotencyKey ?? true,
    }),

  releaseCode: (vendorOrderId: number) =>
    api.get<ReleaseCode>(`/vendor/orders/${vendorOrderId}/handoff-code`),

  /** Rate limited server-side: the reason to reissue is also the reason to farm them. */
  regenerateReleaseCode: (vendorOrderId: number, idempotencyKey?: string) =>
    api.post<ReleaseCode>(`/vendor/orders/${vendorOrderId}/handoff-code/regenerate`, undefined, {
      idempotent: idempotencyKey ?? true,
    }),

  /** An A6 PDF: recipient, destination town, signed QR. No street, no prices, no code. */
  label: (vendorOrderId: number) =>
    api.get<Blob>(`/vendor/orders/${vendorOrderId}/label`, { responseType: 'blob' }),

  assignImei: (vendorOrderId: number, lineId: number, imei: string, idempotencyKey?: string) =>
    api.post<ImeiAssigned>(
      `/vendor/orders/${vendorOrderId}/lines/${lineId}/assign-imei`,
      { imei },
      { idempotent: idempotencyKey ?? true },
    ),

  /**
   * The older status endpoint, kept for CANCELLED before anything has moved.
   * Accept and ready go through their own verbs above, which carry idempotency
   * and issue the release code; this one does not.
   */
  setStatus: (vendorOrderId: number, status: VendorOrderStatus) =>
    api.patch<OrderDetail>(`/vendor/orders/${vendorOrderId}/status`, undefined, {
      query: { status },
    }),
};
