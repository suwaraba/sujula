import { http, newIdempotencyKey } from './client';
import type * as Req from './requests';
import type * as T from './types';
import type {
  AuditAction,
  DeliveryMode,
  DisputeReason,
  DisputeStatus,
  DriverStatus,
  KycDocumentStatus,
  LedgerEntryType,
  ModerationCaseStatus,
  ModerationReason,
  PartnerStatus,
  PaymentStatus,
  PayoutBatchStatus,
  ProductStatus,
  ReportExportStatus,
  ReportType,
  ShipmentStatus,
  UserRole,
} from './enums';

/**
 * Every endpoint this console calls, one function each.
 *
 * Grouped the way the server groups them, so the six `Admin*Controller`s and
 * the six objects below are the same list read twice. Nothing above this layer
 * builds a URL or picks a verb.
 *
 * Writes that the server idempotency-guards send a fresh `Idempotency-Key` per
 * attempt. That is what stops a retry after a timeout from releasing a second
 * payout batch or placing a second order — the server replays the first
 * answer instead of doing the work again.
 */

function idem() {
  return { idempotencyKey: newIdempotencyKey() };
}

export interface Page {
  page?: number;
  size?: number;
}

// ── Auth and identity ────────────────────────────────────────────────────────

export const auth = {
  login: (body: {
    email: string;
    password: string;
    totpCode?: string;
    recoveryCode?: string;
    deviceLabel?: string;
  }) => http.post<T.LoginResult>('/auth/login', body, { skipAuthRetry: true }),

  logout: (refreshToken: string | null) =>
    http.post<{ message: string }>('/auth/logout', { refreshToken }, { skipAuthRetry: true }),

  logoutAll: () => http.post<{ message: string }>('/auth/logout-all', undefined, { skipAuthRetry: true }),

  changePassword: (body: {
    currentPassword: string;
    newPassword: string;
    keepOtherSessions?: boolean;
  }) => http.post<{ message: string }>('/auth/password/change', body),

  mfaSetup: () => http.post<{ secret: string; provisioningUri: string; issuer: string }>('/auth/mfa/setup'),

  mfaActivate: (code: string) =>
    http.post<{ recoveryCodes: string[]; remaining: number }>('/auth/mfa/activate', { code }),

  mfaDisable: (password: string) => http.del<{ message: string }>('/auth/mfa', { body: { password } }),

  regenerateRecoveryCodes: (password: string) =>
    http.post<{ recoveryCodes: string[]; remaining: number }>('/auth/mfa/recovery-codes', { password }),
};

export const me = {
  get: () => http.get<T.Me>('/me'),
  permissions: () => http.get<{ role: UserRole; permissions: string[]; canTrade: boolean }>('/me/permissions'),
  sessions: () => http.get<T.SessionRow[]>('/me/sessions'),
  revokeSession: (sessionId: number) => http.del<void>(`/me/sessions/${sessionId}`),
};

export const reference = {
  currencies: () => http.get<T.CurrenciesResponse>('/currencies'),
  countries: () => http.get<T.CountriesResponse>('/countries'),
};

// ── /admin/dashboard ─────────────────────────────────────────────────────────

export const dashboard = {
  get: () => http.get<T.Dashboard>('/admin/dashboard'),
};

// ── /admin — dispatch: orders ────────────────────────────────────────────────

export interface OrderSearch extends Page {
  q?: string;
  status?: string;
  /** Delivery context. There is deliberately no payer-country filter here. */
  destinationCountry?: string;
  vendorId?: number;
  stuckForHours?: number;
}

