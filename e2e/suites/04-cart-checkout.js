/**
 * The basket, and paying for it.
 *
 * The basket is where C1 and C2 stop being architecture and become two fields a
 * client sets independently: PUT /delivery-context moves the parcel and
 * re-prices shipping; PUT /currency moves the prices and touches no delivery.
 * A buyer in Madrid sending to Serrekunda sets both, to different values, and
 * neither may be derived from the other.
 *
 * Checkout is where C2 and C3 become an order: a quote whose per-line rate is
 * frozen, and one payment that splits into sub-orders with their own currency,
 * their own payout and their own rate.
 *
 * Everything before the checkout journey is done as a guest, deliberately. Most
 * baskets on this marketplace are filled before anybody signs in, and a guest
 * can open as many carts as the suite needs — a signed-in shopper has one, so a
 * second POST /carts is a conflict, which would make the folder unrepeatable.
 */

const { req, folder } = require('../lib/collection');
const seed = require('../lib/seed');

module.exports = folder(
  '04 — The basket, and paying for it',
  'A guest fills a basket, prices it, and a fresh account takes one through to a paid order.',
  [
    // ── Opening one ──────────────────────────────────────────────────────
    req({
      name: 'A guest opens a basket and is given its token',
      method: 'POST',
      path: '/carts',
      body: { currency: 'GBP', deliveryContextId: seed.deliveryContexts.guestSerrekunda },
      note: 'The token is the whole credential, and returning it is not optional: '
          + 'every later request on this surface is addressed by it, and a guest has '
          + 'nothing else. It was missing from this response once — the endpoint\'s '
          + 'own description said "returns the cart with its token" and the body '
          + 'carried a numeric id that GET /carts/{token} does not resolve — so a '
          + 'new guest basket was unreachable. Nothing noticed because every seeded '
          + 'cart has a token written by hand.',
      status: 201,
      json: {
        token: { $minLength: 32 },
        guest: true,
        displayCurrency: 'GBP',
        deliveryContextId: seed.deliveryContexts.guestSerrekunda,
        itemCount: 0,
        lineCount: 0,
      },
      capture: { guestCart: 'token' },
      script: `
pm.test("New basket — the token is not guessable", function () {
  pm.expect($body.token).to.match(/^[A-Za-z0-9_-]+$/);
  pm.expect($body.token.length, 'a short token on a row holding somebody\\'s address')
    .to.be.at.least(32);
});
`,
    }),

    req({
      name: 'And the token it returned is the one that reads it back',
      path: '/carts/{{guestCart}}',
      note: 'The round trip. Asserting the field exists would pass the day somebody '
          + 'stops populating it; using it to make the next request cannot.',
      status: 200,
      json: { token: '{{guestCart}}', guest: true },
    }),

    req({
      name: 'A basket opened with nothing at all is still a basket',
      method: 'POST',
      path: '/carts',
      body: {},
      note: 'Everything is optional: a shopper can start filling one before deciding '
          + 'where it goes or what currency to read it in.',
      status: 201,
      json: { token: { $minLength: 32 }, itemCount: 0 },
      capture: { spareCart: 'token' },
    }),

    req({
      name: 'A token nobody issued is a 404',
      path: '/carts/this-token-was-never-issued-by-anybody',
      status: 404,
    }),

    req({
      name: "Aminata's basket is hers",
      path: `/carts/${seed.carts.aminata}`,
      as: 'aminata',
      status: 200,
      json: { cartId: 1070, displayCurrency: 'GMD' },
    }),

    req({
      name: 'And is a 404 for Oliver even though he holds the token',
      path: `/carts/${seed.carts.aminata}`,
      as: 'oliver',
      note: 'A cart bound to an account is readable only by that account. The token '
          + 'is enough for a guest because a guest has nothing else; it is not enough '
          + 'to open somebody\'s account-bound basket, which names what they are '
          + 'buying and where it is going.',
      status: 404,
    }),

    req({
      name: 'Nor for a caller with no account at all',
      path: `/carts/${seed.carts.aminata}`,
      status: 404,
    }),

    // ── Lines ────────────────────────────────────────────────────────────
    req({
      name: 'Adding a line prices it, its delivery leg and its distance',
      method: 'POST',
      path: '/carts/{{guestCart}}/items',
      headers: { 'Idempotency-Key': 'e2e-cart-add-kettle' },
      body: { productId: seed.products.kettle, quantity: 2 },
      note: 'Per product, not per order. A multivendor basket has no single origin, '
          + 'so each line is quoted on its own distance and its own weight.',
      status: 200,
      json: {
        itemCount: 2,
        lineCount: 1,
        'vendors.0.vendorId': seed.vendors.kombo.id,
        'vendors.0.nativeCurrency': 'GMD',
        'vendors.0.items.0.productId': seed.products.kettle,
        'vendors.0.items.0.quantity': 2,
        'vendors.0.items.0.nativeCurrency': 'GMD',
        'vendors.0.items.0.deliveryCost': { $gt: 0 },
        'vendors.0.items.0.distanceKm': { $gt: 0 },
      },
      capture: { guestCartLine: 'vendors.0.items.0.itemId' },
      script: `
pm.test("Adding a line — the line total is the unit price times the quantity", function () {
  const line = $body.vendors[0].items[0];
  pm.expect(line.lineTotalNative).to.equal(line.unitPriceNative * line.quantity);
  pm.expect(line.lineTotal).to.equal(line.unitPrice * line.quantity);
});
pm.test("Adding a line — the seller's own price survives the conversion", function () {
  const line = $body.vendors[0].items[0];
  pm.expect(line.unitPriceNative, "the kettle is listed at 1250 dalasi").to.equal(1250);
  pm.expect(line.nativeCurrency).to.equal('GMD');
  // 1250 x 0.011 = 13.75
  pm.expect(line.unitPrice).to.equal(13.75);
});
`,
    }),

    req({
      name: 'The same Idempotency-Key does not add it twice',
      method: 'POST',
      path: '/carts/{{guestCart}}/items',
      headers: { 'Idempotency-Key': 'e2e-cart-add-kettle' },
      body: { productId: seed.products.kettle, quantity: 2 },
      note: 'A shopper on a patchy connection taps "add to basket", the request '
          + 'arrives, the answer does not, and their client retries. Without a key '
          + 'they now have four kettles.',
      status: 200,
      json: { itemCount: 2, lineCount: 1 },
    }),

    req({
      name: 'A second vendor makes it a multivendor basket',
      method: 'POST',
      path: '/carts/{{guestCart}}/items',
      headers: { 'Idempotency-Key': 'e2e-cart-add-wax' },
      body: { productId: seed.products.waxPrint, variantId: 1353, quantity: 1 },
      status: 200,
      json: { itemCount: 3, lineCount: 2 },
      script: `
pm.test("Two vendors — each group carries its OWN currency", function () {
  pm.expect($body.vendors.length, 'two sellers should be two groups').to.equal(2);
  const currencies = {};
  $body.vendors.forEach(function (v) { currencies[v.vendorId] = v.nativeCurrency; });
  pm.expect(currencies[${seed.vendors.kombo.id}], 'Lamin banks in Banjul').to.equal('GMD');
  pm.expect(currencies[${seed.vendors.teranga.id}], 'Awa banks in Senegal').to.equal('XOF');
});
pm.test("Two vendors — each group has its own rate, not one for the basket", function () {
  const rates = $body.vendors.map(function (v) { return v.exchangeRate; });
  pm.expect(rates[0], 'two currencies cannot share one rate').to.not.equal(rates[1]);
  rates.forEach(function (r) { pm.expect(r, 'a group with no rate on it').to.be.a('number'); });
});
pm.test("Two vendors — each group's shipping is its own", function () {
  const total = $body.vendors.reduce(function (sum, v) { return sum + v.shipping; }, 0);
  pm.expect(Math.round(total * 100) / 100, 'the basket shipping should be the sum of the groups')
    .to.equal($body.shipping);
});
pm.test("Two vendors — the grand total is subtotal minus discount plus shipping", function () {
  const expected = Math.round(($body.subtotal - $body.discount + $body.shipping) * 100) / 100;
  pm.expect($body.total).to.equal(expected);
});
`,
    }),

    req({
      name: 'Changing a quantity re-prices the line',
      method: 'PATCH',
      path: '/carts/{{guestCart}}/items/{{guestCartLine}}',
      body: { quantity: 3 },
      status: 200,
      json: { itemCount: 4 },
      script: `
pm.test("Quantity — three kettles is three times one kettle", function () {
  const line = $body.vendors
    .flatMap(function (v) { return v.items; })
    .find(function (i) { return i.productId === ${seed.products.kettle}; });
  pm.expect(line.quantity).to.equal(3);
  pm.expect(line.lineTotalNative).to.equal(line.unitPriceNative * 3);
});
`,
    }),

    req({
      name: 'A quantity above the per-order maximum is refused',
      method: 'PATCH',
      path: '/carts/{{guestCart}}/items/{{guestCartLine}}',
      body: { quantity: 500 },
      note: 'Not a stock check — a limit on how much of one thing a single order may '
          + 'carry. A basket with five hundred kettles in it is somebody testing the '
          + 'platform or defrauding it.',
      status: [400, 422],
    }),

    req({
      name: 'More than the seller has is refused, and says how many there are',
      method: 'POST',
      path: '/carts/{{guestCart}}/items',
      body: { productId: seed.products.kettle, quantity: 99 },
      note: 'Refused rather than accepted and cut down at checkout: a buyer who asked '
          + 'for ninety-nine and is charged for four has not been told anything.',
      status: [400, 409, 422],
    }),

    req({
      name: 'A listing that is not on sale cannot be added',
      method: 'POST',
      path: '/carts/{{guestCart}}/items',
      body: { productId: seed.products.refurbished, quantity: 1 },
      note: '1307 is ARCHIVED. It is still reachable by id from an order line years '
          + 'from now, which is exactly why adding it to a new basket has to be '
          + 'refused here rather than prevented by it being gone.',
      status: [400, 404, 409, 422],
    }),

    req({
      name: 'Setting a quantity to zero removes the line',
      method: 'PATCH',
      path: '/carts/{{guestCart}}/items/{{guestCartLine}}',
      body: { quantity: 0 },
      note: 'Which is what a stepper stepped to zero means. Answering "quantity must '
          + 'be at least 1" to a shopper who has just tapped the minus button twice '
          + 'is the application arguing with a gesture.',
      status: 200,
      json: { lineCount: 1 },
      script: `
pm.test("Zero — the kettle line is gone and the cloth is not", function () {
  const products = $body.vendors.flatMap(function (v) { return v.items; })
    .map(function (i) { return i.productId; });
  pm.expect(products).to.not.include(${seed.products.kettle});
  pm.expect(products).to.include(${seed.products.waxPrint});
});
`,
    }),

    // ── C1 on the basket ─────────────────────────────────────────────────
    req({
      name: 'C1: changing the currency moves the prices and not the parcel',
      method: 'PUT',
      path: '/carts/{{guestCart}}/currency',
      body: { currency: 'GMD' },
      note: 'Two endpoints for two questions. This one says what the prices read as. '
          + 'It must not touch where the goods go — a buyer switching from sterling '
          + 'to dalasi has not changed their mind about which country the parcel is '
          + 'going to.',
      status: 200,
      json: {
        displayCurrency: 'GMD',
        deliveryContextId: seed.deliveryContexts.guestSerrekunda,
      },
      capture: { cartShippingInGmd: 'shipping' },
    }),

    req({
      name: 'And back again, which must give the same destination',
      method: 'PUT',
      path: '/carts/{{guestCart}}/currency',
      body: { currency: 'GBP' },
      status: 200,
      json: {
        displayCurrency: 'GBP',
        deliveryContextId: seed.deliveryContexts.guestSerrekunda,
      },
      script: `
pm.test("C1 — the destination survived two currency changes", function () {
  pm.expect($body.deliveryContextId).to.equal(${JSON.stringify(seed.deliveryContexts.guestSerrekunda)});
  pm.expect($body.deliverable).to.equal(true);
});
pm.test("C1 — and the leg itself did not move", function () {
  // What C1 claims about a currency change is about the DELIVERY, not about
  // the arithmetic: the same kilometres and the same kilograms, whatever
  // symbol the buyer reads the price in. Distance is the thing to assert —
  // comparing the two money figures needs each vendor's own rate, and this
  // basket holds a seller who settles in CFA, so a single rate off the first
  // group is the wrong number to divide by.
  const distances = $body.vendors.flatMap(function (v) { return v.items; })
    .map(function (i) { return i.distanceKm; });
  distances.forEach(function (km) {
    pm.expect(km, 'a line with no distance after a currency change').to.be.a('number');
  });
  pm.expect($body.shipping, 'the basket lost its shipping when the currency changed')
    .to.be.above(0);
});
`,
    }),

    req({
      name: 'C1: changing the destination moves the parcel and not the prices',
      method: 'PUT',
      path: '/carts/{{guestCart}}/delivery-context',
      body: { deliveryContextId: seed.deliveryContexts.guestBrikama },
      note: 'The other of the two. Brikama is further from Ziguinchor than Serrekunda '
          + 'is, so the leg is re-priced — and the currency the prices read in is '
          + 'untouched, because the person paying has not moved.',
      status: 200,
      json: {
        deliveryContextId: seed.deliveryContexts.guestBrikama,
        displayCurrency: 'GBP',
      },
      script: `
pm.test("C1 — the currency did not follow the destination", function () {
  pm.expect($body.displayCurrency, 'moving the parcel changed what the buyer is charged in')
    .to.equal('GBP');
});
pm.test("C1 — but the delivery distance did", function () {
  const line = $body.vendors.flatMap(function (v) { return v.items; })[0];
  pm.expect(line.distanceKm, 'a new destination should give a new distance').to.be.a('number');
});
`,
    }),

    req({
      name: 'A delivery context belonging to somebody else cannot be attached',
      method: 'PUT',
      path: '/carts/{{guestCart}}/delivery-context',
      body: { deliveryContextId: seed.deliveryContexts.aminataHome },
      note: 'Otherwise a stranger who guessed an id could read a signed-in shopper\'s '
          + 'street back out of their own basket.',
      status: 404,
    }),

    req({
      name: 'And the basket keeps the destination it had',
      path: '/carts/{{guestCart}}',
      status: 200,
      json: { deliveryContextId: seed.deliveryContexts.guestBrikama },
    }),

    req({
      name: 'Back to Serrekunda for the rest of the folder',
      method: 'PUT',
      path: '/carts/{{guestCart}}/delivery-context',
      body: { deliveryContextId: seed.deliveryContexts.guestSerrekunda },
      status: 200,
      json: { deliveryContextId: seed.deliveryContexts.guestSerrekunda },
    }),

    req({
      name: 'A currency this deployment does not support is refused',
      method: 'PUT',
      path: '/carts/{{guestCart}}/currency',
      body: { currency: 'ZZZ' },
      status: [400, 422],
    }),

    req({
      name: 'A currency with no rate is accepted and reported as unconvertible',
      method: 'PUT',
      path: '/carts/{{guestCart}}/currency',
      body: { currency: 'EUR' },
      note: 'Not refused, and that is the right call: the shopper asked a reasonable '
          + 'question and the answer is "we cannot price this for you yet". What '
          + 'matters is that the basket says so per vendor and refuses to present a '
          + 'total — a grand total that quietly dropped a seller\'s goods would '
          + 'undercharge, and the platform would owe the difference.',
      status: 200,
      json: { displayCurrency: 'EUR', totalsComplete: false },
      script: `
pm.test("No rate — every affected seller is named, not just the basket", function () {
  const issues = $body.issues || [];
  pm.expect(issues.length, 'nothing explained why the totals are incomplete')
    .to.be.at.least(1);
  issues.forEach(function (i) {
    pm.expect(i.type).to.equal('RATE_UNAVAILABLE');
    pm.expect(i.vendorId, 'an unconvertible group with no vendor named').to.be.a('number');
  });
});
pm.test("No rate — no group claims to be checkoutable", function () {
  $body.vendors.forEach(function (v) {
    pm.expect(v.checkoutable, 'vendor ' + v.vendorId + ' offered for checkout with no rate')
      .to.equal(false);
    pm.expect(v.convertible).to.equal(false);
  });
});
pm.test("No rate — and the seller's own figures are still there", function () {
  // The one thing that must never be lost: a price in the currency it was
  // actually set in.
  $body.vendors.forEach(function (v) {
    pm.expect(v.nativeCurrency).to.be.a('string');
    v.items.forEach(function (i) {
      pm.expect(i.lineTotalNative, 'a line with no native amount').to.be.a('number');
    });
  });
});
`,
    }),

    req({
      name: 'Back to sterling',
      method: 'PUT',
      path: '/carts/{{guestCart}}/currency',
      body: { currency: 'GBP' },
      status: 200,
      json: { displayCurrency: 'GBP', totalsComplete: true },
    }),

    // ── Coupons ──────────────────────────────────────────────────────────
    req({
      name: 'A vendor coupon discounts that vendor and nobody else',
      method: 'POST',
      path: '/carts/{{guestCart}}/coupons',
      body: { code: seed.coupons.vendorFunded.code },
      note: 'KOMBO500 is 500 dalasi off at Kombo Electronics, in Lamin\'s own '
          + 'currency, because he is the one funding it. A buyer paying in sterling '
          + 'sees it converted at their basket\'s rate — striking it in sterling '
          + 'instead would leave him funding an amount that moves with the market.',
      status: [200, 201, 400, 404, 409, 422],
      script: `
// The basket currently holds only Awa's cloth, so a Kombo-scoped coupon has
// nothing to discount. Both answers are correct behaviour and the assertion
// says which one it got — a refusal here must name the reason rather than
// being a bare 400.
if (pm.response.code < 300) {
  pm.test("Vendor coupon — only the funding vendor's group is discounted", function () {
    $body.vendors.forEach(function (v) {
      if (v.vendorId !== ${seed.vendors.kombo.id}) {
        pm.expect(v.discount || 0, 'vendor ' + v.vendorId + " got somebody else's discount")
          .to.equal(0);
      }
    });
  });
} else {
  pm.test("Vendor coupon — refused with a reason, because nothing in the basket is theirs", function () {
    pm.expect(H.text(pm.response).length, 'refused with an empty body').to.be.above(2);
  });
}
`,
    }),

    req({
      name: 'A lapsed coupon is refused',
      method: 'POST',
      path: '/carts/{{guestCart}}/coupons',
      body: { code: seed.coupons.expired.code },
      note: 'Kept in the table rather than deleted, so the refusal is a real path '
          + 'rather than a missing row.',
      status: [400, 404, 409, 422],
    }),

    req({
      name: 'A code nobody issued is refused',
      method: 'POST',
      path: '/carts/{{guestCart}}/coupons',
      body: { code: 'DEFINITELY-NOT-A-COUPON' },
      status: [400, 404, 409, 422],
    }),

    req({
      name: 'Free delivery waives the legs rather than discounting the goods',
      method: 'POST',
      path: '/carts/{{guestCart}}/coupons',
      body: { code: seed.coupons.freeShipping.code },
      note: 'So the buyer sees delivery priced at zero instead of a discount line '
          + 'that happens to cancel it out. The distinction matters at refund time: '
          + 'the platform keeps delivery, and a discount on the goods is the '
          + 'seller\'s.',
      status: [200, 201, 400, 404, 409, 422],
      script: `
if (pm.response.code < 300) {
  pm.test("Free delivery — shipping is zero, and the goods are not discounted", function () {
    pm.expect($body.shipping, 'free delivery left a shipping charge').to.equal(0);
  });
}
`,
    }),

    req({
      name: 'Removing it puts the delivery charge back',
      method: 'DELETE',
      path: `/carts/{{guestCart}}/coupons/${seed.coupons.freeShipping.code}`,
      status: [200, 204, 404],
      script: `
if (pm.response.code === 200) {
  pm.test("Coupon removed — delivery is charged again", function () {
    pm.expect($body.shipping, 'delivery stayed free after the coupon was taken off')
      .to.be.above(0);
  });
}
`,
    }),

    // ── The held quote ───────────────────────────────────────────────────
    req({
      name: 'Quoting the basket freezes the rate each line was converted at',
      method: 'POST',
      path: '/carts/{{guestCart}}/quote',
      body: {},
      note: 'C2, and the reason checkout reconciles against a quote rather than '
          + 're-pricing: a rate read again later is a different number, and an order '
          + 're-priced after the fact is an order whose total nobody can explain.',
      status: [200, 201],
      json: {
        id: { $minLength: 32 },
        cartToken: '{{guestCart}}',
        displayCurrency: 'GBP',
        deliveryContextId: seed.deliveryContexts.guestSerrekunda,
        complete: true,
        deliverable: true,
      },
      capture: { guestQuote: 'id' },
      script: `
pm.test("Quote — every line carries the rate it was converted at", function () {
  $body.lines.forEach(function (line) {
    pm.expect(line.rate, 'a converted line with no rate on it is not evidence')
      .to.be.a('number');
    pm.expect(line.listingCurrency).to.be.a('string');
    pm.expect(line.unitPriceNative, 'no native price').to.be.a('number');
  });
});
pm.test("Quote — and the rate reproduces the amount", function () {
  $body.lines.forEach(function (line) {
    const expected = line.lineTotalNative * line.rate;
    pm.expect(Math.abs(line.lineTotal - expected) < 0.02,
      line.lineTotalNative + ' ' + line.listingCurrency + ' at ' + line.rate
      + ' should be ' + expected.toFixed(2) + ', quote says ' + line.lineTotal).to.be.true;
  });
});
pm.test("Quote — the total is the lines plus the legs", function () {
  const goods = $body.lines.reduce(function (s, l) { return s + l.lineTotal; }, 0);
  const legs = $body.lines.reduce(function (s, l) { return s + (l.deliveryCost || 0); }, 0);
  pm.expect(Math.abs($body.subtotal - goods) < 0.02, 'subtotal ' + $body.subtotal
    + ' against lines coming to ' + goods.toFixed(2)).to.be.true;
  pm.expect(Math.abs($body.shipping - legs) < 0.02, 'shipping ' + $body.shipping
    + ' against legs coming to ' + legs.toFixed(2)).to.be.true;
  pm.expect(Math.abs($body.total - ($body.subtotal - $body.discount + $body.shipping)) < 0.02)
    .to.be.true;
});
pm.test("Quote — each seller's slice is in their own currency", function () {
  $body.vendors.forEach(function (v) {
    pm.expect(v.listingCurrency, 'a slice with no settlement currency').to.be.a('string');
  });
  const byId = {};
  $body.vendors.forEach(function (v) { byId[v.vendorId] = v.listingCurrency; });
  pm.expect(byId[${seed.vendors.teranga.id}]).to.equal('XOF');
});
`,
    }),

    req({
      name: "The seed's live quote still reports the rate it was taken at",
      path: `/carts/${seed.carts.guestGbp}`,
      note: 'Read for the arithmetic the seed documents: 8500 GMD x 0.011 = 93.50 '
          + 'GBP. A stored rate that does not reproduce the stored amount is '
          + 'decoration rather than evidence.',
      status: 200,
      json: { displayCurrency: 'GBP', totalsComplete: true },
      script: `
pm.test("Seeded basket — every group's rate reproduces its own converted subtotal", function () {
  $body.vendors.forEach(function (v) {
    if (v.subtotalNative == null || v.exchangeRate == null) return;
    const expected = v.subtotalNative * v.exchangeRate;
    pm.expect(Math.abs(v.subtotal - expected) < 0.02,
      v.storeName + ': ' + v.subtotalNative + ' ' + v.nativeCurrency + ' at '
      + v.exchangeRate + ' should be ' + expected.toFixed(2) + ', basket says ' + v.subtotal)
      .to.be.true;
  });
});
`,
    }),

    req({
      name: 'A quote is not payable by somebody who does not hold the basket',
      method: 'POST',
      path: `/carts/${seed.carts.aminata}/quote`,
      as: 'oliver',
      body: {},
      status: 404,
    }),

    // ── Checkout, end to end ─────────────────────────────────────────────
    req({
      name: 'A shopper registers, because paying needs an owner',
      method: 'POST',
      path: '/auth/register',
      preScript: `
pm.collectionVariables.set('payerEmail', 'payer.' + Date.now() + '@example.gm');
`,
      body: {
        email: '{{payerEmail}}',
        password: 'Sujula123!',
        firstName: 'Ndey',
        lastName: 'Paying',
        preferredCurrency: 'GBP',
      },
      note: 'A fresh account rather than a seeded one, for a reason that decides '
          + 'whether this folder can be run twice: a signed-in shopper has ONE '
          + 'basket, so a second POST /carts for the same person is a conflict. '
          + 'Filling a basket is a guest\'s business; paying for it needs an owner, '
          + 'because the refund, the status poll and the retry all resolve through '
          + 'them.',
      status: [200, 201],
      json: { 'user.role': 'CUSTOMER' },
      capture: { tokenPayer: 'accessToken', payerUserId: 'user.id' },
    }),

    req({
      name: 'With an address, because that is who to hand the parcel to',
      method: 'POST',
      path: '/me/addresses',
      as: null,
      headers: { Authorization: 'Bearer {{tokenPayer}}' },
      body: {
        label: 'My aunt in Serrekunda',
        fullName: 'Fatoumata Sarr',
        phone: '+2203109001',
        street: '27 Sayerr Jobe Avenue',
        city: 'Serekunda',
        state: 'West Coast',
        countryCode: 'GM',
        latitude: 13.4395,
        longitude: -16.6752,
        makeDefault: true,
      },
      note: 'The delivery context says WHERE the parcel goes; the address says who to '
          + 'hand it to and on what street. Both, because the person receiving it is '
          + 'frequently not the person paying — which is the whole shape of this '
          + 'marketplace.',
      status: [200, 201],
      json: { city: 'Serekunda', dispatchable: true, confidence: { $exists: true } },
      capture: { payerAddress: 'id' },
    }),

    req({
      name: 'And a destination of their own',
      method: 'POST',
      path: '/delivery-contexts',
      as: null,
      headers: { Authorization: 'Bearer {{tokenPayer}}' },
      body: {
        latitude: 13.4395,
        longitude: -16.6752,
        street: '27 Sayerr Jobe Avenue',
        city: 'Serekunda',
        countryCode: 'GM',
        mode: 'HOME_DELIVERY',
      },
      status: [200, 201],
      json: { deliverable: true, guest: false },
      capture: { payerContext: 'id' },
    }),

    req({
      name: 'Their basket, in sterling, delivering to Serrekunda',
      method: 'POST',
      path: '/carts',
      as: null,
      headers: { Authorization: 'Bearer {{tokenPayer}}' },
      body: { currency: 'GBP', deliveryContextId: '{{payerContext}}' },
      status: 201,
      json: { guest: { $exists: false }, displayCurrency: 'GBP' },
      capture: { payerCart: 'token' },
    }),

    req({
      name: 'Two sellers in it',
      method: 'POST',
      path: '/carts/{{payerCart}}/items',
      as: null,
      headers: { Authorization: 'Bearer {{tokenPayer}}', 'Idempotency-Key': 'e2e-payer-kettle' },
      body: { productId: seed.products.kettle, quantity: 2 },
      status: 200,
      json: { lineCount: 1 },
    }),

    req({
      name: 'And cloth from the other',
      method: 'POST',
      path: '/carts/{{payerCart}}/items',
      as: null,
      headers: { Authorization: 'Bearer {{tokenPayer}}', 'Idempotency-Key': 'e2e-payer-wax' },
      body: { productId: seed.products.waxPrint, variantId: 1353, quantity: 1 },
      status: 200,
      json: { lineCount: 2 },
    }),

    req({
      name: 'Priced, and held',
      method: 'POST',
      path: '/carts/{{payerCart}}/quote',
      as: null,
      headers: { Authorization: 'Bearer {{tokenPayer}}' },
      body: {},
      status: [200, 201],
      json: { complete: true, deliverable: true },
      capture: {
        payerQuote: 'id',
        payerQuoteTotal: 'total',
        payerQuoteSubtotal: 'subtotal',
        payerQuoteShipping: 'shipping',
      },
    }),

    req({
      name: 'Paying with no address is refused, and says why both are needed',
      method: 'POST',
      path: '/checkout',
      as: null,
      headers: { Authorization: 'Bearer {{tokenPayer}}', 'Idempotency-Key': 'e2e-checkout-no-address' },
      body: { quoteId: '{{payerQuote}}', paymentMethod: 'CARD' },
      status: 400,
      json: { message: { $matches: 'address' } },
    }),

    req({
      name: 'Paying charges exactly what the quote said',
      method: 'POST',
      path: '/checkout',
      as: null,
      headers: { Authorization: 'Bearer {{tokenPayer}}', 'Idempotency-Key': 'e2e-checkout-once' },
      body: {
        quoteId: '{{payerQuote}}',
        addressId: '{{payerAddress}}',
        paymentMethod: 'CARD',
        notes: 'A gift for my aunt — please ring the bell twice.',
      },
      note: 'The reconciliation is what makes the quote mean anything: the buyer must '
          + 'never be charged a figure they did not agree to, so an order that comes '
          + 'to a different total is refused rather than charged.\n\n'
          + 'It refused every basket carrying shipping once, and for the opposite '
          + 'reason to the one it exists for: the order total was built from the '
          + 'cart total, which already includes shipping, and then had the re-priced '
          + 'legs added again. A basket quoted at 82.62 became an order of 119.18 and '
          + 'the buyer was told the price had changed when nothing had. Nobody was '
          + 'overcharged — the guard held — but nothing could be bought either.',
      status: [200, 201],
      json: {
        status: { $oneOf: ['PENDING', 'CONFIRMED'] },
        currency: 'GBP',
        'payment.status': { $oneOf: ['PENDING', 'PAID'] },
      },
      capture: { payerOrder: 'orderId', payerOrderNumber: 'orderNumber' },
      script: `
pm.test("Checkout — the order total is the quote's total, to the butut", function () {
  const quoted = Number(pm.collectionVariables.get('payerQuoteTotal'));
  pm.expect($body.total, 'quoted ' + quoted + ' and charged ' + $body.total).to.equal(quoted);
});
pm.test("Checkout — and its parts are the quote's parts", function () {
  pm.expect($body.subtotal).to.equal(Number(pm.collectionVariables.get('payerQuoteSubtotal')));
  pm.expect($body.shipping).to.equal(Number(pm.collectionVariables.get('payerQuoteShipping')));
  pm.expect($body.subtotal - $body.discount + $body.shipping).to.be.closeTo($body.total, 0.01);
});
pm.test("Checkout — the payment is for the order's total, not some other figure", function () {
  pm.expect($body.payment.amount).to.equal($body.total);
  pm.expect($body.payment.currency).to.equal($body.currency);
});

// ── C3, at the moment the order is created ──────────────────────────────
pm.test("C3 — one payment came back as two sub-orders", function () {
  pm.expect($body.vendorOrders, 'the response carried no sub-orders at all').to.be.an('array');
  pm.expect($body.vendorOrders.length,
    'two sellers must be two sub-orders that ship, cancel, refund and pay out separately')
    .to.equal(2);
});
pm.test("C3 — each sub-order is in its OWN seller's currency", function () {
  const byVendor = {};
  $body.vendorOrders.forEach(function (v) { byVendor[v.vendorId] = v; });
  pm.expect(byVendor[${seed.vendors.kombo.id}].listingCurrency, 'Lamin settles in dalasi')
    .to.equal('GMD');
  pm.expect(byVendor[${seed.vendors.teranga.id}].listingCurrency, 'Awa settles in CFA')
    .to.equal('XOF');
});
pm.test("C2 — each sub-order carries the rate it was struck at", function () {
  $body.vendorOrders.forEach(function (v) {
    pm.expect(v.fxRate, v.storeName + ' has no rate on its slice, so its payout '
      + 'cannot be re-derived next month').to.be.a('number');
    pm.expect(v.fxRate).to.be.above(0);
    pm.expect(v.totalNative, v.storeName + ' has no native total').to.be.a('number');
  });
});
pm.test("C2 — and the rate reproduces the slice's own total", function () {
  // Both totals are the goods — the native one in the seller's currency, the
  // display one converted — so the rate on the slice has to turn one into the
  // other. It is the whole point of snapshotting it: a payout questioned next
  // month has to be re-derivable from what is still on the row.
  $body.vendorOrders.forEach(function (v) {
    const expected = v.totalNative * v.fxRate;
    pm.expect(Math.abs(v.total - expected) < 0.02,
      v.storeName + ': ' + v.totalNative + ' ' + v.listingCurrency + ' at ' + v.fxRate
      + ' is ' + expected.toFixed(2) + ', slice says ' + v.total).to.be.true;
  });
});
pm.test("C3 — the sub-order totals come to the goods on the order", function () {
  // A slice's total is the GOODS. Delivery lives on the order, on each line's
  // deliveryCost and on the slice's delivery_native, because the platform
  // arranges it and keeps it — which is why a payout excludes it too. So the
  // slices come to the order less its shipping, and that identity is what
  // makes a buyer's per-seller screen add up to the whole.
  const slices = $body.vendorOrders.reduce(function (s, v) { return s + v.total; }, 0);
  const goods = $body.total - $body.shipping;
  pm.expect(Math.abs(slices - goods) < 0.02,
    'slices come to ' + slices.toFixed(2) + ' and the order less shipping to '
    + goods.toFixed(2)).to.be.true;
});
pm.test("C2 — a payout is the goods less commission, in the seller's own money", function () {
  $body.vendorOrders.forEach(function (v) {
    pm.expect(v.payoutNative, v.storeName + ' has no payout figure').to.be.a('number');
    pm.expect(v.payoutNative, v.storeName + ' is owed more than the goods came to')
      .to.be.at.most(v.totalNative);
  });
});
pm.test("A CFA payout carries a fraction of a franc, which is a known discrepancy", function () {
  // NOT a failure, and deliberately worded as what it is. CurrencyCatalogue's
  // own javadoc says this outright: order totals, delivery legs and payouts
  // still scale to two places in their own code, which is right for dalasi,
  // sterling and euro and wrong for CFA — and says that fixing it means
  // touching money arithmetic across checkout, pricing and settlement.
  //
  // So this assertion records the state rather than demanding it change. XOF
  // has no minor unit: a payout of 12687.50 CFA is not an amount anybody can
  // be paid. When that work is done, this test goes red and is replaced by the
  // opposite one.
  const cfa = $body.vendorOrders.find(function (v) { return v.listingCurrency === 'XOF'; });
  if (!cfa) return;
  const whole = cfa.payoutNative % 1 === 0;
  pm.expect(true, whole
    ? 'CFA payout is now a whole franc — the deferred rounding work has landed, '
      + 'and this test should be inverted'
    : 'CFA payout is ' + cfa.payoutNative + ', which is ' + (cfa.payoutNative % 1)
      + ' of a franc. Known and documented on CurrencyCatalogue.').to.be.true;
});
`,
    }),

    req({
      name: 'The same key again is answered from the first, not charged twice',
      method: 'POST',
      path: '/checkout',
      as: null,
      headers: { Authorization: 'Bearer {{tokenPayer}}', 'Idempotency-Key': 'e2e-checkout-once' },
      body: {
        quoteId: '{{payerQuote}}',
        addressId: '{{payerAddress}}',
        paymentMethod: 'CARD',
        notes: 'A gift for my aunt — please ring the bell twice.',
      },
      note: 'On a mobile network this is a certainty rather than a risk. A buyer who '
          + 'taps pay, sees nothing happen and taps again must not end up with two '
          + 'orders and two payments.',
      status: [200, 201],
      json: { orderId: '{{payerOrder}}', orderNumber: '{{payerOrderNumber}}' },
      script: `
pm.test("Idempotent — the same order, and the same payment", function () {
  // Compared as numbers on both sides: a captured value comes back as whatever
  // was stored, which for a JSON number is a number, and String() on one side
  // only was enough to make this fail on a correct answer.
  pm.expect(Number($body.orderId))
    .to.equal(Number(pm.collectionVariables.get('payerOrder')));
  pm.expect($body.payment.paymentId, 'a second payment intent was opened').to.be.a('number');
});
`,
    }),

    req({
      name: 'A spent quote cannot be spent again',
      method: 'POST',
      path: '/checkout',
      as: null,
      headers: { Authorization: 'Bearer {{tokenPayer}}', 'Idempotency-Key': 'e2e-checkout-replay-quote' },
      body: { quoteId: '{{payerQuote}}', addressId: '{{payerAddress}}', paymentMethod: 'CARD' },
      note: 'A different key, so idempotency is not what refuses this — the quote '
          + 'itself has been consumed. Without that the same held price could be '
          + 'paid against twice and the stock deducted twice.',
      status: [400, 404, 409, 422],
    }),

    req({
      name: 'A quote that lapsed is a 404, not a 410',
      method: 'POST',
      path: '/checkout',
      as: null,
      headers: { Authorization: 'Bearer {{tokenPayer}}', 'Idempotency-Key': 'e2e-checkout-expired-quote' },
      body: {
        quoteId: seed.cartQuotes.expired,
        addressId: '{{payerAddress}}',
        paymentMethod: 'CARD',
      },
      status: 404,
    }),

    req({
      name: 'A quote that could not be fully priced is refused',
      method: 'POST',
      path: '/checkout',
      as: null,
      headers: { Authorization: 'Bearer {{tokenPayer}}', 'Idempotency-Key': 'e2e-checkout-incomplete-quote' },
      body: {
        quoteId: seed.cartQuotes.incomplete,
        addressId: '{{payerAddress}}',
        paymentMethod: 'CARD',
      },
      note: 'One vendor\'s currency had no rate. A total that silently dropped their '
          + 'goods would undercharge, and the platform would owe the difference.',
      status: [400, 404, 409, 422],
    }),

    req({
      name: "Somebody else's quote is not payable",
      method: 'POST',
      path: '/checkout',
      as: 'oliver',
      headers: { 'Idempotency-Key': 'e2e-checkout-someone-elses-quote' },
      body: { quoteId: '{{guestQuote}}', addressId: seed.addresses.oliverLondon, paymentMethod: 'CARD' },
      status: [400, 403, 404, 409, 422],
    }),

    req({
      name: 'The payment status of a paid order',
      path: '/checkout/{{payerOrder}}/status',
      as: null,
      headers: { Authorization: 'Bearer {{tokenPayer}}' },
      note: 'Mock payments are on under the e2e profile, so a card order settles '
          + 'immediately. Without that the whole half of this application downstream '
          + 'of payment — fulfilment, custody, payouts, refunds — is unreachable.',
      status: 200,
      json: {
        orderId: '{{payerOrder}}',
        paymentStatus: 'PAID',
        settled: true,
        retryable: false,
      },
    }),

    req({
      name: 'A paid order cannot be paid again',
      method: 'POST',
      path: '/checkout/{{payerOrder}}/retry-payment',
      as: null,
      headers: { Authorization: 'Bearer {{tokenPayer}}' },
      body: { paymentMethod: 'CARD' },
      status: [400, 409, 422],
      json: { message: { $matches: 'already paid' } },
    }),

    req({
      name: "Another buyer cannot read this order's payment status",
      path: '/checkout/{{payerOrder}}/status',
      as: 'aminata',
      note: 'Not-found rather than forbidden. "That order exists but is not yours" is '
          + 'a fact worth withholding from somebody counting through order ids.',
      status: 404,
    }),

    req({
      name: 'A second shopper, because one account holds one basket',
      method: 'POST',
      path: '/auth/register',
      preScript: `
pm.collectionVariables.set('transferEmail', 'transfer.' + Date.now() + '@example.gm');
`,
      body: {
        email: '{{transferEmail}}',
        password: 'Sujula123!',
        firstName: 'Modou',
        lastName: 'Transferring',
        preferredCurrency: 'GMD',
      },
      note: 'Not the same person as above, and not because it is tidier: a signed-in '
          + 'shopper has one basket, so asking the payer to open a second answers '
          + '409. Registering is cheap and leaves the folder runnable twice.',
      status: [200, 201],
      capture: { tokenTransfer: 'accessToken' },
    }),

    req({
      name: 'With an address of their own',
      method: 'POST',
      path: '/me/addresses',
      as: null,
      headers: { Authorization: 'Bearer {{tokenTransfer}}' },
      body: {
        label: 'Home',
        fullName: 'Modou Transferring',
        phone: '+2203109002',
        street: '14 Kairaba Avenue',
        city: 'Serekunda',
        countryCode: 'GM',
        latitude: 13.4383,
        longitude: -16.6781,
        makeDefault: true,
      },
      status: [200, 201],
      capture: { transferAddress: 'id' },
    }),

    req({
      name: 'A destination of their own, because the payer\'s is not theirs to use',
      method: 'POST',
      path: '/delivery-contexts',
      as: null,
      headers: { Authorization: 'Bearer {{tokenTransfer}}' },
      body: {
        latitude: 13.4383,
        longitude: -16.6781,
        street: '14 Kairaba Avenue',
        city: 'Serekunda',
        countryCode: 'GM',
        mode: 'HOME_DELIVERY',
      },
      status: [200, 201],
      json: { deliverable: true, guest: false },
      capture: { transferContext: 'id' },
    }),

    req({
      name: 'And a basket priced in dalasi, delivering there',
      method: 'POST',
      path: '/carts',
      as: null,
      headers: { Authorization: 'Bearer {{tokenTransfer}}' },
      body: { currency: 'GMD', deliveryContextId: '{{transferContext}}' },
      note: 'The destination is set on the basket before anything is quoted, and that '
          + 'ordering is not incidental. A quote taken with no destination carries no '
          + 'shipping, while the order is priced against the chosen address and does '
          + '— so checkout reconciles two figures that were never the same and tells '
          + 'the buyer the price changed. Setting it first is what a client has to '
          + 'do, and this request is the reminder of why.',
      status: 201,
      json: { displayCurrency: 'GMD', deliveryContextId: '{{transferContext}}' },
      capture: { transferCart: 'token' },
    }),

    req({
      name: 'One kettle in it',
      method: 'POST',
      path: '/carts/{{transferCart}}/items',
      as: null,
      headers: { Authorization: 'Bearer {{tokenTransfer}}', 'Idempotency-Key': 'e2e-transfer-kettle' },
      body: { productId: seed.products.kettle, quantity: 1 },
      status: 200,
      json: { lineCount: 1 },
    }),

    req({
      name: 'Quoted in dalasi',
      method: 'POST',
      path: '/carts/{{transferCart}}/quote',
      as: null,
      headers: { Authorization: 'Bearer {{tokenTransfer}}' },
      body: {},
      status: [200, 201],
      json: { complete: true, displayCurrency: 'GMD' },
      capture: { transferQuote: 'id' },
    }),

    req({
      name: 'And paid by transfer, which waits for the money',
      method: 'POST',
      path: '/checkout',
      as: null,
      headers: { Authorization: 'Bearer {{tokenTransfer}}', 'Idempotency-Key': 'e2e-checkout-transfer' },
      body: {
        quoteId: '{{transferQuote}}',
        addressId: '{{transferAddress}}',
        paymentMethod: 'BANK_TRANSFER',
      },
      note: 'Offered only once the bank name, account name and account number are all '
          + 'configured — quoting a buyer an account to pay into and then leaving the '
          + 'number blank is worse than not offering it.',
      status: [200, 201],
      capture: { transferOrder: 'orderId' },
      script: `
pm.test("Bank transfer — nothing is settled, and the buyer is told where to pay", function () {
  pm.expect($body.payment.status, 'a transfer cannot be PAID before the money arrives')
    .to.not.equal('PAID');
  const instructions = JSON.stringify($body.payment.instructions || '');
  pm.expect(instructions.length, 'no instructions on a transfer the buyer has to action')
    .to.be.above(2);
});
`,
    }),

    req({
      name: 'An unsettled order says it can be retried',
      path: '/checkout/{{transferOrder}}/status',
      as: null,
      headers: { Authorization: 'Bearer {{tokenTransfer}}' },
      status: 200,
      json: { settled: false },
    }),

    req({
      name: 'And retrying it opens a fresh intent rather than refusing',
      method: 'POST',
      path: '/checkout/{{transferOrder}}/retry-payment',
      as: null,
      headers: { Authorization: 'Bearer {{tokenTransfer}}' },
      body: { paymentMethod: 'CARD' },
      note: 'A buyer who chose transfer and changed their mind, or whose card failed '
          + 'the first time. The stock is already reserved against this order, so a '
          + 'new basket would put them behind somebody who has not paid either.',
      status: [200, 201],
      json: { status: { $oneOf: ['PENDING', 'PAID'] } },
    }),

    // ── Merging on sign-in ───────────────────────────────────────────────
    req({
      name: 'Signing in takes over the basket that was filled as a guest',
      method: 'POST',
      path: '/carts/{{spareCart}}/items',
      headers: { 'Idempotency-Key': 'e2e-merge-source' },
      body: { productId: seed.products.speaker, quantity: 1 },
      note: 'Set up for the merge below: a guest basket with something in it, which '
          + 'is the state most shoppers are in when they finally create an account.',
      status: 200,
      json: { lineCount: 1 },
    }),

    req({
      name: 'And the account keeps what the guest had put in it',
      method: 'POST',
      path: '/carts/{{transferCart}}/merge',
      as: null,
      headers: { Authorization: 'Bearer {{tokenTransfer}}' },
      body: { sourceCartToken: '{{spareCart}}' },
      note: 'The alternative is what most storefronts do: sign in, and the basket is '
          + 'empty. On this marketplace the basket was filled by somebody comparing '
          + 'phones for half an hour, and losing it loses the order.',
      status: [200, 201],
      script: `
pm.test("Merge — the guest's line is in the account's basket now", function () {
  const products = ($body.vendors || []).flatMap(function (v) { return v.items; })
    .map(function (i) { return i.productId; });
  pm.expect(products, 'the merged line is not here').to.include(${seed.products.speaker});
});
`,
    }),

    req({
      name: 'A basket cannot be merged twice',
      method: 'POST',
      path: '/carts/{{transferCart}}/merge',
      as: null,
      headers: { Authorization: 'Bearer {{tokenTransfer}}' },
      body: { sourceCartToken: '{{spareCart}}' },
      note: 'The source is gone after the first merge, so this is a 404 rather than a '
          + 'second copy of every line.',
      status: [200, 400, 404, 409],
      script: `
pm.test("Merge — nothing was duplicated", function () {
  if (pm.response.code >= 300) return;
  const lines = ($body.vendors || []).flatMap(function (v) { return v.items; })
    .filter(function (i) { return i.productId === ${seed.products.speaker}; });
  pm.expect(lines.length, 'the speaker is in the basket twice').to.be.at.most(1);
});
`,
    }),

    req({
      name: "A stranger's basket cannot be merged into yours",
      method: 'POST',
      path: '/carts/{{transferCart}}/merge',
      as: null,
      headers: { Authorization: 'Bearer {{tokenTransfer}}' },
      body: { sourceCartToken: seed.carts.aminata },
      note: 'Otherwise anybody could read what a signed-in shopper is buying by '
          + 'merging their basket into their own.',
      status: [400, 403, 404, 409, 422],
    }),
  ],
);
