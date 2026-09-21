# Vendor Console — Client Specification

> The back office a seller uses: their store, their catalogue, their stock,
> their orders, their money. **66 operations under `/vendor/**`.**
>
> Read [`README.md`](README.md) first.

---

## 1. The model this client must hold

**A seller lives entirely in their own currency.** Lamin in Serekunda settles in
GMD; Awa in Ziguinchor settles in XOF. Neither ever sees the other's, and
neither sees what the buyer paid.

`GET /vendor/orders` for order 1401 — a single GBP payment from London split
across both of them — returns, for Lamin, **his slice only, priced only in GMD**.
The response contains no GBP, no London, and no Oliver.

**So the vendor console has exactly one currency on screen: the seller's own.**
If a foreign currency appears anywhere in this client, something is wrong.

The second rule: **authenticated is only the outer gate.** Every id in every path
is resolved together with the caller's vendor in a single query. There is no
path variable you can change to reach another shop. You do not need to send a
vendor id anywhere, and you should be suspicious of any code that does.

---

## 2. Onboarding

```
POST  /vendor/stores                       opens in PENDING_KYC
GET   /vendor/stores/{storeId}
PATCH /vendor/stores/{storeId}             policies, hours, collection point
```

**Do not infer the settlement currency from the address.** They are two answers
to two questions. A store opened with a Senegalese address and no currency
settles in **GMD**, the platform default — seeded store 1103 is a Dakar stall
that settles in GMD because its owner banks in Banjul. Ask explicitly.

Coordinates are optional. A market stall with no street number opens with
`geocodeConfidence: NONE` and prices collections from a scope fallback until the
owner drops a pin. **Do not block store creation on a map.** Offer the pin as an
improvement, and show what it would change.

### 2.1 Verification

```
GET  /vendor/stores/{id}/kyc     where it has got to, and why
POST /vendor/stores/{id}/kyc     storage keys — never bytes
```

Three things the UI has to get right:

1. **What is required depends on what the store claims to be.** A store that
   gave a registration number and a tax number is asked for both certificates. A
   store that gave neither is asked for an identity document and a proof of
   address — which is all a market trader has. **Render the required list from
   the response**, never from a hard-coded checklist. Demanding a company's
   paperwork from a market trader is how a marketplace turns away the sellers it
   exists for.
2. **Rejections carry a reason and superseded documents survive.** Awa's first
   national ID was refused as too dark to read; the second was accepted; both are
   in the response. Show the sequence — it is the record of why onboarding took
   three weeks. Status `ACTION_REQUIRED` with the reason attached is the useful
   state; a bare "incomplete" sends somebody back to upload the same document
   again.
3. **The bytes never pass through the API, and `GET /kyc` does not hand the key
   back.** A URL to somebody's passport in a JSON response is a URL in a browser
   cache. Upload direct to storage, POST the key, and never expect to render the
   document again.

### 2.2 Where the money goes — the step-up screen

```http
PUT /vendor/stores/{storeId}/bank-account
{ …details…, "password": "…", "totpCode": "…" }
```

**This is the one endpoint in the console that asks who you are again**, and the
UI must be built for it rather than surprised by it.

A bearer token says somebody held a credential an hour ago, which is not enough
to redirect every future payout for a shop. So the password is re-entered, **and
the authenticator code as well when the account has one**. Sending the password
alone on an MFA account is refused — a step-up easier to pass than the sign-in
that reached it is not a step-up.

Two failure states to render properly:

- **MFA is on and you sent only a password** → ask for the code, do not show a
  generic error.
- **The deployment has no field-encryption key** → the endpoint refuses *before
  reading anything*. Show the refusal as an operator problem, not a user error:
  a system that looks like it encrypts bank details and does not is worse than
  one that admits it cannot.

What comes back about a saved destination is four digits, a holder name and a
currency — nothing that could send money anywhere. **The currency is the
vendor's own**, whatever any order was charged in.

### 2.3 Staff

```
GET    /vendor/stores/{id}/staff
POST   /vendor/stores/{id}/staff             invite by email
PATCH  /vendor/stores/{id}/staff/{userId}
DELETE /vendor/stores/{id}/staff/{userId}
```

**Invite by email, and the invitee usually has no account here.** The usual case
is a relative or an assistant who has never used the platform. The invitation
waits for them to register. Build the flow for that, not for "search existing
users".

Invitations lapse (7 days by default) and there is a cap per store, so an invite
loop cannot be used to send mail.

