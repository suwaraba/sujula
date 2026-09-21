/**
 * The wire types, mirroring the backend's DTO records field for field.
 *
 * Hand-written rather than generated, and kept next to the endpoint functions
 * that use them, because the OpenAPI document is not served in production
 * (`sujula.docs.enabled` is off) and a type that only exists when a dev profile
 * is running is a type nobody can regenerate when it matters.
 *
 * One modelling decision is load-bearing and is not a style choice:
 * {@link ShipmentDetail.destination} is `Destination | null`. The backend
 * returns the whole object or nothing at all — never an object with blank
 * fields — so that a client cannot render an empty address where a real one
 * used to be. That distinction is preserved here so the compiler makes every
 * caller acknowledge it.
 */

// ── Enumerations, exactly as the backend spells them ─────────────────────────

export type DriverStatus = 'PENDING' | 'APPROVED' | 'SUSPENDED' | 'REJECTED' | 'ACTIVE';

export type VehicleType = 'CAR' | 'TAXI' | 'BICI' | 'MOTOR' | 'TRICYCLE';

export type LegType =
  | 'ORIGIN_TO_RECIPIENT'
  | 'ORIGIN_TO_PICKUP'
  | 'PICKUP_TO_RECIPIENT'
  | 'PICKUP_TO_PICKUP'
  | 'PICKUP_TO_COUNTER';

export type LegAssignmentStatus =
  | 'UNASSIGNED'
  | 'OFFERED'
  | 'ACCEPTED'
  | 'DECLINED'
  | 'EXPIRED'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'CANCELLED';

export type ShipmentStatus =
  | 'AWAITING_COLLECTION'
  | 'DRIVER_OFFERED'
  | 'DRIVER_ASSIGNED'
  | 'AT_ORIGIN'
  | 'IN_TRANSIT'
  | 'AT_PICKUP_POINT'
  | 'OUT_FOR_DELIVERY'
  | 'DELIVERED'
  | 'ATTEMPT_FAILED'
  | 'RETURNED'
  | 'CANCELLED';

export type CustodyEventType =
  | 'ARRIVED_AT_ORIGIN'
  | 'COLLECTED'
  | 'DEPOSITED'
  | 'REDISPATCHED'
  | 'RELEASED'
  | 'TRANSFERRED'
  | 'FAILED_ATTEMPT'
  | 'RETURNED';

/** What went wrong at the door, and what the platform does about it. */
export type FailureReason =
  | 'NOBODY_HOME'
  | 'ADDRESS_NOT_FOUND'
  | 'NO_CODE'
  | 'REFUSED'
  | 'UNSAFE'
  | 'DRIVER_UNABLE';

export type UserRole = 'CUSTOMER' | 'VENDOR' | 'DELIVERY' | 'SUPPORT' | 'ADMIN' | 'PICKUP_POINT';

// ── Identity ─────────────────────────────────────────────────────────────────

export interface Profile {
  id: number;
  email: string;
  firstName: string;
  lastName: string;
  fullName?: string;
  phone?: string;
  role: UserRole;
  emailVerified: boolean;
  phoneVerified: boolean;
  mfaEnabled: boolean;
  preferredCurrency?: string;
  preferredLanguage?: string;
  countryCode?: string;
  profileImageUrl?: string;
  createdAt?: string;
}

export interface Tokens {
  accessToken: string;
  tokenType: string;
  /** Seconds. The client refreshes before this lapses rather than on a 401. */
  expiresIn: number;
  refreshToken: string;
  sessionId?: number;
  user?: Profile;
}

/**
 * The answer to a sign-in attempt.
 *
 * `mfaRequired` is not an error: the password was right and one more thing is
 * needed. A client that cannot tell the two apart shows the wrong message.
 */
export interface LoginResult {
  mfaRequired: boolean;
  tokens?: Tokens;
}

export interface Me {
  profile: Profile;
  permissions: string[];
  resolvedCurrency?: string;
  resolvedLanguage?: string;
  vendorId?: number;
  vendorStatus?: string;
  activeSessions: number;
}

// ── The driver ───────────────────────────────────────────────────────────────

export interface Kyc {
  documentsSubmitted: boolean;
  submittedAt?: string;
  reviewedAt?: string;
  rejectionReason?: string;
  whatIsNeeded?: string;
}

export interface Score {
  acceptancePercent?: string;
  offersReceived: number;
  accepted: number;
  declined: number;
  note?: string;
}

