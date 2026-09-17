/**
 * Vendor to driver to counter to recipient, and the proof at every link.
 *
 * C4 says custody is a chain and not a status: every transfer is a verified
 * event with evidence, and the status is a consequence of that evidence rather
 * than an input to it. So the assertions here are mostly about what is REFUSED
 * — a collection by a driver who has not accepted the leg, a release from a
 * counter without the recipient's code, a delivery without a position — because
 * a chain is only as good as the links it will not let you skip.
 *
 * C5 runs alongside it. The person the goods are for may have no account, no
 * app and no email, and she is the only one who knows whether she will be at
 * home on Thursday. Her surface is open on purpose, addressed by a code, and
 * carries a first name and a town and nothing else.
 */

const { req, folder } = require('../lib/collection');
const seed = require('../lib/seed');

module.exports = folder(
  '06 — The custody chain: driver, counter, recipient',
  'Who may move a parcel, what they have to present, and what the recipient can change.',
  [
    // ── The driver's own app ─────────────────────────────────────────────
    req({
      name: "Ebrima's profile: what he drives and what he may carry",
      path: '/driver/profile',
      as: 'ebrima',
      status: 200,
      json: {
        status: 'APPROVED',
        vehicleType: 'MOTOR',
        maxWeightKg: { $gt: 0 },
        countryCode: 'GM',
        'kyc.documentsSubmitted': true,
        'score.acceptancePercent': { $gt: 0 },
      },
      script: `
pm.test("Driver profile — his acceptance rate is derived, not stored as a claim", function () {
  const score = $body.score;
  const expected = score.offersReceived > 0
    ? Math.round((score.accepted / score.offersReceived) * 10000) / 100
    : 0;
  pm.expect(Math.abs(score.acceptancePercent - expected) < 0.05,
    score.accepted + ' of ' + score.offersReceived + ' is ' + expected
    + '%, profile says ' + score.acceptancePercent).to.be.true;
});
`,
    }),

    req({
      name: 'His assignments, and the lapsed offer that is deliberately not in them',
      path: '/driver/assignments',
      as: 'ebrima',
      note: 'Leg 1911 is his and in progress. Leg 1912 is an offer that lapsed and is '
          + 'NOT listed — a driver shown a dead offer will tap it and read the '
          + 'refusal as a broken app.',
      status: 200,
      script: `
const all = ($body.offered || []).concat($body.accepted || []);
const ids = all.map(function (l) { return l.legId; });
pm.test("Assignments — the live leg is there", function () {
  pm.expect(ids).to.include(${seed.legs.ebrimaInProgress});
});
pm.test("Assignments — the lapsed offer is not", function () {
  pm.expect(ids, 'leg ${seed.legs.lapsedOffer} lapsed and went back to the pool')
    .to.not.include(${seed.legs.lapsedOffer});
});
pm.test("Assignments — each leg says what it pays, in the driver's own currency", function () {
  all.forEach(function (leg) {
    pm.expect(leg.earning, 'leg ' + leg.legId + ' does not say what it pays').to.be.a('number');
    pm.expect(leg.earningCurrency).to.equal('GMD');
    pm.expect(leg.pickupFrom, 'leg ' + leg.legId + ' does not say where to go').to.be.a('string');
  });
});
pm.test("Assignments — a list carries no recipient's phone number", function () {
  // He gets the destination when he is carrying the parcel, not while he is
  // deciding whether to. A list of offers is a list a driver scrolls in
  // public.
  pm.expect(H.text(pm.response)).to.not.include('+2203100077');
});
`,
    }),

    req({
      name: 'Accepting an offer that has already lapsed is refused, and does not blame him',
      method: 'POST',
      path: `/driver/assignments/${seed.legs.lapsedOffer}/accept`,
      as: 'ebrima',
      headers: { 'Idempotency-Key': 'e2e-accept-lapsed' },
      body: {},
      note: 'It went back to the pool. The message says so rather than "not allowed", '
          + 'because a driver who taps a stale offer has done nothing wrong and will '
          + 'otherwise conclude the app is broken.',
      status: [400, 404, 409, 422],
      script: `
pm.test("Lapsed offer — the refusal explains rather than accuses", function () {
  pm.expect(H.text(pm.response).length, 'refused with an empty body').to.be.above(2);
});
`,
    }),

    // ── C4 and PII: what a driver can see, and when ──────────────────────
    req({
      name: 'The parcel he is carrying: he gets the door, because he has to knock on it',
      path: `/driver/shipments/${seed.shipments.inFlight}`,
      as: 'ebrima',
      status: 200,
      json: {
        id: seed.shipments.inFlight,
        status: { $exists: true },
        'destination.recipientName': { $exists: true },
        'destination.street': { $exists: true },
        'destination.city': 'Serrekunda',
        'origin.storeName': 'Kombo Electronics',
      },
      script: `
pm.test("In flight — he has the name, the street and the number", function () {
  pm.expect($body.destination.recipientPhone, 'he cannot ring her without it')
    .to.be.a('string');
  pm.expect($body.destination.lat, 'and cannot navigate without a pin').to.be.a('number');
});
pm.test("In flight — and nothing about who paid for it", function () {
  const text = H.text(pm.response);
  // Fatou in Madrid paid for this parcel. A driver has no reason to know that
  // anybody did, let alone who.
  ['Fatou', 'fatou.admin@sujula.gm', 'EUR', '132.16'].forEach(function (leak) {
    pm.expect(text, 'the driver is shown ' + leak).to.not.include(leak);
  });
});
pm.test("In flight — the chain is events, not a status somebody set", function () {
  pm.expect($body.chain, 'no custody chain on the parcel').to.be.an('array');
  $body.chain.forEach(function (event) {
    pm.expect(event.type, 'an event with no type').to.be.a('string');
    pm.expect(event.occurredAt, 'an event with no time').to.be.a('string');
    pm.expect(event.attestedByPosition,
      'an event that does not say whether a position corroborated it')
      .to.be.a('boolean');
  });
});
pm.test("In flight — his own leg is marked as his", function () {
  const mine = $body.legs.filter(function (l) { return l.mine; });
  pm.expect(mine.length, 'none of the legs are marked his').to.be.at.least(1);
});
`,
    }),

    req({
      name: 'The parcel he handed over: the destination block is ABSENT, not blank',
      path: `/driver/shipments/${seed.shipments.finished}`,
      as: 'ebrima',
      note: 'A driver who delivered a parcel yesterday has no reason to still hold '
          + 'somebody\'s front door. Absent rather than emptied, because a client '
          + 'that renders a blank street has been handed a field to fill in.',
      status: 200,
      json: {
        id: seed.shipments.finished,
        status: 'DELIVERED',
        destination: { $exists: false },
        origin: { $exists: false },
      },
      bodyExcludes: [
        { value: 'Aminata', why: 'the recipient of a finished delivery is no longer his business' },
        { value: 'Sayerr Jobe', why: 'nor her street' },
      ],
      script: `
pm.test("Finished — the chain is still there, because that is the record", function () {
  // The window closes on the PII and not on the evidence: the chain is what
  // answers "who had this parcel, and when" years from now.
  pm.expect($body.chain.length, 'a delivered parcel with no custody events').to.be.at.least(2);
  const types = $body.chain.map(function (e) { return e.type; });
  pm.expect(types).to.include('COLLECTED');
});
pm.test("Finished — and so is what he earned for it", function () {
  pm.expect($body.legs[0].earning).to.be.a('number');
  pm.expect($body.legs[0].completedAt).to.be.a('string');
});
`,
    }),

    req({
      name: 'A parcel nobody has accepted is not his to read the door of',
      path: `/driver/shipments/${seed.shipments.unclaimed}`,
      as: 'ebrima',
      note: 'Shipment 1902 has no events at all and its DRIVER_OFFERED status comes '
          + 'from the leg rather than from anything having happened — which is the '
          + 'state most parcels are in at any moment.',
      status: [200, 403, 404],
      script: `
if (pm.response.code === 200) {
  pm.test("Unclaimed — no destination, because it is not his", function () {
    pm.expect($body.destination, 'a parcel he has not accepted handed him the address')
      .to.not.exist;
  });
}
`,
    }),

    req({
      name: 'Collecting a parcel nobody has accepted is refused',
      method: 'POST',
      path: `/driver/shipments/${seed.shipments.unclaimed}/collect`,
      as: 'ebrima',
      headers: { 'Idempotency-Key': 'e2e-collect-unclaimed' },
      body: { code: seed.handoff.forVendorOrder1505, latitude: 13.4383, longitude: -16.6781 },
      note: 'Nobody has accepted that leg, so the parcel is not his to move. This is '
          + 'the first link of the chain refusing to be skipped.',
      status: [400, 403, 404, 409, 422],
    }),

    req({
      name: 'A dead handoff code does not open the first link either',
      method: 'POST',
      path: `/driver/shipments/${seed.shipments.inFlight}/collect`,
      as: 'ebrima',
      headers: { 'Idempotency-Key': 'e2e-collect-dead-code' },
      body: { code: seed.handoff.deadPrevious, latitude: 13.4383, longitude: -16.6781 },
      note: '304912 was the collection code for slice 1505 and was regenerated. A code '
          + 'that still worked after being replaced would make regenerating one '
          + 'pointless — which is the whole reason a seller can.',
      status: [400, 403, 404, 409, 422],
    }),

    req({
      name: 'Asking for the recipient\'s code emails the buyer, and never shows it to the driver',
      method: 'POST',
      path: `/driver/shipments/${seed.shipments.inFlight}/request-recipient-code`,
      as: 'ebrima',
      headers: { 'Idempotency-Key': 'e2e-request-recipient-code' },
      body: {},
      note: 'It reaches Fatou in Madrid, who passes the six digits to her sister. '
          + 'Isatou needs no account, no app and no email of her own. The driver '
          + 'never sees it: one who could read it could mark a parcel delivered '
          + 'without meeting anybody.',
      status: [200, 201, 202, 400, 409, 429],
      script: `
pm.test("Recipient code — the six digits are never in the answer", function () {
  const text = H.text(pm.response);
  pm.expect(text, 'the driver was handed the code he is supposed to be given by the recipient')
    .to.not.include(${JSON.stringify(seed.recipientCode.shipment1901)});
  // Any six-digit run at all is worth refusing here, not just this one.
  pm.expect(text, 'a six-digit code appears in the response').to.not.match(/\\b\\d{6}\\b/);
});
`,
    }),

    req({
      name: 'Delivering with the wrong code is refused',
      method: 'POST',
      path: `/driver/shipments/${seed.shipments.inFlight}/deliver`,
      as: 'ebrima',
      headers: { 'Idempotency-Key': 'e2e-deliver-wrong-code' },
      body: {
        code: '000000',
        latitude: 13.4384,
        longitude: -16.6781,
        photoKey: 'custody/e2e/handover.jpg',
      },
      note: 'The last link, and the one somebody would forge if any single piece of '
          + 'evidence were enough on its own.',
      status: [400, 403, 409, 422],
    }),

    req({
      name: 'And delivering with no position is refused even with the right code',
      method: 'POST',
      path: `/driver/shipments/${seed.shipments.inFlight}/deliver`,
      as: 'ebrima',
      headers: { 'Idempotency-Key': 'e2e-deliver-no-position' },
      body: {
        code: seed.recipientCode.shipment1901,
        photoKey: 'custody/e2e/handover.jpg',
      },
      note: 'A code, a position and a photograph. Each on its own is forgeable by '
          + 'somebody: the code by whoever overheard it, the position by an app, the '
          + 'photograph by anybody holding a similar box. Together they are evidence.',
      status: [400, 403, 422],
    }),

    req({
      name: 'Another driver cannot read his parcel at all',
      path: `/driver/shipments/${seed.shipments.inFlight}`,
      as: 'isatou',
      note: 'Isatou runs a counter rather than a bike, so she fails the role as well '
          + 'as the ownership — but the point holds for a second driver: the driver '
          + 'is resolved from the session and goes into the query, so a path '
          + 'variable cannot open somebody else\'s parcel.',
      status: [403, 404],
    }),

    req({
      name: 'His earnings, per currency, with the legs behind each figure',
      path: '/driver/earnings',
      as: 'ebrima',
      status: 200,
      script: `
pm.test("Earnings — the total is the sum of the lines, which is what makes it checkable", function () {
  $body.byCurrency.forEach(function (group) {
    const summed = group.lines.reduce(function (s, l) { return s + l.amount; }, 0);
    pm.expect(Math.abs(group.total - summed) < 0.02,
      group.currency + ': lines come to ' + summed.toFixed(2) + ' and the total says '
      + group.total).to.be.true;
    pm.expect(group.legs, 'the leg count does not match the lines')
      .to.equal(group.lines.length);
  });
});
pm.test("Earnings — never one total across currencies", function () {
  pm.expect($body.total, 'a single total across two currencies is not a number')
    .to.not.exist;
});
pm.test("Earnings — a line says where it went, not who it went to", function () {
  $body.byCurrency.forEach(function (group) {
    group.lines.forEach(function (line) {
      pm.expect(line.dropTo, 'a leg with no destination town').to.be.a('string');
      pm.expect(JSON.stringify(line), 'a recipient name in an earnings statement')
        .to.not.match(/recipientName|recipientPhone/);
    });
  });
});
`,
    }),

    req({
      name: 'His history is towns and dates, not addresses',
      path: '/driver/history',
      as: 'ebrima',
      note: 'Where a driver has been all day is data about him; where each parcel went '
          + 'is data about somebody else. The history keeps the first and drops the '
          + 'second down to a town.',
      status: 200,
      script: `
pm.test("History — a town and a country per row, and no street", function () {
  ($body.rows || []).forEach(function (row) {
    pm.expect(row.dropCity, 'a row with no destination town').to.be.a('string');
    pm.expect(JSON.stringify(row)).to.not.match(/street|recipientName|recipientPhone/i);
  });
});
`,
    }),

    // ── The counter ──────────────────────────────────────────────────────
    req({
      name: 'Isatou\'s counters, with the figures the public search withholds',
      path: '/pickup/points',
      as: 'isatou',
      note: 'The operator\'s own view is the opposite decision from the public one: '
          + 'she gets the real count, the capacity, the commission and the closure, '
          + 'because it is her business. The public search gets a band.',
      status: 200,
      script: `
pm.test("Her counters — real counts rather than a band alone", function () {
  pm.expect($body.points.length, 'she runs at least one counter').to.be.at.least(1);
  $body.points.forEach(function (p) {
    pm.expect(p.storedParcels, p.name + ' does not say how many are on the shelf')
      .to.be.a('number');
    pm.expect(p.capacity, p.name + ' does not say how many it holds').to.be.a('number');
    pm.expect(p.commissionPerParcel, p.name + ' does not say what she earns')
      .to.be.a('number');
  });
});
pm.test("Her counters — the one closed for a funeral is HERS and is listed", function () {
  // The public search hides it, for the shopper's sake. Hiding it from the
  // operator would hide her own counter from her.
  const ids = $body.points.map(function (p) { return p.id; });
  pm.expect(ids).to.include(${seed.pickupPoints.latrikunda});
  const closed = $body.points.find(function (p) { return p.id === ${seed.pickupPoints.latrikunda}; });
  pm.expect(closed.closureReason).to.match(/funeral/i);
});
`,
    }),

    req({
      name: 'Three piles at the counter: coming, on the shelf, and overdue',
      path: `/pickup/points/${seed.pickupPoints.westfield}/parcels`,
      as: 'isatou',
      status: 200,
      json: {
        storedCount: { $gte: 1 },
        capacityBand: { $oneOf: ['AVAILABLE', 'BUSY', 'FULL', 'CLOSED'] },
        'stored.0.shipmentId': seed.shipments.atCounter,
        'stored.0.shelfCode': 'A-118',
      },
      script: `
pm.test("The shelf — a parcel that is here has a name and a shelf", function () {
  const parcel = $body.stored[0];
  pm.expect(parcel.recipientName, 'she has to know who to hand it to').to.be.a('string');
  pm.expect(parcel.shelfCode, 'and where it is').to.be.a('string');
  pm.expect(parcel.daysRemaining, 'and how long she has to keep it').to.be.a('number');
});
pm.test("The shelf — the phone is a hint rather than a number", function () {
  const parcel = $body.stored[0];
  pm.expect(parcel.recipientPhoneHint, 'no hint to check a caller against')
    .to.be.a('string');
  pm.expect(parcel.recipientPhoneHint, 'a full number on a counter screen')
    .to.match(/[^0-9+]/);
  pm.expect(H.text(pm.response)).to.not.include('+2203100010');
});
pm.test("The shelf — an incoming parcel carries no recipient name", function () {
  // They are not here yet. A counter that knew who every parcel in the
  // country was for would be a directory.
  ($body.incoming || []).forEach(function (parcel) {
    pm.expect(parcel.recipientName, 'an incoming parcel named its recipient').to.not.exist;
  });
});
pm.test("The shelf — the stored count is the shelf, not an assertion", function () {
  pm.expect($body.storedCount).to.equal($body.stored.length);
});
`,
    }),

    req({
      name: 'Releasing a parcel needs the code AND the name of whoever is collecting',
      method: 'POST',
      path: `/pickup/points/${seed.pickupPoints.westfield}/parcels/${seed.shipments.atCounter}/release`,
      as: 'isatou',
      headers: { 'Idempotency-Key': 'e2e-release-no-name' },
      body: { code: '123456' },
      note: 'A code alone would let anybody who overheard it take the parcel; a name '
          + 'alone anybody who read the label. A brother collecting for his sister is '
          + 'allowed and written down.',
      status: [400, 403, 422],
    }),

    req({
      name: 'And the wrong code is refused even with a name',
      method: 'POST',
      path: `/pickup/points/${seed.pickupPoints.westfield}/parcels/${seed.shipments.atCounter}/release`,
      as: 'isatou',
      headers: { 'Idempotency-Key': 'e2e-release-wrong-code' },
      body: { code: '000000', collectedBy: 'Her brother Lamin' },
      status: [400, 403, 409, 422],
    }),

    req({
      name: 'Returning a parcel before its deadline is refused',
      method: 'POST',
      path: `/pickup/points/${seed.pickupPoints.westfield}/parcels/${seed.shipments.atCounter}/return`,
      as: 'isatou',
      headers: { 'Idempotency-Key': 'e2e-return-early' },
      body: { reason: 'Nobody has come for it' },
      note: 'Somebody may be travelling to collect it, and sending it back early takes '
          + 'a decision that is not the counter\'s.',
      status: [400, 403, 409, 422],
      script: `
pm.test("Early return — the refusal says when it could be returned", function () {
  pm.expect(H.text(pm.response).length, 'refused with no reason').to.be.above(2);
});
`,
    }),

    req({
      name: 'A closed counter says WHICH of the four reasons it is',
      method: 'POST',
      path: `/pickup/points/${seed.pickupPoints.latrikunda}/parcels/${seed.shipments.atCounter}/accept`,
      as: 'isatou',
      headers: { 'Idempotency-Key': 'e2e-accept-at-closed' },
      body: { driverCode: '123456' },
      note: 'Closed, suspended, switched off, or full. A driver standing outside with '
          + 'a parcel needs to know which, because three of them mean "come back" and '
          + 'one means "go somewhere else".',
      status: [400, 403, 409, 422],
    }),

    req({
      name: "Another operator's counter is not readable",
      path: `/pickup/points/${seed.pickupPoints.westfield}/parcels`,
      as: 'ebrima',
      note: 'These rows lead to recipients\' names and phone numbers, so the operator '
          + 'is resolved from the session and goes into every query beside the point '
          + 'id.',
      status: [403, 404],
    }),

    req({
      name: 'What the counter earns, which is the operator\'s own business',
      path: `/pickup/points/${seed.pickupPoints.westfield}/earnings`,
      as: 'isatou',
      status: 200,
    }),

    // ── C5: the recipient, who has no account ────────────────────────────
    req({
      name: 'C5: her parcel page, with no account and no app',
      path: `/parcels/${seed.parcelCodes.inFlight}`,
      note: 'Isatou in Serrekunda did not sign up for anything. The code is the whole '
          + 'credential, and the page is built to be worth nothing to a stranger '
          + 'holding it — which is why it carries a first name and a town.\n\n'
          + 'Note also that this is the SHIPMENT\'s code and not the order\'s: an '
          + 'order can be several parcels from several sellers, and the person at the '
          + 'door is waiting for one of them.',
      status: 200,
      json: {
        trackingCode: seed.parcelCodes.inFlight,
        stage: 'OUT_FOR_DELIVERY',
        forName: 'Isatou',
        destinationCity: 'Serrekunda',
        destinationCountry: 'GM',
        itemCount: { $gte: 1 },
        'youCan.choosePickupPoint': true,
        'youCan.reschedule': true,
        'youCan.authoriseSafeDrop': true,
      },
      bodyExcludes: [
        { value: 'Ceesay', why: 'a first name only: the link may have been forwarded' },
        { value: '+2203100077', why: 'her own number is not something to print back at her' },
        { value: 'Fatou', why: 'and the payer is nobody she was told about' },
        { value: '132.16', why: 'nor what the gift cost' },
        { value: 'EUR', why: 'nor the currency it was bought in' },
        { value: 'Samsung', why: 'no contents: a parcel description is a theft target' },
        { value: 'Ebrima', why: "and not the driver's name" },
        { value: 'SJL-SEED-0004', why: 'no order number' },
      ],
      script: `
pm.test("C5 — she is told what she can still change and, when she cannot, why", function () {
  pm.expect($body.youCan, 'nothing tells her what she is allowed to do').to.be.an('object');
  if ($body.youCan.reschedule === false) {
    pm.expect($body.youCan.why, 'refused with no reason given to the one person waiting')
      .to.be.a('string');
  }
});
pm.test("C5 — her standing instructions are reported back to her", function () {
  pm.expect($body.standing, 'no standing instructions block').to.be.an('object');
  pm.expect($body.standing.safeDropAuthorised).to.be.a('boolean');
});
pm.test("C5 — the history is fixed phrases, as the public page is", function () {
  ($body.history || []).forEach(function (event) {
    pm.expect(event.description).to.be.a('string');
    pm.expect(event.description, 'internal wording on the recipient page')
      .to.not.include('outbound leg');
  });
});
`,
    }),

    req({
      name: 'A delivered parcel tells her it is done, and closes the three choices',
      path: `/parcels/${seed.parcelCodes.delivered}`,
      note: 'And says why rather than greying three buttons out, because she is the '
          + 'one person who cannot ask anybody.',
      status: 200,
      json: {
        stage: 'DELIVERED',
        forName: 'Aminata',
        'youCan.choosePickupPoint': false,
        'youCan.reschedule': false,
        'youCan.authoriseSafeDrop': false,
        'youCan.why': { $exists: true },
      },
    }),

    req({
      name: "An order's tracking code is not a parcel code",
      path: `/parcels/${seed.trackingCodes.order1401}`,
      note: 'Two different credentials for two different things, and neither resolves '
          + 'the other. An order is several parcels; a parcel is what somebody is '
          + 'standing at the door for.',
      status: 404,
    }),

    req({
      name: 'A code nobody was issued is a 404',
      path: '/parcels/PARCZZZZZZZZZZZZ',
      status: 404,
    }),

    req({
      name: 'She asks for her own code, with no account',
      method: 'POST',
      path: `/parcels/${seed.parcelCodes.inFlight}/request-code`,
      body: {},
      note: 'Open on purpose, and the clearest case of C5 on the platform: requiring a '
          + 'sign-in here would hand the decision back to whoever has an account, '
          + 'which is exactly the wrong person.',
      status: [200, 201, 202, 400, 429],
      script: `
pm.test("Her code — the answer says it was sent, not what it was", function () {
  pm.expect(H.text(pm.response), 'the code itself came back to an anonymous caller')
    .to.not.match(/\\b\\d{6}\\b/);
});
`,
    }),

    req({
      name: 'She chooses a counter instead of the door',
      method: 'POST',
      path: `/parcels/${seed.parcelCodes.inFlight}/choose-pickup-point`,
      body: { pickupPointId: seed.pickupPoints.westfield, code: seed.recipientCode.shipment1901 },
      note: 'Verified by the six digits rather than by a session, because she has no '
          + 'session. Her code is what proves she is the person the parcel is for.',
      status: [200, 201, 202, 400, 403, 409, 422],
      script: `
if (pm.response.code < 300) {
  pm.test("Her choice — recorded, and the parcel is going to the counter", function () {
    pm.expect(JSON.stringify($body)).to.match(/${seed.pickupPoints.westfield}|Westfield/);
  });
} else {
  pm.test("Her choice — refused with a reason she can act on", function () {
    pm.expect(H.text(pm.response).length).to.be.above(2);
  });
}
`,
    }),

    req({
      name: 'Without her code, nobody changes where her parcel goes',
      method: 'POST',
      path: `/parcels/${seed.parcelCodes.inFlight}/choose-pickup-point`,
      body: { pickupPointId: seed.pickupPoints.latrikunda },
      note: 'The page is open, so the code is the only thing stopping whoever was '
          + 'forwarded the message from redirecting somebody else\'s parcel to a '
          + 'counter they can reach.',
      status: [400, 401, 403, 422],
    }),

    req({
      name: 'And a wrong code is refused',
      method: 'POST',
      path: `/parcels/${seed.parcelCodes.inFlight}/reschedule`,
      body: { code: '000000', from: '2026-09-19T14:00:00', until: '2026-09-19T18:00:00' },
      status: [400, 401, 403, 422],
    }),

    req({
      name: 'A window in the past is refused whatever the code',
      method: 'POST',
      path: `/parcels/${seed.parcelCodes.inFlight}/reschedule`,
      body: {
        code: seed.recipientCode.shipment1901,
        from: '2020-01-01T09:00:00',
        until: '2020-01-01T17:00:00',
      },
      note: 'A delivery window that has already passed is not a reschedule, it is a '
          + 'parcel nobody will ever attempt again.',
      status: [400, 403, 422],
    }),

    req({
      name: 'Authorising a safe drop names the place and the person',
      method: 'POST',
      path: `/parcels/${seed.parcelCodes.inFlight}/authorise-safe-drop`,
      body: {
        code: seed.recipientCode.shipment1901,
        location: 'with the pharmacy next door',
        person: 'Ndey',
      },
      note: 'Because the alternative is a driver deciding on her behalf. What she has '
          + 'authorised goes onto the parcel the driver reads, and the photograph is '
          + 'then the only proof there will be — so the authorisation has to be hers '
          + 'and has to be recorded.',
      status: [200, 201, 202, 400, 403, 409, 422],
    }),

    req({
      name: 'Nobody can authorise one on her behalf',
      method: 'POST',
      path: `/parcels/${seed.parcelCodes.inFlight}/authorise-safe-drop`,
      body: { location: 'behind the gate', person: 'Anybody' },
      status: [400, 401, 403, 422],
    }),

    req({
      name: 'A delivered parcel cannot be rescheduled by anybody',
      method: 'POST',
      path: `/parcels/${seed.parcelCodes.delivered}/reschedule`,
      body: {
        code: seed.recipientCode.shipment1901,
        from: '2026-09-20T09:00:00',
        until: '2026-09-20T17:00:00',
      },
      status: [400, 403, 409, 422],
    }),
  ],
);