export const orders = {
  search: (query: OrderSearch) => http.get<T.PagedResponse<T.OrderRow>>('/admin/orders', query),

  detail: (orderId: number) => http.get<T.OrderDetail>(`/admin/orders/${orderId}`),

  placeOnBehalf: (body: Req.PlaceOrderOnBehalfRequest) =>
    http.post<T.OrderPlaced>('/admin/orders', body, idem()),

  forceCancel: (orderId: number, body: Req.ForceCancelOrderRequest) =>
    http.post<T.OrderCancelled>(`/admin/orders/${orderId}/cancel`, body, idem()),

  forceStatus: (orderId: number, vendorOrderId: number, body: Req.ForceStatusRequest) =>
    http.post<T.StatusForced>(
      `/admin/orders/${orderId}/vendor-orders/${vendorOrderId}/force-status`,
      body,
      idem(),
    ),
};

// ── /admin — dispatch: shipments ─────────────────────────────────────────────

export interface ShipmentSearch extends Page {
  status?: ShipmentStatus;
  country?: string;
  driverId?: number;
  waitingOverHours?: number;
}

export const shipments = {
  search: (query: ShipmentSearch) => http.get<T.PagedResponse<T.ShipmentRow>>('/admin/shipments', query),

  unassigned: (limit?: number) =>
    http.get<T.UnassignedShipment[]>('/admin/shipments/unassigned', { limit }),

  assign: (shipmentId: number, body: Req.AssignShipmentRequest) =>
    http.post<T.AssignmentMade>(`/admin/shipments/${shipmentId}/assign`, body, idem()),

  unassign: (shipmentId: number, body: Req.UnassignShipmentRequest) =>
    http.post<T.AssignmentRemoved>(`/admin/shipments/${shipmentId}/unassign`, body, idem()),

  reassign: (shipmentId: number, body: Req.ReassignShipmentRequest) =>
    http.post<T.AssignmentMade>(`/admin/shipments/${shipmentId}/reassign`, body, idem()),

  cancel: (shipmentId: number, body: Req.CancelShipmentRequest) =>
    http.post<T.ShipmentCancelled>(`/admin/shipments/${shipmentId}/cancel`, body, idem()),

  custodyChain: (shipmentId: number) =>
    http.get<T.CustodyChainView>(`/admin/shipments/${shipmentId}/custody-chain`),

  /**
   * Records a link in the chain that could not be proven the ordinary way.
   *
   * Step-up guarded, attested to a named person, and marked `overridden` on the
   * event forever after. The chain keeps its hole visible rather than closing
   * over it.
   */
  overrideHandoff: (shipmentId: number, body: Req.OverrideHandoffRequest) =>
    http.post<T.HandoffOverridden>(`/admin/shipments/${shipmentId}/override-handoff`, body, idem()),
};

// ── /admin — moderation ──────────────────────────────────────────────────────

export interface StoreSearch extends Page {
  q?: string;
  status?: PartnerStatus;
  country?: string;
  payoutsHeld?: boolean;
}

export const stores = {
  search: (query: StoreSearch) => http.get<T.PagedResponse<T.StoreRow>>('/admin/stores', query),

  approve: (vendorId: number, body: Req.ApproveStoreRequest) =>
    http.post<T.StoreDecision>(`/admin/stores/${vendorId}/approve`, body, idem()),

  reject: (vendorId: number, body: Req.RejectStoreRequest) =>
    http.post<T.StoreDecision>(`/admin/stores/${vendorId}/reject`, body, idem()),

  suspend: (vendorId: number, body: Req.SuspendStoreRequest) =>
    http.post<T.StoreDecision>(`/admin/stores/${vendorId}/suspend`, body, idem()),

  changeCommission: (vendorId: number, body: Req.ChangeCommissionRequest) =>
    http.patch<T.CommissionChanged>(`/admin/stores/${vendorId}/commission`, body),
};

export interface KycSearch extends Page {
  status?: KycDocumentStatus;
  vendorId?: number;
}

export const kyc = {
  queue: (query: KycSearch) => http.get<T.PagedResponse<T.KycRow>>('/admin/kyc/queue', query),

  approve: (documentId: number, body: Req.ApproveKycRequest) =>
    http.post<T.KycDecision>(`/admin/kyc/${documentId}/approve`, body, idem()),

  reject: (documentId: number, body: Req.RejectKycRequest) =>
    http.post<T.KycDecision>(`/admin/kyc/${documentId}/reject`, body, idem()),
};

