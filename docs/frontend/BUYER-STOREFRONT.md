# Buyer Storefront — Client Specification

> Web and mobile. The application a shopper uses to browse, fill a basket, pay,
> and follow a parcel. **Guests are first-class** — most baskets here are filled
> before anybody signs in.
>
> Read [`README.md`](README.md) first. It carries auth, errors, money formatting
> and idempotency, which this document assumes.

---

## 1. The model this client must hold

A buyer in Madrid pays in euro for a phone that goes to their sister in
Serrekunda. That single sentence creates every requirement below.

```
  PAYER CONTEXT                       DELIVERY CONTEXT
  where the buyer is                  where the goods go
  ─────────────────                   ──────────────────
  → display currency                  → shipping cost
  → payment methods offered           → serviceability
                                      → pickup points
                                      → CATALOGUE RANKING
```

**These are two pieces of state in your client, set by two different endpoints,
and they must never be derived from one another.**

The test: change the delivery context and the ranking must move while the
currency stays put; change the shopper's country and the currency must move
while the ranking stays put. If either drags the other, the storefront is
broken.

---

## 2. Screens and the calls behind them

### 2.1 Boot

```
GET /config/public     feature flags, minimum app version, support contacts
GET /currencies        codes, symbols, minorUnits      ← cache
GET /countries         buys / ships flags              ← cache
GET /locales           languages, rtl                  ← cache
GET /categories        the tree
```

Check `minimumAppVersion` before drawing anything else.

### 2.2 Choosing where the parcel goes

This is the first thing the storefront should ask, before browsing if possible,
because it changes everything after it.

```http
POST /delivery-contexts
{ "addressId": 1050 }                              // signed in, saved address
{ "latitude": 13.4383, "longitude": -16.6781, "countryCode": "GM" }   // a pin
{ "pickupPointId": 1096 }                          // collection, no pin at all
```

Returns an id. **Keep it for the session** and pass it everywhere.

For a guest the id is the **whole** of their claim to that destination — it is
256 bits of secure random for exactly that reason, and it carries somebody's
home address. Treat it like a password: never in a URL you log, never in a
shared link.

`GET /delivery-contexts/{id}` for another account's context is **404**.

### 2.3 Browsing — the C1 screen

```http
GET /products?deliverableTo=seed-ctx-aminata-home&currency=GBP&page=0&size=20
```

Or, with no context yet:

```http
GET /products?deliveryLat=13.4383&deliveryLng=-16.6781&deliveryCountry=GM&currency=GBP
```

| Parameter | Is | Never |
|---|---|---|
| `deliverableTo` / `deliveryLat` / `deliveryLng` / `deliveryCountry` | **the recipient's** location | the shopper's |
| `currency` | **the payer's** | inferred from the delivery country |

Omitting the location is not an error — the catalogue answers unranked. Omitting
the currency lets the server resolve it from browser locale and IP.

**The response echoes back which location it used, under `delivery`.** Render
it. *"Showing what can reach Serrekunda"* is the single most useful line of text
on this screen, and it is the only way the shopper knows the ranking is about
their sister rather than about them.

Other catalogue reads, all taking the same location parameters:

```
GET /products/{slug}                  one product
GET /products/{productId}/variants    priced in the buyer's currency
GET /products/{productId}/reviews
GET /products/{productId}/related
GET /products/{productId}/questions
GET /categories/{slug}                one category + the filters it can offer
GET /stores/{slug}                    a public storefront
GET /stores/{slug}/products           ranked against the delivery location
GET /search?q=…                       + GET /search/suggest for typeahead
GET /brands
```

`POST /products/{id}/questions` is the one catalogue write and it **requires an
account** — it puts public text on a seller's shopfront, which without an
account behind it is a spam channel with no cost to the sender.

### 2.4 Serviceability and the shipping estimate

Both public — a shopper asks before signing in, and often before having an
account.

```http
POST /delivery/serviceability
{ "origin": { "vendorId": 1101 },
  "destination": { "latitude": 13.2714, "longitude": -16.6492, "countryCode": "GM" },
  "nearestPickupPoints": 3 }

POST /delivery/quote
{ "origin": { "vendorId": 1101 },
  "destination": { … },
  "weightKg": 2.5, "value": 3000, "currency": "GMD" }
```

> **`complete: false` is a real answer and you must handle it.** It means the
> quote could not be fully converted — usually no published rate for the
> requested currency. **Do not render the number anyway.** Quoting the rate
> card's own currency under someone else's symbol is how a buyer is charged £50
> for a D50 delivery.

These take POST rather than GET because a destination is an address: too long
for a query string, and not something to leave in access logs.

