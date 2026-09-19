import type {
  AuditAction,
  CallbackOutcome,
  CustodyEventType,
  DeliveryMode,
  DisputeOutcome,
  DisputeReason,
  DisputeStatus,
  DriverStatus,
  JobRunStatus,
  KycDocumentStatus,
  LedgerEntryType,
  LegAssignmentStatus,
  ModerationCaseStatus,
  NotificationEvent,
  OrderStatus,
  PartnerStatus,
  PaymentMethod,
  PaymentStatus,
  PayoutBatchStatus,
  PayoutStatus,
  Permission,
  ProductStatus,
  ReportExportStatus,
  ReportType,
  SanctionType,
  ShipmentStatus,
  UserRole,
  VendorOrderStatus,
} from './enums';

/**
 * A monetary amount as the wire carries it.
 *
 * Jackson writes `BigDecimal` as a JSON number, and a JSON number in
 * JavaScript is a float. So this is the one type the console never does
 * arithmetic on: it is read, carried and formatted, and every figure that is
 * added up was added up by the server, in one currency, against the rate it
 * was snapshotted at. `formatMoney` is the only thing that should touch it.
 */
export type Decimal = number | string;

/** `LocalDateTime` / `LocalDate` on the wire — ISO-8601, no zone. */
export type IsoDateTime = string;
export type IsoDate = string;

export interface PagedResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
}

/** The body `GlobalExceptionHandler` writes for every failure. */
export interface ApiErrorBody {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  /** Field-level messages, present only on a validation failure. */
  errors?: Record<string, string>;
  /** Present only on a 500, for quoting into a bug report. */
  reference?: string;
}

// ── Auth and identity ────────────────────────────────────────────────────────

export interface Profile {
  id: number;
  email: string;
  firstName: string | null;
  lastName: string | null;
  fullName: string | null;
  phone: string | null;
  role: UserRole;
  emailVerified: boolean;
  phoneVerified: boolean;
  mfaEnabled: boolean;
  preferredCurrency: string | null;
  preferredLanguage: string | null;
  countryCode: string | null;
  profileImageUrl: string | null;
  createdAt: IsoDateTime;
}

export interface Tokens {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  refreshToken: string;
  sessionId: number | null;
  user: Profile;
}

export interface LoginResult {
  mfaRequired: boolean;
  tokens: Tokens | null;
}

export interface LinkedAccount {
  provider: string;
  email: string | null;
  linkedAt: IsoDateTime;
}

export interface Me {
  profile: Profile;
  permissions: Permission[];
  resolvedCurrency: string;
  resolvedLanguage: string;
  vendorId: number | null;
  vendorStatus: string | null;
  linkedAccounts: LinkedAccount[];
  activeSessions: number;
}

export interface SessionRow {
  id: number;
  deviceLabel: string | null;
  userAgent: string | null;
  ipAddress: string | null;
  countryCode: string | null;
  current: boolean;
  active: boolean;
  createdAt: IsoDateTime;
  lastSeenAt: IsoDateTime | null;
  expiresAt: IsoDateTime | null;
  revokedAt: IsoDateTime | null;
  revokedReason: string | null;
}

// ── Reference data ───────────────────────────────────────────────────────────

/**
 * One currency as `CurrencyCatalogue` describes it.
 *
 * `minorUnits` is the reason this is fetched rather than assumed: XOF has none,
 * so 1250.50 CFA is not an amount that exists, and a console that formats every
 * currency to two places invents money in one of them.
 */
export interface CurrencyInfo {
  code: string;
  name: string;
  symbol: string | null;
  minorUnits: number;
  smallestUnit: Decimal;
  buyerFacing: boolean;
  settlement: boolean;
  base: boolean;
}

export interface CurrenciesResponse {
  base: string;
  currencies: CurrencyInfo[];
}

export interface CountryInfo {
  code: string;
  name: string;
  currency: string;
  dialCode: string | null;
  buy: boolean;
  ship: boolean;
}