export interface ProductSearch extends Page {
  status?: ProductStatus;
  vendorId?: number;
}

export const products = {
  queue: (query: ProductSearch) =>
    http.get<T.PagedResponse<T.ProductRow>>('/admin/moderation/products', query),

  approve: (productId: number, body: Req.ApproveProductRequest) =>
    http.post<T.ProductDecision>(`/admin/products/${productId}/approve`, body, idem()),

  reject: (productId: number, body: Req.RejectProductRequest) =>
    http.post<T.ProductDecision>(`/admin/products/${productId}/reject`, body, idem()),

  suspend: (productId: number, body: Req.SuspendProductRequest) =>
    http.post<T.ProductDecision>(`/admin/products/${productId}/suspend`, body, idem()),

  create: (body: Req.CreateProductRequest) =>
    http.post<T.ProductDecision>('/admin/products', body, idem()),

  patch: (productId: number, body: Req.PatchProductRequest) =>
    http.patch<T.ProductDecision>(`/admin/products/${productId}`, body),
};

export interface ReviewSearch extends Page {
  hiddenOnly?: boolean;
}

export const reviews = {
  queue: (query: ReviewSearch) =>
    http.get<T.PagedResponse<T.ReviewRow>>('/admin/moderation/reviews', query),

  publish: (reviewId: number, body: Req.ModerateReviewRequest) =>
    http.post<T.ReviewDecision>(`/admin/reviews/${reviewId}/publish`, body, idem()),

  reject: (reviewId: number, body: Req.ModerateReviewRequest) =>
    http.post<T.ReviewDecision>(`/admin/reviews/${reviewId}/reject`, body, idem()),
};

export interface CaseSearch extends Page {
  status?: ModerationCaseStatus;
  reason?: ModerationReason;
  assigneeId?: number;
}

export const cases = {
  search: (query: CaseSearch) =>
    http.get<T.PagedResponse<T.CaseRow>>('/admin/moderation/cases', query),

  resolve: (caseId: number, body: Req.ResolveCaseRequest) =>
    http.post<T.CaseResolved>(`/admin/moderation/cases/${caseId}/resolve`, body, idem()),
};

// ── /admin — money ───────────────────────────────────────────────────────────

export interface PaymentSearch extends Page {
  q?: string;
  status?: PaymentStatus;
  currency?: string;
  transactionId?: string;
  from?: string;
  to?: string;
}

export const payments = {
  search: (query: PaymentSearch) => http.get<T.PagedResponse<T.PaymentRow>>('/admin/payments', query),

  detail: (paymentId: number) => http.get<T.PaymentRow>(`/admin/payments/${paymentId}`),

  /** One vendor's sub-order, never a proportion of the whole payment. */
  refund: (paymentId: number, body: Req.RefundRequest) =>
    http.post<T.RefundMade>(`/admin/payments/${paymentId}/refund`, body, idem()),
};

export interface LedgerSearch extends Page {
  vendorId?: number;
  currency?: string;
  type?: LedgerEntryType;
  orderId?: number;
  reference?: string;
  from?: string;
  to?: string;
}

export const ledger = {
  search: (query: LedgerSearch) => http.get<T.LedgerPage>('/admin/ledger', query),
  reconciliation: (asOf?: string) =>
    http.get<T.Reconciliation>('/admin/ledger/reconciliation', { asOf }),
};

export interface BalanceSearch extends Page {
  vendorId?: number;
  currency?: string;
  payableOnly?: boolean;
}

export const balances = {
  search: (query: BalanceSearch) => http.get<T.PagedResponse<T.VendorBalance>>('/admin/balances', query),
};

export interface BatchSearch extends Page {
  status?: PayoutBatchStatus;
  currency?: string;
}

