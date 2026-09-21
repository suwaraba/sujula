import type {
  CallbackOutcome,
  CustodyEventType,
  DeliveryMode,
  DisputeOutcome,
  ModerationReason,
  NotificationEvent,
  ReportType,
  SanctionType,
  UserRole,
  VendorOrderStatus,
} from './enums';
import type { Decimal, IsoDate, IsoDateTime } from './types';

/**
 * Request bodies, transcribed from the server's `*Requests` records.
 *
 * `StepUp` is factored out rather than repeated: six operations on this
 * surface re-ask for the administrator's password, and for their authenticator
 * code when they have one. They are the six that cannot be undone by the next
 * person to look — money out, a dispute decided, somebody's second factor
 * cleared, a handover recorded that nobody could prove.
 */
export interface StepUp {
  password: string;
  totpCode?: string | null;
}

// ── Users ────────────────────────────────────────────────────────────────────

export interface CreateUserRequest {
  email: string;
  firstName: string;
  lastName: string;
  phone?: string | null;
  role: UserRole;
  countryCode?: string | null;
  reason: string;
}

export interface PatchUserRequest {
  firstName?: string | null;
  lastName?: string | null;
  phone?: string | null;
  countryCode?: string | null;
  preferredCurrency?: string | null;
  preferredLanguage?: string | null;
  reason: string;
}

export interface ActivateRequest {
  reason: string;
}

export interface DeactivateRequest {
  reason: string;
  category: ModerationReason;
}

export interface SuspendUserRequest {
  days: number;
  reason: string;
  category: ModerationReason;
}

export interface ChangeRoleRequest {
  role: UserRole;
  reason: string;
  scopeCountry?: string | null;
}

export interface ForceLogoutRequest {
  reason: string;
}

export interface ResetMfaRequest extends StepUp {
  reason: string;
}

export interface ImpersonateRequest {
  reason: string;
  reference?: string | null;
  minutes?: number | null;
}

// ── Dispatch ─────────────────────────────────────────────────────────────────

export interface ForceCancelOrderRequest {
  /** Null cancels every slice; an id cancels one vendor's and leaves the rest. */
  vendorOrderId?: number | null;
  reason: string;
  refund?: boolean | null;
}

export interface ForceStatusRequest {
  status: VendorOrderStatus;
  note: string;
}

export interface PlaceOrderLine {
  productId: number;
  variantId?: number | null;
  quantity: number;
}

export interface PlaceOrderOnBehalfRequest {
  customerId: number;
  reason: string;
  lines: PlaceOrderLine[];
  /** The delivery context. Not derived from where the customer is. */
  deliveryAddressId: number;
  notes?: string | null;
}

export interface AssignShipmentRequest {
  driverId: number;
  acceptanceMinutes?: number | null;
  note?: string | null;
}

export interface UnassignShipmentRequest {
  reason: string;
}

export interface ReassignShipmentRequest {
  driverId: number;
  reason: string;
  acceptanceMinutes?: number | null;
}

export interface OverrideHandoffRequest extends StepUp {
  eventType: CustodyEventType;
  note: string;
  attestedBy: string;
  lat?: number | null;
  lng?: number | null;
}

export interface CancelShipmentRequest {
  reason: string;
}

// ── Moderation ───────────────────────────────────────────────────────────────

export interface ApproveStoreRequest {
  note?: string | null;
}

export interface RejectStoreRequest {
  reason: string;
}

export interface SuspendStoreRequest {
  reason: string;
  category: ModerationReason;
  holdPayouts?: boolean | null;
}

export interface ChangeCommissionRequest {
  rate: Decimal;
  effectiveFrom: IsoDateTime;
  note?: string | null;
}

export interface ApproveKycRequest {
  note?: string | null;
}

export interface RejectKycRequest {
  reason: string;
}

export interface ApproveProductRequest {
  note?: string | null;
}

export interface RejectProductRequest {
  reason: ModerationReason;
  detail: string;
}

export interface SuspendProductRequest {
  reason: ModerationReason;
  detail: string;
  openCaseAgainstSeller?: boolean | null;
}

export interface CreateProductRequest {
  vendorId: number;
  name: string;
  description: string;
  price: Decimal;
  /** The vendor's own currency — listing currency is payout currency. */
  priceCurrency: string;
  stock: number;
  categoryId: number;
  reason: string;
}

export interface PatchProductRequest {
  name?: string | null;
  description?: string | null;
  price?: Decimal | null;
  stock?: number | null;
  reason: string;
}

export interface ModerateReviewRequest {
  reason: ModerationReason;
  note?: string | null;
}

export interface ResolveCaseRequest {
  upheld: boolean;
  note: string;
  sanctionType?: SanctionType | null;
  suspensionDays?: number | null;
  restrictedPermission?: string | null;
}

// ── Money ────────────────────────────────────────────────────────────────────