export interface CountriesResponse {
  base: string;
  countries: CountryInfo[];
}

// ── Admin: users ─────────────────────────────────────────────────────────────

export interface UserRow {
  id: number;
  email: string;
  name: string | null;
  phone: string | null;
  role: UserRole;
  countryCode: string | null;
  enabled: boolean;
  blocked: boolean;
  emailVerified: boolean;
  mfaEnabled: boolean;
  lockedBy: string | null;
  lockedUntil: IsoDateTime | null;
  createdAt: IsoDateTime;
  lastSeenAt: IsoDateTime | null;
}

export interface Spend {
  currency: string;
  total: Decimal;
  orders: number;
}

export interface UserActivity {
  orders: number;
  ordersCancelled: number;
  returns: number;
  disputes: number;
  spendByCurrency: Spend[];
  reviewsWritten: number;
  reviewsReported: number;
  productsListed: number;
  deliveriesCompleted: number;
  firstOrderAt: IsoDateTime | null;
  lastOrderAt: IsoDateTime | null;
}

export interface SanctionRow {
  id: number;
  type: SanctionType;
  reason: string | null;
  reasonText: string | null;
  restrictedPermission: string | null;
  issuedAt: IsoDateTime;
  issuedBy: string | null;
  expiresAt: IsoDateTime | null;
  liftedAt: IsoDateTime | null;
  liftedBy: string | null;
  liftedReason: string | null;
  active: boolean;
}

export interface UserSessionRow {
  id: number;
  deviceLabel: string | null;
  ipAddress: string | null;
  countryCode: string | null;
  createdAt: IsoDateTime;
  lastSeenAt: IsoDateTime | null;
  expiresAt: IsoDateTime | null;
  revokedAt: IsoDateTime | null;
  revokedReason: string | null;
  impersonatedBy: string | null;
}

export interface UserCaseRow {
  id: number;
  reference: string;
  reason: string;
  status: string;
  subjectLabel: string | null;
  dueBy: IsoDateTime | null;
  overdue: boolean;
}

export interface VendorSummary {
  id: number;
  storeName: string;
  status: string;
  settlementCurrency: string;
  lastChangedAt: IsoDateTime | null;
}

export interface UserDetail {
  account: UserRow;
  activity: UserActivity;
  sanctions: SanctionRow[];
  sessions: UserSessionRow[];
  openCases: UserCaseRow[];
  vendor: VendorSummary | null;
  note: string | null;
}

export interface UserCreated {
  id: number;
  email: string;
  role: UserRole;
  setupEmailSent: boolean;
  message: string;
}

export interface AccountChanged {
  id: number;
  enabled: boolean;
  sanction: SanctionRow | null;
  sessionsEnded: number;
  message: string;
}

export interface RoleChanged {
  id: number;
  from: UserRole;
  to: UserRole;
  sessionsEnded: number;
  message: string;
}

export interface SessionsEnded {
  id: number;
  ended: number;
  message: string;
}

export interface MfaReset {
  id: number;
  wasEnabled: boolean;
  sessionsEnded: number;
  message: string;
}

export interface ImpersonationOpened {
  userId: number;
  actingAs: string;
  accessToken: string;
  expiresAt: IsoDateTime;
  sessionId: number;
  reason: string;
  warning: string;
}

// ── Admin: dispatch (orders and shipments) ───────────────────────────────────

export interface OrderRow {
  id: number;
  orderNumber: string;
  status: OrderStatus;
  paymentStatus: string | null;
  buyerName: string | null;
  buyerEmail: string | null;
  /** Delivery context. Never the payer's. */
  destinationCity: string | null;
  destinationCountry: string | null;
  total: Decimal;
  currency: string;
  vendorOrders: number;
  storeNames: string[];
  hoursInStatus: number;
  placedAt: IsoDateTime;
}

