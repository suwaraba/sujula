/**
 * Money, places, and the two questions a shopper asks before signing in.
 *
 * The sharpest thing on this surface is that XOF has no minor unit. A client
 * that formats every amount to two places shows CFA totals nobody can tender,
 * and CurrencyCatalogue is the only authority on that — so it is asserted here
 * rather than assumed anywhere.
 *
 * The other is C1, visible in reference data before any order exists: buying
 * and shipping are separate flags on a country, and a held rate is a rate that
 * does not move.
 */

const { req, folder } = require('../lib/collection');
const seed = require('../lib/seed');

module.exports = folder(
  '02 — Currencies, rates, places and delivery quotes',
  'The reference surface, and the two public POSTs a shopper makes before they have an account.',
  [
    // ── Currencies ───────────────────────────────────────────────────────
    req({
      name: 'Every currency carries its real minor unit',
      path: '/currencies',
      note: 'XOF has none — there is no centime of CFA in circulation — so 1250.50 '
          + 'is not an amount that exists. A client rounding everything to two '
          + 'places would show francs that cannot be paid, and money arithmetic '
          + 'that rounded to two would leave halves of a franc nobody can settle.',
      status: 200,
      json: { base: 'GMD' },
      script: `
const list = $body.currencies;
const by = {};
list.forEach(function (c) { by[c.code] = c; });

pm.test("Currencies — CFA has no minor unit, and says so", function () {
  pm.expect(by.XOF, 'XOF missing from the catalogue').to.be.an('object');
  pm.expect(by.XOF.minorUnits, 'XOF must have no minor unit').to.equal(0);
  pm.expect(by.XOF.smallestUnit, 'the smallest tenderable XOF amount is one franc').to.equal(1);
});
pm.test("Currencies — the dalasi, the pound and the euro have two", function () {
  ['GMD', 'GBP', 'EUR'].forEach(function (code) {
    pm.expect(by[code], code + ' missing from the catalogue').to.be.an('object');
    pm.expect(by[code].minorUnits, code + ' should have two minor units').to.equal(2);
    pm.expect(by[code].smallestUnit).to.equal(0.01);
  });
});
pm.test("Currencies — every entry answers the question at all", function () {
  list.forEach(function (c) {
    pm.expect(c.minorUnits, c.code + ' has no minorUnits').to.be.a('number');
    pm.expect(c.smallestUnit, c.code + ' has no smallestUnit').to.be.a('number');
  });
});
pm.test("Currencies — exactly one is the base", function () {
  pm.expect(list.filter(function (c) { return c.base; }).map(function (c) { return c.code; }))
    .to.eql(['GMD']);
});
`,
    }),

    req({
      name: 'A published rate comes back direct',
      path: '/currencies/rates',
      query: { base: 'GMD', quote: 'GBP' },
      status: 200,
      json: {
        base: 'GMD',
        quote: 'GBP',
        rate: seed.fx.gmdToGbp,
        inverted: false,
        indicative: true,
        fetchedAt: { $exists: true },
      },
    }),

    req({
      name: 'A rate with no direct row comes back inverted, and discloses it',
      path: '/currencies/rates',
      query: { base: 'GBP', quote: 'XOF' },
      note: 'Disclosed rather than quietly reciprocated, because a reciprocal carries '
          + 'no spread in that direction — and a buyer told a rate is direct when it '
          + 'is derived has been told something false about what they are paying.',
      status: 200,
      json: {
        base: 'GBP',
        quote: 'XOF',
        inverted: true,
        rate: { $gt: 0 },
        message: { $matches: 'invert' },
      },
    }),

    req({
      name: 'An unpublished pair says so rather than guessing',
      path: '/currencies/rates',
      query: { base: 'GMD', quote: 'SEK' },
      note: 'No rate, no number. A quoted rate that was actually a guess is how a '
          + 'buyer is charged fifty pounds for a fifty-dalasi delivery.',
      status: 200,
      json: {
        base: 'GMD',
        quote: 'SEK',
        rate: { $exists: false },
        message: { $matches: 'No rate' },
      },
    }),

    req({
      name: 'Holding a rate returns a quote with the rate on it',
      method: 'POST',
      path: '/currencies/quote',
      body: { base: 'GMD', quote: 'GBP', amount: 4500 },
      note: 'Fifteen minutes, and the arithmetic is checkable: 4500 x 0.011 = 49.50. A '
          + 'stored rate that does not reproduce the stored amount is decoration '
          + 'rather than evidence.',
      status: [200, 201],
      json: {
        base: 'GMD',
        quote: 'GBP',
        rate: seed.fx.gmdToGbp,
        baseAmount: 4500,
        quoteAmount: 49.5,
        consumed: false,
        expiresInSeconds: { $gt: 0 },
        id: { $minLength: 20 },
      },
      capture: { heldQuoteId: 'id' },
      script: `
pm.test("Held rate — the amount is the rate applied to the amount", function () {
  const expected = Math.round($body.baseAmount * $body.rate * 100) / 100;
  pm.expect($body.quoteAmount, $body.baseAmount + ' x ' + $body.rate + ' should be ' + expected)
    .to.equal(expected);
});
pm.test("Held rate — the id is not guessable", function () {
  // 256 bits of base64url. A guessable id on a guest's quote is a stranger
  // reading what somebody was quoted.
  pm.expect($body.id.length, 'the id is short enough to enumerate').to.be.at.least(32);
  pm.expect($body.id).to.match(/^[A-Za-z0-9_-]+$/);
});
`,
    }),

    req({
      name: 'The rate the quote was taken at is the rate it keeps',
      path: '/currencies/quote/{{heldQuoteId}}',
      note: 'C2 in its smallest form. Read again, it still says the same number — a '
          + 'quote that re-read the rate table would not be a quote, and an order '
          + 're-priced after the fact is an order whose total nobody can explain.',
      status: 200,
      json: {
        id: '{{heldQuoteId}}',
        rate: seed.fx.gmdToGbp,
        quoteAmount: 49.5,
        consumed: false,
      },
    }),

    req({
      name: "Oliver's seeded quote is his",
      path: `/currencies/quote/${seed.fxQuotes.oliverGbp}`,
      as: 'oliver',
      status: 200,
      json: { id: seed.fxQuotes.oliverGbp, rate: seed.fx.gmdToGbp, baseAmount: 4500, quoteAmount: 49.5 },
    }),

    req({
      name: 'And is a 404 for anybody else, not a 403',
      path: `/currencies/quote/${seed.fxQuotes.oliverGbp}`,
      as: 'aminata',
      note: 'Forbidden would confirm the id exists, which tells whoever guessed it '
          + 'that they guessed right.',
      status: 404,
      json: { status: 404 },
    }),

    req({
      name: "A guest's quote is readable by whoever holds the id",
      path: `/currencies/quote/${seed.fxQuotes.guestXof}`,
      note: 'No account, so the unguessable id is the whole of the claim to it. Note '
          + 'the converted amount: 3870 CFA, a whole franc, because XOF has no '
          + 'smaller unit.',
      status: 200,
      json: {
        id: seed.fxQuotes.guestXof,
        quote: 'XOF',
        quoteAmount: 3870,
      },
      script: `
pm.test("Guest quote — a CFA amount is a whole number of francs", function () {
  const amount = $body.quoteAmount;
  pm.expect(amount % 1, amount + ' has a fraction of a franc in it').to.equal(0);
});
`,
    }),

    req({
      name: 'A spent quote is still readable, and reports itself spent',
      path: `/currencies/quote/${seed.fxQuotes.consumed}`,
      as: 'oliver',
      note: 'A client reloading a confirmation page should see it reported as used '
          + 'rather than as missing — months from now this is the evidence of what '
          + 'the buyer agreed to.',
      status: 200,
      json: { id: seed.fxQuotes.consumed, consumed: true, rate: seed.fx.gmdToGbp },
    }),

    req({
      name: 'An expired quote is a 404, not a 410',
      path: `/currencies/quote/${seed.fxQuotes.expired}`,
      note: 'An expired bearer credential and one that never existed should be '
          + 'indistinguishable: 410 would confirm that this id was once real.',
      status: 404,
    }),

    // ── Delivery contexts ────────────────────────────────────────────────
    req({
      name: "Aminata's delivery context: the destination, and the confidence of its pin",
      path: `/delivery-contexts/${seed.deliveryContexts.aminataHome}`,
      as: 'aminata',
      note: 'USER_CONFIRMED because she moved the pin herself, so it is dispatchable '
          + 'and a later edit to the street will not move it.',
      status: 200,
      json: {
        id: seed.deliveryContexts.aminataHome,
        guest: false,
        city: 'Serekunda',
        countryCode: 'GM',
        confidence: 'USER_CONFIRMED',
        needsPinConfirmation: false,
        deliverable: true,
        mode: 'HOME_DELIVERY',
        addressId: seed.addresses.aminataHome,
        currency: 'GMD',
      },
    }),

    req({
      name: 'Somebody else reading it gets a 404',
      path: `/delivery-contexts/${seed.deliveryContexts.aminataHome}`,
      as: 'oliver',
      note: 'For a guest the id is the only thing between a stranger and their home '
          + 'address, so confirming one exists is not free.',
      status: 404,
    }),

    req({
      name: "A guest's context, read with no account at all",
      path: `/delivery-contexts/${seed.deliveryContexts.guestBrikama}`,
      note: 'CENTROID: the right block, the wrong unit. needsPinConfirmation is true, '
          + 'which is a client being told to show a map rather than a delivery being '
          + 'refused — most of this region has no street numbers.',
      status: 200,
      json: {
        guest: true,
        city: 'Brikama',
        confidence: 'CENTROID',
        needsPinConfirmation: true,
        deliverable: true,
      },
    }),

    req({
      name: 'A context for collection has no destination pin and is still deliverable',
      path: `/delivery-contexts/${seed.deliveryContexts.guestPickup}`,
      note: 'Nothing is being delivered to a door, so there is no door to locate. '
          + 'Refusing for want of a pin would make collection unavailable to exactly '
          + 'the people who need it.',
      status: 200,
      json: { deliverable: true, mode: 'PICKUP_POINT' },
    }),

    req({
      name: 'An expired context is a 404 rather than a 410',
      path: `/delivery-contexts/${seed.deliveryContexts.expired}`,
      status: 404,
    }),

    // ── Serviceability and price, both public ────────────────────────────
    req({
      name: 'Serviceability: can this shop reach this address, and how',
      method: 'POST',
      path: '/delivery/serviceability',
      body: {
        origin: { vendorId: seed.vendors.kombo.id },
        destination: { latitude: seed.modouPin.lat, longitude: seed.modouPin.lng, countryCode: 'GM' },
        nearestPickupPoints: 3,
      },
      note: 'Asked before there is a basket, so no account. The origin is the SHOP and '
          + 'the destination is where the goods go — neither is the buyer, who may be '
          + 'on another continent.',
      status: 200,
      json: {
        deliverable: true,
        distanceEstimated: false,
        crossBorder: false,
        originCountry: 'GM',
        destinationCountry: 'GM',
        distanceKm: { $gt: 0 },
      },
      script: `
pm.test("Serviceability — all three ways of receiving goods are answered", function () {
  const modes = $body.options.map(function (o) { return o.mode; });
  ['HOME_DELIVERY', 'PICKUP_POINT', 'VENDOR_PICKUP'].forEach(function (mode) {
    pm.expect(modes, mode + ' was not answered either way').to.include(mode);
  });
});
pm.test("Serviceability — a counter closed for the week is not offered", function () {
  // The radius search excludes a shuttered counter and this list did not, so
  // the storefront hid Latrikunda while this response went on offering it.
  // This is the list a buyer actually picks a collection point from.
  const offered = ($body.nearestPickupPoints || []).map(function (p) { return p.id; });
  pm.expect(offered, 'offered counter ${seed.pickupPoints.latrikunda}, which is closed for a funeral')
    .to.not.include(${seed.pickupPoints.latrikunda});
});
pm.test("Serviceability — the counters it does offer are ranked by distance from the DESTINATION", function () {
  const points = $body.nearestPickupPoints || [];
  for (let i = 1; i < points.length; i++) {
    pm.expect(points[i].distanceKm, 'the list is not in distance order')
      .to.be.at.least(points[i - 1].distanceKm);
  }
});
`,
    }),

    req({
      name: 'A quote prices each way of receiving the goods separately',
      method: 'POST',
      path: '/delivery/quote',
      body: {
        origin: { vendorId: seed.vendors.kombo.id },
        destination: { latitude: seed.modouPin.lat, longitude: seed.modouPin.lng, countryCode: 'GM' },
        weightKg: 2.5,
        value: 3000,
        currency: 'GMD',
      },
      note: 'Distance and weight, independently, then scaled by how the buyer '
          + 'receives it. Collecting from the shop is free because nothing is '
          + 'delivered.',
      status: 200,
      json: {
        currency: 'GMD',
        complete: true,
        weightKg: 2.5,
        distanceEstimated: false,
      },
      script: `
const prices = $body.prices;
const by = {};
prices.forEach(function (p) { by[p.mode] = p; });

pm.test("Delivery quote — collecting from the seller costs nothing", function () {
  pm.expect(by.VENDOR_PICKUP.cost, 'nothing is delivered, so there is nothing to charge for')
    .to.equal(0);
});
pm.test("Delivery quote — a counter run is cheaper than a door", function () {
  pm.expect(by.PICKUP_POINT.cost).to.be.below(by.HOME_DELIVERY.cost);
});
pm.test("Delivery quote — every price is a whole number of butut", function () {
  // GMD has two minor units, so a price with three decimals is a price nobody
  // can pay. Compared against the decimal text rather than against cost * 100:
  // 313.34 * 100 is 31333.999999999996 in IEEE 754, so the multiplication is
  // the thing that would fail rather than the price.
  prices.forEach(function (p) {
    if (p.cost == null) return;
    const decimals = (String(p.cost).split('.')[1] || '').length;
    pm.expect(decimals, p.mode + ' is priced at ' + p.cost + ', finer than a butut')
      .to.be.at.most(2);
  });
});
`,
    }),

    req({
      name: 'Quoting into a currency with no published rate is marked not final',
      method: 'POST',
      path: '/delivery/quote',
      body: {
        origin: { vendorId: seed.vendors.kombo.id },
        destination: { latitude: seed.modouPin.lat, longitude: seed.modouPin.lng, countryCode: 'GM' },
        weightKg: 1,
        value: 1000,
        currency: 'SEK',
      },
      note: 'No SEK rate is published, so the rate substituted is 1 and the figure is '
          + 'the rate card\'s own dalasi number. The response says so twice — '
          + 'complete:false on the quote, and a reason on every priced line — and '
          + 'this request pins BOTH, because the disclosure is the only thing '
          + 'standing between that figure and a client displaying "275.84 kr" for a '
          + 'delivery that costs 275.84 dalasi.\n\n'
          + 'Worth saying plainly: a number under the wrong currency code is a '
          + 'sharper edge than complete:false suggests, and a client that ignores '
          + 'the flag shows a wrong price with no hint of it. Pinned here as it '
          + 'stands rather than quietly changed, because which way to resolve it — '
          + 'drop the figure, or report it in the currency it is actually in — is a '
          + 'product decision rather than a bug fix.',
      status: 200,
      json: { currency: 'SEK', complete: false },
      script: `
pm.test("Delivery quote — every priced line says the figure is not final", function () {
  ($body.prices || []).forEach(function (p) {
    if (p.mode === 'VENDOR_PICKUP' || p.cost == null || !p.available) return;
    pm.expect(p.reason, p.mode + ' carries a figure with nothing saying it is unconverted')
      .to.be.a('string');
    pm.expect(p.reason).to.match(/exchange rate/i);
  });
});
pm.test("Delivery quote — the same request in the rate card's own currency IS final", function () {
  // The control for the assertion above: identical request, GMD, and the only
  // difference in the answer is the disclosure.
  pm.sendRequest({
    url: pm.variables.replaceIn('{{baseUrl}}/delivery/quote'),
    method: 'POST',
    header: { 'Content-Type': 'application/json' },
    body: { mode: 'raw', raw: JSON.stringify({
      origin: { vendorId: ${seed.vendors.kombo.id} },
      destination: { latitude: ${seed.modouPin.lat}, longitude: ${seed.modouPin.lng}, countryCode: 'GM' },
      weightKg: 1, value: 1000, currency: 'GMD',
    }) },
  }, function (error, answer) {
    pm.expect(error).to.equal(null);
    const dalasi = answer.json();
    pm.expect(dalasi.complete, 'a GMD quote off a GMD rate card should be complete').to.equal(true);
    const home = dalasi.prices.find(function (p) { return p.mode === 'HOME_DELIVERY'; });
    pm.expect(home.reason, 'a complete quote should carry no not-final reason').to.not.exist;
    // And this is the finding in one line: the SEK figure IS the dalasi figure.
    const sek = $body.prices.find(function (p) { return p.mode === 'HOME_DELIVERY'; });
    pm.expect(sek.cost, 'the unconverted SEK figure should equal the GMD one')
      .to.equal(home.cost);
  });
});
`,
    }),

    req({
      name: 'A quote with no destination still answers, and says which part it guessed',
      method: 'POST',
      path: '/delivery/quote',
      body: { origin: { vendorId: seed.vendors.kombo.id }, weightKg: 1, currency: 'GMD' },
      note: 'Refusing would have been the easier design and the wrong one: a shopper '
          + 'who has not typed an address yet still wants to know roughly what '
          + 'shipping costs, and "we will not say" loses the sale. So the distance '
          + 'comes from a scope fallback and distanceEstimated says so — while home '
          + 'delivery, which genuinely needs a door, is marked unavailable with the '
          + 'reason on it rather than priced against a place that does not exist.',
      status: 200,
      json: { distanceEstimated: true },
      script: `
const byMode = {};
($body.prices || []).forEach(function (p) { byMode[p.mode] = p; });
pm.test("No destination — home delivery is not priced against nowhere", function () {
  pm.expect(byMode.HOME_DELIVERY.available).to.equal(false);
  pm.expect(byMode.HOME_DELIVERY.cost, 'priced a delivery to an unknown address').to.not.exist;
  pm.expect(byMode.HOME_DELIVERY.reason).to.be.a('string');
});
pm.test("No destination — collection is still priced, because it needs no door", function () {
  pm.expect(byMode.PICKUP_POINT.available).to.equal(true);
  pm.expect(byMode.PICKUP_POINT.cost).to.be.a('number');
});
pm.test("No destination — and the estimate is flagged as one", function () {
  pm.expect($body.distanceEstimated,
    'a fallback distance presented as a measured one is worse than no figure').to.equal(true);
});
`,
    }),

    // ── Geocoding, degraded on purpose ───────────────────────────────────
    req({
      name: 'Without a geocoder, address validation says nobody looked',
      method: 'POST',
      path: '/geo/validate-address',
      body: { street: '14 Kairaba Avenue', city: 'Serekunda', countryCode: 'GM' },
      note: 'available:false is "nobody looked", which is a different thing from "we '
          + 'looked and found nothing" and leads to different advice. The e2e profile '
          + 'has no API key on purpose, because most development runs do not and '
          + 'everything still has to work.',
      status: 200,
      json: { available: false },
    }),

    req({
      name: 'And the pickup-point search still works without one',
      path: '/pickup-points',
      query: { lat: seed.westfield.lat, lng: seed.westfield.lng, radius: 5 },
      note: 'A shopper picks where to collect before signing in, and frequently '
          + 'before they have an account at all. Latrikunda is not in this list: it '
          + 'is closed for a funeral, and sending somebody to a shuttered counter is '
          + 'worse than showing nothing.',
      status: 200,
      json: {
        'points.0.id': seed.pickupPoints.westfield,
        'points.0.capacity': 'AVAILABLE',
        'points.0.openNow': true,
        searchLat: seed.westfield.lat,
      },
      script: `
const points = $body.points;
pm.test("Counters — the one closed for a funeral is not offered", function () {
  pm.expect(points.map(function (p) { return p.id; }))
    .to.not.include(${seed.pickupPoints.latrikunda});
});
pm.test("Counters — how full each is comes back as a band, never a count", function () {
  points.forEach(function (p) {
    pm.expect(['AVAILABLE', 'BUSY', 'FULL', 'CLOSED'], 'capacity was ' + p.capacity)
      .to.include(p.capacity);
    pm.expect(JSON.stringify(p), 'a parcel count is a fact about somebody\\'s business')
      .to.not.match(/"storedParcels"|"parcelCount"/);
  });
});
pm.test("Counters — no operator is named", function () {
  points.forEach(function (p) {
    pm.expect(JSON.stringify(p)).to.not.include('Isatou');
    pm.expect(JSON.stringify(p)).to.not.include('isatou.pickup@sujula.gm');
  });
});
`,
    }),

    req({
      name: 'A counter you were sent to is readable even while it is shut',
      path: `/pickup-points/${seed.pickupPoints.latrikunda}`,
      note: 'The opposite decision from the search above, and the right one: somebody '
          + 'holding a parcel notice for this counter needs to know why the door is '
          + 'locked and when it opens. Hiding it would leave them standing outside.',
      status: 200,
      json: {
        id: seed.pickupPoints.latrikunda,
        capacity: 'CLOSED',
        openNow: false,
        closureReason: { $matches: 'funeral' },
        closedUntil: { $exists: true },
      },
    }),

    req({
      name: 'A search with nowhere to look is refused rather than listing the country',
      path: '/pickup-points',
      status: [400, 422],
      note: 'Returning every counter in The Gambia to a caller who gave no position '
          + 'is not an answer to the question they asked.',
    }),
  ],
);
