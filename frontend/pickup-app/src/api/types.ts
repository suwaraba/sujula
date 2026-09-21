/**
 * The wire shapes, mirrored from the application's DTOs.
 *
 * Narrow on purpose: this app is the counter operator's, so a field only an
 * administrator, a buyer or a driver ever sees is not declared here.
 *
 * Two things are worth noticing in what follows. There is no field anywhere
 * that carries a collection code — the operator is never shown one, only ever
 * types in what somebody reads to them. And every amount arrives with the
 * currency it is in, because a counter that has handled parcels priced in
 * dalasi and in CFA has two earnings and they are not addable.
 */

// ── Enums, as the application spells them ───────────────────────────────────

export type PartnerStatus =
  | 'PENDING_KYC' | 'PENDING' | 'APPROVED' | 'SUSPENDED' | 'REJECTED' | 'ACTIVE';

export type ShipmentStatus =
  | 'AWAITING_COLLECTION' | 'DRIVER_OFFERED' | 'DRIVER_ASSIGNED' | 'AT_ORIGIN'
  | 'IN_TRANSIT' | 'AT_PICKUP_POINT' | 'OUT_FOR_DELIVERY' | 'DELIVERED'
  | 'ATTEMPT_FAILED' | 'RETURNED' | 'CANCELLED';

/**
 * How full a counter is, as a band rather than a count.
 *
 * The public surface reports it this way deliberately: that a shop is holding
 * a hundred and ninety parcels is a fact about somebody's business. The
 * operator's own view carries the real number alongside it.
 */
export type Capacity = 'AVAILABLE' | 'LIMITED' | 'FULL' | 'CLOSED';

/**
 * Why a parcel was turned away — a code rather than free text, because the
 * reason decides what happens to it next and only some of them are the
 * driver's problem to solve.
 */
export type RejectReason = 'DAMAGED' | 'OVER_CAPACITY' | 'WRONG_PARCEL' | 'TOO_LARGE' | 'CLOSING';

// ── Auth and identity ───────────────────────────────────────────────────────

export type Tokens = {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  refreshToken: string;
  sessionId: number | null;
  user: Profile;
};

export type LoginResult = { mfaRequired: boolean; tokens: Tokens | null };

export type Profile = {
  id: number;
  email: string;
  firstName: string | null;
  lastName: string | null;
  fullName: string | null;
  phone: string | null;
  role: string;
  emailVerified: boolean;
  phoneVerified: boolean;
  mfaEnabled: boolean;
  preferredCurrency: string | null;
  preferredLanguage: string | null;
  countryCode: string | null;
  profileImageUrl: string | null;
  createdAt: string;
};

export type Me = {
  profile: Profile;
  permissions: string[];
  resolvedCurrency: string;
  resolvedLanguage: string;
  vendorId: number | null;
  vendorStatus: string | null;
  linkedAccounts: { provider: string; email: string | null }[];
  activeSessions: number;
};

// ── Reference data ──────────────────────────────────────────────────────────

/** `minorUnits` is why this is fetched: XOF has none, and commission is money. */
export type Currency = {
  code: string;
  name: string;
  symbol: string | null;
  minorUnits: number;
  smallestUnit: number;
  buyerFacing: boolean;
  settlement: boolean;
  base: boolean;
};

export type Currencies = { base: string; currencies: Currency[] };

export type Country = {
  code: string;
  name: string;
  currency: string | null;
  dialCode: string | null;
  buy: boolean;
  ship: boolean;
};

export type Countries = { base: string; countries: Country[] };

// ── The counters this operator runs ─────────────────────────────────────────

export type OperatorPoint = {
  id: number;
  name: string;
  status: PartnerStatus;
  active: boolean;
  addressStreet: string | null;
  city: string | null;
  country: string | null;
  lat: number | null;
  lng: number | null;
  openingHours: string | null;
  capacity: number | null;
  storedParcels: number | null;
  overdueParcels: number | null;
  incomingParcels: number | null;
  capacityBand: Capacity;
  /** Shut until this moment. Parcels already held stay held. */
  closedUntil: string | null;
  closureReason: string | null;
  /** How long a parcel may sit before it has to go back. */
  storageDays: number | null;
  commissionPerParcel: number | null;
  commissionCurrency: string | null;
  contactPhone: string | null;
  contactEmail: string | null;
  managerName: string | null;
  createdAt: string;
  message: string | null;
};

export type OperatorPoints = { points: OperatorPoint[]; message: string | null };