export interface VendorOrderView {
  id: number;
  storeName: string;
  status: VendorOrderStatus;
  /** What the buyer was charged, in the display currency. */
  total: Decimal;
  currency: string;
  /** What the vendor is owed, in the vendor's own currency. */
  totalNative: Decimal;
  nativeCurrency: string;
  /** The rate the two were joined at, and when it was taken. Never recomputed. */
  fxRate: Decimal | null;
  fxRateAt: IsoDateTime | null;
  acceptedAt: IsoDateTime | null;
  readyAt: IsoDateTime | null;
  cancelledAt: IsoDateTime | null;
  disputeFrozenAt: IsoDateTime | null;
  rejectionReason: string | null;
}

export interface LedgerLine {
  id: number;
  type: string;
  amount: Decimal;
  currency: string;
  storeName: string | null;
  description: string | null;
  reference: string | null;
  availableFrom: IsoDateTime | null;
  occurredAt: IsoDateTime;
}

export interface LegView {
  id: number;
  sequence: number;
  legType: string;
  status: LegAssignmentStatus;
  driverName: string | null;
  driverPhone: string | null;
  from: string | null;
  to: string | null;
  offeredAt: IsoDateTime | null;
  offerExpiresAt: IsoDateTime | null;
  acceptedAt: IsoDateTime | null;
  completedAt: IsoDateTime | null;
  earning: Decimal | null;
  earningCurrency: string | null;
}

export interface ParcelView {
  id: number;
  reference: string;
  trackingCode: string | null;
  status: ShipmentStatus;
  storeName: string | null;
  destinationCity: string | null;
  failedAttempts: number;
  nextAttemptAfter: IsoDateTime | null;
  heldAtPickupPoint: string | null;
  shelfCode: string | null;
  legs: LegView[];
  collectedAt: IsoDateTime | null;
  deliveredAt: IsoDateTime | null;
}

export interface OrderDetail {
  summary: OrderRow;
  slices: VendorOrderView[];
  ledger: LedgerLine[];
  parcels: ParcelView[];
  warnings: string[];
}

export interface OrderCancelled {
  orderId: number;
  status: OrderStatus;
  cancelledVendorOrderIds: number[];
  refundReferences: string[];
  parcelsCancelled: number;
  message: string;
}

export interface StatusForced {
  vendorOrderId: number;
  from: VendorOrderStatus;
  to: VendorOrderStatus;
  auditReference: string;
  warning: string | null;
}

export interface OrderPlaced {
  orderId: number;
  orderNumber: string;
  total: Decimal;
  currency: string;
  vendorOrderIds: number[];
  message: string;
}

export interface ShipmentRow {
  id: number;
  reference: string;
  status: ShipmentStatus;
  storeName: string | null;
  destinationCity: string | null;
  destinationCountry: string | null;
  driverName: string | null;
  failedAttempts: number;
  hoursWaiting: number;
  overdue: boolean;
  createdAt: IsoDateTime;
}

export interface DriverCandidate {
  driverId: number;
  name: string;
  phone: string | null;
  zone: string | null;
  distanceKm: number | null;
  acceptanceScore: Decimal | null;
  openJobs: number;
  available: boolean;
  vehicleType: string | null;
  why: string | null;
}

export interface UnassignedShipment {
  parcel: ShipmentRow;
  candidates: DriverCandidate[];
  note: string | null;
}

export interface AssignmentMade {
  shipmentId: number;
  legId: number;
  driverId: number;
  driverName: string;
  offerExpiresAt: IsoDateTime | null;
  message: string;
}

export interface AssignmentRemoved {
  shipmentId: number;
  legId: number;
  previousDriver: string | null;
  scoreAffected: boolean;
  message: string;
}

export interface HandoffOverridden {
  shipmentId: number;
  eventId: number;
  type: CustodyEventType;
  statusNow: ShipmentStatus;
  note: string | null;
  warning: string;
}

export interface ShipmentCancelled {
  shipmentId: number;
  status: ShipmentStatus;
  message: string;
}

