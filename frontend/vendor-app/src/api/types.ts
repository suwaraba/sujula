/**
 * The wire shapes, mirrored from the application's DTOs.
 *
 * Hand-written rather than generated, and deliberately narrow: this app binds
 * the seller's surface, so a field that only an administrator or a buyer ever
 * sees is not declared here. Two things are worth noticing in what follows.
 *
 * Money arrives as a JSON number written from a `BigDecimal`. It is kept as a
 * `number` for display only — every arithmetic decision about money is the
 * server's, and nothing in this app adds two amounts together and shows the
 * result as authoritative.
 *
 * Amounts come with the currency they are in, always. There is no field on a
 * seller's surface that is "the amount" without one, because a seller's figures
 * are in their settlement currency and the buyer's were not.
 */

// ── Enums, as the application spells them ───────────────────────────────────

export type PartnerStatus = 'PENDING_KYC' | 'PENDING' | 'APPROVED' | 'SUSPENDED' | 'REJECTED' | 'ACTIVE';
export type KycStatus = 'NOT_STARTED' | 'INCOMPLETE' | 'IN_REVIEW' | 'ACTION_REQUIRED' | 'VERIFIED';

export type ProductStatus =
  | 'DRAFT' | 'IN_REVIEW' | 'APPROVED' | 'PUBLISHED'
  | 'UNPUBLISHED' | 'REJECTED' | 'SUSPENDED' | 'ARCHIVED';

export type ProductCondition = 'NEW' | 'OPEN_BOX' | 'REFURBISHED' | 'USED' | 'FOR_PARTS';
export type DeliveryScope = 'DOMESTIC' | 'REGIONAL' | 'INTERNATIONAL' | string;

export type VendorOrderStatus =
  | 'PENDING' | 'PREPARING' | 'READY_FOR_PICKUP' | 'SHIPPED'
  | 'DELIVERED' | 'CANCELLED' | 'REFUNDED';

export type MediaStatus = 'PENDING' | 'READY' | 'REJECTED' | string;
export type GeocodeConfidence = 'EXACT' | 'INTERPOLATED' | 'APPROXIMATE' | 'UNKNOWN' | string;

export type KycDocumentType =
  | 'NATIONAL_ID' | 'PASSPORT' | 'DRIVING_LICENCE' | 'BUSINESS_REGISTRATION'
  | 'TAX_CERTIFICATE' | 'PROOF_OF_ADDRESS' | 'BANK_STATEMENT' | string;
export type KycDocumentStatus = 'SUBMITTED' | 'ACCEPTED' | 'REJECTED' | 'EXPIRED' | string;

export type BankAccountType = 'BANK' | 'MOBILE_MONEY' | string;
export type StoreStaffStatus = 'INVITED' | 'ACTIVE' | 'REVOKED' | 'EXPIRED' | string;
export type StorePermission = string;

export type PayoutStatus = 'REQUESTED' | 'APPROVED' | 'PROCESSING' | 'PAID' | 'FAILED' | 'CANCELLED' | string;
export type LedgerEntryType = string;
export type StockMovementReason =
  | 'RESTOCK' | 'CORRECTION' | 'DAMAGE' | 'LOSS' | 'RETURN' | 'MANUAL' | string;
export type ImeiGrade = 'NEW' | 'A_GRADE' | 'B_GRADE' | 'C_GRADE' | 'FOR_PARTS';
export type ImeiStatus =
  | 'IN_STOCK' | 'RESERVED' | 'SOLD' | 'RETURNED' | 'LOST' | 'WRITTEN_OFF' | string;
export type DayOfWeek =
  | 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY';

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

/**
 * `vendorId` is the whole gate. It is present when this account sells and
 * absent when it does not, which is what decides whether the app opens the
 * fulfilment desk or the application form.
 */
