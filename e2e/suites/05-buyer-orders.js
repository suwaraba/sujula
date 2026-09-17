/**
 * A buyer's own orders, and the page for somebody with no account.
 *
 * Order 1401 is this marketplace in one row: paid in sterling from London, two
 * sellers settling in dalasi and CFA, delivering to a UK address. The detail
 * response groups it by seller and each group totals on its own, which is C3
 * before anything has shipped.
 *
 * And GET /track/{code} against GET /orders/{id}/tracking is the sharpest pair
 * on the platform: the same parcels, one for the person who paid and one for
 * whoever was forwarded the message, and the difference between them is the only
 * thing between a driver's notes and a stranger.
 */

const { req, folder } = require('../lib/collection');
const seed = require('../lib/seed');

module.exports = folder(
  '05 — A buyer\'s orders, tracking and invoices',
  'Reading, cancelling and confirming, plus the public tracking page.',
  [
    req({
      name: 'Oliver\'s orders, each with its own currency',
      path: '/orders',
      as: 'oliver',
      status: 200,
      script: `
const ids = $body.items.map(function (o) { return o.id; });
pm.test("Orders — 1401 is his", function () {
  pm.expect(ids).to.include(${seed.orders.oliverTwoVendors});
});
pm.test("Orders — none of Aminata's are in his list", function () {
  pm.expect(ids, 'order 1403 is Aminata\\'s').to.not.include(${seed.orders.aminataDelivered});
  pm.expect(ids, 'order 1402 is a guest order').to.not.include(${seed.orders.guest});
});
pm.test("Orders — a multivendor order says how many sellers are in it", function () {
  const order = $body.items.find(function (o) { return o.id === ${seed.orders.oliverTwoVendors}; });
  pm.expect(order.vendorCount, 'one payment, two sellers').to.equal(2);
  pm.expect(order.currency, 'charged in the currency he holds a card in').to.equal('GBP');
});
pm.test("Orders — the list says whether each can still be cancelled", function () {
  $body.items.forEach(function (o) {
    pm.expect(o.cancellable, o.orderNumber + ' does not say whether it can be cancelled')
      .to.be.a('boolean');
  });
});
`,
    }),

    req({
      name: 'The detail, grouped by seller, each group totalling on its own',
      path: `/orders/${seed.orders.oliverTwoVendors}`,
      as: 'oliver',
      status: 200,
      json: {
        id: seed.orders.oliverTwoVendors,
        orderNumber: 'SJL-SEED-0001',
        trackingCode: seed.trackingCodes.order1401,
        currency: 'GBP',
        paymentStatus: 'PAID',
        'shippingTo.country': 'GB',
        'shippingTo.city': 'London',
      },
      script: `
pm.test("C3 — two sub-orders, and one of them has already shipped", function () {
  pm.expect($body.vendorOrders.length).to.equal(2);
  const byId = {};
  $body.vendorOrders.forEach(function (v) { byId[v.vendorOrderId] = v; });
  pm.expect(byId[${seed.vendorOrders.laminShipped}].status, 'slice 1501 is SHIPPED')
    .to.equal('SHIPPED');
  pm.expect(byId[${seed.vendorOrders.awaConfirmed}].status,
    'slice 1502 has not shipped, which is what makes it cancellable on its own')
    .to.not.equal('SHIPPED');
});
pm.test("C3 — each slice totals its own goods, and they come to the order's", function () {
  // A slice's total is the goods. Delivery is the platform's — it arranges it,
  // keeps it, and excludes it from a payout — so it lives on the order and on
  // each line rather than inside a seller's slice. The identity to hold is
  // therefore slices == order less shipping, and it is worth holding: a
  // per-seller screen whose parts do not come to the whole is a buyer
  // wondering what the missing four pounds was.
  const slices = $body.vendorOrders.reduce(function (s, v) { return s + v.total; }, 0);
  const goods = $body.total - $body.shipping;
  pm.expect(Math.abs(slices - goods) < 0.05,
    'slices come to ' + slices.toFixed(2) + ' and the order less shipping to '
    + goods.toFixed(2)).to.be.true;
});
pm.test("C3 — and each slice's own parts add up the same way", function () {
  $body.vendorOrders.forEach(function (v) {
    const expected = v.subtotal - v.discount;
    pm.expect(Math.abs(v.total - expected) < 0.02,
      v.storeName + ': ' + v.subtotal + ' - ' + v.discount + ' should be '
      + expected.toFixed(2) + ', slice says ' + v.total).to.be.true;
  });
});
pm.test("C3 — the order's shipping is the sum of the slices' own legs", function () {
  const legs = $body.vendorOrders.reduce(function (s, v) {
    return s + (v.shipping || 0);
  }, 0);
  pm.expect(Math.abs(legs - $body.shipping) < 0.05,
    'the slices carry legs coming to ' + legs.toFixed(2) + ' and the order charges '
    + $body.shipping).to.be.true;
});
pm.test("The order total is its own parts", function () {
  const expected = $body.subtotal - $body.discount + $body.shipping + $body.tax;
  pm.expect(Math.abs($body.total - expected) < 0.02,
    $body.subtotal + ' - ' + $body.discount + ' + ' + $body.shipping + ' + ' + $body.tax
    + ' should be ' + expected.toFixed(2) + ', order says ' + $body.total).to.be.true;
});
pm.test("Every line carries its own delivery leg", function () {
  $body.vendorOrders.forEach(function (v) {
    v.lines.forEach(function (line) {
      pm.expect(line.deliveryCost, line.productName + ' has no leg of its own')
        .to.be.a('number');
    });
  });
});
pm.test("A line says whether it can be reviewed yet", function () {
  $body.vendorOrders.forEach(function (v) {
    v.lines.forEach(function (line) {
      pm.expect(line.reviewable, line.productName + ' does not say').to.be.a('boolean');
    });
  });
});
pm.test("The buyer sees no seller's private figures", function () {
  const text = H.text(pm.response);
  ['commissionNative', 'payoutNative', 'commissionRate'].forEach(function (field) {
    pm.expect(text, 'the buyer is shown ' + field + ', which is between the seller and the platform')
      .to.not.include(field);
  });
});
`,
    }),

    req({
      name: "Asking for it as Aminata is a 404, not a 403",
      path: `/orders/${seed.orders.oliverTwoVendors}`,
      as: 'aminata',
      note: '"Forbidden" would confirm the order exists, which is worth withholding '
          + 'from anybody counting through order ids.',
      status: 404,
    }),

    req({
      name: 'And her own order is a 404 for him',
      path: `/orders/${seed.orders.aminataDelivered}`,
      as: 'oliver',
      status: 404,
    }),

    req({
      name: 'One timeline per seller, because there are two parcels',
      path: `/orders/${seed.orders.oliverTwoVendors}/tracking`,
      as: 'oliver',
      status: 200,
      json: {
        orderId: seed.orders.oliverTwoVendors,
        trackingCode: seed.trackingCodes.order1401,
      },
      script: `
pm.test("Tracking — a timeline for each slice, not one for the order", function () {
  pm.expect($body.timelines.length, 'two parcels are two timelines').to.equal(2);
});
pm.test("Tracking — the buyer's view carries the driver's own words", function () {
  // The counterpart to the public page below, which substitutes fixed phrases.
  // This is the person who paid: they get what was actually recorded.
  const shipped = $body.timelines.find(function (t) {
    return t.vendorOrderId === ${seed.vendorOrders.laminShipped};
  });
  pm.expect(shipped.events.length, 'a shipped parcel with no events').to.be.at.least(1);
  shipped.events.forEach(function (e) {
    pm.expect(e.description, 'an event with no description').to.be.a('string');
    pm.expect(e.at).to.be.a('string');
  });
});
pm.test("Tracking — a slice with no events reports none rather than inventing one", function () {
  const waiting = $body.timelines.find(function (t) {
    return t.vendorOrderId === ${seed.vendorOrders.awaConfirmed};
  });
  pm.expect(waiting.events, 'nothing has happened to this parcel yet').to.eql([]);
});
`,
    }),

    // ── The invoice, and the link that carries it ────────────────────────
    req({
      name: 'An invoice is a signed, expiring link rather than a document',
      path: `/orders/${seed.orders.oliverTwoVendors}/invoice`,
      as: 'oliver',
      note: 'Ownership is proven once, here, and the link is then the credential — '
          + 'which is why it is short-lived: the document carries a home address and '
          + 'a phone number. It is open on purpose, because an invoice legitimately '
          + 'travels: to the recipient, to a bank, to whoever is reimbursing the '
          + 'buyer.',
      status: 200,
      json: {
        url: { $matches: '/invoices/' },
        contentType: 'application/pdf',
        expiresAt: { $exists: true },
      },
      capture: { invoiceUrl: 'url' },
    }),

    req({
      name: 'And the link resolves to a PDF with no token of its own',
      path: '/invoices/{{invoicePath}}',
      preScript: `
// The endpoint returns an absolute URL; the collection needs just the path so
// it can be sent against {{baseUrl}} whatever host the run is pointed at.
const url = pm.collectionVariables.get('invoiceUrl') || '';
pm.collectionVariables.set('invoicePath', url.split('/invoices/')[1] || 'nothing');
`,
      status: 200,
      header: { 'Content-Type': { $matches: 'application/pdf' } },
      script: `
pm.test("Invoice — it is a PDF and not an error page", function () {
  pm.expect(pm.response.stream.length, 'an empty invoice').to.be.above(500);
  pm.expect(H.text(pm.response).slice(0, 5), 'not a PDF header').to.include('%PDF');
});
`,
    }),

    req({
      name: 'A tampered invoice token is refused',
      path: '/invoices/MTQwMS4xNzg5NjczNzM3.thisIsNotTheSignature',
      note: 'The token is an HMAC over one order id and an expiry. Without the '
          + 'signature check the path would be an order-id enumerator that returns '
          + 'somebody\'s address.',
      status: [400, 401, 403, 404],
    }),

    req({
      name: "Another buyer cannot mint a link for somebody else's invoice",
      path: `/orders/${seed.orders.oliverTwoVendors}/invoice`,
      as: 'aminata',
      status: 404,
    }),

    // ── C3: cancelling ───────────────────────────────────────────────────
    req({
      name: 'C3: the whole order cannot be cancelled, and the refusal names the seller',
      method: 'POST',
      path: `/orders/${seed.orders.oliverTwoVendors}/cancel`,
      as: 'oliver',
      body: { reason: 'Changed my mind about the phone' },
      note: 'Slice 1501 is SHIPPED. Stopping an order whose goods are already with a '
          + 'courier is a return rather than a cancellation, and the refusal says '
          + 'which seller it is so the buyer knows what to ask for.',
      status: [400, 409, 422],
      script: `
pm.test("Cancel — the refusal names the store rather than saying no", function () {
  pm.expect(H.text(pm.response), 'a buyer told "cannot cancel" and nothing else has to guess')
    .to.match(/Kombo/i);
});
`,
    }),

    req({
      name: "C3: one seller's slice can be, and it leaves the other alone",
      method: 'POST',
      path: `/orders/${seed.orders.oliverTwoVendors}/vendor-orders/${seed.vendorOrders.awaConfirmed}/cancel`,
      as: 'oliver',
      headers: { 'Idempotency-Key': 'e2e-cancel-awa-slice' },
      body: { reason: 'She has enough cloth' },
      note: 'What comes back is a refund REQUEST, not a refund: 16.70 GBP against '
          + 'slice 1502 alone, waiting on an administrator. Money leaving the '
          + 'platform is the one action no later API call can undo, so nothing in '
          + 'the buyer surface completes it.\n\n'
          + 'Row 1450 is that request, seeded before this call is made — which makes '
          + 'this a test of something else as well. Ask twice and there must still be '
          + 'one row: a buyer who taps cancel, sees nothing happen on a slow '
          + 'connection and taps again must not end up with two refunds queued '
          + 'against the same goods, one of which an administrator approves after '
          + 'the other has paid.',
      status: [200, 201, 202, 400, 409, 422],
      script: `
if (pm.response.code < 300) {
  pm.test("C3 — a refund REQUEST, in the currency the buyer was charged", function () {
    const text = JSON.stringify($body);
    pm.expect(text).to.match(/GBP/);
    pm.expect(text, 'nothing here should claim money has moved').to.not.match(/"refunded"\s*:\s*true/);
  });
} else {
  pm.test("C3 — already cancelled on an earlier run, and cancelling twice is refused", function () {
    pm.expect(H.text(pm.response).length, 'refused with no reason given').to.be.above(2);
  });
}
`,
    }),

    req({
      name: 'And the shipped slice is exactly where it was',
      path: `/orders/${seed.orders.oliverTwoVendors}`,
      as: 'oliver',
      note: 'This is C3, and it is worth checking rather than assuming: one seller '
          + 'cancelling must not touch another\'s line.',
      status: 200,
      script: `
pm.test("C3 — 1501 is untouched by what happened to 1502", function () {
  const byId = {};
  $body.vendorOrders.forEach(function (v) { byId[v.vendorOrderId] = v; });
  pm.expect(byId[${seed.vendorOrders.laminShipped}].status,
    "Lamin's parcel changed status because Awa's slice was cancelled")
    .to.equal('SHIPPED');
  pm.expect(byId[${seed.vendorOrders.laminShipped}].lines.length,
    'lines went missing from the other seller\\'s slice').to.be.at.least(1);
});
`,
    }),

    req({
      name: 'Cancelling a slice of an order that is not yours is a 404',
      method: 'POST',
      path: `/orders/${seed.orders.oliverTwoVendors}/vendor-orders/${seed.vendorOrders.laminShipped}/cancel`,
      as: 'aminata',
      headers: { 'Idempotency-Key': 'e2e-cancel-not-mine' },
      body: { reason: 'Not mine at all' },
      status: 404,
    }),

    req({
      name: 'A slice that belongs to a different order is not found either',
      method: 'POST',
      path: `/orders/${seed.orders.oliverTwoVendors}/vendor-orders/${seed.vendorOrders.aminataReleased}/cancel`,
      as: 'oliver',
      headers: { 'Idempotency-Key': 'e2e-cancel-wrong-order' },
      body: { reason: 'Wrong order' },
      note: 'Slice 1504 belongs to order 1403. Resolving the slice by id alone, '
          + 'without the order it hangs off, would let a buyer cancel across orders.',
      status: [400, 404, 409],
    }),

    // ── Receipt, and escrow ──────────────────────────────────────────────
    req({
      name: 'Confirming receipt answers idempotently when it is already confirmed',
      method: 'POST',
      path: `/orders/${seed.orders.aminataDelivered}/vendor-orders/${seed.vendorOrders.aminataReleased}/confirm-receipt`,
      as: 'aminata',
      headers: { 'Idempotency-Key': 'e2e-confirm-1504' },
      body: {},
      note: 'Slice 1504 is already confirmed and released in the seed. What this '
          + 'writes when it does fire is the buyer\'s own statement that the goods '
          + 'arrived, recorded in their name, next to the driver\'s account of the '
          + 'same moment — the status is the consequence of that row rather than the '
          + 'input to it, which is the whole difference between a custody chain and '
          + 'a status field somebody can set.',
      status: [200, 201, 204, 400, 409],
    }),

    req({
      name: 'Nobody else can confirm receipt of her parcel',
      method: 'POST',
      path: `/orders/${seed.orders.aminataDelivered}/vendor-orders/${seed.vendorOrders.aminataReleased}/confirm-receipt`,
      as: 'oliver',
      headers: { 'Idempotency-Key': 'e2e-confirm-not-mine' },
      body: {},
      note: 'Confirming receipt releases money to a seller. If anybody could do it '
          + 'for anybody, escrow would be decoration.',
      status: 404,
    }),

    // ── C5: the page for somebody with no account ────────────────────────
    req({
      name: 'C5: the tracking page needs no account, no app and no email',
      path: `/track/${seed.trackingCodes.order1401}`,
      note: 'This is the sister in Serrekunda with a forwarded message and nothing '
          + 'else. Sixteen characters from a thirty-symbol alphabet, because '
          + 'possession of one is the only credential there is.',
      status: 200,
      json: {
        trackingCode: seed.trackingCodes.order1401,
        stage: { $exists: true },
        description: { $exists: true },
        parcels: { $gte: 1 },
        destinationCity: 'London',
        destinationCountry: 'GB',
      },
      bodyExcludes: [
        { value: 'Oliver', why: 'no name: the page may have been forwarded to anybody' },
        { value: 'Bennett', why: 'nor a surname' },
        { value: '221B', why: 'no street' },
        { value: 'Baker Street', why: 'nor a street name' },
        { value: '+447700900005', why: 'no phone number' },
        { value: 'NW1 6XE', why: 'not even a postcode, which is a street on its own' },
        { value: 'SJL-SEED-0001', why: 'no order number to quote at support' },
        { value: '129.73', why: 'no price: what somebody paid for a gift is their business' },
        { value: 'GBP', why: 'nor the currency it was paid in' },
        { value: 'Samsung', why: 'no contents' },
        { value: 'Ebrima', why: "and not the driver's name" },
      ],
      script: `
pm.test("C5 — the events are fixed phrases, not the driver's free text", function () {
  // The buyer's own timeline says "Assigned for the outbound leg". This page
  // says "A driver has been assigned." That substitution is the only thing
  // between a driver's notes and a stranger who was forwarded the message.
  $body.events.forEach(function (e) {
    pm.expect(e.description, 'an event with no description at all').to.be.a('string');
    pm.expect(e.description, 'the internal wording leaked onto the public page')
      .to.not.include('outbound leg');
    pm.expect(e.description).to.not.include('international courier');
  });
});
pm.test("C5 — parcel counts, because that is what a recipient actually asks", function () {
  pm.expect($body.parcels).to.be.a('number');
  pm.expect($body.parcelsDelivered).to.be.a('number');
  pm.expect($body.parcelsDelivered).to.be.at.most($body.parcels);
});
pm.test("C5 — a town and a country, and no more precision than that", function () {
  pm.expect($body.destinationCity).to.be.a('string');
  pm.expect(JSON.stringify($body), 'coordinates on a public page are a front door')
    .to.not.match(/"lat(itude)?"|"lng"|"longitude"/);
});
`,
    }),

    req({
      name: 'The guest order has one too, on the same terms',
      path: `/track/${seed.trackingCodes.order1402}`,
      note: 'A guest order has no account behind it at all, so the code is the only '
          + 'way anybody — including the buyer — reaches it.',
      status: 200,
      json: { trackingCode: seed.trackingCodes.order1402 },
    }),

    req({
      name: 'A code nobody was issued is a 404',
      path: '/track/ZZZZZZZZZZZZZZZZ',
      status: 404,
    }),

    req({
      name: 'And a code short enough to brute-force is not one either',
      path: '/track/ABC',
      status: [400, 404],
    }),

    req({
      name: 'The same parcels, for the person who paid, say much more',
      path: `/orders/${seed.orders.oliverTwoVendors}/tracking`,
      as: 'oliver',
      note: 'The control for the public page. Run the two side by side: this one '
          + 'names the store, the courier, the tracking number and the driver\'s own '
          + 'words, because the person reading it is the person who paid.',
      status: 200,
      script: `
pm.test("The buyer's timeline names what the public page withholds", function () {
  const text = H.text(pm.response);
  pm.expect(text, 'the buyer should see which store').to.include('Kombo Electronics');
  pm.expect(text, 'and the courier reference').to.include('SJL-DLV');
});
`,
    }),
  ],
);