### 2.5 The basket

```
POST   /carts                                open one → cartToken
GET    /carts/{t}                            read
POST   /carts/{t}/items                      add
PATCH  /carts/{t}/items/{itemId}             quantity
DELETE /carts/{t}/items/{itemId}
PUT    /carts/{t}/delivery-context           WHERE  ← re-prices shipping
PUT    /carts/{t}/currency                   WHAT   ← touches no delivery
POST   /carts/{t}/coupons                    apply
DELETE /carts/{t}/coupons/{code}
POST   /carts/{t}/merge                      take over a guest cart on sign-in
POST   /carts/{t}/quote                      price it and HOLD the figures
```

**The cart token is the credential.** 256 bits of secure random, because a cart
holds a destination, a list of what somebody is buying and for whom, and what
they are about to spend. Store it like a session token. Never put it in a
shareable URL.

**The response is grouped by store**, each group with its own shipping and each
line with its own delivery leg — a multivendor basket has no single origin, and
two sellers in two towns ship two parcels. Render the groups. A single "Shipping"
line is a lie about what is happening.

**On sign-in, call `POST /carts/{guestToken}/merge`** before doing anything
else, or the shopper's basket vanishes at the worst possible moment.

### 2.6 The quote — the step clients skip and should not

```http
POST /carts/{t}/quote
```

Prices the cart and **holds the figures for fifteen minutes**, freezing the rate
each line was converted at.

Four states you must handle:

| State | Do |
|---|---|
| **live** | Proceed to checkout |
| **consumed** | Already spent on an order. Still readable — a confirmation page reloaded must show "used", not "missing" |
| **expired** | `POST /checkout` is a **404**. Re-quote |
| **incomplete** | One vendor's currency had no rate. **Checkout refuses it.** Show *which vendor* is unpriceable, so the shopper can remove that item rather than staring at a dead button |

Show the countdown. Fifteen minutes is generous on a good connection and tight
on a bad one.

### 2.7 Checkout

```http
POST /checkout
Idempotency-Key: <one uuid per user intention, reused across retries>

{ "quoteId": "…", "addressId": 1050, "paymentMethod": "CARD", "notes": "…" }
```

The server validates, reserves stock, creates the order and its per-vendor
sub-orders, and opens a payment intent — **in that order**.

```jsonc
{ "orderId": 1401, "orderNumber": "…", "status": "PENDING",
  "currency": "GBP", "subtotal": …, "shipping": …, "total": …,
  "vendorOrders": [
    { "vendorOrderId": 1501, "storeName": "Kombo Electronics",
      "status": "CONFIRMED", "total": 93.50,
      "listingCurrency": "GMD", "totalNative": 8500,
      "payoutNative": 7650, "fxRate": 0.011 },
    { "vendorOrderId": 1502, "storeName": "Teranga Textiles", … }
  ],
  "payment": { "paymentId": …, "method": "CARD", "status": "PENDING",
               "checkoutUrl": "…", "clientSecret": "…", "instructions": null },
  "placedAt": "…" }
```

> **`vendorOrders` is the shape the thing actually has.** One payment, several
> slices that ship, cancel, refund and pay out independently. Render them as
> separate things from this screen onward. A client showing one order with one
> status will eventually be wrong about half of it.

`payment` has three mutually exclusive shapes: `checkoutUrl` (redirect the
buyer), `clientSecret` (confirm client-side), or `instructions` (show them — bank
transfer, cash on delivery). Handle all three.

**If the price moved under the quote, checkout is rejected and nothing is
charged.** Catch this specifically and offer "prices changed — review and
re-quote". It is the one failure a shopper who did nothing wrong can hit.

### 2.8 Waiting for the payment

```http
GET /checkout/{orderId}/status
```

```jsonc
{ "orderStatus": "CONFIRMED", "paymentStatus": "PAID",
  "settled": true, "retryable": false, "paidAt": "…", "failureReason": null }
```

**Poll `settled`, not `paymentStatus`.** `settled` is true only once the payment
is confirmed **by a webhook** — by the provider, not by the client's own
optimism about a redirect that came back.

`retryable: true` → offer `POST /checkout/{orderId}/retry-payment`, which opens
a fresh intent against the same order.

Poll every 2s for the first 30s, then back off. Stop at ~5 minutes and tell the
buyer they will be emailed.

### 2.9 Orders