export type Me = {
  profile: Profile;
  permissions: string[];
  resolvedCurrency: string;
  resolvedLanguage: string;
  vendorId: number | null;
  vendorStatus: PartnerStatus | null;
  linkedAccounts: { provider: string; email: string | null }[];
  activeSessions: number;
};

// ── Multi-factor ────────────────────────────────────────────────────────────

/**
 * `provisioningUri` is the `otpauth://` URI an authenticator scans. The server
 * sends no QR image — rendering one is the client's job, and there is no reason
 * for a shared secret to make a second trip through a PNG encoder.
 */
export type MfaSetup = { secret: string; provisioningUri: string; issuer: string };

export type MfaActivation = { recoveryCodes: string[]; remaining: number };

export type MfaStatus = { enabled: boolean; recoveryCodesRemaining: number };

export type SessionRow = {
  id: number;
  device: string | null;
  userAgent: string | null;
  ipAddress: string | null;
  current: boolean;
  createdAt: string;
  lastSeenAt: string | null;
  expiresAt: string | null;
};

// ── Reference data ──────────────────────────────────────────────────────────

/**
 * `minorUnits` is why this is fetched rather than assumed. XOF has none, so
 * formatting a CFA amount to two places invents a coin that does not exist.
 */
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

export type CategoryNode = {
  id: number;
  name: string;
  slug: string;
  description: string | null;
  imageUrl: string | null;
  sortOrder: number;
  productCount: number;
  children: CategoryNode[] | null;
};

/** The tree comes wrapped, not as a bare array. */
export type CategoryTree = { categories: CategoryNode[] };

// ── Store (the seller's own shop) ───────────────────────────────────────────

export type StoreAddress = {
  street: string | null;
  city: string | null;
  state: string | null;
  postalCode: string | null;
  countryCode: string | null;
  latitude: number | null;
  longitude: number | null;
  confidence: GeocodeConfidence | null;
  /** The pin was guessed rather than given, and a driver would be sent to a guess. */
  needsPinConfirmation: boolean;
  /** Whether a collection can actually be routed to this address. */
  dispatchable: boolean;
  instructions: string | null;
  geocodedAt: string | null;
};

export type OperatingHours = {
  day: DayOfWeek;
  closed: boolean;
  opensAt: string | null;
  closesAt: string | null;
};

export type PayoutDestination = {
  id: number;
  accountType: BankAccountType;
  accountHolderName: string;
  bankName: string | null;
  accountNumberLast4: string | null;
  ibanLast4: string | null;
  mobileMoneyLast4: string | null;
  mobileMoneyProvider: string | null;
  swiftCode: string | null;
  currency: string | null;
  verified: boolean;
  lastChangedAt: string | null;
};

export type Store = {
  id: number;
  storeName: string;
  storeSlug: string;
  description: string | null;
  storeEmail: string | null;
  storePhone: string | null;
  website: string | null;
  logoUrl: string | null;
  bannerUrl: string | null;
  status: PartnerStatus;
  kycStatus: KycStatus;
  canTrade: boolean;
  blockedReason: string | null;
  settlementCurrency: string;
  address: StoreAddress | null;
  pickupAddress: StoreAddress | null;
  operatingHours: OperatingHours[];
  returnPolicy: string | null;
  shippingPolicy: string | null;
  storePolicy: string | null;
  handlingDays: number | null;
  vacationMode: boolean;
  vacationMessage: string | null;
  businessRegistrationNumber: string | null;
  taxNumber: string | null;
  payoutDestination: PayoutDestination | null;
  staffCount: number;
  createdAt: string;
};

export type KycDoc = {
  id: number;
  type: KycDocumentType;
  status: KycDocumentStatus;
  originalFilename: string | null;
  sizeBytes: number | null;
  expiresOn: string | null;
  rejectionReason: string | null;
  submittedAt: string | null;
  reviewedAt: string | null;
};

export type KycState = {
  status: KycStatus;
  storeStatus: PartnerStatus;
  missing: KycDocumentType[];
  actionsRequired: string[];
  documents: KycDoc[];
  submittedAt: string | null;
  decidedAt: string | null;
};