**The owner is not a staff row.** The endpoint puts them at the top of the list
from the vendor record. Render them as the owner, not as staff with every
permission.

Permissions come from a **store-only enum**. There is no value that reaches
another vendor's data or the platform's. Render the enum the server gives you;
do not invent permission names.

---

## 3. Catalogue

```
GET    /vendor/products                     drafts and suspended included
POST   /vendor/products                     ALWAYS created as DRAFT
GET    /vendor/products/{id}
PATCH  /vendor/products/{id}
DELETE /vendor/products/{id}                archive or delete — see below
POST   /vendor/products/{id}/submit-for-review
POST   /vendor/products/{id}/publish        only if APPROVED
POST   /vendor/products/{id}/unpublish
POST   /vendor/products/{id}/variants  ·  PATCH|DELETE …/variants/{vid}
POST   /vendor/products/{id}/media  ·  PATCH …/media/reorder  ·  DELETE …/media/{mid}
PUT    /vendor/products/{id}/translations/{locale}
```

### 3.1 The ladder

```
DRAFT ──submit──▶ IN_REVIEW ──moderator──▶ APPROVED ──seller──▶ PUBLISHED
```

**The last step belongs to the seller.** Being allowed to sell and choosing to
are different decisions, and approval arriving overnight should not put a
listing live before the seller has set the stock. Render "Approved — publish when
you are ready" as an action, not as a done state.

Publishing a DRAFT directly is **refused**, and that refusal is the only thing
making the review queue real rather than advisory.

### 3.2 The edit that sends a listing back — warn before it happens

Every listing carries a digest of exactly what a moderator looked at: **name,
description, price, category, brand, condition**. Change any of those and the
listing returns to `IN_REVIEW` and goes off sale.

Change the **stock** and nothing happens — a seller who had to re-enter a queue
to restock would stop using the queue.

> **Build this into the edit form.** Mark the six re-review fields visibly and
> confirm before saving: *"Changing the price will take this listing off sale
> until it is reviewed again."* A seller who discovers that after the fact loses
> a day's trading and trusts the console less.

Useful invariant for your UI: **`active` is true if and only if `status` is
`PUBLISHED`.** Render one state, not two.

### 3.3 Deleting

`DELETE` has two outcomes and the response tells you which. A listing that order
lines point at is **archived** — a buyer's receipt, invoice and review must keep
resolving years from now. A listing nobody ever ordered is genuinely deleted.

Say which happened. "Archived — past orders still reference it" is a different
promise from "deleted".

### 3.4 Translations

```http
PUT /vendor/products/{id}/translations/fr-SN
```

Nobody in this chain can pick the goods up and look at them, so the listing text
is the whole of what a buyer gets. Translations carry a **machine-translated
flag and the buyer is told** — a machine's version of *"six yards of wax print,
cut to order"* is usually fine and occasionally nonsense, and somebody spending a
month's remittance should know which they are reading.

A partial translation (name only) is allowed and is a real state. Show
completeness per locale.

### 3.5 Bulk import and export

```
GET  /vendor/products/import-template     which columns are understood
POST /vendor/products/bulk-import         queued, returns a reference
GET  /vendor/imports/{reference}          progress and errors
GET  /vendor/products/export              queued the same way
```

**Asynchronous, and the UI must be built for it.** A seller uploading four
hundred rows over a mobile connection loses the response long before the work
finishes. Return the reference immediately and poll.

Two kinds of failure, needing different words:

- **Row failures** — "27 in, 3 rejected". Each error names the row **as the
  seller's own spreadsheet numbers it** (header is row 1) and quotes their text
  back: *"there is no category called 'Phonez'"*. Render them as a list against
  row numbers so the seller can fix the file.
- **File failure** — somebody uploaded a PDF. Nothing was attempted. Say so
  **once**; four hundred identical row errors is not help.

Beyond 200 recorded errors the server returns a count instead of a list.

**Every imported row is a DRAFT.** An import that could publish would be the way
past moderation. Tell the seller that up front so they are not waiting for four
hundred listings to appear on sale.

Max 3 queued jobs per vendor — the fourth is refused, because a seller queueing
twenty has made a mistake and the second is almost always the same file again.

---

## 4. Stock

```
GET   /vendor/inventory?lowStock=true
GET   /vendor/inventory/{variantId}/movements
PATCH /vendor/inventory/{variantId}
POST  /vendor/inventory/bulk
```

### 4.1 Stock is a ledger, not a number

Movements sum to the figure: `0 + 10 − 3 − 4 = 3`. **Render the movements, not
just the count** — a count that can be assigned directly is a count nobody can
explain.

