/**
 * A seller's catalogue, their shelf, and the desk they pack from.
 *
 * Three invariants carry this folder, and each is a design decision rather than
 * a nicety.
 *
 * The movement is the record and the count is the consequence. A stock figure
 * that can be assigned directly is a figure nobody can explain, so the ledger
 * sums to the count and this folder adds it up.
 *
 * The moderation ladder is DRAFT to IN_REVIEW to APPROVED to PUBLISHED, and the
 * last step is the seller's — being allowed to sell and choosing to are
 * different decisions. A draft that could publish itself would make the review
 * queue advisory.
 *
 * And the units are the authority for a serialised product. Two second-hand
 * phones of the same model are not interchangeable when one was opened once and
 * the other has a scratched screen, and that gap is most of the dispute surface
 * on this marketplace.
 */

const { req, folder } = require('../lib/collection');
const seed = require('../lib/seed');

module.exports = folder(
  '08 — A seller\'s catalogue, stock, handsets and fulfilment desk',
  'The moderation ladder, the stock ledger, the optimistic lock and the collection code.',
  [
    // ── The catalogue ────────────────────────────────────────────────────
    req({
      name: 'A seller sees their drafts and their withdrawn listings',
      path: '/vendor/products',
      as: 'lamin',
      note: 'The opposite of the public catalogue, which shows only what moderation '
          + 'approved. A seller who could not see their own draft could not finish '
          + 'it.',
      status: 200,
      script: `
const ids = $body.items.map(function (p) { return p.id; });
pm.test("His catalogue — the counts name every rung, archived included", function () {
  // The withdrawn listing is deliberately out of the default page — it is not
  // work in progress and a seller does not want it in the way. What it must
  // not be is invisible, so the counts come back with the page rather than as
  // a second round trip, and the archived tab is drawn from them.
  pm.expect($body.counts, 'no tab counts, so a back office cannot draw its tabs')
    .to.be.an('object');
  pm.expect($body.counts.ARCHIVED, 'the withdrawn listing is not counted anywhere')
    .to.be.at.least(1);
  pm.expect(ids, 'the withdrawn listing is in the default page')
    .to.not.include(${seed.products.refurbished});
});
pm.test("His catalogue — nothing of Awa's is in it", function () {
  pm.expect(ids, "the wax print is Awa's").to.not.include(${seed.products.waxPrint});
});
pm.test("His catalogue — live is true if and only if the status is PUBLISHED", function () {
  // Two fields describing one fact drift the moment something sets one without
  // the other, and the way they drift here is a listing moderation pulled that
  // carries on selling.
  $body.items.forEach(function (p) {
    pm.expect(p.live, p.name + ' is ' + p.status + ' and live=' + p.live)
      .to.equal(p.status === 'PUBLISHED');
  });
});
pm.test("His catalogue — every listing says what it is priced in", function () {
  $body.items.forEach(function (p) {
    pm.expect(p.currency, p.name + ' has no currency').to.equal('GMD');
  });
});
`,
    }),

    req({
      name: 'And the archived tab returns what its own count promised',
      path: '/vendor/products',
      query: { status: 'ARCHIVED' },
      as: 'lamin',
      note: 'Asking for the archived tab IS asking to include archived listings. It '
          + 'came back empty while the counts block beside it said there was one, so '
          + 'a seller clicking the tab their own screen had just offered them got '
          + 'nothing, and no message either.',
      status: 200,
      json: { 'items.0.id': seed.products.refurbished, 'items.0.status': 'ARCHIVED' },
      script: `
pm.test("Archived tab — the count and the page agree", function () {
  pm.expect($body.items.length).to.equal($body.counts.ARCHIVED);
});
`,
    }),

    req({
      name: 'A new listing is created as a DRAFT, always',
      method: 'POST',
      path: '/vendor/products',
      as: 'lamin',
      headers: { 'Idempotency-Key': 'e2e-new-listing' },
      body: {
        name: 'Anker PowerCore 10000',
        shortDescription: 'Ten thousand milliamp power bank',
        description: 'Two USB-A outputs, charges a phone three times over.',
        sku: 'KOM-ANK10K',
        price: 1850,
        categoryId: 1214,
        condition: 'NEW',
        stock: 12,
        weightKg: 0.24,
      },
      note: 'Never PUBLISHED and never APPROVED, whatever the body asks for. A seller '
          + 'who could create an approved listing would be a seller who never '
          + 'entered the review queue.',
      status: [200, 201, 400, 409],
      script: `
if (pm.response.code < 300) {
  pm.test("New listing — DRAFT, and not live", function () {
    pm.expect($body.status).to.equal('DRAFT');
    pm.expect($body.live, 'a draft on sale').to.equal(false);
  });
  pm.test("New listing — priced in the seller's own currency, derived not sent", function () {
    pm.expect($body.currency, "Lamin settles in dalasi").to.equal('GMD');
  });
  pm.collectionVariables.set('draftProduct', $body.id);
} else {
  pm.test("New listing — refused with a reason, most likely the SKU already exists", function () {
    pm.expect(H.text(pm.response).length).to.be.above(2);
  });
}
`,
    }),

    req({
      name: 'And it cannot put itself on sale',
      method: 'POST',
      path: '/vendor/products/{{draftProduct}}/publish',
      as: 'lamin',
      headers: { 'Idempotency-Key': 'e2e-publish-draft' },
      body: {},
      note: 'A draft cannot publish itself, and that refusal is the only thing making '
          + 'the review queue real rather than advisory.',
      status: [400, 403, 404, 409, 422],
    }),

    req({
      name: 'Submitting it for review is what a seller can do',
      method: 'POST',
      path: '/vendor/products/{{draftProduct}}/submit-for-review',
      as: 'lamin',
      headers: { 'Idempotency-Key': 'e2e-submit-draft' },
      body: {},
      status: [200, 201, 202, 400, 404, 409],
      script: `
if (pm.response.code < 300) {
  pm.test("Submitted — IN_REVIEW, and still not live", function () {
    pm.expect($body.status).to.equal('IN_REVIEW');
    pm.expect($body.live).to.equal(false);
  });
}
`,
    }),

    req({
      name: 'Changing the stock on an approved listing leaves it on sale',
      method: 'PATCH',
      path: `/vendor/products/${seed.products.kettle}`,
      as: 'lamin',
      body: { stock: 24 },
      note: 'A seller who had to re-enter the review queue to restock would stop '
          + 'using the queue. The approved content hash is a digest of what a '
          + 'moderator actually looked at — the name, the text, the price, the '
          + 'category, the brand, the condition — and stock is none of those.',
      status: 200,
      json: { status: 'PUBLISHED', live: true },
    }),

    req({
      name: 'Changing the price takes it off sale and back into the queue',
      method: 'PATCH',
      path: `/vendor/products/${seed.products.kettle}`,
      as: 'lamin',
      body: { price: 1390 },
      note: 'The hash stops matching, so the listing goes back to IN_REVIEW and active '
          + 'goes false. Which is the point: a moderator approved a kettle at 1250, '
          + 'not a kettle at any price the seller later chooses.',
      status: 200,
      script: `
pm.test("Price edit — back into the queue, and off sale", function () {
  pm.expect($body.status, 'a re-priced listing stayed approved').to.equal('IN_REVIEW');
  pm.expect($body.live, 'a listing in the review queue is still on sale').to.equal(false);
});
`,
    }),

    req({
      name: 'And it is gone from the public catalogue immediately',
      path: '/products',
      query: { store: seed.vendors.kombo.slug },
      note: 'Every public query filters on active, and ProductLifecycle is the only '
          + 'thing allowed to write it. This is that pair holding under a real edit '
          + 'rather than in a unit test.',
      status: 200,
      script: `
pm.test("Public catalogue — the re-priced kettle is not on sale", function () {
  const ids = $body.items.map(function (p) { return p.id; });
  pm.expect(ids, 'a listing in the review queue is on the shopfront')
    .to.not.include(${seed.products.kettle});
});
`,
    }),

    // ── The rest of the ladder, and putting the shelf back ───────────────
    //
    // Not tidying up for its own sake. The three requests above take a
    // published listing off sale, and folders 03 and 04 assert on the public
    // catalogue having six listings and the kettle costing 1250 — so a run that
    // stopped here would leave the next one red in two other folders for a
    // reason nobody would trace back to this one.
    //
    // Restoring it is also the other half of the test. The ladder is DRAFT to
    // IN_REVIEW to APPROVED to PUBLISHED, and only a moderator makes the third
    // step and only the seller the fourth. Walking it is worth more than the
    // edit that pushed the listing back down it.
    req({
      name: 'A moderator approves the change, and the listing returns to where it was',
      method: 'POST',
      path: `/admin/products/${seed.products.kettle}/approve`,
      as: 'fatou',
      headers: { 'Idempotency-Key': 'e2e-approve-kettle' },
      body: { note: 'Price change only; nothing else moved.' },
      note: 'Straight back to PUBLISHED rather than to APPROVED, and that is the '
          + 'right answer for THIS listing: the seller had already decided to sell '
          + 'it, and a price edit does not withdraw that decision. The rung that '
          + 'needs the seller is the one below — see the draft further up this '
          + 'folder, which cannot publish itself even once it is approved.',
      status: [200, 201, 400, 409],
      script: `
if (pm.response.code < 300) {
  pm.test("Approved — back on the rung it was on before the edit", function () {
    pm.expect(['APPROVED', 'PUBLISHED'],
      'approving a re-priced listing left it at ' + $body.status).to.include($body.status);
  });
}
`,
    }),

    req({
      name: 'Putting the price back sends it round the loop again',
      method: 'PATCH',
      path: `/vendor/products/${seed.products.kettle}`,
      as: 'lamin',
      body: { price: 1250 },
      note: 'Which proves the hash check is on the content rather than on a one-off '
          + 'flag: the same field changing back is still a change a moderator '
          + 'approved a different value for.',
      status: 200,
      json: { status: 'IN_REVIEW', live: false },
    }),

    req({
      name: 'Approved again',
      method: 'POST',
      path: `/admin/products/${seed.products.kettle}/approve`,
      as: 'fatou',
      headers: { 'Idempotency-Key': 'e2e-reapprove-kettle' },
      body: { note: 'Back to its original price.' },
      status: [200, 201, 400, 409],
    }),

    req({
      name: 'Published if it needs it, which leaves the shelf as this folder found it',
      method: 'POST',
      path: `/vendor/products/${seed.products.kettle}/publish`,
      as: 'lamin',
      headers: { 'Idempotency-Key': 'e2e-republish-kettle' },
      body: {},
      note: 'A no-op when the approval already restored it, and the step that finishes '
          + 'the job when it did not. Either answer is correct; what matters is the '
          + 'state afterwards, which the next request checks.',
      status: [200, 201, 400, 409],
    }),

    req({
      name: 'The shopfront has its six listings back, at their original prices',
      path: '/products',
      note: 'The assertion that makes the restoration real rather than intended.\n\n'
          + 'Folders 03 and 04 assert that the catalogue has six listings and that '
          + 'the kettle costs 1250 — and they run BEFORE this one, so a run that '
          + 'left the listing in the review queue would leave the NEXT run red in '
          + 'two other folders for a reason nobody would trace back to here. Which '
          + 'is the argument for restoring rather than restarting: a suite that '
          + 'leaves the world as it found it can be run twice.',
      status: 200,
      json: { totalElements: 6 },
      script: `
pm.test("Restored — the kettle is on sale at its original price", function () {
  const kettle = $body.items.find(function (p) { return p.id === ${seed.products.kettle}; });
  pm.expect(kettle, 'the kettle is not on the shopfront, so folder 03 is about to fail')
    .to.be.an('object');
  pm.expect(kettle.listingPrice, 'folder 04 asserts 1250 for this listing')
    .to.equal(1250);
});
`,
    }),

    req({
      name: "Another seller cannot edit his listing",
      method: 'PATCH',
      path: `/vendor/products/${seed.products.kettle}`,
      as: 'awa',
      body: { price: 1 },
      status: 404,
    }),

    req({
      name: 'A translation, marked as machine-made when it is',
      method: 'PUT',
      path: `/vendor/products/${seed.products.waxPrint}/translations/fr-SN`,
      as: 'awa',
      body: {
        name: 'Wax imprimé — six yards, indigo',
        shortDescription: 'Coton fini à la main, pièce de six yards',
        description: 'Coupé sur mesure. Six yards, teinture indigo.',
        machineTranslated: false,
      },
      note: 'Awa writes French in Ziguinchor; the buyer paying is in London; the '
          + 'parcel goes to Serrekunda. Nobody in that chain can pick the cloth up '
          + 'and look at it, so the listing text is the whole of what they get — and '
          + 'a machine\'s version of "six yards of wax print, cut to order" is '
          + 'usually fine and occasionally nonsense. Somebody spending a month\'s '
          + 'remittance should be told which they are reading.',
      status: [200, 201, 204],
    }),

    req({
      name: 'The import that half worked, with the seller\'s own words quoted back',
      path: `/vendor/imports/${seed.imports.withRowErrors}`,
      as: 'lamin',
      note: 'Twenty-seven of thirty rows are in the catalogue as drafts and three did '
          + 'not parse. Each error names the row as the seller\'s own spreadsheet '
          + 'numbers it — header is row 1 — and quotes their text back: "there is no '
          + 'category called \'Phonez\'" rather than "invalid category". That '
          + 'difference is the whole reason to build this instead of telling them to '
          + 'use the form.',
      status: 200,
      script: `
pm.test("Import — counts in and counts rejected, not just a status", function () {
  const text = JSON.stringify($body);
  pm.expect(text).to.match(/27|"created"|"imported"/);
  pm.expect($body.status, 'an import with no status').to.be.a('string');
});
pm.test("Import — the row errors quote the seller's own text", function () {
  const errors = JSON.stringify($body.errors || $body.rowErrors || []);
  pm.expect(errors, 'no row errors on an import that rejected three rows')
    .to.have.length.of.at.least(10);
  pm.expect(errors, "the seller's own bad value should be quoted back at them")
    .to.match(/Phonez/i);
});
`,
    }),

    req({
      name: 'The import where the file itself was unreadable says so once',
      path: `/vendor/imports/${seed.imports.unreadableFile}`,
      as: 'lamin',
      note: 'Somebody uploaded a PDF. Nothing was attempted, and saying so once beats '
          + 'four hundred identical row errors.',
      status: 200,
      script: `
pm.test("Unreadable file — one message about the file, not a list about rows", function () {
  const errors = $body.errors || $body.rowErrors || [];
  pm.expect(errors.length, 'a file nothing could be read from produced row errors')
    .to.be.at.most(1);
  pm.expect(JSON.stringify($body), 'nothing says what was wrong with the file')
    .to.have.length.of.at.least(20);
});
`,
    }),

    req({
      name: "Another seller's import reference is not found",
      path: `/vendor/imports/${seed.imports.withRowErrors}`,
      as: 'awa',
      note: 'Import errors name a seller\'s products, their prices and their SKUs. The '
          + 'reference is random rather than sequential for that reason, and '
          + 'ownership is checked in the query as well — the reference is the second '
          + 'lock, not the only one.',
      status: 404,
    }),

    req({
      name: 'The import template is a list of column names and no data',
      path: '/vendor/products/import-template',
      as: 'lamin',
      note: 'The one endpoint under /vendor that any account may read, because there '
          + 'is nothing here belonging to anybody: it describes the file format.',
      status: 200,
      script: `
pm.test("Template — required columns are named separately from optional ones", function () {
  pm.expect($body.requiredColumns, 'no required columns').to.be.an('array');
  pm.expect($body.requiredColumns).to.include('name');
  pm.expect($body.requiredColumns).to.include('price');
  pm.expect($body.optionalColumns).to.be.an('array');
});
pm.test("Template — and no seller's data is in it", function () {
  pm.expect(H.text(pm.response)).to.not.include('KOM-');
});
`,
    }),

    // ── The shelf ────────────────────────────────────────────────────────
    req({
      name: 'Stock that is running out, flagged rather than found by reading',
      path: '/vendor/inventory',
      query: { lowStock: true },
      as: 'lamin',
      status: 200,
      script: `
pm.test("Low stock — everything returned is actually low", function () {
  pm.expect($body.items.length).to.be.at.least(1);
  $body.items.forEach(function (v) {
    pm.expect(v.lowStock, v.sku + ' came back from a low-stock query with lowStock=false')
      .to.equal(true);
    pm.expect(v.stock, v.sku + ' is flagged low at ' + v.stock
      + ' against a threshold of ' + v.lowStockThreshold)
      .to.be.at.most(v.lowStockThreshold);
  });
});
pm.test("Low stock — a serialised variant reports its sellable units", function () {
  const serialised = $body.items.filter(function (v) { return v.serialised; });
  serialised.forEach(function (v) {
    pm.expect(v.sellableUnits, v.sku + ' is serialised and does not say how many units')
      .to.be.a('number');
    pm.expect(v.sellableUnits, v.sku + ': the count must follow the units')
      .to.equal(v.stock);
  });
});
pm.test("Low stock — every row carries the version it was read at", function () {
  // Which is what makes an absolute correction safe. See the optimistic lock
  // below.
  $body.items.forEach(function (v) {
    pm.expect(v.version, v.sku + ' has no version').to.be.a('number');
  });
});
`,
    }),

    req({
      name: 'The movements add up to the count, which is the whole design',
      path: `/vendor/inventory/${seed.variants.phoneOther}/movements`,
      as: 'lamin',
      note: '0 + 10 - 3 - 4 = 3. A count that can be assigned directly is a count '
          + 'nobody can explain, so the movement is the record and the figure is what '
          + 'the movements come to.',
      status: 200,
      json: { variantId: seed.variants.phoneOther, currentStock: 3 },
      script: `
pm.test("Ledger — the movements sum to the stock", function () {
  const summed = $body.movements.reduce(function (s, m) { return s + m.quantityChange; }, 0);
  pm.expect(summed, 'movements come to ' + summed + ' and the stock says '
    + $body.currentStock).to.equal($body.currentStock);
});
pm.test("Ledger — every row's before and after are consistent with its change", function () {
  $body.movements.forEach(function (m) {
    pm.expect(m.stockBefore + m.quantityChange,
      'movement ' + m.id + ': ' + m.stockBefore + ' + ' + m.quantityChange
      + ' should be ' + m.stockAfter).to.equal(m.stockAfter);
  });
});
pm.test("Ledger — the sales are in the trail beside the seller's own corrections", function () {
  // An audit showing the manual edits and quietly omitting the orders that
  // took the stock would be wrong in exactly the case somebody opens it for.
  const reasons = $body.movements.map(function (m) { return m.reason; });
  pm.expect(reasons, 'the orders that took stock are missing from the trail')
    .to.include('SALE');
  pm.expect(reasons).to.include('RESTOCK');
  pm.expect(reasons).to.include('CORRECTION');
});
pm.test("Ledger — a SALE has no person on it, because an order took the stock", function () {
  $body.movements.filter(function (m) { return m.reason === 'SALE'; })
    .forEach(function (m) {
      pm.expect(m.recordedBy, 'a sale attributed to a member of staff').to.not.exist;
      // And no order number either: stock is reserved before the order exists,
      // which is the right way round.
      pm.expect(m.reference, 'a sale carrying an order number it cannot have').to.not.exist;
    });
});
pm.test("Ledger — a correction names who counted the shelf", function () {
  const correction = $body.movements.find(function (m) { return m.id === 1452; });
  pm.expect(correction.recordedBy, 'a manual correction with nobody behind it')
    .to.be.a('string');
  pm.expect(correction.note, 'and with no reason written down').to.be.a('string');
  pm.expect(correction.quantityChange, 'the row that earns the feature: minus four')
    .to.equal(-4);
});
`,
    }),

    req({
      name: 'A delta needs no version: plus five is plus five whoever else is writing',
      method: 'PATCH',
      path: `/vendor/inventory/${seed.variants.phoneOther}`,
      as: 'lamin',
      body: { delta: 5, reason: 'RESTOCK', note: 'Container from Dakar' },
      status: 200,
      script: `
pm.test("Delta — the answer is before, after and the new version", function () {
  pm.expect($body.stockAfter, 'no resulting figure').to.be.a('number');
  pm.expect($body.stockBefore, 'no starting figure, so the caller cannot tell what moved')
    .to.be.a('number');
  pm.expect($body.stockAfter - $body.stockBefore, 'the answer does not reflect the delta sent')
    .to.equal(5);
  pm.expect($body.version, 'no version, so the next absolute correction has nothing to send')
    .to.be.a('number');
});
pm.test("Delta — and it says in words what it did", function () {
  pm.expect($body.message, 'a stock change with no sentence a seller can read')
    .to.be.a('string');
});
`,
      capture: { phoneOtherVersion: 'version' },
    }),

    req({
      name: 'An absolute figure carries the version it was read at',
      method: 'PATCH',
      path: `/vendor/inventory/${seed.variants.phoneOther}`,
      as: 'lamin',
      body: { setTo: 12, version: '{{phoneOtherVersion}}', reason: 'CORRECTION', note: 'Counted the shelf' },
      note: 'Two people counting the same shelf and saving 10 and 12 must not leave '
          + 'whichever committed last, with the other simply wrong and nothing '
          + 'anywhere to say so. Which is why the endpoint offers both forms and asks '
          + 'for the version on only one.',
      status: [200, 409],
      script: `
if (pm.response.code === 200) {
  pm.test("Absolute — the shelf reads twelve", function () {
    pm.expect($body.stockAfter).to.equal(12);
  });
} else {
  pm.test("Absolute — refused because the version had already moved", function () {
    pm.expect(H.text(pm.response).length).to.be.above(2);
  });
}
`,
    }),

    req({
      name: 'And the same version again is refused, because the first moved it',
      method: 'PATCH',
      path: `/vendor/inventory/${seed.variants.phoneOther}`,
      as: 'lamin',
      body: { setTo: 10, version: '{{phoneOtherVersion}}', reason: 'CORRECTION', note: 'Counted it again' },
      note: 'The whole point of the lock. Ten and twelve are two people\'s counts of '
          + 'one shelf, and last-write-wins loses one of them silently.',
      status: [400, 409, 422],
    }),

    req({
      name: 'A serialised variant refuses a typed figure outright',
      method: 'PATCH',
      path: `/vendor/inventory/${seed.variants.phone}`,
      as: 'lamin',
      body: { setTo: 9, version: 0, reason: 'CORRECTION' },
      note: 'Variant 1350 is IMEI-tracked. The units are the authority and the count '
          + 'follows them — a shop whose count did not follow would go on selling a '
          + 'phone that is in a drawer with water damage.',
      status: [400, 409, 422],
    }),

    req({
      name: "Another seller's shelf is not adjustable",
      method: 'PATCH',
      path: `/vendor/inventory/${seed.variants.phoneOther}`,
      as: 'awa',
      body: { delta: -3, reason: 'DAMAGE' },
      status: 404,
    }),

    // ── Handsets ────────────────────────────────────────────────────────
    req({
      name: 'The handsets behind a serialised variant, one row each',
      path: '/vendor/imei-units',
      query: { variantId: seed.variants.phone },
      as: 'lamin',
      note: 'Phones are the one product a count cannot describe: most sold here are '
          + 'second-hand, and the buyer is often thousands of miles away choosing a '
          + 'gift for somebody at home. Two units of the same model are not '
          + 'interchangeable when one was opened once and the other has a scratched '
          + 'screen.',
      status: 200,
      script: `
pm.test("Handsets — every IMEI satisfies its own Luhn check digit", function () {
  function luhn(imei) {
    let sum = 0;
    for (let i = 0; i < imei.length; i++) {
      let digit = parseInt(imei.charAt(imei.length - 1 - i), 10);
      if (i % 2 === 1) {
        digit *= 2;
        if (digit > 9) digit -= 9;
      }
      sum += digit;
    }
    return sum % 10 === 0;
  }
  pm.expect($body.items.length).to.be.at.least(1);
  $body.items.forEach(function (unit) {
    pm.expect(unit.imei, 'an IMEI that is not fifteen digits').to.match(/^\\d{15}$/);
    pm.expect(luhn(unit.imei), unit.imei + ' fails its own check digit').to.be.true;
  });
});
pm.test("Handsets — the written-off unit is not sellable", function () {
  const off = $body.items.find(function (u) { return u.id === ${seed.imei.writtenOff}; });
  if (off) {
    pm.expect(['WRITTEN_OFF', 'DAMAGED', 'BLOCKED'],
      'unit ${seed.imei.writtenOff} is written off and reports ' + off.status)
      .to.include(off.status);
  }
});
`,
    }),

    req({
      name: 'An IMEI one digit off its check digit is rejected, and the others are not',
      method: 'POST',
      path: '/vendor/imei-units',
      as: 'lamin',
      headers: { 'Idempotency-Key': 'e2e-register-imei' },
      body: {
        productId: seed.products.phone,
        variantId: seed.variants.phone,
        units: [
          { imei: '013227009086252', serialNumber: 'RF8N90ABCDX', grade: 'A_GRADE' },
          { imei: seed.imei.invalid, serialNumber: 'RF8N90ABCDY', grade: 'B_GRADE' },
        ],
      },
      note: 'Recording an IMEI at all is mostly about making a stolen handset harder '
          + 'to launder, and an IMEI that fails its own check digit is a typo or a '
          + 'fabrication. That line alone is rejected while the others register — a '
          + 'seller entering forty handsets should not lose thirty-nine to one '
          + 'mistake.',
      status: [200, 201, 207, 400, 409, 422],
      script: `
pm.test("Registering — the answer accounts for both lines, one way or the other", function () {
  // Either the bad line is named as rejected while the good one registers, or
  // the whole batch is refused and says which line was wrong. What must not
  // happen is a silent partial success that leaves a seller unsure which of
  // forty handsets is on the system.
  const text = H.text(pm.response);
  pm.expect(text.length, 'answered with nothing at all').to.be.above(2);
  pm.expect(text, 'neither the failing IMEI nor a count of failures is mentioned')
    .to.match(/${seed.imei.invalid}|reject|invalid|fail|check digit|Luhn/i);
});
`,
    }),

    req({
      name: 'A seller cannot mark a handset SOLD',
      method: 'PATCH',
      path: `/vendor/imei-units/${seed.imei.writtenOff}`,
      as: 'lamin',
      body: { status: 'SOLD' },
      note: 'The order does that, so the record and the sale cannot disagree.',
      status: [400, 403, 409, 422],
    }),

    req({
      name: 'Nor touch BLOCKED in either direction',
      method: 'PATCH',
      path: `/vendor/imei-units/${seed.imei.writtenOff}`,
      as: 'lamin',
      body: { status: 'BLOCKED' },
      note: 'Setting it would let them flag a rival\'s stock; clearing it would let '
          + 'them launder a stolen handset. Both directions, which is why the '
          + 'refusal is on the value rather than on the transition.',
      status: [400, 403, 409, 422],
    }),

    // ── Discounts ───────────────────────────────────────────────────────
    req({
      name: 'A promotion that overlaps another is refused, and the refusal names it',
      method: 'POST',
      path: `/vendor/promotions/${seed.promotions.conflicting}/activate`,
      as: 'lamin',
      headers: { 'Idempotency-Key': 'e2e-activate-conflicting' },
      body: {},
      note: 'Both cover product 1301 and their windows touch. Two discounts on one '
          + 'item do not compound into a price anybody can predict — they compound '
          + 'into whichever the pricing code reaches first, which is a different '
          + 'answer on different days and an argument with a buyer either way. '
          + '"Conflicts with an existing promotion" would leave a seller hunting '
          + 'through their own list, so the refusal says which one.',
      status: [400, 409, 422],
      script: `
pm.test("Conflict — the refusal names the promotion it clashes with", function () {
  const text = H.text(pm.response);
  pm.expect(text, 'a seller told "conflicts" and nothing else has to go hunting')
    .to.match(/${seed.promotions.active}|Tobaski phone sale/i);
});
`,
    }),

    req({
      name: 'One that does not overlap is allowed',
      method: 'POST',
      path: `/vendor/promotions/${seed.promotions.activatable}/activate`,
      as: 'lamin',
      headers: { 'Idempotency-Key': 'e2e-activate-ok' },
      body: {},
      status: [200, 201, 400, 409],
      script: `
if (pm.response.code < 300) {
  pm.test("Activated — running, with an empty conflict list", function () {
    pm.expect($body.status).to.equal('ACTIVE');
    pm.expect($body.running).to.equal(true);
    pm.expect($body.conflicts, 'activated with conflicts against it').to.eql([]);
  });
  pm.test("Activating twice is idempotent and says so", function () {
    // Run a second time, because a seller taps a button twice and a promotion
    // that re-activated would move its window.
    pm.expect($body.message, 'no sentence explaining what happened').to.be.a('string');
  });
}
`,
    }),

    req({
      name: "His promotions are his, struck in his own currency",
      path: '/vendor/promotions',
      query: { size: 50 },
      as: 'lamin',
      status: 200,
      script: `
pm.test("Promotions — every amount is in the seller's own currency", function () {
  // 500 GMD off, because Lamin banks in dalasi. A buyer paying in sterling sees
  // that converted at the rate their order was quoted at; striking it in GBP
  // would leave him funding an amount that moves with the market between
  // writing the promotion and the order landing.
  $body.items.forEach(function (p) {
    if (p.currency) {
      pm.expect(p.currency, p.name + ' is struck in ' + p.currency).to.equal('GMD');
    }
  });
});
pm.test("Promotions — none of Awa's are in his list", function () {
  const ids = $body.items.map(function (p) { return p.id; });
  pm.expect(ids, "promotion ${seed.promotions.freeShipping} belongs to Teranga Textiles")
    .to.not.include(${seed.promotions.freeShipping});
});
pm.test("Promotions — a draft says what is blocking it", function () {
  const draft = $body.items.find(function (p) { return p.status === 'DRAFT'; });
  if (!draft) return;
  pm.expect(draft.running, 'a draft promotion reported as running').to.equal(false);
  pm.expect(draft.blockedReason || draft.conflicts || draft.description,
    draft.name + ' is a draft with nothing saying why').to.exist;
});
`,
    }),

    req({
      name: "And Awa's free-delivery promotion is hers, in CFA",
      path: '/vendor/promotions',
      query: { size: 50 },
      as: 'awa',
      note: 'A free-delivery promotion and a goods discount do not compete for the '
          + 'same number — one waives the leg and the other reduces the price — which '
          + 'is why the conflict rule is about what a promotion touches rather than '
          + 'about dates alone. 1473 covers her whole shop, which is what no product '
          + 'rows means.',
      status: 200,
      script: `
pm.test("Her promotions — the free-delivery one is running", function () {
  const shipping = $body.items.find(function (p) {
    return p.id === ${seed.promotions.freeShipping};
  });
  pm.expect(shipping, 'promotion ${seed.promotions.freeShipping} is missing from her list')
    .to.be.an('object');
  pm.expect(shipping.type).to.match(/SHIP/i);
  pm.expect(shipping.running).to.equal(true);
});
pm.test("Her promotions — struck in CFA, because she banks in Senegal", function () {
  $body.items.forEach(function (p) {
    if (p.currency) {
      pm.expect(p.currency, p.name + ' is struck in ' + p.currency).to.equal('XOF');
    }
  });
});
pm.test("Her promotions — and none of Lamin's", function () {
  const ids = $body.items.map(function (p) { return p.id; });
  [${seed.promotions.active}, ${seed.promotions.activatable}, ${seed.promotions.conflicting}]
    .forEach(function (id) {
      pm.expect(ids, 'promotion ' + id + ' belongs to Kombo Electronics').to.not.include(id);
    });
});
`,
    }),

    // ── The fulfilment desk ─────────────────────────────────────────────
    req({
      name: "A seller's slice, and the whole of what they learn about either person",
      path: `/vendor/orders/${seed.vendorOrders.laminPacked}`,
      as: 'lamin',
      note: 'A packed parcel for Isatou in Serrekunda, paid for by Fatou in Madrid. '
          + 'The shipping block is a name, a town, a country and three digits of a '
          + 'phone — no street, because the platform routes the parcel and the QR on '
          + 'the label resolves the address for whoever scans it; and no payer at '
          + 'all.',
      status: 200,
      json: {
        id: seed.vendorOrders.laminPacked,
        status: 'READY_FOR_PICKUP',
        currency: 'GMD',
        'shipping.town': 'Serrekunda',
        'shipping.country': 'GM',
        'fx.paidIn': 'EUR',
        'fx.settledIn': 'GMD',
        'fx.rate': { $gt: 0 },
      },
      bodyExcludes: [
        { value: 'Fatou', why: 'the payer is nobody the seller was told about' },
        { value: 'fatou.admin@sujula.gm', why: 'nor her address' },
        { value: 'Madrid', why: 'nor where she is' },
        { value: '12 Kairaba Avenue', why: "and not the recipient's street either" },
      ],
      script: `
pm.test("Slice — a phone hint rather than a number", function () {
  pm.expect($body.shipping.phoneHint, 'no hint to check a caller against')
    .to.be.a('string');
  pm.expect($body.shipping.phoneHint, 'a full number on a seller screen')
    .to.match(/[^0-9+]/);
  pm.expect(H.text(pm.response)).to.not.include('+2203100077');
});
pm.test("C2 — his own figures, and the rate they were struck at", function () {
  // 9700 GMD of goods, 10% commission, 8730 payable. Delivery is the
  // platform's and is NOT added to the payout.
  pm.expect($body.goodsTotal).to.equal(9700);
  pm.expect($body.commission).to.equal(970);
  pm.expect($body.payout, 'goods less commission, and delivery is not his')
    .to.equal(8730);
  pm.expect($body.payout).to.equal($body.goodsTotal - $body.commission);
});
pm.test("C2 — the rate says what it converted between, not just a number", function () {
  pm.expect($body.fx.source, 'a rate with no provenance').to.be.a('string');
  pm.expect($body.fx.rateAt, 'a rate with no moment').to.be.a('string');
});
pm.test("Slice — the handsets on the line are named, because they are not interchangeable", function () {
  const line = $body.lines[0];
  pm.expect(line.serialised).to.equal(true);
  pm.expect(line.assignedImeis, 'a serialised line with no handset assigned')
    .to.be.an('array').and.to.have.length.of.at.least(1);
  pm.expect(line.handsetsOutstanding).to.equal(0);
});
`,
    }),

    req({
      name: 'The collection code, served no-store and never written on the parcel',
      path: `/vendor/orders/${seed.vendorOrders.laminPacked}/handoff-code`,
      as: 'lamin',
      note: 'Read aloud to the driver. A code printed on the box it protects protects '
          + 'nothing, which is why the label carries a signed QR instead.',
      status: 200,
      json: {
        code: seed.handoff.forVendorOrder1505,
        timesIssued: { $gte: 1 },
        message: { $exists: true },
      },
      header: { 'Cache-Control': { $matches: 'no-store' } },
      script: `
pm.test("Collection code — the answer tells the seller not to write it down", function () {
  pm.expect($body.message, 'no instruction with a code that must not be printed')
    .to.match(/driver|not.*write|parcel/i);
});
`,
    }),

    req({
      name: "And it is a 404 for the other seller",
      path: `/vendor/orders/${seed.vendorOrders.laminPacked}/handoff-code`,
      as: 'awa',
      note: 'The collection code is a seller-only secret. It gets no permitAll of the '
          + 'kind the tracking page and the invoice link have, because those two are '
          + 'built to be worth nothing to a stranger holding them and this is not.',
      status: 404,
    }),

    req({
      name: 'The parcel label: an A6 PDF with none of the four things you would expect',
      path: `/vendor/orders/${seed.vendorOrders.laminPacked}/label`,
      as: 'lamin',
      note: 'Read what is NOT on it: no street, no price, no contents, and not the '
          + 'collection code.',
      status: 200,
      script: `
pm.test("Label — it is a PDF", function () {
  pm.expect(H.text(pm.response).slice(0, 5)).to.include('%PDF');
});
pm.test("Label — the collection code is not printed on the box it protects", function () {
  pm.expect(H.text(pm.response),
    'the code is on the label, which makes the code pointless')
    .to.not.include(${JSON.stringify(seed.handoff.forVendorOrder1505)});
});
pm.test("Label — no street and no price", function () {
  const text = H.text(pm.response);
  ['12 Kairaba Avenue', '9700', '132.16'].forEach(function (leak) {
    pm.expect(text, 'the label carries ' + leak).to.not.include(leak);
  });
});
`,
    }),

    req({
      name: 'Marking a packed slice ready again changes nothing, and does NOT reissue the code',
      method: 'POST',
      path: `/vendor/orders/${seed.vendorOrders.laminPacked}/ready`,
      as: 'lamin',
      headers: { 'Idempotency-Key': 'e2e-ready-already' },
      body: {},
      note: 'Idempotent rather than refused, and that is the better of the two: a '
          + 'seller taps "ready for collection" twice on a patchy connection, and '
          + 'answering 409 to the second tap tells them something is wrong when '
          + 'nothing is.\n\n'
          + 'What must not happen is a NEW collection code, because the driver is '
          + 'already on their way with the first one — so the assertion is on '
          + 'reissued being false and the code being unchanged, which is the part '
          + 'that matters. The seed note calling this "refused" describes an older '
          + 'shape.',
      status: 200,
      json: {
        status: 'READY_FOR_PICKUP',
        'releaseCode.code': seed.handoff.forVendorOrder1505,
        'releaseCode.reissued': false,
        'releaseCode.timesIssued': 1,
      },
      script: `
pm.test("Ready again — the answer says plainly that nothing changed", function () {
  pm.expect($body.message, 'no sentence telling the seller what happened')
    .to.be.a('string');
  pm.expect($body.message).to.match(/already|unchanged/i);
});
`,
    }),

    req({
      name: 'Awa\'s rejected slice carries her reason on it',
      path: `/vendor/orders/${seed.vendorOrders.awaRejected}`,
      as: 'awa',
      note: 'Then look at 1505 again: untouched, still going. One payment, two '
          + 'vendors, independent outcomes — C3 from the seller\'s side.',
      status: 200,
      json: {
        id: seed.vendorOrders.awaRejected,
        status: 'CANCELLED',
        currency: 'XOF',
        rejectionReason: { $matches: 'indigo' },
        cancelledAt: { $exists: true },
      },
      script: `
pm.test("Rejected — the reason is hers, in her own words", function () {
  pm.expect($body.rejectionReason, 'cancelled with no reason recorded').to.be.a('string');
  pm.expect($body.rejectionReason.length, 'a reason too short to mean anything')
    .to.be.above(20);
});
pm.test("C3 — and her figures are in CFA, not the buyer's euros", function () {
  pm.expect($body.currency).to.equal('XOF');
  pm.expect($body.fx.paidIn, 'the buyer paid in euros').to.equal('EUR');
  pm.expect($body.fx.settledIn, 'she settles in CFA').to.equal('XOF');
});
`,
    }),

    req({
      name: 'And the other seller\'s slice of the same order is untouched',
      path: `/vendor/orders/${seed.vendorOrders.laminPacked}`,
      as: 'lamin',
      status: 200,
      json: { status: 'READY_FOR_PICKUP', cancelledAt: { $exists: false } },
    }),

    req({
      name: "A seller cannot read another seller's slice of their own order",
      path: `/vendor/orders/${seed.vendorOrders.awaRejected}`,
      as: 'lamin',
      note: 'Both slices belong to order 1404. Sharing an order does not share a '
          + 'slice: what Awa charges, what she is paid and why she cancelled are her '
          + 'business.',
      status: 404,
    }),

    // ── The money ───────────────────────────────────────────────────────
    req({
      name: 'A balance is the sum of the ledger, never a stored figure',
      path: '/vendor/balance',
      as: 'lamin',
      note: '1000 dalasi available and 17,640 pending. The pending figure is escrow — '
          + 'parcels that have not been confirmed delivered. Nothing stores either '
          + 'number; both are sums of vendor_ledger_entries.',
      status: 200,
      script: `
pm.test("Balance — one figure per currency and never one total", function () {
  pm.expect($body.byCurrency.length).to.be.at.least(1);
  pm.expect($body.total, 'a single total across currencies is not a number').to.not.exist;
  $body.byCurrency.forEach(function (b) {
    pm.expect(b.currency).to.be.a('string');
    pm.expect(b.available).to.be.a('number');
    pm.expect(b.pending, 'no escrow figure').to.be.a('number');
  });
});
pm.test("Balance — his is in dalasi", function () {
  pm.expect($body.byCurrency.map(function (b) { return b.currency; })).to.eql(['GMD']);
});
pm.test("Balance — the total is its own parts", function () {
  $body.byCurrency.forEach(function (b) {
    pm.expect(b.total, b.currency + ': ' + b.available + ' + ' + b.pending
      + ' should be ' + (b.available + b.pending)).to.equal(b.available + b.pending);
  });
});
`,
    }),

    req({
      name: 'And the rows behind it add up to it',
      path: '/vendor/transactions',
      as: 'lamin',
      note: 'Which is the only thing that makes a balance checkable. Add the amount '
          + 'column up and you get the balance.',
      status: 200,
      script: `
pm.test("Transactions — every entry says what it was and when", function () {
  const entries = $body.entries || $body.content || [];
  pm.expect(entries.length).to.be.at.least(1);
  entries.forEach(function (e) {
    pm.expect(e.type, 'an entry with no type').to.be.a('string');
    pm.expect(e.amount, 'an entry with no amount').to.be.a('number');
    pm.expect(e.currency).to.be.a('string');
    pm.expect(e.occurredAt).to.be.a('string');
    pm.expect(e.heldInEscrow, 'an entry that does not say whether it is held')
      .to.be.a('boolean');
  });
});
pm.test("Transactions — the entries come to the balance", function () {
  const entries = $body.entries || $body.content || [];
  const gmd = entries.filter(function (e) { return e.currency === 'GMD'; });
  const summed = gmd.reduce(function (s, e) { return s + e.amount; }, 0);
  pm.sendRequest({
    url: pm.variables.replaceIn('{{baseUrl}}/vendor/balance'),
    method: 'GET',
    header: { Authorization: 'Bearer ' + pm.collectionVariables.get('tokenLamin') },
  }, function (error, answer) {
    pm.expect(error).to.equal(null);
    const balance = answer.json().byCurrency.find(function (b) { return b.currency === 'GMD'; });
    pm.expect(Math.abs(summed - balance.total) < 0.05,
      'the ledger comes to ' + summed.toFixed(2) + ' and the balance says '
      + balance.total + '. A balance that is not the sum of its rows is a figure '
      + 'nobody can check.').to.be.true;
  });
});
`,
    }),

    req({
      name: 'A failed payout came back as a reversal rather than being deleted',
      path: '/vendor/transactions',
      query: { currency: 'XOF' },
      as: 'awa',
      note: 'Her payout failed and the reversal explains the gap instead of hiding it. '
          + 'And look at slice 1506: four rows — sale, commission, refund, commission '
          + 'returned — that come to exactly nothing. Netting them into one would '
          + 'hide the thing a seller opens a refund to check.',
      status: 200,
      script: `
pm.test("Reversal — the payout and its reversal are both rows", function () {
  const entries = $body.entries || $body.content || [];
  const types = entries.map(function (e) { return e.type; });
  pm.expect(types, 'the payout that failed is missing').to.include('PAYOUT');
  pm.expect(types, 'it was deleted rather than reversed').to.include('PAYOUT_REVERSAL');
});
pm.test("Reversal — and they cancel out exactly", function () {
  const entries = $body.entries || $body.content || [];
  const payout = entries.find(function (e) { return e.type === 'PAYOUT'; });
  const reversal = entries.find(function (e) { return e.type === 'PAYOUT_REVERSAL'; });
  pm.expect(payout.amount + reversal.amount,
    payout.amount + ' and ' + reversal.amount + ' do not cancel').to.equal(0);
  pm.expect(reversal.description, 'a reversal with no reason a seller can read')
    .to.be.a('string');
});
pm.test("Refund — the commission is returned as its own row", function () {
  const entries = $body.entries || $body.content || [];
  const types = entries.map(function (e) { return e.type; });
  pm.expect(types, 'a refund with no commission reversal beside it')
    .to.include('COMMISSION_REVERSAL');
});
pm.test("Every amount is in CFA, and CFA has no centime", function () {
  const entries = $body.entries || $body.content || [];
  entries.forEach(function (e) {
    pm.expect(e.currency).to.equal('XOF');
  });
});
`,
    }),

    req({
      name: 'A payout request with money still held is refused, and says which kind of nothing',
      method: 'POST',
      path: '/vendor/payouts/request',
      as: 'awa',
      headers: { 'Idempotency-Key': 'e2e-payout-awa' },
      body: { currency: 'XOF' },
      note: 'Money still held against parcels, rather than no money at all. The two '
          + 'need different words: one means "wait", the other means "you have not '
          + 'sold anything".',
      status: [400, 409, 422],
      script: `
pm.test("Payout — the refusal distinguishes held money from no money", function () {
  pm.expect(H.text(pm.response), 'refused with no explanation of which it is')
    .to.match(/escrow|held|confirm|deliver/i);
});
`,
    }),

    req({
      name: 'Revenue per currency, never one total',
      path: '/vendor/analytics/overview',
      as: 'lamin',
      note: 'A shop trading in two currencies gets two figures and a note saying why '
          + 'they are not added. Adding them would produce a number with no unit.',
      status: 200,
      script: `
pm.test("Analytics — one revenue figure per currency", function () {
  pm.expect($body.byCurrency).to.be.an('array').and.to.have.length.of.at.least(1);
  pm.expect($body.revenue, 'a single revenue figure across currencies').to.not.exist;
  $body.byCurrency.forEach(function (c) {
    pm.expect(c.netRevenue, c.currency + ': ' + c.revenue + ' - ' + c.commission
      + ' should be ' + (c.revenue - c.commission)).to.equal(c.revenue - c.commission);
  });
});
pm.test("Analytics — the period and what it is compared with are both stated", function () {
  pm.expect($body.period.from).to.be.a('string');
  pm.expect($body.comparedWith.days, 'compared against a period of a different length')
    .to.equal($body.period.days);
});
pm.test("Analytics — the conversion rate says plainly what a view is", function () {
  // Views are page loads, including reloads and the same person twice. A
  // conversion rate presented as a fact about people would be wrong by a
  // multiple nobody can estimate.
  pm.expect($body.conversionNote, 'a conversion rate with no caveat on it')
    .to.be.a('string');
  pm.expect($body.conversionNote).to.match(/not unique|page loads|reload/i);
});
`,
    }),

    req({
      name: 'The phone was looked at 180 times and bought once',
      path: '/vendor/analytics/products',
      as: 'lamin',
      status: 200,
      script: `
pm.test("Product analytics — views and units sold are separate numbers", function () {
  pm.expect($body.rows, 'no per-product rows').to.be.an('array').and.to.have.length.of.at.least(1);
  const phone = $body.rows.find(function (r) { return r.productId === ${seed.products.phone}; });
  pm.expect(phone, 'the Galaxy is not in the rows').to.be.an('object');
  pm.expect(phone.views, 'looked at a hundred and eighty-odd times').to.be.above(100);
  pm.expect(phone.unitsSold, 'and bought a couple of times').to.be.at.least(1);
});
pm.test("Product analytics — the conversion rate is the two divided", function () {
  $body.rows.forEach(function (r) {
    if (!r.views || r.conversionPercent == null) return;
    const expected = Math.round((r.unitsSold / r.views) * 1000) / 10;
    pm.expect(Math.abs(r.conversionPercent - expected) < 0.2,
      r.name + ': ' + r.unitsSold + ' of ' + r.views + ' is ' + expected
      + '%, row says ' + r.conversionPercent).to.be.true;
  });
});
pm.test("Product analytics — a figure that cannot be computed says why", function () {
  // The withdrawn listing has no stock, so its stock turn is not a number. A
  // null with a note beside it is a seller being told; a zero is a seller
  // being misled.
  $body.rows.forEach(function (r) {
    if (r.stockTurn === null) {
      pm.expect(r.stockTurnNote, r.name + ': no stock turn and no reason given')
        .to.be.a('string');
    }
  });
});
pm.test("Product analytics — every row is in the seller's own currency", function () {
  $body.rows.forEach(function (r) {
    pm.expect(r.currency, r.name + ' has no currency').to.equal('GMD');
  });
});
`,
    }),

    req({
      name: 'Customers: counts and countries, and nothing else',
      path: '/vendor/analytics/customers',
      as: 'lamin',
      note: 'Destinations are where parcels went, never where the payer was, and a '
          + 'country with only a handful of orders is left out entirely — because at '
          + 'small counts a country IS an identity.',
      status: 200,
      bodyExcludes: [
        { value: 'Oliver', why: 'a seller gets counts, not a customer list' },
        { value: 'oliver.bennett@example.co.uk', why: 'and certainly not addresses' },
        { value: 'Aminata', why: 'nor names' },
        { value: '221B', why: 'nor streets' },
      ],
    }),

    req({
      name: 'A statement whose brought-forward plus movements equals its carried-forward',
      path: `/vendor/statements/${seed.statementPeriod}`,
      query: { currency: 'GMD' },
      as: 'lamin',
      status: [200, 404],
      script: `
if (pm.response.code === 200) {
  pm.test("Statement — it is a document rather than an error page", function () {
    pm.expect(pm.response.stream.length, 'an empty statement').to.be.above(400);
  });
}
`,
    }),

    req({
      name: "Another seller's figures are not readable",
      path: '/vendor/balance',
      as: 'ndeye',
      note: 'What is behind this rule is revenue, margins, a bank-ready statement and '
          + 'the destinations a shop ships to. An unguarded route here hands a '
          + "competitor a shop's whole trading position.",
      status: [400, 403, 404],
    }),
  ],
);