export const payouts = {
  batches: (query: BatchSearch) => http.get<T.PagedResponse<T.BatchRow>>('/admin/payouts/batches', query),

  batch: (batchId: number) => http.get<T.BatchRow>(`/admin/payouts/batches/${batchId}`),

  prepareBatch: (body: Req.PrepareBatchRequest) =>
    http.post<T.BatchSaved>('/admin/payouts/batches', body, idem()),

  /** Four-eyes: the server refuses a batch approved by the person who prepared it. */
  approveBatch: (batchId: number, body: Req.ApproveBatchRequest) =>
    http.post<T.BatchSaved>(`/admin/payouts/batches/${batchId}/approve`, body, idem()),

  cancelBatch: (batchId: number, body: Req.CancelBatchRequest) =>
    http.post<T.BatchSaved>(`/admin/payouts/batches/${batchId}/cancel`, body, idem()),

  retryItem: (payoutId: number, body: Req.RetryPayoutItemRequest) =>
    http.post<T.PayoutRetried>(`/admin/payouts/items/${payoutId}/retry`, body, idem()),
};

export interface RateSearch extends Page {
  currency?: string;
  from?: string;
  to?: string;
}

export const fx = {
  rates: (query: RateSearch) => http.get<T.PagedResponse<T.RateRow>>('/admin/fx/rates', query),

  refresh: (body: Req.RefreshRatesRequest) =>
    http.post<T.RatesRefreshed>('/admin/fx/refresh', body, idem()),

  spreads: () => http.get<T.SpreadRow[]>('/admin/fx/spread'),

  setSpread: (body: Req.SetSpreadRequest) => http.patch<T.SpreadSet>('/admin/fx/spread', body),
};

export interface ExportSearch extends Page {
  requestedBy?: number;
  status?: ReportExportStatus;
}

export const reports = {
  revenue: (query: { from?: string; to?: string; vendorId?: number }) =>
    http.get<T.RevenueReport>('/admin/reports/revenue', query),

  exports: (query: ExportSearch) => http.get<T.PagedResponse<T.ExportRow>>('/admin/reports/exports', query),

  requestExport: (type: ReportType, body: Req.RequestExportRequest) =>
    http.post<T.ExportQueued>(`/admin/reports/${type}/export`, body, idem()),
};

// ── /admin — logistics ───────────────────────────────────────────────────────

export interface DriverSearch extends Page {
  q?: string;
  status?: DriverStatus;
  countryCode?: string;
  zoneCode?: string;
  availableOnly?: boolean;
}

export const drivers = {
  search: (query: DriverSearch) => http.get<T.PagedResponse<T.DriverRow>>('/admin/drivers', query),

  approve: (driverId: number, body: Req.ApproveDriverRequest) =>
    http.post<T.DriverDecision>(`/admin/drivers/${driverId}/approve`, body, idem()),

  suspend: (driverId: number, body: Req.SuspendDriverRequest) =>
    http.post<T.DriverDecision>(`/admin/drivers/${driverId}/suspend`, body, idem()),

  setZones: (driverId: number, body: Req.SetDriverZonesRequest) =>
    http.patch<T.DriverDecision>(`/admin/drivers/${driverId}/zones`, body),
};

export interface PickupPointSearch extends Page {
  q?: string;
  status?: PartnerStatus;
  countryCode?: string;
  overdueOnly?: boolean;
  fullOnly?: boolean;
}

export const pickupPoints = {
  search: (query: PickupPointSearch) =>
    http.get<T.PagedResponse<T.PickupPointRow>>('/admin/pickup-points', query),

  create: (body: Req.CreatePickupPointRequest) =>
    http.post<T.PickupPointSaved>('/admin/pickup-points', body, idem()),

  patch: (pointId: number, body: Req.PatchPickupPointRequest) =>
    http.patch<T.PickupPointSaved>(`/admin/pickup-points/${pointId}`, body),

  suspend: (pointId: number, body: Req.SuspendPickupPointRequest) =>
    http.post<T.PickupPointSaved>(`/admin/pickup-points/${pointId}/suspend`, body, idem()),
};