export interface CustodyEventView {
  id: number;
  type: CustodyEventType;
  occurredAt: IsoDateTime;
  recordedAt: IsoDateTime;
  recordedBy: string | null;
  recordedByRole: string | null;
  /** Whether the receiving party presented a code. The evidence, not the claim. */
  codePresented: boolean;
  reasonCode: string | null;
  lat: number | null;
  lng: number | null;
  accuracyMetres: Decimal | null;
  metresFromExpected: Decimal | null;
  withinGeofence: boolean;
  photoUrl: string | null;
  signatureUrl: string | null;
  note: string | null;
  capturedOffline: boolean;
  overridden: boolean;
}

export interface CustodyChainView {
  shipmentId: number;
  reference: string;
  status: ShipmentStatus;
  events: CustodyEventView[];
  note: string | null;
}

// ── Admin: moderation ────────────────────────────────────────────────────────

export interface StoreRow {
  id: number;
  storeName: string;
  slug: string;
  status: PartnerStatus;
  ownerEmail: string;
  countryCode: string | null;
  settlementCurrency: string;
  commissionRate: Decimal | null;
  payoutsHeld: boolean;
  payoutsHeldReason: string | null;
  payoutsHeldAt: IsoDateTime | null;
  liveProducts: number;
  openCases: number;
  createdAt: IsoDateTime;
}

export interface StoreDecision {
  vendorId: number;
  status: PartnerStatus;
  productsSuspended: number;
  payoutsHeld: boolean;
  payoutsPutOnHold: number;
  caseReference: string | null;
  message: string;
}

export interface CommissionRow {
  rate: Decimal;
  effectiveFrom: IsoDateTime;
  effectiveUntil: IsoDateTime | null;
  setBy: string | null;
  note: string | null;
  inForce: boolean;
}

export interface CommissionChanged {
  vendorId: number;
  previousRate: Decimal | null;
  newRate: Decimal;
  effectiveFrom: IsoDateTime;
  ordersAffected: number;
  history: CommissionRow[];
  message: string;
}

export interface KycRow {
  id: number;
  vendorId: number;
  storeName: string;
  type: string;
  status: KycDocumentStatus;
  fileUrl: string | null;
  originalFilename: string | null;
  expiresOn: IsoDate | null;
  submittedAt: IsoDateTime;
  reviewedAt: IsoDateTime | null;
  reviewedBy: string | null;
  rejectionReason: string | null;
  completesTheSet: boolean;
}

export interface KycDecision {
  documentId: number;
  status: KycDocumentStatus;
  storeStatus: PartnerStatus;
  message: string;
}

export interface ProductRow {
  id: number;
  name: string;
  status: ProductStatus;
  vendorId: number;
  storeName: string;
  price: Decimal;
  currency: string;
  stock: number | null;
  primaryImageUrl: string | null;
  submittedAt: IsoDateTime | null;
  rejectionReason: string | null;
  openCases: number;
}

export interface ProductDecision {
  productId: number;
  status: ProductStatus;
  caseReference: string | null;
  message: string;
}

export interface ReviewRow {
  id: number;
  productId: number;
  productName: string;
  storeName: string;
  rating: number;
  title: string | null;
  comment: string | null;
  authorName: string | null;
  verifiedPurchase: boolean;
  reportCount: number;
  reportReasons: string[];
  hidden: boolean;
  createdAt: IsoDateTime;
}

export interface ReviewDecision {
  reviewId: number;
  visible: boolean;
  reportsCleared: number;
  message: string;
}

export interface CaseRow {
  id: number;
  reference: string;
  status: ModerationCaseStatus;
  reason: string;
  subjectType: string;
  subjectId: number;
  subjectLabel: string | null;
  accountableEmail: string | null;
  storeName: string | null;
  source: string | null;
  raisedBy: string | null;
  assignedTo: string | null;
  dueBy: IsoDateTime | null;
  overdue: boolean;
  priorCases: number;
  createdAt: IsoDateTime;
}

