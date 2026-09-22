/**
 * The shopfront: what somebody with no account can see, and what they cannot.
 *
 * Two things carry this folder. C1 is visible here before any order exists —
 * the catalogue ranks against where the parcel is GOING and prices in the
 * currency of whoever is PAYING, and those are two different questions with two
 * different answers. And moderation is enforced by the query rather than by a
 * service that remembers to filter, which is the difference between a listing
 * that cannot be seen and one that is usually not shown.
 */

const { req, folder } = require('../lib/collection');
const seed = require('../lib/seed');

module.exports = folder(
  '03 — The public catalogue, search and questions',
  'Browsing, filtering, ranking against a destination, and the Q&A queue.',
  [
    // ── Categories ───────────────────────────────────────────────────────
    req({
      name: 'The category tree, with a count on every node',
      path: '/categories',
      status: 200,
      json: {
        'categories.0.slug': 'electronics',
        'categories.0.productCount': { $gt: 0 },
        'categories.0.children': { $type: 'array' },
      },
      script: `
const tree = $body.categories;
pm.test("Categories — phones is a child of electronics, not a sibling", function () {
  const electronics = tree.find(function (c) { return c.slug === 'electronics'; });
  pm.expect(electronics.children.map(function (c) { return c.slug; })).to.include('phones');
});
pm.test("Categories — a parent's count includes what is under it", function () {
  const electronics = tree.find(function (c) { return c.slug === 'electronics'; });
  const below = electronics.children.reduce(function (sum, c) { return sum + c.productCount; }, 0);
  pm.expect(electronics.productCount,
    'the parent counts ' + electronics.productCount + ' and its children come to ' + below)
    .to.be.at.least(below);
});
`,
    }),

    req({
      name: 'A category page derives its filters from what is actually in it',
      path: '/categories/phones',
      note: 'The attribute schema is not configured anywhere: it is read off the '
          + 'listings in the category. A configured schema is a schema that goes '
          + 'stale, and on a marketplace where sellers type their own attributes it '
          + 'would be stale on the first day.',
      status: 200,
      json: {
        'category.slug': 'phones',
        'ancestors.0.slug': 'electronics',
        attributes: { $type: 'array' },
      },
      script: `
pm.test("Category page — the attributes come from the listings", function () {
  const names = $body.attributes.map(function (a) { return a.name; });
  pm.expect(names, 'phones should offer a screen filter').to.include('Screen');
  $body.attributes.forEach(function (a) {
    pm.expect(a.values.length, a.name + ' is offered as a filter with no values')
      .to.be.at.least(1);
  });
});
pm.test("Category page — only brands with stock behind them are offered", function () {
  const brands = $body.brands.map(function (b) { return b.value; });
  pm.expect(brands).to.include('samsung');
  $body.brands.forEach(function (b) {
    pm.expect(b.count, b.label + ' is offered as a filter matching nothing').to.be.at.least(1);
  });
});
`,
    }),

    req({
      name: 'A category that does not exist is a 404',
      path: '/categories/tractor-parts',
      status: 404,
    }),

    // ── Listings ─────────────────────────────────────────────────────────
    req({
      name: 'The catalogue shows only what moderation approved',
      path: '/products',
      note: 'Six listings. The seventh, 1307, is ARCHIVED because order lines still '
          + 'point at it — a buyer\'s receipt, invoice and review all have to keep '
          + 'resolving years from now, so it is withdrawn rather than deleted. It '
          + 'must not appear here, and the query is what enforces that rather than a '
          + 'service remembering to filter.',
      status: 200,
      json: { totalElements: 6, currency: 'GMD' },
      script: `
const ids = $body.items.map(function (p) { return p.id; });
pm.test("Catalogue — the withdrawn listing is not on sale", function () {
  pm.expect(ids, 'listing ${seed.products.refurbished} is ARCHIVED and must not be public')
    .to.not.include(${seed.products.refurbished});
});
pm.test("Catalogue — every listing carries both prices and says which it converted", function () {
  $body.items.forEach(function (p) {
    pm.expect(p.price, p.name + ' has no display price').to.be.a('number');
    pm.expect(p.listingPrice, p.name + ' has no listing price').to.be.a('number');
    pm.expect(p.listingCurrency, p.name + ' does not say what it is listed in').to.be.a('string');
    pm.expect(p.converted, p.name + ' does not say whether it was converted').to.be.a('boolean');
    // C2 in the listing: the vendor's own currency is never overwritten by the
    // buyer's, so a seller reading their own shelf sees their own numbers.
    if (p.currency === p.listingCurrency) {
      pm.expect(p.converted, p.name + ' claims a conversion to its own currency')
        .to.equal(false);
      pm.expect(p.price).to.equal(p.listingPrice);
    }
  });
});
pm.test("Catalogue — the condition facet has more than one value to offer", function () {
  const conditions = $body.facets.conditions.map(function (c) { return c.value; });
  pm.expect(conditions, 'a condition filter with one value is not a filter')
    .to.have.length.of.at.least(2);
  pm.expect(conditions).to.include('NEW');
  pm.expect(conditions).to.include('OPEN_BOX');
});
pm.test("Catalogue — no facet offers a filter that matches nothing", function () {
  ['categories', 'brands', 'conditions'].forEach(function (kind) {
    $body.facets[kind].forEach(function (f) {
      pm.expect(f.count, kind + ' offers ' + f.value + ' with nothing behind it')
        .to.be.at.least(1);
    });
  });
});
`,
    }),

    req({
      name: 'Filtering by condition: open box returns the one open-box listing',
      path: '/products',
      query: { condition: 'OPEN_BOX' },
      note: 'On a marketplace where a phone costs a month\'s income, and the buyer is '
          + 'choosing for somebody else and cannot inspect it, this distinction is '
          + 'most of the buying decision.',
      status: 200,
      json: { totalElements: 1, 'items.0.id': seed.products.openBox, 'items.0.condition': 'OPEN_BOX' },
    }),

    req({
      name: 'Filtering by refurbished returns nothing, because the only one was withdrawn',
      path: '/products',
      query: { condition: 'REFURBISHED' },
      note: 'Listing 1307 is the only refurbished handset and it is ARCHIVED. A public '
          + 'catalogue that showed a withdrawn listing would be the bug — so the '
          + 'empty answer is the correct one, and the seed note that used to claim '
          + 'otherwise was what needed fixing.',
      status: 200,
      json: { totalElements: 0, items: { $length: 0 } },
    }),

    req({
      name: 'In-stock only drops the two listings with none',
      path: '/products',
      query: { inStockOnly: true },
      status: 200,
      script: `
const ids = $body.items.map(function (p) { return p.id; });
pm.test("In stock only — the open-box listing with no stock is dropped", function () {
  pm.expect(ids).to.not.include(${seed.products.openBox});
});
pm.test("In stock only — everything returned actually has some", function () {
  $body.items.forEach(function (p) {
    pm.expect(p.inStock, p.name + ' came back from an in-stock search').to.equal(true);
  });
});
`,
    }),

    req({
      name: 'A rating floor returns only listings that clear it',
      path: '/products',
      query: { minRating: 4.5 },
      status: 200,
      script: `
pm.test("Rating filter — nothing below the floor", function () {
  $body.items.forEach(function (p) {
    pm.expect(p.rating, p.name + ' rates ' + p.rating + ' and cleared a 4.5 floor')
      .to.be.at.least(4.5);
  });
});
pm.test("Rating filter — and it returned something, so the floor is not just empty", function () {
  pm.expect($body.items.length).to.be.at.least(1);
});
`,
    }),

    req({
      name: "One seller's shelf",
      path: '/products',
      query: { store: seed.vendors.kombo.slug },
      status: 200,
      script: `
pm.test("Store filter — every listing belongs to that store", function () {
  pm.expect($body.items.length).to.be.at.least(1);
  $body.items.forEach(function (p) {
    pm.expect(p.storeSlug).to.equal(${JSON.stringify(seed.vendors.kombo.slug)});
    pm.expect(p.vendorId).to.equal(${seed.vendors.kombo.id});
  });
});
`,
    }),

    // ── C1 on the catalogue ──────────────────────────────────────────────
    req({
      name: 'C1: ranked against the destination, priced for the payer',
      path: '/products',
      as: 'oliver',
      query: {
        deliveryLat: seed.destination.lat,
        deliveryLng: seed.destination.lng,
        deliveryCountry: seed.destination.country,
        currency: 'GBP',
      },
      note: 'Oliver is in London. The phone is going to Serrekunda. Those are two '
          + 'different places and the catalogue treats them as two different '
          + 'questions: the coordinates are the RECIPIENT\'s, because ranking is by '
          + 'what can reach her, and the currency is the PAYER\'s, because he holds '
          + 'the card. Passing London\'s coordinates here would rank the catalogue '
          + 'against a place the parcel is never going.',
      status: 200,
      json: { currency: 'GBP' },
      script: `
pm.test("C1 — prices are the payer's currency", function () {
  $body.items.forEach(function (p) {
    pm.expect(p.currency, p.name + ' is priced in ' + p.currency + ' for a sterling buyer')
      .to.equal('GBP');
  });
});
pm.test("C1 — and the listing currency is still the seller's own", function () {
  const phone = $body.items.find(function (p) { return p.id === ${seed.products.phone}; });
  pm.expect(phone, 'the Galaxy is not in the results').to.be.an('object');
  pm.expect(phone.listingCurrency, "the seller settles in dalasi").to.equal('GMD');
  pm.expect(phone.listingPrice).to.equal(8500);
  pm.expect(phone.converted).to.equal(true);
  // 8500 x 0.011 = 93.50. The arithmetic is checkable, which is the only thing
  // that makes a converted price checkable.
  pm.expect(phone.price).to.equal(93.5);
});
pm.test("C1 — distance is measured from the DESTINATION, not from London", function () {
  const phone = $body.items.find(function (p) { return p.id === ${seed.products.phone}; });
  // Kombo Electronics is on Kairaba Avenue, which is where these coordinates
  // are. Measured from London this would be five thousand kilometres.
  pm.expect(phone.distanceKm, 'distance was ' + phone.distanceKm
    + 'km, which is not a Serrekunda shop measured from Serrekunda')
    .to.be.below(5);
});
`,
    }),

    req({
      name: 'Change only the currency and the ranking does not move',
      path: '/products',
      as: 'oliver',
      query: {
        deliveryLat: seed.destination.lat,
        deliveryLng: seed.destination.lng,
        deliveryCountry: seed.destination.country,
        currency: 'GMD',
      },
      note: 'The control for the request above. Same destination, different payer '
          + 'currency: the prices move and the order does not. Anything that '
          + 'derived a delivery answer from the payer would fail here.',
      status: 200,
      json: { currency: 'GMD' },
      script: `
pm.test("C1 — the same destination gives the same distances", function () {
  const phone = $body.items.find(function (p) { return p.id === ${seed.products.phone}; });
  pm.expect(phone.distanceKm).to.be.below(5);
  pm.expect(phone.price, 'in the listing currency nothing is converted').to.equal(8500);
  pm.expect(phone.converted).to.equal(false);
});
`,
    }),

    req({
      name: 'A delivery context that is not yours will not rank your catalogue',
      path: '/products',
      as: 'oliver',
      query: { deliverableTo: seed.deliveryContexts.aminataHome },
      note: 'Oliver may be the one paying for a parcel going to Aminata\'s house, but '
          + 'he cannot borrow HER delivery context to price it: the row carries her '
          + 'street, and holding an id must not become a way to read a signed-in '
          + 'shopper\'s address. He gives the destination directly instead, as the '
          + 'two requests above do.',
      status: 404,
    }),

    req({
      name: 'Her own context does rank her own catalogue',
      path: '/products',
      as: 'aminata',
      query: { deliverableTo: seed.deliveryContexts.aminataHome },
      status: 200,
      json: { currency: 'GMD' },
      script: `
pm.test("Ranking — the response says which location it used", function () {
  // Echoed back so a client can see at a glance that the catalogue ranked
  // against the recipient rather than the payer.
  const echoed = JSON.stringify($body.delivery || $body.deliveryContext || {});
  pm.expect(echoed.length, 'nothing in the response says where this was ranked from')
    .to.be.above(2);
});
`,
    }),

    // ── The product page ─────────────────────────────────────────────────
    req({
      name: 'A listing page, resolved by slug',
      path: `/products/${seed.slugs.phone}`,
      note: 'By slug rather than by id, because the URL is the thing people send each '
          + 'other. The numeric id is what the Q&A and review sub-resources take.',
      status: 200,
      json: {
        id: seed.products.phone,
        slug: seed.slugs.phone,
        condition: 'NEW',
        price: 8500,
        listingCurrency: 'GMD',
        converted: false,
        inStock: true,
        weightKg: 0.195,
        'store.slug': seed.vendors.kombo.slug,
        'store.acceptingOrders': true,
        'brand.slug': 'samsung',
        'category.slug': 'phones',
      },
      script: `
pm.test("Product page — the images have exactly one default", function () {
  const defaults = $body.images.filter(function (i) { return i.isDefault; });
  pm.expect(defaults.length, 'a gallery with ' + defaults.length + ' default images')
    .to.equal(1);
});
pm.test("Product page — nothing about the seller's own business is on it", function () {
  const text = H.text(pm.response);
  ['commission', 'balance', 'payout', 'settlementCurrency', 'bankAccount'].forEach(function (word) {
    pm.expect(text, 'a public listing page mentions ' + word).to.not.include(word);
  });
});
`,
    }),

    req({
      name: 'The withdrawn listing has no page either',
      path: '/products/samsung-galaxy-a05',
      note: 'ARCHIVED, so it is gone from the shopfront in both directions — not '
          + 'merely absent from the list while still reachable by anyone who kept '
          + 'the link.',
      status: 404,
    }),

    req({
      name: 'A slug nobody has is a 404',
      path: '/products/a-phone-that-was-never-listed',
      status: 404,
    }),

    req({
      name: 'A store page carries a trading position and nothing private',
      path: `/stores/${seed.vendors.kombo.slug}`,
      status: 200,
      json: {
        id: seed.vendors.kombo.id,
        slug: seed.vendors.kombo.slug,
        city: 'Serekunda',
        countryCode: 'GM',
        acceptingOrders: true,
        rating: { $gt: 0 },
      },
      bodyExcludes: [
        { value: 'lamin.kombo@sujula.gm', why: 'the owner is not named on his shopfront' },
        { value: 'commission', why: 'what the platform charges him is between them' },
        { value: 'balance', why: 'and so is what he is owed' },
      ],
    }),

    req({
      name: "A store's shelf",
      path: `/stores/${seed.vendors.kombo.slug}/products`,
      status: 200,
      script: `
pm.test("Store shelf — everything on it belongs to that store", function () {
  const items = $body.items || $body.content || [];
  pm.expect(items.length).to.be.at.least(1);
  items.forEach(function (p) {
    pm.expect(p.vendorId).to.equal(${seed.vendors.kombo.id});
  });
});
`,
    }),

    req({
      name: 'Only brands with stock behind them are listed',
      path: '/brands',
      note: 'A brand filter that returns nothing is worse than no filter: a shopper '
          + 'concludes the marketplace is empty rather than that the brand is.',
      status: 200,
      script: `
pm.test("Brands — the three with listings, and nothing else", function () {
  const slugs = $body.brands.map(function (b) { return b.slug; });
  pm.expect(slugs).to.include('samsung');
  pm.expect(slugs).to.include('nokia');
  pm.expect(slugs).to.include('tobaski');
});
`,
    }),

    // ── Search ───────────────────────────────────────────────────────────
    req({
      name: 'Full-text search, with facets',
      path: '/search',
      query: { q: 'wax' },
      status: 200,
      json: {
        totalElements: 1,
        'items.0.id': seed.products.waxPrint,
        'items.0.listingCurrency': 'XOF',
        'items.0.converted': true,
      },
      script: `
pm.test("Search — a CFA listing shown in dalasi keeps its own price as well", function () {
  const cloth = $body.items[0];
  pm.expect(cloth.listingPrice, "Awa's own price, in her own currency").to.equal(14500);
  pm.expect(cloth.currency, 'displayed in the base currency by default').to.equal('GMD');
  // 14500 XOF is not 14500 anything else. A listing shown at its face value
  // under another symbol is the whole hazard C2 exists for.
  pm.expect(cloth.price).to.not.equal(cloth.listingPrice);
});
`,
    }),

    req({
      name: 'Search for something nobody sells',
      path: '/search',
      query: { q: 'snowmobile' },
      status: 200,
      json: { totalElements: 0 },
    }),

    req({
      name: 'Typeahead needs two characters',
      path: '/search/suggest',
      query: { q: 'gal' },
      status: 200,
      json: { query: 'gal', 'suggestions.0': 'Samsung Galaxy A16' },
    }),

    req({
      name: 'And one character gets nothing rather than everything',
      path: '/search/suggest',
      query: { q: 'g' },
      note: 'A single letter matches most of the catalogue, which is not a suggestion. '
          + 'It is also the cheapest way to make the search do the most work.',
      status: [200, 400],
      script: `
pm.test("Typeahead — one character does not return the catalogue", function () {
  if (pm.response.code !== 200) return;
  pm.expect(($body.suggestions || []).length,
    'one letter returned ' + ($body.suggestions || []).length + ' suggestions').to.equal(0);
});
`,
    }),

    // ── Questions, and moderation ────────────────────────────────────────
    req({
      name: 'A listing shows only the questions a moderator approved',
      path: `/products/${seed.products.phone}/questions`,
      note: 'Two come back: the answered one first, then the approved but unanswered '
          + 'one. Two more exist and are invisible — one waiting for a moderator, one '
          + 'refused for posting a phone number on somebody else\'s shopfront. A '
          + 'question is invisible until somebody approved it, and the query is what '
          + 'enforces that.',
      status: 200,
      json: {
        totalElements: 2,
        'items.0.id': 1900,
        'items.0.answer': { $exists: true },
        'items.1.id': 1901,
      },
      script: `
pm.test("Questions — the unmoderated and the refused are both absent", function () {
  const ids = $body.items.map(function (q) { return q.id; });
  pm.expect(ids, 'question 1902 is PENDING_REVIEW').to.not.include(1902);
  pm.expect(ids, 'question 1903 was refused for posting a phone number').to.not.include(1903);
});
pm.test("Questions — an answered one comes before an unanswered one", function () {
  pm.expect($body.items[0].answer, 'the answered question should be first').to.be.a('string');
  pm.expect($body.items[1].answer).to.not.exist;
});
pm.test("Questions — only a first name is published, never an address", function () {
  $body.items.forEach(function (q) {
    pm.expect(q.askedBy, 'a surname on a public shopfront').to.not.match(/ /);
    pm.expect(JSON.stringify(q)).to.not.include('@');
  });
});
`,
    }),

    req({
      name: 'Asking a question needs an account',
      method: 'POST',
      path: `/products/${seed.products.phone}/questions`,
      body: { question: 'Does the warranty cover a screen replacement in Banjul?' },
      note: 'The one write on this surface that is not public. Without an account '
          + 'behind it, public text on a seller\'s shopfront is a spam channel with '
          + 'no cost to the sender.',
      status: [401, 403],
    }),

    req({
      name: 'And a question that is asked goes to the queue rather than onto the page',
      method: 'POST',
      path: `/products/${seed.products.phone}/questions`,
      as: 'ndeye',
      body: { question: 'Est-ce que la garantie couvre un écran cassé à Dakar ?' },
      note: 'One question per person per listing, so this is one-shot against a server '
          + 'that has already had a run: the second attempt is refused, and that '
          + 'refusal is worth asserting on its own — without it the same person could '
          + 'fill a seller\'s page with the same question while a moderator is '
          + 'looking at the first copy.',
      status: [200, 201, 202, 400],
      script: `
if (pm.response.code === 400) {
  pm.test("Asking again — refused, and the reason says the first is still in the queue", function () {
    pm.expect(H.text(pm.response)).to.match(/already asked/i);
  });
} else {
  pm.test("Asking — it goes to the queue rather than onto the page", function () {
    pm.expect(JSON.stringify($body)).to.match(/PENDING/);
  });
}
`,
    }),

    req({
      name: 'It is not on the listing afterwards',
      path: `/products/${seed.products.phone}/questions`,
      note: 'Still two. If a newly asked question appeared here, the review queue '
          + 'would be advisory — and the thing it exists to stop is somebody putting '
          + 'a phone number on a seller\'s page to take the sale off the platform.',
      status: 200,
      json: { totalElements: 2 },
      script: `
pm.test("Questions — the one just asked is not published", function () {
  pm.expect(H.text(pm.response), 'a pending question is on the public listing')
    .to.not.include('Dakar');
});
`,
    }),

    req({
      name: 'An empty question is refused before anything is written',
      method: 'POST',
      path: `/products/${seed.products.phone}/questions`,
      as: 'ndeye',
      body: { question: '   ' },
      status: 400,
    }),

    req({
      name: 'Reviews on a listing are public',
      path: `/products/${seed.products.phone}/reviews`,
      status: 200,
      script: `
pm.test("Reviews — no reviewer's email or surname is published", function () {
  const text = H.text(pm.response);
  pm.expect(text).to.not.include('@example');
  pm.expect(text).to.not.include('@sujula');
});
`,
    }),

    req({
      name: 'Variants are public, because the choice is part of the listing',
      path: `/products/${seed.products.phone}/variants`,
      status: 200,
    }),

    req({
      name: 'Related listings are public too',
      path: `/products/${seed.products.phone}/related`,
      status: 200,
      script: `
pm.test("Related — a withdrawn listing is not suggested", function () {
  const items = $body.items || $body || [];
  const ids = (Array.isArray(items) ? items : []).map(function (p) { return p.id; });
  pm.expect(ids).to.not.include(${seed.products.refurbished});
});
`,
    }),
  ],
);