export type StaffMember = {
  /**
   * The staff row's id. Absent for the owner, who is not a staff row — the
   * server omits null fields, so this is genuinely missing rather than null.
   */
  id: number | null;
  /**
   * The account behind this person. Absent until an invitation is accepted:
   * somebody invited by email has no account yet, and the staff endpoints are
   * addressed by user id, so they cannot be edited or removed until they join.
   */
  userId: number | null;
  email: string;
  displayName: string | null;
  status: StoreStaffStatus;
  permissions: StorePermission[];
  owner: boolean;
  invitedAt: string | null;
  inviteExpiresAt: string | null;
  acceptedAt: string | null;
  revokedAt: string | null;
};

export type StaffList = { members: StaffMember[]; limit: number };

/** The legacy `/api/vendors` projection, still the only place some fields live. */
export type VendorProfile = {
  id: number;
  userId: number;
  storeName: string;
  storeSlug: string;
  addressStreet: string | null;
  addressCity: string | null;
  addressState: string | null;
  addressPostalCode: string | null;
  addressCountryCode: string | null;
  latitude: number | null;
  longitude: number | null;
  status: PartnerStatus;
  settlementCurrency: string;
  balance: number | null;
  rating: number | null;
  totalReviews: number | null;
  totalSold: number | null;
  createdAt: string;
};

// ── Promotions and coupons ──────────────────────────────────────────────────

export type PromotionType = 'PERCENT' | 'FIXED' | 'BUY_X_GET_Y' | 'FREE_SHIPPING' | 'BUNDLE';
export type PromotionStatus = 'DRAFT' | 'ACTIVE' | 'PAUSED' | 'EXPIRED' | 'CANCELLED';
export type CouponType = 'PERCENTAGE' | 'FIXED_AMOUNT' | 'FREE_SHIPPING';

export type Promotion = {
  id: number;
  name: string;
  description: string | null;
  type: PromotionType;
  status: PromotionStatus;
  /** Active *and* inside its window. A promotion can be ACTIVE and not running yet. */
  running: boolean;
  blockedReason: string | null;
  percentOff: number | null;
  amountOff: number | null;
  /** Present when the promotion takes an amount rather than a percentage. */
  currency: string | null;
  buyQuantity: number | null;
  getQuantity: number | null;
  getDiscountPercent: number | null;
  bundlePrice: number | null;
  minimumBasket: number | null;
  maximumDiscount: number | null;
  /** True when it names neither products nor categories: the whole shop. */
  storeWide: boolean;
  productIds: number[] | null;
  categoryIds: number[] | null;
  startsAt: string | null;
  endsAt: string | null;
  timesApplied: number;
  activatedAt: string | null;
  createdAt: string;
};