export interface ZoneSearch extends Page {
  q?: string;
  countryCode?: string;
  active?: boolean;
}

export const zones = {
  search: (query: ZoneSearch) => http.get<T.PagedResponse<T.ZoneRow>>('/admin/zones', query),

  detail: (zoneId: number) => http.get<T.ZoneDetail>(`/admin/zones/${zoneId}`),

  create: (body: Req.CreateZoneRequest) => http.post<T.ZoneSaved>('/admin/zones', body, idem()),

  patch: (zoneId: number, body: Req.PatchZoneRequest) =>
    http.patch<T.ZoneSaved>(`/admin/zones/${zoneId}`, body),
};

export interface RateCardSearch extends Page {
  zoneId?: number;
  countryCode?: string;
  mode?: DeliveryMode;
  activeOnly?: boolean;
}

export const rateCards = {
  search: (query: RateCardSearch) =>
    http.get<T.PagedResponse<T.RateCardRow>>('/admin/rate-cards', query),

  preview: (query: { zoneId?: number; countryCode?: string }) =>
    http.get<T.RateCardPreview[]>('/admin/rate-cards/preview', query),

  create: (body: Req.CreateRateCardRequest) =>
    http.post<T.RateCardSaved>('/admin/rate-cards', body, idem()),

  patch: (cardId: number, body: Req.PatchRateCardRequest) =>
    http.patch<T.RateCardSaved>(`/admin/rate-cards/${cardId}`, body),
};

// ── /admin — platform ────────────────────────────────────────────────────────

export interface DisputeSearch extends Page {
  status?: DisputeStatus;
  openOnly?: boolean;
  assignedTo?: number;
  unassignedOnly?: boolean;
  vendorId?: number;
  reason?: DisputeReason;
  overdueOnly?: boolean;
}

export const disputes = {
  search: (query: DisputeSearch) => http.get<T.PagedResponse<T.DisputeRow>>('/admin/disputes', query),

  detail: (disputeId: number) => http.get<T.DisputeRow>(`/admin/disputes/${disputeId}`),

  assign: (disputeId: number, body: Req.AssignDisputeRequest) =>
    http.post<T.DisputeRow>(`/admin/disputes/${disputeId}/assign`, body, idem()),

  addNote: (disputeId: number, body: Req.AddNoteRequest) =>
    http.post<T.NoteAdded>(`/admin/disputes/${disputeId}/notes`, body, idem()),

  resolve: (disputeId: number, body: Req.ResolveDisputeRequest) =>
    http.post<T.DisputeDecided>(`/admin/disputes/${disputeId}/resolve`, body, idem()),

  requestCallback: (disputeId: number, body: Req.RequestCallbackRequest) =>
    http.post<T.CallbackRow>(`/admin/disputes/${disputeId}/request-callback`, body, idem()),
};

export const callbacks = {
  outstanding: (query: Page) => http.get<T.PagedResponse<T.CallbackRow>>('/admin/callbacks', query),

  record: (callbackId: number, body: Req.RecordCallbackRequest) =>
    http.post<T.CallbackRow>(`/admin/callbacks/${callbackId}/outcome`, body, idem()),
};

export const comms = {
  announce: (body: Req.AnnounceRequest) =>
    http.post<T.AnnouncementSent>('/admin/announcements', body, idem()),

  notify: (body: Req.SendNotificationRequest) =>
    http.post<T.NotificationSent>('/admin/notifications/send', body, idem()),
};

export interface AuditSearch extends Page {
  actorUserId?: number;
  action?: AuditAction;
  targetType?: string;
  targetId?: number;
  q?: string;
  from?: string;
  to?: string;
}

export const audit = {
  search: (query: AuditSearch) => http.get<T.PagedResponse<T.AuditRow>>('/admin/audit-log', query),
};

