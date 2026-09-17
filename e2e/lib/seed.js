/**
 * Everything the development seed puts in the database, named once.
 *
 * Every id, code and token below is read out of
 * src/main/resources/db/seed/dev-seed.sql — nothing here is invented, and
 * nothing here is a fixture this collection creates for itself. That
 * distinction matters: a collection that sets up its own world only ever
 * proves the application is consistent with itself, while one that asserts
 * against a seed written by hand will notice the day a query starts returning
 * something different.
 *
 * When the seed changes, this file changes with it and the run goes red in the
 * places that actually depended on the old value.
 */

module.exports = {
  /**
   * Every seeded account has the same password. The seed's own header says so,
   * and the hash it stores was verified against the application's encoder.
   */
  PASSWORD: 'Sujula123!',

  users: {
    fatou:     { id: 1001, email: 'fatou.admin@sujula.gm',        role: 'ADMIN',           currency: 'GMD', country: 'GM' },
    lamin:     { id: 1002, email: 'lamin.kombo@sujula.gm',        role: 'VENDOR',          currency: 'GMD', country: 'GM' },
    awa:       { id: 1003, email: 'awa.teranga@sujula.sn',        role: 'VENDOR',          currency: 'XOF', country: 'SN' },
    aminata:   { id: 1004, email: 'aminata.ceesay@example.gm',    role: 'CUSTOMER',        currency: 'GMD', country: 'GM' },
    oliver:    { id: 1005, email: 'oliver.bennett@example.co.uk', role: 'CUSTOMER',        currency: 'GBP', country: 'GB' },
    modou:     { id: 1006, email: 'modou.sanneh@example.gm',      role: 'CUSTOMER',        currency: 'GMD', country: 'GM' },
    ebrima:    { id: 1007, email: 'ebrima.driver@sujula.gm',      role: 'DELIVERY',        currency: 'GMD', country: 'GM' },
    isatou:    { id: 1008, email: 'isatou.pickup@sujula.gm',      role: 'PICKUP_OPERATOR', currency: 'GMD', country: 'GM' },
    sulayman:  { id: 1009, email: 'sulayman.blocked@example.gm',  role: 'CUSTOMER',        currency: 'GMD', country: 'GM' },
    mariama:   { id: 1010, email: 'mariama.jarju@example.gm',     role: 'VENDOR',          currency: 'GMD', country: 'GM' },
    ndeye:     { id: 1011, email: 'ndeye.sarr@example.sn',        role: 'CUSTOMER',        currency: 'XOF', country: 'SN' },
    binta:     { id: 1012, email: 'binta.support@sujula.gm',      role: 'SUPPORT',         currency: 'GMD', country: 'GM' },
    alieu:     { id: 1013, email: 'alieu.suspended@example.gm',   role: 'VENDOR',          currency: 'GMD', country: 'GM' },
  },

  /**
   * Aminata's authenticator is real: the secret below is the one seeded against
   * her account, so a TOTP computed from it is the code the server expects.
   * Her recovery codes are stored as hashes, as the API stores them; these are
   * the raw values.
   */
  mfa: {
    aminataTotpSecret: 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP',
    aminataRecoveryUnused: ['7K2M-9QX4', 'B3TN-6RWZ'],
    aminataRecoverySpent: 'H8PD-2LVC',
  },

  /** Refresh tokens seeded as SHA-256 digests, so these work without a login. */
  refreshTokens: {
    aminataPhone:  'sujula-dev-refresh-aminata-phone',
    aminataLaptop: 'sujula-dev-refresh-aminata-laptop',
    laminVendor:   'sujula-dev-refresh-lamin-vendor',
    oliverLondon:  'sujula-dev-refresh-oliver-london',
    /** Session 1805 has rotated past this one: replaying it is treated as theft. */
    rotatedAway:   'sujula-dev-refresh-rotated-away',
    /** Seeded a week in the past, so it is expired whenever the seed is run. */
    modouExpired:  'sujula-dev-refresh-modou-expired',
  },

  sessions: { aminataPhone: 1801, aminataLaptop: 1802, laminVendor: 1803, oliverLondon: 1804, replayed: 1805 },

  /** Modou's live phone challenge, good for thirty minutes from the seed run. */
  phoneVerification: { modouPhone: '+2203100006', modouCode: '445120' },

  dataRequests: {
    oliverExportDone: 'EXP-7F3A2B91',
    modouExportQueued: 'EXP-C40D18E2',
    blockedErasureOpen: 'ERA-9B21EF07',
    exportFailed: 'EXP-1D5C77A4',
  },

  addresses: {
    aminataHome: 1050,      // USER_CONFIRMED, dispatchable, and order 1403 names it
    aminataShop: 1051,      // CENTROID, needs a pin, nothing ordered against it
    oliverLondon: 1052,     // EXACT
    modouHighway: 1053,     // NONE, saved anyway
    aminataOldFlat: 1054,   // soft-deleted; still resolves for order 1403
  },

  deliveryContexts: {
    aminataHome: 'seed-ctx-aminata-home',
    guestBrikama: 'seed-ctx-guest-brikama',
    guestSerrekunda: 'seed-ctx-guest-serrekunda',
    guestPickup: 'seed-ctx-guest-pickup',
    oliverLondon: 'seed-ctx-oliver-london',
    expired: 'seed-ctx-expired',
  },

  fxQuotes: {
    oliverGbp: 'seed-fx-oliver-gbp',
    guestXof: 'seed-fx-guest-xof',
    consumed: 'seed-fx-consumed',
    expired: 'seed-fx-expired',
  },

  vendors: {
    kombo:   { id: 1101, slug: 'kombo-electronics', owner: 1002, settles: 'GMD', country: 'GM' },
    teranga: { id: 1102, slug: 'teranga-textiles',  owner: 1003, settles: 'XOF', country: 'SN' },
    /** In Dakar, settles in GMD, and has no coordinates: three facts, independent. */
    mariama: { id: 1103, slug: null,                owner: 1010, settles: 'GMD', country: 'SN' },
  },

  /**
   * Slugs, which are what the public product and store pages resolve by.
   *
   * GET /products/{slug} takes a slug and GET /products/{productId}/questions
   * takes a number, so both forms of every listing are needed.
   */
  slugs: {
    phone: 'samsung-galaxy-a16',
    featurePhone: 'nokia-110-4g',
    kettle: 'tobaski-electric-kettle',
    waxPrint: 'wax-print-six-yards-indigo',
  },

  products: {
    phone: 1301,        // the one with 180 views and one sale, and two questions
    speaker: 1302,
    kettle: 1303,
    openBox: 1304,      // OPEN_BOX, no stock
    waxPrint: 1305,     // Awa's, French translation attached
    refurbished: 1307,  // REFURBISHED, no stock, archived rather than deleted
  },

  variants: {
    phone: 1350,        // IMEI-tracked: stock follows the units, not a typed figure
    phoneOther: 1351,   // movement ledger sums to 3
  },

  imei: {
    writtenOff: 1462,
    /** One digit off its Luhn check, so registering it is refused. */
    invalid: '490154203237519',
  },

  promotions: { active: 1470, activatable: 1471, conflicting: 1472, freeShipping: 1473 },
  coupons: {
    platformFunded: { id: 1080, code: 'TERANGA10', type: 'PERCENTAGE' },
    vendorFunded: { id: 1081, code: 'KOMBO500', type: 'FIXED_AMOUNT', vendor: 1101 },
    freeShipping: { id: 1082, code: 'FREESHIP', type: 'FREE_SHIPPING' },
    /** Lapsed, kept so the refusal can be exercised. */
    expired: { id: 1083, code: 'EXPIRED20', type: 'PERCENTAGE' },
  },

  carts: {
    guestGbp: 'seed-cart-guest-gbp',
    aminata: 'seed-cart-aminata',
  },
  cartQuotes: {
    live: 'seed-quote-live',
    consumed: 'seed-quote-consumed',
    expired: 'seed-quote-expired',
    incomplete: 'seed-quote-incomplete',
  },

  orders: {
    oliverTwoVendors: 1401,  // paid GBP, two sellers settling GMD and XOF
    guest: 1402,
    aminataDelivered: 1403,
  },

  vendorOrders: {
    laminShipped: 1501,      // SHIPPED, which is why the whole order cannot cancel
    awaConfirmed: 1502,      // CONFIRMED, so this slice alone can
    aminataReleased: 1504,   // receipt confirmed and money released
    laminPacked: 1505,       // packed for Isatou, paid for from Madrid
    awaRejected: 1506,       // Awa refused it, with her reason on the row
  },

  handoff: { forVendorOrder1505: '871460', deadPrevious: '304912' },

  trackingCodes: {
    order1401: 'K7MPQ4RTVX2ND9YH',
    order1402: 'B3WQHJ7FNXR5MTCD',
    order1403: 'Z9DKP2VMHT6RXQFB',
  },

  shipments: {
    finished: 1900,    // Ebrima handed it over: no destination block any more
    inFlight: 1901,    // his now, so the destination block is there
    unclaimed: 1902,   // no events at all; DRIVER_OFFERED comes from the leg
    atCounter: 1903,   // on shelf A-118 at Westfield
  },

  legs: { ebrimaInProgress: 1911, lapsedOffer: 1912 },

  /** Isatou's code for shipment 1901. Emailed to Fatou in Madrid to pass on. */
  recipientCode: { shipment1901: '540913' },

  custodyEvents: { collectedOutsideGeofence: 1923 },

  pickupPoints: {
    westfield: 1096,     // open, one parcel on the shelf
    latrikunda: 1097,    // closed for a funeral, so it is not in the search
  },

  refundRequests: { awaSlice1502: 1450 },

  imports: {
    withRowErrors: 'IMP-7QK2M4XR9DTB5VNC',   // 27 in, 3 rejected, reasons attached
    unreadableFile: 'IMP-3HJ8P6WZ2FKD7RQY',  // somebody uploaded a PDF
    finishedExport: 'EXP-5MNX9TQ2JVH4BKDW',
  },

  staffInvites: { bintaOpen: 1371, removed: 1372 },

  kycDocuments: { awaIdRefused: 1364, awaIdAccepted: 1365, awaProofRefused: 1366 },

  sanctions: { alieuSuspension: 2200 },

  idempotencyKeys: { aminataHomeAddress: 'seed-key-aminata-home' },

  /** Rates the seed publishes, and one it deliberately does not. */
  fx: {
    published: [{ base: 'GMD', quote: 'GBP' }],
    invertedOnly: [{ base: 'GBP', quote: 'XOF' }],
    absent: [{ base: 'GMD', quote: 'SEK' }],
    /** 8500 GMD x 0.011 = 93.50 GBP, which is what seed-quote-live's subtotal says. */
    gmdToGbp: 0.011,
  },

  /** Serrekunda: where the parcels go, and what the catalogue ranks against. */
  destination: { lat: 13.4383, lng: -16.6781, country: 'GM' },
  /** The pin Modou's highway address takes when he confirms it. */
  modouPin: { lat: 13.2714, lng: -16.6492 },
  /** Westfield junction, for the pickup-point search. */
  westfield: { lat: 13.4429, lng: -16.6776 },
};