export type PromotionPage = {
  items: Promotion[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

/**
 * What activating produced.
 *
 * `conflicts` is declared because the DTO carries it, and is always empty in
 * practice: the server *refuses* an overlapping activation with a 400 whose
 * message names the promotion already discounting those goods and says what to
 * do about it. Two discounts on one item do not add up to a price anybody can
 * predict. So the failure path is where the conflict is handled, not this.
 */
export type PromotionActivated = {
  id: number;
  status: PromotionStatus;
  running: boolean;
  conflicts: { promotionId: number; name: string; overlap: string; productIds: number[] }[];
  message: string;
};

export type Coupon = {
  id: number;
  code: string;
  description: string | null;
  type: CouponType;
  value: number | null;
  currency: string | null;
  minimumOrderAmount: number | null;
  maximumDiscountAmount: number | null;
  usageLimit: number | null;
  timesUsed: number;
  remaining: number | null;
  perUserLimit: number | null;
  active: boolean;
  /** Whether a buyer typing it right now would have it accepted. */
  redeemable: boolean;
  blockedReason: string | null;
  startsAt: string | null;
  expiresAt: string | null;
  createdAt: string;
};

export type CouponPage = {
  items: Coupon[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type Redemptions = {
  code: string;
  timesUsed: number;
  usageLimit: number | null;
  redemptions: { id: number; customer: string | null; orderId: number | null; usedAt: string }[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

// ── Catalogue ───────────────────────────────────────────────────────────────

export type ProductSummary = {
  id: number;
  name: string;
  slug: string;
  sku: string | null;
  status: ProductStatus;
  live: boolean;
  price: number;
  currency: string;
  stock: number | null;
  lowStock: boolean;
  leadImageUrl: string | null;
  variantCount: number;
  imageCount: number;
  blockedReason: string | null;
  updatedAt: string;
};

export type ProductPage = {
  items: ProductSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  counts: Partial<Record<ProductStatus, number>>;
};

export type ProductModeration = {
  editable: boolean;
  canSubmit: boolean;
  canPublish: boolean;
  editsNeedReview: boolean;
  reason: string | null;
  submittedAt: string | null;
  reviewedAt: string | null;
  publishedAt: string | null;
  unpublishedAt: string | null;
  archivedAt: string | null;
};

export type ProductMedia = {
  id: number;
  url: string;
  altText: string | null;
  sortOrder: number;
  isDefault: boolean;
  status: MediaStatus;
  originalFilename: string | null;
  sizeBytes: number | null;
};

export type VariantValue = { optionValueId: number; option: string; value: string };

export type ProductVariant = {
  id: number;
  sku: string | null;
  stock: number | null;
  priceOverride: number | null;
  effectivePrice: number;
  active: boolean;
  values: VariantValue[];
};

export type OptionSummary = { id: number; code: string; name: string; values: VariantValue[] };

export type TranslationSummary = {
  locale: string;
  name: string;
  machineTranslated: boolean;
  updatedAt: string;
};

export type ProductDetail = {
  id: number;
  name: string;
  slug: string;
  shortDescription: string | null;
  description: string | null;
  status: ProductStatus;
  live: boolean;
  moderation: ProductModeration;
  price: number;
  compareAtPrice: number | null;
  currency: string;
  sku: string | null;
  stock: number | null;
  lowStockThreshold: number | null;
  allowBackorder: boolean;
  categoryId: number | null;
  categoryName: string | null;
  brandId: number | null;
  brandName: string | null;
  condition: ProductCondition;
  deliveryScope: DeliveryScope | null;
  weightKg: number | null;
  dimensions: string | null;
  /**
   * Where the goods are. Filled from the store's pin when a listing does not
   * name one — see `VendorCatalogueServiceImpl`, which copies the store's
   * pickup coordinates onto every new product.
   */
  country: string | null;
  media: ProductMedia[];
  variants: ProductVariant[];
  options: OptionSummary[];
  translations: TranslationSummary[];
  totalSold: number | null;
  rating: number | null;
  totalReviews: number | null;
  createdAt: string;
  updatedAt: string;
};

export type ProductSaved = {
  id: number;
  status: ProductStatus;
  live: boolean;
  /** An edit that changed the listing's substance sends it back to moderation. */
  needsReview: boolean;
  message: string;
};

export type ProductRemoved = {
  id: number;
  deleted: boolean;
  status: ProductStatus;
  message: string;
};

export type PresignedUpload = { uploadUrl: string; publicUrl: string };

// ── Bulk import and export ──────────────────────────────────────────────────

export type CatalogueJobStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'PARTIAL' | string;
export type CatalogueJobType = 'IMPORT' | 'EXPORT' | string;

export type RowError = { row: number; field: string | null; message: string; value: string | null };

/**
 * An import or an export, in progress or finished.
 *
 * Both are jobs rather than requests that block: a spreadsheet of four hundred
 * listings is not something to hold a connection open for. `reference` is how
 * the job is read back.
 */
export type CatalogueJob = {
  reference: string;
  type: CatalogueJobType;
  status: CatalogueJobStatus;
  originalFilename: string | null;
  format: string | null;
  totalRows: number;
  succeededRows: number;
  failedRows: number;
  failureReason: string | null;
  errors: RowError[] | null;
  errorsShown: number;
  errorsTotal: number;
  downloadUrl: string | null;
  downloadExpiresAt: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
};

export type ImportTemplate = { requiredColumns: string[]; optionalColumns: string[] };

// ── Inventory ───────────────────────────────────────────────────────────────

export type InventoryItem = {
  productId: number;
  variantId: number;
  productName: string;
  sku: string | null;
  variantLabel: string | null;
  productStatus: ProductStatus;
  live: boolean;
  stock: number;
  lowStockThreshold: number | null;
  lowStock: boolean;
  outOfStock: boolean;
  allowBackorder: boolean;
  price: number;
  currency: string;
  /** Optimistic lock. Sent back on an adjustment so two tills cannot overwrite each other. */
  version: number | null;
  serialised: boolean;
  sellableUnits: number | null;
  lastMovementAt: string | null;
};

export type InventoryPage = {
  items: InventoryItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  lowStockCount: number;
  outOfStockCount: number;
};

export type StockAdjusted = {
  variantId: number;
  stockBefore: number;
  stockAfter: number;
  version: number | null;
  message: string;
};

export type StockMovement = {
  id: number;
  reason: StockMovementReason;
  quantityChange: number;
  stockBefore: number;
  stockAfter: number;
  reference: string | null;
  note: string | null;
  recordedBy: string | null;
  recordedAt: string;
};

/**
 * Movements come back in their own envelope, not the generic paged one — it
 * carries the variant's current stock alongside its history, which is what the
 * screen needs to say "this is where the number came from".
 */
export type StockMovements = {
  variantId: number;
  sku: string | null;
  currentStock: number;
  movements: StockMovement[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type ImeiUnit = {
  id: number;
  imei: string;
  imei2: string | null;
  serialNumber: string | null;
  productId: number | null;
  productName: string | null;
  variantId: number | null;
  variantLabel: string | null;
  status: ImeiStatus;
  grade: ImeiGrade | null;
  gradeNote: string | null;
  costPrice: number | null;
  batteryHealth: number | null;
  warrantyExpiresOn: string | null;
  soldOnOrderNumber: string | null;
  soldAt: string | null;
  note: string | null;
  registeredAt: string;
};

/**
 * What a batch registration did.
 *
 * Partial by design: a sheet of forty handsets with two bad check digits
 * registers thirty-eight and tells you which two, rather than refusing the lot.
 * `rejected` is why the screen must show it.
 */
export type HandsetsRegistered = {
  requested: number;
  registered: number;
  units: ImeiUnit[];
  rejected: { variantId: number | null; message: string }[];
  stockAfter: number | null;
  message: string;
};

export type ImeiUnitPage = {
  items: ImeiUnit[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  sellable: number;
};

// ── Orders and fulfilment ───────────────────────────────────────────────────

export type OrderSummary = {
  id: number;
  orderNumber: string;
  status: VendorOrderStatus;
  allowedNextStatuses: VendorOrderStatus[];
  itemCount: number;
  totalUnits: number;
  currency: string;
  goodsTotal: number;
  commission: number;
  payout: number;
  placedAt: string;
  updatedAt: string;
};

export type OrderStats = {
  currency: string;
  ordersByStatus: Partial<Record<VendorOrderStatus, number>>;
  /**
   * Orders still on the seller's side of the chain: PENDING, PREPARING and
   * READY_FOR_PICKUP together. Not "new orders" — an order packed and waiting
   * for a driver is counted here too, because it is still in the shop.
   */
  awaitingAction: number;
  earnedToDate: number;
  inFlight: number;
};

/**
 * What the seller is told about where the parcel goes.
 *
 * Note what is not here: no street, no buyer, no payer country, no amount in
 * the buyer's currency. This block comes from the *delivery* context, not the
 * payer's (C1), and a seller packing a box needs a town and a name, not an
 * address they have no business holding.
 */
export type OrderShipping = {
  recipientName: string | null;
  town: string | null;
  country: string | null;
  international: boolean;
  deliveryMode: string | null;
  pickupPointName: string | null;
  phoneHint: string | null;
};

/** How the buyer's currency became this seller's, and at what moment (C2). */
export type OrderFx = {
  paidIn: string;
  settledIn: string;
  rate: number;
  rateAt: string;
  source: string | null;
};

export type OrderLine = {
  lineId: number;
  productId: number;
  variantId: number | null;
  productName: string;
  sku: string | null;
  variantSku: string | null;
  selectedOptions: string | null;
  imageUrl: string | null;
  quantity: number;
  unitPrice: number;
  lineTotal: number;
  /** True when the variant is tracked handset by handset. */
  serialised: boolean;
  /** The handsets bound so far, in the order they were scanned. */
  assignedImeis: string[] | null;
  /** How many still need binding before this line can be packed. */
  handsetsOutstanding: number;
};

export type OrderDetail = {
  id: number;
  orderNumber: string;
  status: VendorOrderStatus;
  allowedNextStatuses: VendorOrderStatus[];
  currency: string;
  goodsSubtotal: number;
  discount: number;
  goodsTotal: number;
  /**
   * A percentage, not a fraction: 10 means 10%. The ledger computes
   * `commission = goodsTotal * commissionRate / 100`, so this is displayed as
   * it arrives.
   */
  commissionRate: number;
  commission: number;
  delivery: number;
  payout: number;
  fx: OrderFx | null;
  couponCode: string | null;
  lines: OrderLine[];
  placedAt: string;
  updatedAt: string;
  cancelledAt: string | null;
  acceptedAt: string | null;
  readyAt: string | null;
  collectedAt: string | null;
  rejectionReason: string | null;
  shipping: OrderShipping | null;
  /** Whether `/ready` will be accepted: every serialised line has its handsets bound. */
  readyToPack: boolean;
};

export type Accepted = {
  vendorOrderId: number;
  orderNumber: string;
  status: VendorOrderStatus;
  acceptedAt: string;
  message: string;
};

export type Rejected = {
  vendorOrderId: number;
  orderNumber: string;
  status: VendorOrderStatus;
  cancelledAt: string;
  reason: string;
  refundReference: string | null;
  orderFullyCancelled: boolean;
  itemsReturnedToStock: number;
  message: string;
};

/**
 * The code a driver must present to take the parcel.
 *
 * Served `no-store`, shown to the seller and to nobody else. This app never
 * writes it to storage, never puts it in a query string and never logs it.
 */
export type ReleaseCode = {
  code: string;
  issuedAt: string;
  expiresAt: string;
  timesIssued: number;
  reissued: boolean;
  message: string | null;
};

export type ReadyResult = {
  vendorOrderId: number;
  orderNumber: string;
  status: VendorOrderStatus;
  readyAt: string;
  releaseCode: ReleaseCode;
  message: string;
};

export type ImeiAssigned = {
  vendorOrderId: number;
  lineId: number;
  imei: string;
  boundToLine: number;
  requiredForLine: number;
  lineComplete: boolean;
  imeis: string[];
  orderReadyToPack: boolean;
  message: string;
};

// ── Money ───────────────────────────────────────────────────────────────────

export type CurrencyBalance = {
  currency: string;
  available: number;
  pending: number;
  onHold: number;
  atRisk: number;
  total: number;
};

export type Balance = { byCurrency: CurrencyBalance[]; asAt: string; note: string | null };

export type LedgerFx = { paidIn: string; settledIn: string; rate: number; rateAt: string };

export type Transaction = {
  id: number;
  type: LedgerEntryType;
  description: string | null;
  amount: number;
  currency: string;
  occurredAt: string;
  reference: string | null;
  vendorOrderId: number | null;
  orderNumber: string | null;
  heldInEscrow: boolean;
  availableFrom: string | null;
  fx: LedgerFx | null;
};

export type Transactions = {
  entries: Transaction[];
  page: number;
  size: number;
  totalEntries: number;
  totalPages: number;
  note: string | null;
};

export type PayoutRow = {
  id: number;
  reference: string;
  status: PayoutStatus;
  amount: number;
  currency: string;
  period: string | null;
  requestedAt: string;
  processedAt: string | null;
  failureReason: string | null;
  note: string | null;
};

export type Payouts = {
  payouts: PayoutRow[];
  page: number;
  size: number;
  totalPayouts: number;
  totalPages: number;
};

export type PayoutRequested = {
  id: number;
  reference: string;
  status: PayoutStatus;
  amount: number;
  currency: string;
  requestedAt: string;
  message: string;
};

// ── Analytics ───────────────────────────────────────────────────────────────

export type AnalyticsPeriod = { from: string; to: string; days: number };

export type OverviewMoney = {
  currency: string;
  revenue: number;
  revenueBefore: number | null;
  revenueChangePercent: number | null;
  commission: number;
  netRevenue: number;
  averageOrderValue: number;
  averageOrderValueBefore: number | null;
  refunded: number;
};

export type AnalyticsOverview = {
  period: AnalyticsPeriod;
  comparedWith: AnalyticsPeriod | null;
  byCurrency: OverviewMoney[];
  orders: number;
  ordersBefore: number;
  ordersChangePercent: number | null;
  unitsSold: number;
  productViews: number;
  productViewsBefore: number | null;
  conversionPercent: number | null;
  conversionPercentBefore: number | null;
  conversionNote: string | null;
  cancelledOrders: number;
  refundedOrders: number;
  note: string | null;
};

export type SalesPoint = {
  bucketStart: string;
  bucketEnd: string;
  revenue: number;
  commission: number;
  net: number;
  orders: number;
  units: number;
};

export type SalesSeries = {
  currency: string;
  points: SalesPoint[];
  total: number;
  commissionTotal: number;
  orders: number;
};

export type AnalyticsSales = {
  period: AnalyticsPeriod;
  groupBy: string;
  byCurrency: SalesSeries[];
  note: string | null;
};

export type ProductRow = {
  productId: number;
  name: string;
  sku: string | null;
  unitsSold: number;
  revenue: number;
  currency: string;
  views: number;
  conversionPercent: number | null;
  stockOnHand: number | null;
  stockTurn: number | null;
  stockTurnNote: string | null;
};

export type AnalyticsProducts = {
  period: AnalyticsPeriod;
  rows: ProductRow[];
  funnel: {
    views: number;
    ordersContainingAProduct: number;
    unitsSold: number;
    viewToOrderPercent: number | null;
    basis: string | null;
  };
  note: string | null;
};

export type AnalyticsCustomers = {
  period: AnalyticsPeriod;
  buyers: number;
  newBuyers: number;
  returningBuyers: number;
  returningPercent: number | null;
  destinations: { country: string; orders: number; units: number }[];
  suppressedDestinations: number;
  privacyNote: string | null;
  note: string | null;
};

export type AnalyticsDelivery = {
  period: AnalyticsPeriod;
  parcels: number;
  delivered: number;
  failed: number;
  inTransit: number;
  successPercent: number | null;
  averageHoursToReady: number | null;
  averageHoursToDelivered: number | null;
  failuresByZone: {
    zone: string;
    country: string | null;
    parcels: number;
    failed: number;
    failurePercent: number | null;
  }[];
  note: string | null;
};

// ── Paging ──────────────────────────────────────────────────────────────────

export type Paged<T> = {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
};