export interface DriverProfile {
  id: number;
  status: DriverStatus;
  online: boolean;
  phone?: string;
  vehicleType?: VehicleType;
  vehiclePlate?: string;
  vehicleModel?: string;
  vehicleColor?: string;
  avatarUrl?: string;
  zone?: string;
  countryCode?: string;
  maxWeightKg?: number;
  licenseNumber?: string;
  licenseExpiresOn?: string;
  kyc?: Kyc;
  score?: Score;
  totalDeliveries?: number;
  averageRating?: string;
  onlineSince?: string;
  lastLocationAt?: string;
  createdAt?: string;
  message?: string;
}

export interface AvailabilitySet {
  online: boolean;
  since?: string;
  message?: string;
}

export interface LocationAccepted {
  recordedAt?: string;
  throttled: boolean;
  nextPingInSeconds?: number;
  message?: string;
}

// ── Assignments ──────────────────────────────────────────────────────────────

/**
 * One leg as it looks before a driver has committed to it: a town, a distance
 * and what it pays. No address — the same leg is offered to several drivers and
 * only one takes it.
 */
export interface Assignment {
  legId: number;
  shipmentId: number;
  shipmentReference?: string;
  legType?: LegType;
  status: LegAssignmentStatus;
  sequence: number;
  pickupFrom?: string;
  dropTo?: string;
  distanceKm?: string;
  earning?: string;
  earningCurrency?: string;
  parcelCount: number;
  offeredAt?: string;
  offerExpiresAt?: string;
  secondsToDecide?: number;
  note?: string;
}

export interface Assignments {
  offered: Assignment[];
  accepted: Assignment[];
  note?: string;
}

export interface AssignmentAnswered {
  legId: number;
  status: LegAssignmentStatus;
  answeredAt?: string;
  score?: Score;
  message?: string;
}

// ── A parcel in the driver's hands ───────────────────────────────────────────

export interface Origin {
  label?: string;
  city?: string;
  lat?: number;
  lng?: number;
  storeName?: string;
  storePhone?: string;
}

/**
 * Where the parcel goes and who to.
 *
 * Present only while custody is active. `null` is the privacy rule expressed in
 * the type: before you accept and after you hand over there is no address here
 * to render, blank or otherwise.
 */
export interface Destination {
  recipientName?: string;
  recipientPhone?: string;
  street?: string;
  city?: string;
  country?: string;
  lat?: number;
  lng?: number;
  instructions?: string;
}

export interface LegSummary {
  legId: number;
  sequence: number;
  legType?: LegType;
  status: LegAssignmentStatus;
  mine: boolean;
  from?: string;
  to?: string;
  earning?: string;
  earningCurrency?: string;
  completedAt?: string;
}

/** One link of the chain. Carries no codes — deliberately. */
export interface ChainEntry {
  type: CustodyEventType;
  occurredAt?: string;
  attestedByPosition: boolean;
  metresFromExpected?: string;
  capturedOffline: boolean;
  note?: string;
}

export interface ShipmentDetail {
  id: number;
  reference?: string;
  status: ShipmentStatus;
  parcelCount: number;
  contentsSummary?: string;
  origin: Origin | null;
  destination: Destination | null;
  legs: LegSummary[];
  chain: ChainEntry[];
  failedAttempts: number;
  nextAttemptAfter?: string;
  custodyActive: boolean;
  privacyNote?: string;
  whatToDoNext?: string;
}

// ── Custody results ──────────────────────────────────────────────────────────

export interface CustodyRecorded {
  eventId?: number;
  type: CustodyEventType;
  shipmentId: number;
  shipmentStatus: ShipmentStatus;
  occurredAt?: string;
  attestedByPosition: boolean;
  metresFromExpected?: string;
  duplicate: boolean;
  message?: string;
}

export interface AttemptFailed {
  eventId?: number;
  shipmentId: number;
  shipmentStatus: ShipmentStatus;
  failedAttempts: number;
  attemptsAllowed: number;
  nextAttemptAfter?: string;
  returning: boolean;
  message?: string;
}

/** The code itself is never in here. */
export interface RecipientCodeRequested {
  shipmentId: number;
  sent: boolean;
  sentTo?: string;
  expiresAt?: string;
  requestsRemaining: number;
  message?: string;
}

export interface SyncOutcome {
  clientEventId: string;
  shipmentId?: number;
  accepted: boolean;
  duplicate: boolean;
  eventId?: number;
  problem?: string;
}

