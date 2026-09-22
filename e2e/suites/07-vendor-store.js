/**
 * A seller's own shop: opening it, proving who they are, saying who else may
 * work in it, and naming where the money goes.
 *
 * Every endpoint here is authenticated at the gate and nothing more — the
 * vendor is resolved from the session and goes into every query, so a path
 * variable cannot reach another seller's store. That is asserted rather than
 * assumed, in both directions: Awa reading Lamin's store is a 404, and Lamin
 * reading his own is a 200.
 *
 * The three rows worth reading are 1101, 1102 and 1103. Lamin's shop is
 * street-numbered and EXACT. Awa's is a CENTROID with a separate depot, because
 * cloth is cut at the shop and collected two streets away. Mariama's stall is in
 * Dakar, settles in GMD because she banks in Banjul, and has no coordinates at
 * all — which is most of the region, not a failure case.
 */

const { req, folder } = require('../lib/collection');
const seed = require('../lib/seed');

module.exports = folder(
  '07 — A seller\'s store, verification, staff and payout details',
  'The onboarding ladder, and the one endpoint that asks who you are again.',
  [
    req({
      name: 'Lamin\'s store, as its owner sees it',
      path: `/vendor/stores/${seed.vendors.kombo.id}`,
      as: 'lamin',
      status: 200,
      json: {
        id: seed.vendors.kombo.id,
        storeSlug: seed.vendors.kombo.slug,
        status: 'APPROVED',
        kycStatus: 'VERIFIED',
        canTrade: true,
        settlementCurrency: 'GMD',
        'address.countryCode': 'GM',
        'address.confidence': 'EXACT',
        'address.dispatchable': true,
        'address.needsPinConfirmation': false,
      },
      script: `
pm.test("Store — the opening hours are a week, not a string", function () {
  pm.expect($body.operatingHours, 'no operating hours').to.be.an('array');
  const days = $body.operatingHours.map(function (h) { return h.day; });
  ['MONDAY', 'SATURDAY', 'SUNDAY'].forEach(function (day) {
    pm.expect(days, day + ' is missing, so a client cannot draw the week').to.include(day);
  });
  $body.operatingHours.forEach(function (h) {
    pm.expect(h.closed, h.day + ' does not say whether it is closed').to.be.a('boolean');
    if (!h.closed) {
      pm.expect(h.opensAt, h.day + ' is open with no opening time').to.be.a('string');
    }
  });
});
pm.test("Store — the owner sees his own commission rate", function () {
  // His own terms. What is NOT here is any other seller's.
  pm.expect(H.text(pm.response)).to.not.include('Teranga');
});
`,
    }),

    req({
      name: "Awa's store is a 404 for Lamin",
      path: `/vendor/stores/${seed.vendors.teranga.id}`,
      as: 'lamin',
      note: 'Ownership is in the query, not a comparison after it — which is the '
          + 'version that ships with the comparison missing.',
      status: 404,
    }),

    req({
      name: 'And his is a 404 for her',
      path: `/vendor/stores/${seed.vendors.kombo.id}`,
      as: 'awa',
      status: 404,
    }),

    req({
      name: "Awa's own store: a centroid, and a depot two streets away",
      path: `/vendor/stores/${seed.vendors.teranga.id}`,
      as: 'awa',
      note: 'Cloth is cut at the shop and collected from the depot, so pricing a '
          + 'collection from the shopfront would be wrong on every order she takes. '
          + 'Two addresses because they answer two questions.',
      status: 200,
      json: {
        id: seed.vendors.teranga.id,
        settlementCurrency: 'XOF',
        'address.countryCode': 'SN',
        'address.confidence': 'CENTROID',
      },
      script: `
pm.test("Her store — the collection point is recorded separately from the shop", function () {
  const pickup = $body.pickupAddress || $body.pickup || {};
  pm.expect(JSON.stringify(pickup).length,
    'no separate collection address, so a driver would be sent to the shopfront')
    .to.be.above(2);
});
`,
    }),

    req({
      name: "Mariama's stall: in Dakar, settling in dalasi, with no pin at all",
      path: `/vendor/stores/${seed.vendors.mariama.id}`,
      as: 'mariama',
      note: 'The row that proves the address and the settlement currency are two '
          + 'independent answers. Her stall stands in Senegal and she banks in '
          + 'Banjul — a system that read the currency off the address would have '
          + 're-denominated her whole business without telling her.\n\n'
          + 'And it has no coordinates, which is not a failure case: a market stall '
          + 'with no street number is most of this region, so the store opens with '
          + 'NONE confidence and collections price from a scope fallback until she '
          + 'drops a pin. Refusing would shut out the sellers this marketplace is '
          + 'mainly for.',
      status: 200,
      json: {
        id: seed.vendors.mariama.id,
        settlementCurrency: 'GMD',
        'address.countryCode': 'SN',
        'address.confidence': 'NONE',
        status: 'PENDING_KYC',
        canTrade: false,
      },
      script: `
pm.test("Her stall — no pin, and still a store", function () {
  pm.expect($body.address.latitude, 'a stall nobody has geocoded').to.not.exist;
  pm.expect($body.address.dispatchable,
    'a store with no pin cannot be dispatched from by distance').to.equal(false);
});
`,
    }),

    req({
      name: 'A shopper who wants to sell registers first',
      method: 'POST',
      path: '/auth/register',
      preScript: `
pm.collectionVariables.set('sellerEmail', 'seller.' + Date.now() + '@example.sn');
`,
      body: {
        email: '{{sellerEmail}}',
        password: 'Sujula123!',
        firstName: 'Ndeye',
        lastName: 'Opening',
        preferredCurrency: 'XOF',
      },
      note: 'A fresh account rather than a seeded persona, and not for tidiness: '
          + 'opening a store RAISES the account to VENDOR. Doing that to Ndeye left '
          + 'her a vendor on the next run and broke the identity folder, which '
          + 'asserts she is a customer — and the role matrix, which calls her one. '
          + 'A collection that quietly re-roles a seeded person has stopped testing '
          + 'the world the seed describes.',
      status: [200, 201],
      json: { 'user.role': 'CUSTOMER' },
      capture: { tokenSeller: 'accessToken' },
    }),

    req({
      name: 'Opening a store with a Senegalese address does not infer CFA',
      method: 'POST',
      path: '/vendor/stores',
      as: null,
      headers: { Authorization: 'Bearer {{tokenSeller}}', 'Idempotency-Key': 'e2e-open-store' },
      body: {
        storeName: 'Sarr Tailoring Dakar',
        description: 'Made to measure, two days.',
        storeEmail: 'sarr.tailoring@example.sn',
        storePhone: '+2217700099',
        addressStreet: '18 Rue Carnot',
        addressCity: 'Dakar',
        addressCountryCode: 'SN',
      },
      note: 'No currency in the body, and the answer is the platform default rather '
          + 'than XOF read off the country. Inferring it is how a seller ends up '
          + 'listing in a currency they cannot be paid in.',
      status: [200, 201, 400, 409],
      script: `
if (pm.response.code < 300) {
  pm.test("New store — PENDING_KYC, always, and cannot trade yet", function () {
    pm.expect($body.status).to.equal('PENDING_KYC');
    pm.expect($body.canTrade, 'a store nobody has verified must not be able to sell')
      .to.equal(false);
  });
  pm.test("New store — the currency is the platform default, not the country's", function () {
    pm.expect($body.settlementCurrency,
      'XOF was inferred from a Senegalese address').to.equal('GMD');
  });
  pm.collectionVariables.set('newStoreId', $body.id);
} else {
  pm.test("New store — refused with a reason rather than a bare 400", function () {
    pm.expect(H.text(pm.response).length).to.be.above(2);
  });
}
`,
    }),

    req({
      name: 'A store with no name is refused before anything is written',
      method: 'POST',
      path: '/vendor/stores',
      as: null,
      headers: { Authorization: 'Bearer {{tokenSeller}}', 'Idempotency-Key': 'e2e-open-store-no-name' },
      body: { addressCity: 'Serekunda', addressCountryCode: 'GM' },
      status: [400, 409, 422],
    }),

    req({
      name: 'Editing policies and hours',
      method: 'PATCH',
      path: `/vendor/stores/${seed.vendors.kombo.id}`,
      as: 'lamin',
      body: {
        returnPolicy: 'Fourteen days on unopened handsets, minus the delivery leg.',
        handlingDays: 2,
      },
      status: 200,
      json: { handlingDays: 2, returnPolicy: { $matches: 'Fourteen days' } },
    }),

    req({
      name: 'Another seller cannot edit his shopfront',
      method: 'PATCH',
      path: `/vendor/stores/${seed.vendors.kombo.id}`,
      as: 'awa',
      body: { returnPolicy: 'No returns at all.' },
      status: 404,
    }),

    req({
      name: 'Vacation mode says the shop is shut rather than hiding it',
      method: 'PATCH',
      path: `/vendor/stores/${seed.vendors.kombo.id}`,
      as: 'lamin',
      body: { vacationMode: true, vacationMessage: 'Back on the 25th, after Tobaski.' },
      note: 'A buyer who bookmarked a listing should be told why they cannot order '
          + 'rather than shown a 404 on a shop that exists.',
      status: 200,
      json: { vacationMode: true },
    }),

    req({
      name: 'And turning it back on',
      method: 'PATCH',
      path: `/vendor/stores/${seed.vendors.kombo.id}`,
      as: 'lamin',
      body: { vacationMode: false },
      status: 200,
      json: { vacationMode: false },
    }),

    // ── Verification ─────────────────────────────────────────────────────
    req({
      name: "Awa's KYC: ACTION_REQUIRED, with the reason attached",
      path: `/vendor/stores/${seed.vendors.teranga.id}/kyc`,
      as: 'awa',
      note: 'Her first national ID was refused as too dark to read; the second was '
          + 'accepted. Both survive, because re-uploading supersedes rather than '
          + 'replaces and the sequence is the record of why her onboarding took three '
          + 'weeks. Her proof of address is still refused — she sent the depot lease '
          + 'instead of the shop\'s — so this reads ACTION_REQUIRED with that reason '
          + 'attached rather than a bare "incomplete" that would send her back to '
          + 'upload the same document again.',
      status: 200,
      json: { status: 'ACTION_REQUIRED' },
      script: `
pm.test("KYC — the refusal says what is wrong with the document she sent", function () {
  pm.expect($body.actionsRequired, 'no actions listed on an ACTION_REQUIRED state')
    .to.be.an('array').and.to.have.length.of.at.least(1);
  pm.expect(JSON.stringify($body.actionsRequired)).to.match(/depot|Rue de France/i);
});
pm.test("KYC — the accepted replacement is listed, and the outstanding refusal with it", function () {
  const ids = $body.documents.map(function (d) { return d.id; });
  pm.expect(ids, 'her accepted national ID is missing')
    .to.include(${seed.kycDocuments.awaIdAccepted});
  pm.expect(ids, 'the refusal she still has to act on is missing')
    .to.include(${seed.kycDocuments.awaProofRefused});
  const outstanding = $body.documents.find(function (d) {
    return d.id === ${seed.kycDocuments.awaProofRefused};
  });
  pm.expect(outstanding.status).to.equal('REJECTED');
  pm.expect(outstanding.rejectionReason, 'refused with no reason kept').to.be.a('string');
});
pm.test("KYC — a superseded refusal is not shown again", function () {
  // Row ${seed.kycDocuments.awaIdRefused} is her first national ID, refused as
  // too dark to read, and ${seed.kycDocuments.awaIdAccepted} replaced it. Both
  // survive in the table — the sequence is the record of why her onboarding
  // took three weeks — but only the live one is on her screen: showing a
  // refusal she has already answered is asking her to answer it twice.
  const ids = $body.documents.map(function (d) { return d.id; });
  pm.expect(ids).to.not.include(${seed.kycDocuments.awaIdRefused});
});
pm.test("KYC — the storage key is never handed back", function () {
  // The bytes never pass through this API; only the key is stored. A URL to
  // somebody's passport in a JSON response is a URL in a browser cache.
  const text = H.text(pm.response);
  pm.expect(text, 'a storage key or URL for an identity document')
    .to.not.match(/storageKey|"url"|https?:\\/\\/[^"]*kyc/);
});
`,
    }),

    req({
      name: 'What is required depends on what the store claims to be',
      path: `/vendor/stores/${seed.vendors.mariama.id}/kyc`,
      as: 'mariama',
      note: '1101 gave a registration number and a tax number, so it is asked for '
          + 'both certificates. Mariama gave neither and is asked for an identity '
          + 'document and a proof of address, which is all a market trader has. '
          + 'Demanding a company\'s paperwork from her is how a marketplace turns '
          + 'away the sellers it exists for.',
      status: 200,
      script: `
pm.test("KYC — a market trader is asked for a trader's documents", function () {
  const asked = JSON.stringify($body.missing || []).toUpperCase();
  pm.expect(asked, 'she is asked for nothing, so nothing gates her').to.not.equal('[]');
  pm.expect(asked, 'a stall with no registration number is asked for its certificate')
    .to.not.include('BUSINESS_REGISTRATION');
  pm.expect(asked).to.not.include('TAX_CERTIFICATE');
});
`,
    }),

    req({
      name: 'Submitting documents takes storage keys, never bytes',
      method: 'POST',
      path: `/vendor/stores/${seed.vendors.mariama.id}/kyc`,
      as: 'mariama',
      headers: { 'Idempotency-Key': 'e2e-kyc-submit' },
      body: {
        documents: [
          {
            type: 'NATIONAL_ID',
            storageKey: 'kyc/1103/national-id-front.jpg',
            originalFilename: 'cni.jpg',
            sizeBytes: 148300,
            contentType: 'image/jpeg',
          },
        ],
      },
      note: 'The upload goes straight to object storage and only the key reaches this '
          + 'API — so an identity document never passes through an application log, a '
          + 'request trace or a request-body dump.',
      status: [200, 201, 202, 400, 409],
    }),

    req({
      name: "Another seller's KYC is a 404",
      path: `/vendor/stores/${seed.vendors.teranga.id}/kyc`,
      as: 'lamin',
      status: 404,
    }),

    // ── Staff ────────────────────────────────────────────────────────────
    req({
      name: 'The staff list puts the owner at the top, from the vendor record',
      path: `/vendor/stores/${seed.vendors.kombo.id}/staff`,
      as: 'lamin',
      note: 'Lamin is not a store_staff row. The owner is not staff, and the endpoint '
          + 'puts them at the top from the vendor record — otherwise a shop\'s own '
          + 'owner is missing from its list of who can see it.',
      status: 200,
      script: `
pm.test("Staff — the owner is first and is marked as the owner", function () {
  pm.expect($body.members[0].owner, 'the first entry is not the owner').to.equal(true);
  pm.expect($body.members[0].userId).to.equal(${seed.users.lamin.id});
});
pm.test("Staff — an invitation to somebody with no account is keyed on their email", function () {
  // The usual invitee is a relative or an assistant who has never used the
  // platform, so the invitation waits for them to register.
  const waiting = $body.members.filter(function (m) { return m.status !== 'ACTIVE'; });
  waiting.forEach(function (m) {
    pm.expect(m.email, 'an invitation with no address to send it to').to.be.a('string');
  });
});
pm.test("Staff — no invitation token is ever listed", function () {
  // Stored as a SHA-256 digest like every other bearer credential. Listing one
  // would mean anybody who can read the staff list can accept an invitation.
  pm.expect(H.text(pm.response)).to.not.match(/inviteToken|"token"/);
});
pm.test("Staff — the removed member's permissions are gone", function () {
  // Row 1372 was removed. Its digest is null and its grants are empty —
  // otherwise a link mailed last week still opens the shop.
  const removed = $body.members.find(function (m) { return m.id === ${seed.staffInvites.removed}; });
  if (removed) {
    pm.expect(removed.permissions || [], 'a removed member still holds grants').to.eql([]);
  }
});
pm.test("Staff — every grant is a store-scoped one", function () {
  // The permissions come from a store-only enum, so there is no value a seller
  // can send that reaches another vendor's data or the platform's. The
  // dangerous grants are unrepresentable rather than rejected, which is the
  // difference between a check that can be forgotten and one that cannot.
  const allowed = ['ORDERS_VIEW', 'ORDERS_FULFIL', 'CATALOGUE_MANAGE',
                   'FINANCE_VIEW', 'STORE_PROFILE_MANAGE', 'STAFF_MANAGE'];
  $body.members.forEach(function (m) {
    (m.permissions || []).forEach(function (p) {
      pm.expect(allowed, m.displayName + ' holds ' + p + ', which is not a store grant')
        .to.include(p);
    });
  });
});
`,
    }),

    req({
      name: 'Inviting somebody by email, before they have an account',
      method: 'POST',
      path: `/vendor/stores/${seed.vendors.kombo.id}/staff`,
      as: 'lamin',
      headers: { 'Idempotency-Key': 'e2e-invite-staff' },
      body: {
        email: 'assistant.kombo@example.gm',
        displayName: 'Kaddy (Saturdays)',
        permissions: ['ORDERS_VIEW', 'ORDERS_FULFIL'],
      },
      status: [200, 201, 400, 409],
      script: `
if (pm.response.code < 300) {
  pm.test("Invitation — exactly the grants asked for, and no more", function () {
    pm.expect($body.permissions.sort()).to.eql(['ORDERS_FULFIL', 'ORDERS_VIEW']);
    pm.expect($body.owner, 'an invitee was marked as the owner').to.not.equal(true);
  });
  pm.test("Invitation — the token is not in the answer", function () {
    pm.expect(H.text(pm.response)).to.not.match(/inviteToken|"token"/);
  });
}
`,
    }),

    req({
      name: 'A grant that is not a store grant cannot be asked for',
      method: 'POST',
      path: `/vendor/stores/${seed.vendors.kombo.id}/staff`,
      as: 'lamin',
      headers: { 'Idempotency-Key': 'e2e-invite-bad-grant' },
      body: {
        email: 'ambitious.kombo@example.gm',
        displayName: 'Ambitious',
        permissions: ['ORDERS_VIEW', 'ADMIN_ALL'],
      },
      note: 'Refused by the enum rather than by a check, which is what makes it '
          + 'unforgettable: there is no value here that reaches another vendor\'s '
          + 'data or the platform\'s.',
      status: [400, 422],
    }),

    req({
      name: "Another seller cannot add staff to his shop",
      method: 'POST',
      path: `/vendor/stores/${seed.vendors.kombo.id}/staff`,
      as: 'awa',
      headers: { 'Idempotency-Key': 'e2e-invite-not-mine' },
      body: { email: 'awa.friend@example.sn', displayName: 'A friend', permissions: ['ORDERS_VIEW'] },
      status: 404,
    }),

    // ── Where the money goes ─────────────────────────────────────────────
    req({
      name: 'Changing where the payouts go asks who you are again',
      method: 'PUT',
      path: `/vendor/stores/${seed.vendors.kombo.id}/bank-account`,
      as: 'lamin',
      body: {
        accountType: 'CHECKING',
        accountHolderName: 'Lamin Touray',
        bankName: 'Trust Bank Gambia',
        accountNumber: '001987654321',
      },
      note: 'No password, so refused. Note also what the body does NOT carry: a '
          + 'currency. A payout destination is denominated in the seller\'s own '
          + 'settlement currency, whatever an order was charged in, so there is '
          + 'nothing here for a seller to get wrong.\n\n'
          + 'A bearer token says somebody held a credential '
          + 'an hour ago, which is not enough to redirect every future payout for a '
          + 'shop — so the password is re-entered, and the authenticator code as well '
          + 'when the account has one.',
      status: [400, 401, 403, 422],
    }),

    req({
      name: 'With the password it is accepted, and the number is not echoed',
      method: 'PUT',
      path: `/vendor/stores/${seed.vendors.kombo.id}/bank-account`,
      as: 'lamin',
      body: {
        accountType: 'CHECKING',
        accountHolderName: 'Lamin Touray',
        bankName: 'Trust Bank Gambia',
        accountNumber: '001987654321',
        password: seed.PASSWORD,
      },
      note: 'With a key set, the account number goes into the column as AES-GCM '
          + 'ciphertext. Nothing the API returns about a destination could be used to '
          + 'send money anywhere: four digits, a holder name and a currency.',
      status: [200, 201, 204],
      bodyExcludes: [
        { value: '001987654321', why: 'the whole account number is never read back' },
        { value: seed.PASSWORD, why: 'nor the password that authorised the change' },
      ],
      script: `
pm.test("Payout destination — four digits, and not a number anybody could pay into", function () {
  const text = H.text(pm.response);
  if (text.length < 3) return;   // 204
  pm.expect(text, 'a full account number came back').to.not.include('001987654321');
  pm.expect(text).to.match(/4321|\\*{2,}|•/);
});
pm.test("Payout destination — reported in the seller's own currency", function () {
  const text = H.text(pm.response);
  if (text.length < 3) return;
  // Never sent by the seller and never taken from an order: 1322 is XOF
  // because Awa banks in Senegal, whatever currency a buyer paid in.
  pm.expect(text, "the destination currency should be Lamin's own").to.include('GMD');
});
`,
    }),

    req({
      name: 'A wrong password is refused',
      method: 'PUT',
      path: `/vendor/stores/${seed.vendors.kombo.id}/bank-account`,
      as: 'lamin',
      body: {
        accountType: 'CHECKING',
        accountHolderName: 'Somebody Else',
        bankName: 'Trust Bank Gambia',
        accountNumber: '009999999999',
        password: 'NotHisPassword1!',
      },
      note: 'The most valuable thing an attacker inside a seller\'s account can '
          + 'change, and the one people ask about afterwards.',
      status: [400, 401, 403],
    }),

    req({
      name: 'A password alone is not enough on an account with an authenticator',
      method: 'PUT',
      path: `/vendor/stores/${seed.vendors.mariama.id}/bank-account`,
      as: 'mariama',
      body: {
        accountType: 'MOBILE_MONEY',
        accountHolderName: 'Mariama Jarju',
        mobileMoneyProvider: 'Wave',
        mobileMoneyPhone: '+2203100010',
        password: seed.PASSWORD,
      },
      note: 'Mariama has no authenticator, so this one succeeds — the assertion that '
          + 'matters is the one in the identity folder, where a password-only step-up '
          + 'on an MFA account is refused. A step-up easier to pass than the sign-in '
          + 'that reached it is a step down.',
      status: [200, 201, 204, 400, 403, 409],
    }),

    req({
      name: "And another seller's destination cannot be changed at all",
      method: 'PUT',
      path: `/vendor/stores/${seed.vendors.kombo.id}/bank-account`,
      as: 'awa',
      body: {
        accountType: 'CHECKING',
        accountHolderName: 'Awa Diallo',
        bankName: 'CBAO',
        accountNumber: '777777777777',
        password: seed.PASSWORD,
      },
      note: 'The whole attack in one request: redirect a trading shop\'s payouts to '
          + 'your own account. Ownership is in the query, so it is a 404 before the '
          + 'password is even looked at.',
      status: 404,
    }),
  ],
);
