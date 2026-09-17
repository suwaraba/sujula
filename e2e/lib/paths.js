/**
 * Fills a path template with a real row.
 *
 * The role matrix calls every endpoint, and most endpoints have an id in the
 * path. Those ids are all seeded rows rather than invented numbers, for a
 * reason that decides whether the assertion means anything: a refusal on
 * /admin/users/999999 could be the role check OR the row not existing, and only
 * one of those is being tested. Pointing at a row that IS there makes the
 * refusal attributable.
 */

const seed = require('./seed');

/**
 * Parameter name to a seeded value.
 *
 * Where a name is used on several surfaces the value has to be right for all
 * of them, so `orderId` is an order, `shipmentId` a shipment, and so on. `id`
 * is the exception and is resolved from the path instead — see below.
 */
const BY_NAME = {
  userId: seed.users.aminata.id,
  staffUserId: seed.users.modou.id,
  sessionId: seed.sessions.aminataLaptop,
  addressId: seed.addresses.aminataHome,
  contextId: seed.deliveryContexts.aminataHome,
  quoteId: seed.fxQuotes.oliverGbp,

  vendorId: seed.vendors.kombo.id,
  storeId: seed.vendors.kombo.id,
  slug: seed.vendors.kombo.slug,
  documentId: seed.kycDocuments.awaIdAccepted,

  productId: seed.products.phone,
  variantId: seed.variants.phone,
  unitId: seed.imei.writtenOff,
  categoryId: 'phones',
  promotionId: seed.promotions.active,
  couponId: seed.coupons.vendorFunded,
  reference: seed.imports.withRowErrors,
  locale: 'fr-SN',
  mediaId: 1,
  imageId: 1,

  cartToken: seed.carts.guestGbp,
  cartItemId: 1071,
  orderId: seed.orders.oliverTwoVendors,
  orderNumber: seed.orders.oliverTwoVendors,
  vendorOrderId: seed.vendorOrders.laminShipped,
  lineId: 1,
  paymentId: 1,
  trackingCode: seed.trackingCodes.order1401,
  code: seed.trackingCodes.order1401,
  token: 'a-token-nobody-issued',

  shipmentId: seed.shipments.inFlight,
  legId: seed.legs.ebrimaInProgress,
  driverId: seed.users.ebrima.id,
  pointId: seed.pickupPoints.westfield,
  zoneId: 1,

  returnId: 1,
  disputeId: 1,
  reviewId: 1,
  threadId: 1,
  caseId: 1,
  callbackId: 1,
  notificationId: 1,
  deviceId: 1,
  batchId: 1,
  payoutId: 1,
  cardId: 1,

  provider: 'stripe',
  jobName: 'finance-exports',
  flagKey: 'delivery.safe-drop',
  period: '2026-09',
  type: 'ORDER',
  targetType: 'PRODUCT',
  targetId: seed.products.phone,
};

/**
 * `{id}` means a different row on each surface, so it is resolved from the path
 * rather than from the name. Longest prefix wins, so /api/users/{id}/password
 * and /api/users/{id} can differ if they ever need to.
 */
const ID_BY_PREFIX = [
  ['/api/admin/orders/', seed.orders.oliverTwoVendors],
  ['/api/categories/', 'phones'],
  ['/api/exchange-rates/', 1220],
  ['/api/users/', seed.users.aminata.id],
  ['/api/vendors/', seed.vendors.kombo.id],
  ['/driver/shipments/', seed.shipments.inFlight],
  ['/pickup-points/', seed.pickupPoints.westfield],
  ['/pickup/points/', seed.pickupPoints.westfield],
];

function valueFor(name, path) {
  if (name === 'id') {
    const match = ID_BY_PREFIX.filter(([prefix]) => path.startsWith(prefix))
      .sort((a, b) => b[0].length - a[0].length)[0];
    if (!match) throw new Error(`no seeded row for {id} on ${path}`);
    return match[1];
  }
  if (!(name in BY_NAME)) throw new Error(`no seeded row for {${name}} on ${path}`);
  return BY_NAME[name];
}

/** Replaces every {param} in a path template with its seeded value. */
function fill(template) {
  return template.replace(/\{(\w+)\}/g, (_, name) => String(valueFor(name, template)));
}

module.exports = { fill, valueFor, BY_NAME };