export type ApplicationSubmitted = {
  id: number;
  name: string;
  status: PartnerStatus;
  submittedAt: string;
  whatHappensNext: string | null;
};

// ── What is at the counter ──────────────────────────────────────────────────

/**
 * A parcel on its way here.
 *
 * Carries no recipient name, and that is deliberate rather than an omission:
 * it is not here yet, and the counter has no business holding a name for a
 * parcel that may be turned away at the door.
 */
export type IncomingParcel = {
  shipmentId: number;
  reference: string;
  status: ShipmentStatus;
  parcelCount: number;
  fromStore: string | null;
  expectedFrom: string | null;
};

/** A parcel on the shelf, or one that has sat there too long. */
export type StoredParcel = {
  shipmentId: number;
  reference: string;
  shelfCode: string | null;
  recipientName: string | null;
  /** The last digits only. The operator matches a person, not looks one up. */
  recipientPhoneHint: string | null;
  parcelCount: number;
  fromStore: string | null;
  storedAt: string;
  storageDeadline: string | null;
  overdue: boolean;
  daysRemaining: number;
  commission: number | null;
  commissionCurrency: string | null;
  note: string | null;
};

/** Three lists because they are three different jobs, worked at different hours. */
export type Parcels = {
  incoming: IncomingParcel[];
  stored: StoredParcel[];
  overdue: StoredParcel[];
  storedCount: number;
  capacity: number;
  capacityBand: Capacity;
  note: string | null;
};

// ── What happened at the counter ────────────────────────────────────────────

/**
 * Every one of these carries `duplicate`.
 *
 * The server dedupes on the client's own event id, so a second press — or a
 * retry after the wifi dropped mid-request — comes back as the same answer
 * marked as a replay. A screen that says "handed over" twice teaches an
 * operator to distrust it; one that says "this was already done" does not.
 */
export type ParcelAccepted = {
  shipmentId: number;
  reference: string;
  shelfCode: string | null;
  storedAt: string;
  storageDeadline: string | null;
  storedCount: number;
  capacity: number;
  commission: number | null;
  commissionCurrency: string | null;
  duplicate: boolean;
  message: string;
};

export type ParcelRejected = {
  shipmentId: number;
  reference: string;
  shipmentStatus: ShipmentStatus;
  reason: string;
  duplicate: boolean;
  message: string;
};

export type ParcelReleased = {
  shipmentId: number;
  reference: string;
  shipmentStatus: ShipmentStatus;
  releasedAt: string;
  releasedTo: string;
  /** Whether the name written down matched the one on the parcel. Recorded either way. */
  nameMatched: boolean;
  storedCount: number;
  duplicate: boolean;
  message: string;
};

export type ParcelReturning = {
  shipmentId: number;
  reference: string;
  shipmentStatus: ShipmentStatus;
  storageDeadline: string | null;
  daysOverdue: number;
  duplicate: boolean;
  message: string;
};

/**
 * The code went to the buyer, not to this screen.
 *
 * `sentTo` is a hint at the destination, never the code. An operator who could
 * read it could hand the parcel to whoever happened to be standing there.
 */
export type CodeResent = {
  shipmentId: number;
  sent: boolean;
  sentTo: string | null;
  expiresAt: string | null;
  requestsRemaining: number;
  message: string;
};

// ── Money ───────────────────────────────────────────────────────────────────

export type EarningLine = {
  shipmentId: number;
  reference: string;
  commission: number;
  currency: string;
  storedAt: string;
  settledAt: string | null;
  outcome: string | null;
};

export type CurrencyEarnings = {
  currency: string;
  total: number;
  parcels: number;
  lines: EarningLine[];
};

export type Earnings = {
  from: string | null;
  to: string | null;
  byCurrency: CurrencyEarnings[];
  parcelsHandled: number;
  note: string | null;
};

// ── The public lookup ───────────────────────────────────────────────────────

export type PublicPoint = {
  id: number;
  name: string;
  addressStreet: string | null;
  city: string | null;
  country: string | null;
  lat: number | null;
  lng: number | null;
  distanceKm: number | null;
  openingHours: string | null;
  capacity: Capacity;
  openNow: boolean;
  closedUntil: string | null;
  closureReason: string | null;
  contactPhone: string | null;
  imageUrl: string | null;
};

export type PublicPoints = {
  points: PublicPoint[];
  searchLat: number | null;
  searchLng: number | null;
  radiusKm: number | null;
  note: string | null;
};