export interface CaseResolved {
  id: number;
  reference: string;
  status: ModerationCaseStatus;
  outcome: string;
  sanctionIssued: SanctionType | null;
  sanctionExpiresAt: IsoDateTime | null;
  accountLocked: boolean;
  message: string;
}

// ── Admin: money ─────────────────────────────────────────────────────────────

export interface SliceRow {
  vendorOrderId: number;
  vendorId: number;
  storeName: string;
  status: string;
  total: Decimal;
  displayCurrency: string;
  totalNative: Decimal;
  nativeCurrency: string;
  fxRate: Decimal | null;
  fxRateAt: IsoDateTime | null;
  alreadyRefundedNative: Decimal;
  escrowReleased: boolean;
  disputeFrozen: boolean;
}

export interface PaymentRow {
  paymentId: number;
  orderId: number;
  orderNumber: string;
  status: PaymentStatus;
  method: PaymentMethod;
  amount: Decimal;
  currency: string;
  amountRefunded: Decimal;
  refundable: Decimal;
  transactionId: string | null;
  reference: string | null;
  buyerName: string | null;
  buyerEmail: string | null;
  /** Payer context — where the buyer was. */
  payerCountry: string | null;
  /** Delivery context — where the goods went. A different question. */
  destinationCountry: string | null;
  paidAt: IsoDateTime | null;
  createdAt: IsoDateTime;
  slices: SliceRow[];
  flags: string[];
}

export interface RefundMade {
  refundRequestId: number;
  reference: string;
  vendorOrderId: number;
  storeName: string;
  amount: Decimal;
  currency: string;
  amountNative: Decimal;
  nativeCurrency: string;
  fxRate: Decimal | null;
  fxRateAt: IsoDateTime | null;
  stepUpRequired: boolean;
  message: string;
}

export interface LedgerRow {
  entryId: number;
  occurredAt: IsoDateTime;
  vendorId: number;
  storeName: string;
  type: LedgerEntryType;
  amount: Decimal;
  currency: string;
  availableFrom: IsoDateTime | null;
  vendorOrderId: number | null;
  orderId: number | null;
  payoutId: number | null;
  reference: string | null;
  description: string | null;
}

export interface CurrencyTotal {
  currency: string;
  total: Decimal;
  rows: number;
}