export interface RefundRequest extends StepUp {
  /** One vendor's sub-order. A refund is never a proportion of the order. */
  vendorOrderId: number;
  /** In the buyer's display currency. Leave both amounts unset for a full refund. */
  amount?: Decimal | null;
  /** In the vendor's own currency. */
  amountNative?: Decimal | null;
  reason: string;
}

export interface PrepareBatchRequest extends StepUp {
  /** A batch is per currency. There is no cross-currency payout run. */
  currency: string;
  minimumAmount?: Decimal | null;
  vendorIds?: number[] | null;
  note?: string | null;
}

export interface ApproveBatchRequest extends StepUp {
  note?: string | null;
}

export interface CancelBatchRequest {
  reason: string;
}

export interface RetryPayoutItemRequest {
  reason: string;
}

export interface RefreshRatesRequest {
  currencies?: string[] | null;
  note?: string | null;
}

export interface SetSpreadRequest {
  fromCurrency: string;
  toCurrency: string;
  basisPoints: number;
  /** From a moment, never retroactively: snapshotted rates do not move. */
  effectiveFrom: IsoDateTime;
  reason: string;
}

export interface RequestExportRequest {
  type: ReportType;
  fromDate?: IsoDate | null;
  toDate?: IsoDate | null;
  currency?: string | null;
  vendorId?: number | null;
  format?: string | null;
}

// ── Logistics ────────────────────────────────────────────────────────────────

export interface ApproveDriverRequest {
  note?: string | null;
  zoneCodes?: string[] | null;
}

export interface SuspendDriverRequest {
  reason: string;
  until?: IsoDateTime | null;
}

export interface SetDriverZonesRequest {
  zoneCodes: string[];
  note?: string | null;
}

export interface CreatePickupPointRequest {
  name: string;
  addressStreet: string;
  addressApartment?: string | null;
  city: string;
  state?: string | null;
  postalCode?: string | null;
  countryCode: string;
  latitude: number;
  longitude: number;
  contactPhone?: string | null;
  contactEmail?: string | null;
  managerName?: string | null;
  openingHours?: string | null;
  capacity?: number | null;
  storageDays?: number | null;
  commissionPerParcel?: Decimal | null;
  commissionCurrency?: string | null;
  operatorUserId?: number | null;
  adminNote?: string | null;
}

export type PatchPickupPointRequest = Partial<Omit<CreatePickupPointRequest, 'countryCode'>> & {
  active?: boolean | null;
};

export interface SuspendPickupPointRequest {
  reason: string;
}

export interface CreateZoneRequest {
  code: string;
  name: string;
  description?: string | null;
  countryCode: string;
  /** GeoJSON, stored exactly as uploaded. */
  geometry: string;
  serviceable?: boolean | null;
  unserviceableReason?: string | null;
  priority?: number | null;
}

export interface PatchZoneRequest {
  name?: string | null;
  description?: string | null;
  geometry?: string | null;
  serviceable?: boolean | null;
  unserviceableReason?: string | null;
  priority?: number | null;
  active?: boolean | null;
}

export interface CreateRateCardRequest {
  name: string;
  zoneId?: number | null;
  countryCode?: string | null;
  mode?: DeliveryMode | null;
  currency: string;
  baseFee: Decimal;
  includedKm?: Decimal | null;
  perKm?: Decimal | null;
  includedKg?: Decimal | null;
  perKg?: Decimal | null;
  minFee?: Decimal | null;
  maxFee?: Decimal | null;
  freeAbove?: Decimal | null;
  effectiveFrom: IsoDate;
  note?: string | null;
}

export interface PatchRateCardRequest {
  name?: string | null;
  note?: string | null;
  effectiveUntil?: IsoDate | null;
  active?: boolean | null;
}

// ── Platform ─────────────────────────────────────────────────────────────────

export interface AssignDisputeRequest {
  /** Null takes it yourself. */
  assigneeUserId?: number | null;
  note?: string | null;
}

export interface AddNoteRequest {
  body: string;
}

export interface ResolveDisputeRequest extends StepUp {
  outcome: DisputeOutcome;
  /** In the vendor's own currency, which is the currency the money sits in. */
  awardedToBuyerNative?: Decimal | null;
  requireReturn?: boolean | null;
  resolutionNote: string;
}

export interface RequestCallbackRequest {
  phone: string;
  contactName?: string | null;
  preferredLanguage?: string | null;
  reason: string;
  callBy: IsoDateTime;
}

export interface RecordCallbackRequest {
  outcome: CallbackOutcome;
  notes?: string | null;
  /** Set when the outcome is RESCHEDULED. */
  callBy?: IsoDateTime | null;
}

export interface AnnounceRequest {
  title: string;
  body: string;
  audienceRole?: UserRole | null;
  countryCode?: string | null;
  event?: NotificationEvent | null;
}

export interface SendNotificationRequest {
  userId: number;
  title: string;
  message: string;
  event?: NotificationEvent | null;
  referenceId?: string | null;
}

export interface SetFeatureFlagRequest {
  enabled: boolean;
  reason: string;
  clientVisible?: boolean | null;
}

export interface TriggerJobRequest {
  reason: string;
}