```
GET  /orders                                                    my orders
GET  /orders/{orderId}                                          grouped by seller
GET  /orders/{orderId}/tracking                                 one timeline per parcel
GET  /orders/{orderId}/invoice                                  a signed, expiring link
POST /orders/{orderId}/cancel                                   the whole order
POST /orders/{orderId}/vendor-orders/{id}/cancel                one seller's items
POST /orders/{orderId}/vendor-orders/{id}/confirm-receipt       these arrived
POST /orders/{orderId}/lines/{lineId}/reviews                   review what you got
```

**Cancellation is per slice, and whole-order cancellation frequently fails.**
Order 1401 cannot be cancelled because Lamin's slice is already SHIPPED — and
the refusal *names the store*. Render that name; "cannot cancel" alone leaves
the buyer guessing which half of their order is the problem.

**What a cancel returns is a refund *request*, not a refund.** Money leaving the
platform is the one action no later API call can undo, so nothing in the buyer
surface completes it. Say "refund requested, awaiting review" — never "refunded".

**Cancel is idempotent.** Tapping twice on a slow connection does not queue two
refunds against the same goods.

`GET /orders/{id}/invoice` returns a **short-lived signed link** (15 minutes),
not the PDF. The link is then the credential and travels legitimately — to a
bank, to whoever is reimbursing the buyer. Do not cache it; re-request it.

### 2.10 After-sales

```
POST /returns                                  ask to send something back
GET  /returns  ·  GET /returns/{id}
POST /returns/{id}/accept-offer                take a partial-refund offer
POST /returns/{id}/escalate                    ask the platform to decide
POST /disputes  ·  /disputes/{id}/messages  ·  /evidence  ·  /withdraw
GET  /messages/threads  ·  POST /messages/threads  ·  …/messages
PATCH|DELETE /reviews/{id}                     your own, inside the window
POST /reviews/{id}/report
```

All of these are **per sub-order**. There is no endpoint that returns or
disputes "an order", because an order is not a thing anybody can be in dispute
about.

Messages have **contact details stripped** — phone numbers and email addresses
are removed. Warn the user before they type one, rather than silently swallowing
it and leaving them waiting for a call that will never come.

### 2.11 Account

```
GET|PATCH /me                      profile, roles, permissions, resolved currency
DELETE /me                         ask for erasure
GET|POST /me/addresses  ·  GET|PATCH|DELETE /me/addresses/{id}
POST /me/addresses/{id}/confirm-pin
GET /me/sessions  ·  DELETE /me/sessions/{id}
GET|POST /me/export                a copy of everything held
GET /me/permissions
```

See [`README.md` §10](README.md) for address confidence and the two deletion
outcomes — both need UI.

---

## 3. Guest checkout

A guest has no account **by definition**, so the whole flow is open:

```
POST /carts                              a cart, no account
POST /delivery-contexts                  a destination, no account
POST /carts/{t}/quote
POST /api/guest/orders                   ⚠ legacy surface — the only guest checkout today
GET  /api/guest/orders/lookup?orderNumber=…&email=…
POST /api/guest/orders/{n}/cancel?email=…
```

Every guest order lookup needs **both** the order number **and** the email used
at checkout, so an order number on its own reveals nothing.

> ⚠ Guest checkout is still on the legacy `/api/**` surface and returns JPA
> entities directly ([`../CODE-REVIEW.md` §3.2](../CODE-REVIEW.md)). Isolate it
> behind one module in your client so the migration is one file.

---

## 4. Rules this client must not break

| Never | Because |
|---|---|
| Pass the shopper's coordinates as the delivery location | It ranks the catalogue against a place the parcel is never going |
| Infer currency from the delivery country | It quotes a Spanish cardholder in dalasi |
| Format every amount to two decimals | **XOF has none.** Read `minorUnits` |
| Multiply by an FX rate in the client | Rates are snapshotted. Your number will not match the invoice |
| Render one status for a multivendor order | The slices are independent |
| Say "refunded" when a cancel returns a refund request | Nothing has moved yet |
| Render "does not exist" from a 404 | 404 also means "not yours" |
| Put the cart token or a delivery-context id in a shareable URL | Each is a bearer credential for somebody's basket or home address |
| Refresh tokens concurrently | The second is treated as theft and the session is revoked |
| Render a `complete: false` delivery quote as a number | It is not a price |

---

## 5. Build order

1. Boot + reference data + currency formatting with `minorUnits`.
2. Delivery context as first-class session state.
3. Browse with the delivery location, rendering the echoed `delivery` block.
4. Cart with per-store grouping.
5. Quote, with all four states.
6. Checkout + the three payment shapes + `settled` polling.
7. Orders as **slices**.
8. Account, addresses with confidence, sessions.
9. After-sales.
10. Guest flow.

Steps 1–3 are where C1 is either implemented correctly or baked in wrong. Do not
rush them.