export interface SyncResult {
  received: number;
  recorded: number;
  duplicates: number;
  rejected: number;
  outcomes: SyncOutcome[];
  message?: string;
}

// ── Money and history ────────────────────────────────────────────────────────

export interface EarningLine {
  legId: number;
  shipmentId: number;
  shipmentReference?: string;
  legType?: LegType;
  amount: string;
  currency: string;
  completedAt?: string;
  dropTo?: string;
}

export interface CurrencyEarnings {
  currency: string;
  total: string;
  legs: number;
  averagePerLeg?: string;
  lines: EarningLine[];
}

/** Kept apart by currency. Adding dalasi to CFA would need a rate nobody agreed to. */
export interface Earnings {
  from?: string;
  to?: string;
  byCurrency: CurrencyEarnings[];
  deliveries: number;
  note?: string;
}

export interface HistoryRow {
  shipmentId: number;
  reference?: string;
  status: ShipmentStatus;
  legType?: LegType;
  dropCity?: string;
  dropCountry?: string;
  earning?: string;
  earningCurrency?: string;
  collectedAt?: string;
  completedAt?: string;
}

export interface History {
  rows: HistoryRow[];
  page: number;
  size: number;
  totalRows: number;
  totalPages: number;
}

// ── Requests ─────────────────────────────────────────────────────────────────

export interface ApplyRequest {
  phone: string;
  vehicleType: VehicleType;
  vehiclePlate?: string;
  vehicleModel?: string;
  vehicleColor?: string;
  licenseNumber: string;
  licenseExpiresOn?: string;
  idDocumentNumber: string;
  idDocumentType?: string;
  idDocumentUrl?: string;
  licenseDocumentUrl?: string;
  nextOfKinName?: string;
  nextOfKinPhone?: string;
  zone: string;
  countryCode?: string;
  maxWeightKg?: number;
}

export interface UpdateProfileRequest {
  phone?: string;
  vehicleType?: VehicleType;
  vehiclePlate?: string;
  vehicleModel?: string;
  vehicleColor?: string;
  zone?: string;
  countryCode?: string;
  maxWeightKg?: number;
  avatarUrl?: string;
}

export interface PingRequest {
  lat: number;
  lng: number;
  accuracy?: number;
  /** The device's own clock, for a ping buffered while out of signal. */
  capturedAt?: string;
}

/**
 * Proof that a handover happened. Shared by collection, deposit, delivery and
 * transfer, because they are the same act with different parties.
 */
export interface HandoverRequest {
  code?: string;
  /** The signed token lifted off the parcel label's QR, where one was scanned. */
  qrToken?: string;
  lat?: number;
  lng?: number;
  accuracy?: number;
  photoUrl?: string;
  signatureUrl?: string;
  note?: string;
  capturedAt?: string;
  /** What makes a retry safe. The server decides whether it has seen it before. */
  clientEventId?: string;
}

export interface ArrivedRequest {
  lat?: number;
  lng?: number;
  accuracy?: number;
  capturedAt?: string;
  clientEventId?: string;
}

export interface DeliveryFailedRequest {
  reason: FailureReason;
  note?: string;
  photoUrl?: string;
  lat?: number;
  lng?: number;
  accuracy?: number;
  capturedAt?: string;
  clientEventId?: string;
}

export interface TransferRequest {
  toDriverId: number;
  receivingDriverCode: string;
  myCode: string;
  lat?: number;
  lng?: number;
  accuracy?: number;
  photoUrl?: string;
  note?: string;
  capturedAt?: string;
  clientEventId?: string;
}

/** One offline-captured event, named by the device that captured it. */
export interface SyncEntry {
  shipmentId: number;
  type: CustodyEventType;
  clientEventId: string;
  capturedAt: string;
  code?: string;
  lat?: number;
  lng?: number;
  accuracy?: number;
  photoUrl?: string;
  reasonCode?: string;
  note?: string;
}

export interface SyncBatch {
  events: SyncEntry[];
}

// ── Reference ────────────────────────────────────────────────────────────────

export interface PublicConfig {
  features: Record<string, boolean>;
  minimumAppVersions: Record<string, string>;
  support?: {
    email?: string;
    phone?: string;
    whatsapp?: string;
    termsUrl?: string;
    privacyUrl?: string;
  };
  baseCurrency?: string;
  baseCountry?: string;
  defaultLocale?: string;
  fxQuoteTtlSeconds?: number;
}

export interface PresignedUpload {
  uploadUrl: string;
  publicUrl: string;
}