export const featureFlags = {
  list: () => http.get<T.FlagRow[]>('/admin/feature-flags'),
  set: (flagKey: string, body: Req.SetFeatureFlagRequest) =>
    http.patch<T.FlagRow>(`/admin/feature-flags/${encodeURIComponent(flagKey)}`, body),
};

export interface JobHistorySearch extends Page {
  jobName?: string;
  status?: string;
}

export const jobs = {
  list: () => http.get<T.JobRow[]>('/admin/jobs'),
  history: (query: JobHistorySearch) => http.get<T.PagedResponse<T.JobRow>>('/admin/jobs/history', query),
  run: (jobName: string, body: Req.TriggerJobRequest) =>
    http.post<T.JobTriggered>(`/admin/jobs/${encodeURIComponent(jobName)}/run`, body, idem()),
};

// ── /admin — users ───────────────────────────────────────────────────────────

export interface UserSearch extends Page {
  q?: string;
  role?: UserRole;
  country?: string;
  blocked?: boolean;
  lockedOut?: boolean;
}

export const users = {
  search: (query: UserSearch) => http.get<T.PagedResponse<T.UserRow>>('/admin/users', query),

  detail: (userId: number) => http.get<T.UserDetail>(`/admin/users/${userId}`),

  create: (body: Req.CreateUserRequest) => http.post<T.UserCreated>('/admin/users', body, idem()),

  patch: (userId: number, body: Req.PatchUserRequest) =>
    http.patch<T.UserDetail>(`/admin/users/${userId}`, body),

  activate: (userId: number, body: Req.ActivateRequest) =>
    http.post<T.AccountChanged>(`/admin/users/${userId}/activate`, body, idem()),

  deactivate: (userId: number, body: Req.DeactivateRequest) =>
    http.post<T.AccountChanged>(`/admin/users/${userId}/deactivate`, body, idem()),

  suspend: (userId: number, body: Req.SuspendUserRequest) =>
    http.post<T.AccountChanged>(`/admin/users/${userId}/suspend`, body, idem()),

  changeRole: (userId: number, body: Req.ChangeRoleRequest) =>
    http.post<T.RoleChanged>(`/admin/users/${userId}/roles`, body, idem()),

  forceLogout: (userId: number, body: Req.ForceLogoutRequest) =>
    http.post<T.SessionsEnded>(`/admin/users/${userId}/force-logout`, body, idem()),

  resetMfa: (userId: number, body: Req.ResetMfaRequest) =>
    http.post<T.MfaReset>(`/admin/users/${userId}/reset-mfa`, body, idem()),

  impersonate: (userId: number, body: Req.ImpersonateRequest) =>
    http.post<T.ImpersonationOpened>(`/admin/users/${userId}/impersonate`, body, idem()),
};

// ── Legacy payment operations ────────────────────────────────────────────────

/**
 * Three operations on the older `/api/admin` surface that `/admin/payments`
 * does not carry: confirming that a bank transfer arrived, cancelling a
 * payment that will never arrive, and marking one failed. They are reconciling
 * a payment the platform is still waiting on, which is a different job from
 * refunding one it already took.
 */
export const legacyPayments = {
  forOrder: (orderId: number) =>
    http.get<T.LegacyPaymentResponse>(`/api/admin/orders/${orderId}/payment`),

  confirmTransfer: (
    orderId: number,
    body: { amountReceived?: T.Decimal | null; collectionReference?: string | null; note?: string | null },
  ) => http.post<T.LegacyPaymentResponse>(`/api/admin/orders/${orderId}/payment/confirm-transfer`, body),

  cancel: (orderId: number, reason: string) =>
    http.post<T.LegacyPaymentResponse>(`/api/admin/orders/${orderId}/payment/cancel`, undefined, {
      query: { reason },
    }),

  markFailed: (orderId: number, reason: string) =>
    http.post<T.LegacyPaymentResponse>(`/api/admin/orders/${orderId}/payment/fail`, undefined, {
      query: { reason },
    }),
};