The trail contains the seller's own corrections **and the SALEs**. An audit
showing manual edits and quietly omitting the orders that took the stock would
be wrong in exactly the case somebody opens it for. A `SALE` has no order number
(stock is reserved before the order exists) and no person; a `RETURN` has both.
Render the blanks as blanks rather than "unknown".

The row that earns the feature: *minus four, CORRECTION, "counted the shelf, four
short"*. A month of those with no restocks is a shop with a theft problem, and a
ledger calling them all restocks would hide it. **Make the reason field
prominent and make free text easy.**

### 4.2 Two ways to change stock, and only one needs a version

```jsonc
{ "delta": -2, "reason": "DAMAGE" }                         // relative
{ "setTo": 12, "version": 0, "reason": "CORRECTION" }       // absolute
```

An absolute figure carries **the version it was read at**. Two people counting
the same shelf and saving 10 and 12 must not leave whichever committed last with
the other simply wrong and nothing to say so. The second save is **409**.

A delta needs no version: `+5` is `+5` whoever else is writing.

**On 409: re-read, show both figures, and let the user decide.** Never retry
silently.

### 4.3 Handsets

```
GET   /vendor/imei-units?variantId=1350
POST  /vendor/imei-units
PATCH /vendor/imei-units/{unitId}
```

Phones are the one product a count cannot describe. Most sold here are
second-hand, the buyer is often thousands of miles away choosing a gift, and two
units of the same model are not interchangeable when one was opened once and the
other has a scratched screen. That gap is most of the dispute surface on this
marketplace.

- IMEIs are **Luhn checked**. In a bulk registration, **the bad line alone is
  rejected** and the rest register — render per-line results.
- **The units are the authority; the count follows them.** A variant with IMEI
  units **refuses a typed stock figure outright**. Hide that input entirely for
  such variants rather than letting it 400.
- A seller **cannot** mark a unit SOLD (the order does that, so the record and
  the sale cannot disagree) and **cannot** touch BLOCKED in either direction —
  setting it would flag a rival's stock; clearing it would launder a stolen
  handset. Do not render those controls.

---

## 5. Orders and fulfilment

```
GET   /vendor/orders                              my slices
GET   /vendor/orders/stats
GET   /vendor/orders/{vendorOrderId}
POST  /vendor/orders/{id}/accept   ·   /reject     (reject needs a reason)
POST  /vendor/orders/{id}/lines/{lineId}/assign-imei
POST  /vendor/orders/{id}/ready                   packed — mints the collection code
GET   /vendor/orders/{id}/handoff-code
POST  /vendor/orders/{id}/handoff-code/regenerate
GET   /vendor/orders/{id}/label                   A6 PDF with a QR
```

### 5.1 What a seller learns about the people

A name, a town, a country, three digits of a phone. **No street** — the platform
routes the parcel and the QR on the label resolves the address for whoever scans
it. **No payer at all.**

Design the screen around that. A shipping panel with an empty "Address" field
looks broken; a panel that says *"Serrekunda, The Gambia — routed by Sujula"*
looks correct, because it is.

### 5.2 The collection code

```http
GET /vendor/orders/{id}/handoff-code
```

- Served `no-store` and **never logged**. **Do not cache it, do not put it in
  analytics, do not include it in a screenshot-friendly summary.**
- Regenerating kills the previous code.
- **It is deliberately not on the label.** A code printed on the box it protects
  protects nothing. Do not "helpfully" add it to your own print view.

### 5.3 `ready` is idempotent, and that matters

Calling `POST /vendor/orders/{id}/ready` on an already-packed slice says so and
leaves **the collection code unchanged**. A driver is already on the way with the
first code, and a seller tapping the button twice on a patchy connection must not
invalidate it.

So: **do not disable the button after the first tap and do not show an error on
the second.** Show the same confirmation.

On a slice whose phone is unscanned, `ready` is refused **naming the line and the
count**. Render that — it tells the seller exactly what to scan.

### 5.4 Independence

A rejected slice carries the seller's reason and **leaves every other seller's
slice untouched**. Never render another vendor's state, and never imply the
whole order is affected by this shop's decision.

---

## 6. Money

```
GET  /vendor/balance                        what I am owed
GET  /vendor/transactions?currency=XOF      the ledger behind it
GET  /vendor/payouts   ·   POST /vendor/payouts/request
GET  /vendor/statements/{period}?currency=…&format=pdf|csv
```

### 6.1 Available and pending are different things