export interface LedgerPage {
  rows: LedgerRow[];
  /** Per currency, never summed together. */
  totals: CurrencyTotal[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
}

export interface VendorBalance {
  vendorId: number;
  storeName: string;
  settlementCurrency: string;
  currency: string;
  available: Decimal;
  held: Decimal;
  total: Decimal;
  inFlight: Decimal;
  payoutsHeld: boolean;
  payoutsHeldReason: string | null;
  currencyMismatch: boolean;
}

export interface ReconciliationLine {
  currency: string;
  escrowHeld: Decimal;
  availableToVendors: Decimal;
  takenFromBuyers: Decimal;
  paidOut: Decimal;
  inFlight: Decimal;
  difference: Decimal;
  balanced: boolean;
  caveat: string | null;
}

export interface Reconciliation {
  asOf: IsoDate;
  lines: ReconciliationLine[];
  findings: string[];
  message: string;
}

export interface BatchItem {
  payoutId: number;
  vendorId: number;
  storeName: string;
  amount: Decimal;
  currency: string;
  status: PayoutStatus;
  attempts: number;
  failureReason: string | null;
  bankAccountSummary: string | null;
}

export interface BatchRow {
  batchId: number;
  reference: string;
  status: PayoutBatchStatus;
  currency: string;
  total: Decimal;
  itemCount: number;
  preparedByUserId: number | null;
  preparedByEmail: string | null;
  preparedAt: IsoDateTime | null;
  approvedByUserId: number | null;
  approvedByEmail: string | null;
  approvedAt: IsoDateTime | null;
  note: string | null;
  exclusions: string | null;
  items: BatchItem[];
  warnings: string[];
}

export interface BatchSaved {
  batchId: number;
  reference: string;
  status: PayoutBatchStatus;
  currency: string;
  total: Decimal;
  itemCount: number;
  message: string;
}

export interface PayoutRetried {
  payoutId: number;
  status: PayoutStatus;
  attempts: number;
  message: string;
}

export interface RateRow {
  id: number;
  fromCurrency: string;
  toCurrency: string;
  rate: Decimal;
  rateDate: IsoDate;
  recordedAt: IsoDateTime;
  spreadBasisPoints: number | null;
  rateWithSpread: Decimal | null;
}

export interface RatesRefreshed {
  pairsRefreshed: number;
  pairsUnchanged: number;
  failed: string[];
  at: IsoDateTime;
  message: string;
}

export interface SpreadRow {
  id: number;
  fromCurrency: string;
  toCurrency: string;
  basisPoints: number;
  asPercentage: string;
  effectiveFrom: IsoDateTime;
  setByUserId: number | null;
  reason: string | null;
  inForceNow: boolean;
}

export interface SpreadSet {
  spread: SpreadRow;
  superseded: SpreadRow | null;
  message: string;
}

export interface RevenueLine {
  currency: string;
  grossSales: Decimal;
  commission: Decimal;
  commissionReversed: Decimal;
  refunds: Decimal;
  netCommission: Decimal;
  fxMargin: Decimal;
  fxMarginCurrency: string;
  orderCount: number;
}

export interface RevenueReport {
  fromDate: IsoDate;
  toDate: IsoDate;
  lines: RevenueLine[];
  message: string;
}

export interface ExportQueued {
  exportId: number;
  reference: string;
  type: ReportType;
  status: ReportExportStatus;
  fromDate: IsoDate | null;
  toDate: IsoDate | null;
  remainingInWindow: number;
  message: string;
}

export interface ExportRow {
  exportId: number;
  reference: string;
  type: ReportType;
  status: ReportExportStatus;
  requestedByUserId: number;
  requestedByEmail: string;
  fromDate: IsoDate | null;
  toDate: IsoDate | null;
  currency: string | null;
  vendorId: number | null;
  format: string | null;
  rowCount: number | null;
  resultUrl: string | null;
  resultExpiresAt: IsoDateTime | null;
  failureReason: string | null;
  createdAt: IsoDateTime;
  finishedAt: IsoDateTime | null;
}

// ── Admin: logistics ─────────────────────────────────────────────────────────

export interface ZoneBadge {
  zoneId: number;
  code: string;
  name: string;
  serviceable: boolean;
}

export interface DriverRow {
  driverId: number;
  userId: number;
  name: string;
  email: string | null;
  phone: string | null;
  status: DriverStatus;
  countryCode: string | null;
  declaredZone: string | null;
  zones: ZoneBadge[];
  available: boolean;
  onlineSince: IsoDateTime | null;
  lastLocationAt: IsoDateTime | null;
  minutesSinceLastPing: number | null;
  acceptanceScore: Decimal | null;
  offersReceived: number | null;
  offersAccepted: number | null;
  offersDeclined: number | null;
  openJobs: number;
  totalDeliveries: number | null;
  averageRating: Decimal | null;
  totalRatings: number | null;
  flags: string[];
  adminNote: string | null;
}

export interface DriverDecision {
  driverId: number;
  name: string;
  status: DriverStatus;
  zones: ZoneBadge[];
  message: string;
}

export interface PickupPointRow {
  id: number;
  name: string;
  status: PartnerStatus;
  active: boolean;
  city: string | null;
  countryCode: string | null;
  latitude: number | null;
  longitude: number | null;
  operatorName: string | null;
  operatorUserId: number | null;
  capacity: number | null;
  storedParcels: number | null;
  spaceLeft: number;
  overdueParcels: number;
  oldestOverdueSince: IsoDateTime | null;
  storageDays: number | null;
  commissionPerParcel: Decimal | null;
  commissionCurrency: string | null;
  closedUntil: IsoDateTime | null;
  closureReason: string | null;
  flags: string[];
  adminNote: string | null;
}

export interface PickupPointSaved {
  id: number;
  name: string;
  status: PartnerStatus;
  active: boolean;
  message: string;
}

export interface ZoneRow {
  id: number;
  code: string;
  name: string;
  description: string | null;
  countryCode: string;
  serviceable: boolean;
  unserviceableReason: string | null;
  priority: number;
  active: boolean;
  minLatitude: number | null;
  maxLatitude: number | null;
  minLongitude: number | null;
  maxLongitude: number | null;
  vertexCount: number;
  rateCards: number;
  drivers: number;
  updatedAt: IsoDateTime | null;
  lastEditedByUserId: number | null;
}

export interface ZoneDetail {
  zone: ZoneRow;
  /** The GeoJSON exactly as it was uploaded. */
  geometry: string;
}

export interface ZoneSaved {
  id: number;
  code: string;
  vertexCount: number;
  polygonCount: number;
  minLatitude: number | null;
  maxLatitude: number | null;
  minLongitude: number | null;
  maxLongitude: number | null;
  cacheVersion: number;
  zonesLive: number;
  message: string;
}

export interface RateCardRow {
  id: number;
  name: string;
  zoneId: number | null;
  zoneCode: string | null;
  countryCode: string | null;
  mode: DeliveryMode | null;
  currency: string;
  baseFee: Decimal;
  includedKm: Decimal | null;
  perKm: Decimal | null;
  includedKg: Decimal | null;
  perKg: Decimal | null;
  minFee: Decimal | null;
  maxFee: Decimal | null;
  freeAbove: Decimal | null;
  effectiveFrom: IsoDate;
  effectiveUntil: IsoDate | null;
  active: boolean;
  inForceToday: boolean;
  note: string | null;
  createdByUserId: number | null;
  createdAt: IsoDateTime;
}

export interface RateCardSaved {
  card: RateCardRow;
  supersededCardId: number | null;
  supersededEndsOn: IsoDate | null;
  message: string;
}

export interface RateCardPreview {
  cardId: number | null;
  cardName: string | null;
  source: string;
  currency: string;
  fiveKmOneKg: Decimal;
  twentyKmThreeKg: Decimal;
  hundredKmTenKg: Decimal;
}

// ── Admin: platform ──────────────────────────────────────────────────────────

export interface DisputeRow {
  disputeId: number;
  reference: string;
  status: DisputeStatus;
  reason: DisputeReason;
  vendorOrderId: number;
  orderId: number;
  orderNumber: string;
  vendorId: number;
  storeName: string;
  raisedByName: string | null;
  raisedAt: IsoDateTime;
  /** What the vendor is owed, in their own currency. */
  amountNative: Decimal;
  nativeCurrency: string;
  /** What the buyer paid, in theirs. */
  amount: Decimal;
  currency: string;
  moneyFrozen: boolean;
  frozenAt: IsoDateTime | null;
  assignedToUserId: number | null;
  assignedToEmail: string | null;
  assignedAt: IsoDateTime | null;
  dueBy: IsoDateTime | null;
  hoursRemaining: number | null;
  overdue: boolean;
  callbackOutstanding: boolean;
  messageCount: number;
  evidenceCount: number;
  outcome: DisputeOutcome | null;
  resolvedAt: IsoDateTime | null;
}

export interface DisputeDecided {
  disputeId: number;
  reference: string;
  status: DisputeStatus;
  outcome: DisputeOutcome;
  awardedToBuyerNative: Decimal;
  keptByVendorNative: Decimal;
  nativeCurrency: string;
  ledgerEntries: string[];
  returnRequired: boolean;
  message: string;
}

export interface NoteAdded {
  noteId: number;
  disputeId: number;
  internal: boolean;
  createdAt: IsoDateTime;
  message: string;
}

export interface CallbackRow {
  callbackId: number;
  disputeId: number;
  disputeReference: string;
  phone: string;
  contactName: string | null;
  preferredLanguage: string | null;
  reason: string | null;
  requestedByUserId: number | null;
  requestedAt: IsoDateTime;
  callBy: IsoDateTime | null;
  outcome: CallbackOutcome | null;
  calledByUserId: number | null;
  calledAt: IsoDateTime | null;
  notes: string | null;
  attempts: number;
  outstanding: boolean;
  overdue: boolean;
  message: string | null;
}

export interface AnnouncementSent {
  announcementId: number;
  reference: string;
  title: string;
  audienceRole: UserRole | null;
  countryCode: string | null;
  event: NotificationEvent;
  segmentSize: number;
  recipients: number;
  sentAt: IsoDateTime;
  message: string;
}

export interface NotificationSent {
  notificationId: number;
  userId: number;
  event: NotificationEvent;
  delivered: boolean;
  message: string;
}

export interface AuditRow {
  id: number;
  at: IsoDateTime;
  actorUserId: number | null;
  actorEmail: string | null;
  actorName: string | null;
  action: AuditAction;
  targetType: string | null;
  targetId: number | null;
  targetLabel: string | null;
  summary: string | null;
  details: string | null;
  ipAddress: string | null;
}

export interface FlagRow {
  id: number;
  key: string;
  label: string | null;
  description: string | null;
  enabled: boolean;
  clientVisible: boolean;
  lastChangedByUserId: number | null;
  lastChangedAt: IsoDateTime | null;
  lastChangeReason: string | null;
}

export interface JobRow {
  name: string;
  description: string | null;
  enabled: boolean;
  intervalMs: number;
  lastStartedAt: IsoDateTime | null;
  lastFinishedAt: IsoDateTime | null;
  lastStatus: JobRunStatus | null;
  lastDurationMs: number | null;
  lastItemsProcessed: number | null;
  lastFailureReason: string | null;
  lastSuccessAt: IsoDateTime | null;
  failuresInLastDay: number;
  overdue: boolean;
  warnings: string[];
}

export interface JobTriggered {
  name: string;
  status: JobRunStatus;
  itemsProcessed: number;
  durationMs: number | null;
  failureReason: string | null;
  message: string;
}

export interface GmvLine {
  currency: string;
  thisMonth: Decimal;
  today: Decimal;
  orders: number;
}

export interface Dashboard {
  at: IsoDateTime;
  /** Per currency. 400 EUR and 12,000 GMD is not 12,400 of anything. */
  gmv: GmvLine[];
  ordersToday: number;
  ordersThisMonth: number;
  parcelsInFlight: number;
  parcelsDeliveredThisMonth: number;
  parcelsFailedThisMonth: number;
  deliverySuccessRate: Decimal | null;
  disputesOpen: number;
  disputesOverdue: number;
  callbacksOutstanding: number;
  moderationCasesOpen: number;
  kycWaiting: number;
  storesAwaitingApproval: number;
  stuckShipments: number;
  payoutBatchesAwaitingApproval: number;
  failedPayouts: number;
  attention: string[];
}

// ── Legacy payment operations (/api/admin) ───────────────────────────────────

/**
 * The older payment surface. Kept because three of its operations —
 * confirming a bank transfer, cancelling a payment and marking one failed —
 * have no equivalent on `/admin/payments`, which only refunds.
 */
export interface LegacyPaymentResponse {
  paymentId: number;
  orderId: number;
  orderNumber: string | null;
  reference: string | null;
  status: PaymentStatus;
  method: PaymentMethod;
  channel: string | null;
  amount: Decimal;
  amountRefunded: Decimal | null;
  amountOutstanding: Decimal | null;
  currency: string;
  actionRequired: boolean;
  instructions: string | null;
  transactionId: string | null;
  collectionReference: string | null;
  failureReason: string | null;
  note: string | null;
  paidAt: IsoDateTime | null;
  refundedAt: IsoDateTime | null;
  cancelledAt: IsoDateTime | null;
  createdAt: IsoDateTime;
}