*"1,000 available, 17,640 pending"*. **Pending is escrow** — parcels not yet
confirmed delivered. Label it that way. A seller who thinks pending money is
missing money contacts support; one who understands escrow does not.

**Nothing stores either figure.** Both are sums of ledger entries, which is the
only thing that makes a balance checkable. Render the transactions alongside the
balance and let the seller add them up.

### 6.2 Never net rows together

A refunded slice shows **four rows** — sale, commission, refund, commission
returned — summing to exactly nothing. Netting them into one row would hide the
thing a seller opens a refund to check. Show all four.

A failed payout comes back as a **reversal**, not a deletion, so the statement
explains the gap instead of hiding it. Render reversals explicitly.

### 6.3 Refusals that are informative

`POST /vendor/payouts/request` with everything in escrow is refused, and the
message says **which kind of nothing it is**: money still held against parcels,
rather than no money at all. Show the message verbatim.

### 6.4 Statements

`GET /vendor/statements/2026-09?currency=GMD` returns a PDF whose
**brought-forward + movements = carried-forward**. `&format=csv` for the same
figures in a spreadsheet. Offer both — the second is what a seller sends their
accountant.

**One statement per currency.** A shop trading in two currencies gets two
statements, never a combined one.

---

## 7. Analytics

```
GET /vendor/analytics/overview     ·   /sales   ·   /products
GET /vendor/analytics/customers    ·   /delivery
```

Three rules the UI has to respect:

1. **Revenue is per currency, never one total.** A shop trading in two currencies
   gets two figures and a note saying why they are not added. **Render the note.**
   A single summed figure would be a made-up number.
2. **Views are page loads, not people**, and the response says so. Put that on
   the chart, not in a tooltip nobody opens.
3. **Destinations are where parcels went, never where the payer was**, and a
   country with only a handful of orders is left out entirely. Do not present the
   map as complete.

---

## 8. Promotions and coupons

```
GET|POST /vendor/promotions   ·   PATCH|DELETE /vendor/promotions/{id}
POST /vendor/promotions/{id}/activate
GET|POST /vendor/coupons      ·   PATCH /vendor/coupons/{id}
GET /vendor/coupons/{id}/redemptions
```

**Overlaps are refused, and the refusal names the conflicting promotion.**
Two discounts on one item do not compound into a price anybody can predict —
they compound into whichever the pricing code reaches first, which is a different
answer on different days and an argument with a buyer either way. "Conflicts with
an existing promotion" would leave a seller hunting through their own list, so
the server says which one. **Render that name as a link.**

`FREE_SHIPPING` runs happily alongside a goods discount: one discounts the
delivery leg, the other the goods, so they are not competing for the same number.

**Every amount is in the seller's own currency.** A buyer paying GBP sees it
converted at their order's rate. A seller striking a discount in GBP would be
funding an amount that moves with the market between writing the promotion and
the order landing — so the console never offers a foreign currency here.

**Promotion vs coupon:** a promotion is a price the shop is charging; a coupon is
a credential a buyer presents. That is why a coupon can be capped per customer —
there is a customer to count — and a promotion cannot.

---

## 9. Rules this client must not break

| Never | Because |
|---|---|
| Show a currency that is not the seller's own | A vendor is never shown a buyer's currency |
| Sum revenue across currencies | It is a made-up number |
| Send a vendor id in a path or body | The vendor comes from the session; every query already carries it |
| Cache, log or screenshot a collection code | It is `no-store` and never logged server-side |
| Put the collection code on your print view | It is off the label on purpose |
| Disable "Ready" after one tap | It is idempotent, and a second tap must not invalidate a live code |
| Let a seller edit a price without warning | It takes the listing off sale |
| Offer a typed stock figure on an IMEI-tracked variant | The units are the authority |
| Retry a 409 silently | Two people are counting the same shelf |
| Net ledger rows together | It hides the thing a refund is opened to check |
| Hard-code the KYC document checklist | What is required depends on what the store claims to be |

---

## 10. Build order

1. Sign-in, `/me/permissions`, and a "pending approval" state that renders
   honestly.
2. Store creation — **currency asked, never inferred**.
3. KYC with a server-driven checklist.
4. Catalogue with the four-step ladder and the re-review warning.
5. Inventory with movements, both edit forms, and 409 handling.
6. Orders + fulfilment + the code screen.
7. Money: balance, ledger, statements.
8. Analytics with per-currency figures.
9. Promotions with conflict handling.
10. Bulk import with polling and two error shapes.
11. Staff and the step-up bank-account screen.
