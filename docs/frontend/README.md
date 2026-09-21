# Sujula — Client Application Documents

> **Read this first.** There is **no front-end code in this repository.** Sujula
> is a backend: 432 HTTP operations and nothing that draws a screen.
>
> So "a technical document for each front-end application" is, here, a document
> per **client application the API is shaped to serve**. The API makes six of
> them unmistakable — it has a separate, deliberately-designed surface for each,
> with different authentication, different permissions and different rules about
> what may be shown to whom.
>
> Each document below is a **build specification**: which endpoints that client
> owns, what each screen calls, what the client must *not* do, and the rules it
> has to get right or the product breaks.

| Client | Users | Surface | Document |
|---|---|---|---|
| 🛍 **Buyer storefront** | Shoppers, signed in and guests | `/products`, `/carts`, `/checkout`, `/orders`, `/me` | [BUYER-STOREFRONT.md](BUYER-STOREFRONT.md) |
| 🏪 **Vendor console** | Sellers | `/vendor/**` (66 ops) | [VENDOR-CONSOLE.md](VENDOR-CONSOLE.md) |
| 🛵 **Driver app** | Couriers | `/driver/**` (19 ops) | [DRIVER-APP.md](DRIVER-APP.md) |
| 📦 **Pickup counter** | Shop counters holding parcels | `/pickup/**` (10 ops) | [PICKUP-COUNTER.md](PICKUP-COUNTER.md) |
| 🛠 **Admin console** | Staff — ADMIN and SUPPORT | `/admin/**` (95 ops) | [ADMIN-CONSOLE.md](ADMIN-CONSOLE.md) |
| 📬 **Recipient page** | **People with no account** | `/track/*`, `/parcels/*` (6 ops) | [RECIPIENT-PAGE.md](RECIPIENT-PAGE.md) |

This document covers what all six share. **Read it before any of them.**

---

## 1. The one thing that will break your client if you get it wrong

> **The person who pays and the person who receives are different people, in
> different countries.**

Everything unusual about this API follows from that. Two consequences reach
every client:

**Two locations, never one.** The **payer context** (where the buyer is) decides
currency and payment methods. The **delivery context** (where the goods go)
decides shipping cost, serviceability, pickup points and catalogue ranking. They
are separate fields, set by separate endpoints, and a client that collapses them
is wrong — it will rank a Madrid shopper's catalogue against Madrid, showing
stock that can never reach Serrekunda.

There is no `userLat` parameter anywhere in the catalogue API. If you are
looking for one, you have the model backwards.

**One payment, several sub-orders.** An order splits per vendor, and those
slices **ship, cancel, refund and pay out independently**. A UI that renders one
order with one status will eventually be wrong about half of it. Address
cancellation, receipt confirmation, returns and refunds at
`/orders/{orderId}/vendor-orders/{vendorOrderId}/…`, never at the order.

---

## 2. Base URL, and the API generation to use

```
https://<host>/          — the current surface (flat paths)
https://<host>/api/…     — the legacy surface. DO NOT BUILD ON THIS.
```

**116 of the 432 operations are legacy** and duplicate the current surface:
`/api/users`, `/api/user/addresses`, `/api/cart`, `/api/user/orders`,
`/api/products`, `/api/vendors`, `/api/exchange-rates`, `/api/admin`.

Use the flat paths: `/auth`, `/me`, `/products`, `/carts`, `/checkout`,
`/orders`, `/vendor`, `/admin`, `/currencies`. They are the surface the
platform's rules were built into, the one the end-to-end collection exercises,
and the one whose controllers document their own ownership model.

See [`../CODE-REVIEW.md` §3.1](../CODE-REVIEW.md) for why both exist.

---

## 3. Authentication

### 3.1 The exchange

```http
POST /auth/login
Content-Type: application/json

{ "email": "…", "password": "…" }
```

```jsonc
// Signed in
{ "mfaRequired": false,
  "tokens": { "accessToken": "eyJ…", "tokenType": "Bearer",
              "expiresIn": 600, "refreshToken": "…",
              "sessionId": 1801, "user": { … } } }

// Password correct, second factor needed
{ "mfaRequired": true, "tokens": null }
```

> **`mfaRequired` is not an error, and it is modelled as a successful response on
> purpose.** "Your password was right and I need one more thing" is not a
> failure, and a client that cannot tell it from a wrong password shows the wrong
> message. Branch on the flag; do not branch on the status code.

Retry with `totpCode`, or with `recoveryCode` (each works once; a spent one is
refused rather than treated as never having existed).

### 3.2 Carrying the token

```http
Authorization: Bearer <accessToken>
```

**The access token lasts 10 minutes.** That is not a bug to work around — the
server keeps no copy of it and cannot revoke it, so its lifetime *is* the blast
radius of a stolen one.

### 3.3 Refreshing — the part clients get wrong

```http
POST /auth/refresh
{ "refreshToken": "…" }
```

Three rules:

1. **The refresh token rotates.** The response contains a **new** one. Store it.
   The old one is dead.
2. **Never refresh concurrently.** Two requests that both 401 must not both
   refresh — one will present a token the other has rotated past, and **the
   server treats that as theft: 401, and the whole session is revoked.** Your
   user is signed out for real.

   Serialise it: one refresh in flight, with every other request queued behind
   it and replayed after.
3. **Refresh proactively**, at ~80% of `expiresIn`, rather than reacting to
   401s. Fewer races, fewer user-visible stalls.

### 3.4 Signing out

`POST /auth/logout` (this device), `POST /auth/logout-all` (everywhere),
`DELETE /me/sessions/{id}` (one remote device).

Remote sign-out takes effect on that device's **very next request**, not
whenever its token expires. The session check rides along in the same query that
loads the user.

### 3.5 Render from `/me/permissions`, not from the role

```http
GET /me/permissions
```

A role is not the answer. A vendor whose application is pending *has* the VENDOR
role and cannot list a single product; a suspended vendor keeps the role and
loses the ability to trade. Drawing the back office from the role alone produces
a screen full of buttons the API then refuses.

This endpoint is **advisory** — it describes what the server will allow so you
can render honestly. It grants nothing. Every endpoint still enforces.

---

## 4. CSRF

Only needed for **cookie-session** writes. If you send `Authorization: Bearer`,
you can ignore this entire section.

The server sets a readable `XSRF-TOKEN` cookie; echo its **raw** value back as
`X-XSRF-TOKEN` on writes. Angular does this natively; axios via
`xsrfCookieName`; fetch wrappers by hand.

Exempt: `/auth/**`, `/me/**`, `/webhooks/**`, `/api/payments/callback`,
`/geo/**`, `/delivery/**`, `/delivery-contexts`, `/currencies`, `/carts`,
`/checkout`.

---

## 5. Errors

One shape, everywhere:

```jsonc
{ "timestamp": "2026-09-21T22:15:52Z",
  "status": 400,
  "error": "Bad Request",
  "message": "There is no category called 'Phonez'.",
  "fieldErrors": { "price": "must be greater than 0" },   // validation only
  "traceId": "…" }
```

| Status | Means | What the client should do |
|---|---|---|
| 400 | Validation, or a business rule refused | **Show `message` directly.** These are written as sentences for the person who hit them |
| 401 | Not signed in, or the token is bad | Refresh once (§3.3), then sign in |
| 403 | Signed in, not allowed | Don't retry. Re-fetch `/me/permissions` — the account's state may have changed |
| 404 | Not there — **or not yours** | See §5.1 |
| 409 | Concurrent edit (optimistic lock) | Re-read, show both values, let the user choose |
| 429 | Rate limited | Back off |
| 500 | Server | Show `traceId`; it is in the server log |

### 5.1 404 means "not yours" as often as "not there"

This API answers **404 for another user's row**, deliberately. `403 Forbidden`
would confirm the row exists, and on this platform a row is somebody's home
address, order or held exchange rate.

An **expired** credential also answers 404, not 410 — an expired bearer
credential and one that never existed must be indistinguishable.

**So never render "this order does not exist" from a 404.** Render *"we could
not find that"*, which is true in both cases.

---

## 6. Idempotency

Send `Idempotency-Key: <uuid>` on any write you might retry. On a mobile network
a timed-out request is indistinguishable from one that never arrived, so clients
retry — and without a key the shopper gets the address twice, or two orders.

- **Same key, same body** → the recorded response is replayed. Nothing is saved
  twice.
- **Same key, different body** → **refused**. A key reused for different content
  is not a retry, and replaying the first answer would silently discard the
  second request.

**Generate the key once per user intention, not per HTTP attempt.** Re-use it
across all retries of that intention.

Most important on `POST /checkout`, `POST /me/addresses`, any custody event, and
every cancel.

---

## 7. Money and currency — the rules every client must implement

### 7.1 Read `minorUnits`. Never assume two.

```http
GET /currencies
```

```jsonc
[ { "code": "XOF", "name": "West African CFA franc", "symbol": "CFA", "minorUnits": 0 },
  { "code": "GMD", "name": "Gambian dalasi",         "symbol": "D",   "minorUnits": 2 },
  { "code": "GBP", …, "minorUnits": 2 } ]
```

**XOF has no minor unit.** There is no centime of CFA in circulation. A client
that formats every amount to two places shows a buyer in Ziguinchor a total they
cannot tender.

```js
new Intl.NumberFormat(locale, {
  style: 'currency', currency: code,
  minimumFractionDigits: minorUnits, maximumFractionDigits: minorUnits
}).format(amount)
```

### 7.2 Never do FX arithmetic in the client

Rates are **snapshotted, not recomputed**. Every converted figure the server
returns carries the rate it was converted at and the moment that rate was taken.
A client that multiplies produces a number nobody can explain and that will not
match the invoice.

Display what the server gave you. If you need a conversion, ask for it.

### 7.3 Two rate endpoints that promise different things

| | Promise | Use for |
|---|---|---|
| `GET /currencies/rates?base=&quote=` | **Indicative.** What the pair was last published at, with its age. Commits to nothing | A price hint, a currency picker |
| `POST /currencies/quote` | **Commits.** This rate, for 15 minutes, with an id | Anything the user is about to be charged |

A pair with no direct rate comes back `inverted: true` with the reciprocal —
disclosed, because a reciprocal carries no spread in that direction. A pair with
no rate at all says so rather than guessing: **handle that state**, do not render
`NaN`.

A held quote can be **live**, **consumed** (still readable, reported as used — a
confirmation page reloaded must not show "missing") or **expired** (404).

---

## 8. First paint: what every client fetches before drawing

None of these need an account, and none of them read the database — they are
configuration, so changing what a deployment supports is a property change
rather than a migration.

| Endpoint | Gives you |
|---|---|
| `GET /config/public` | Feature flags, **minimum app versions**, support contacts. A hand-assembled allow-list, never a filtered view of configuration |
| `GET /currencies` | Codes, names, symbols, **`minorUnits`**, in display order |
| `GET /countries` | **Two separate flags per country: `buys` and `ships`.** GB buys and does not ship — a buyer in London orders for delivery to Serekunda, and one "supported" boolean could not express that |
| `GET /locales` | Languages, each with **`rtl`** so you know to flip |

Cache them for the session. Check `minimumAppVersion` from `/config/public`
before anything else and show an upgrade screen if you are below it.

---

## 9. Pagination

```
?page=0&size=20
```

```jsonc
{ "content": [ … ], "page": 0, "size": 20,
  "totalElements": 137, "totalPages": 7, "first": true, "last": false }
```

Zero-indexed. Admin list endpoints add their own filters; see
[ADMIN-CONSOLE.md](ADMIN-CONSOLE.md).

---

## 10. Addresses, and why they carry a confidence

Not every address here has a street number. A market stall in Dakar with no
street name is most of the region, not a failure case — so an address saves
regardless and carries how well it is located.

| `geocodeConfidence` | Means | What the client should do |
|---|---|---|
| `EXACT` | Street-numbered and mapped | Nothing |
| `USER_CONFIRMED` | The user moved the pin themselves | Nothing. **A later edit to the street will not move it** |
| `CENTROID` | Right block, wrong unit | `needsPinConfirmation: true` → **show a map and ask** |
| `NONE` | On no street map at all | Saved anyway. Delivery prices from a scope fallback. Offer the pin |

```http
POST /me/addresses/{id}/confirm-pin
{ "latitude": 13.2714, "longitude": -16.6492 }
```

**Deleting an address has two outcomes**, and the response tells you which:

```jsonc
{ "retained": true,  … }   // an order names it — hidden from the book, kept for the record
{ "retained": false, … }   // nothing ever ordered against it — genuinely gone
```

A retained address is a 404 from `GET /me/addresses/{id}` afterwards. Do not
show it.

---

## 11. Notifications

```
GET  /notifications                          newest first
POST /notifications/{id}/read
POST /notifications/read-all
GET  /notifications/preferences              every event × every channel
PUT  /notifications/preferences              change some switches
POST /notifications/devices                  register for push
```

Three channels: `IN_APP`, `EMAIL`, `PUSH`. **There is no SMS**, deliberately —
a channel nothing sends on is a preference somebody switches on and then waits
for a message that never comes.

**`IN_APP` cannot be switched off for important events.** It is the *record*,
not a message: somebody who turned everything else off still needs somewhere to
find out what happened. Render that switch disabled with an explanation rather
than letting it fail.

---

## 12. Uploads

Two steps, always. **File bytes never pass through this API.**

```
POST /api/products/images/presign   →  { uploadUrl, storageKey, … }
PUT  <uploadUrl>                     →  the bytes, direct to object storage
POST /vendor/products/{id}/media     →  { storageKey }   confirm it
```

For KYC documents the same pattern applies, and `GET /kyc` **does not hand the
key back**: a URL to somebody's passport in a JSON response is a URL in a browser
cache.

If object storage is unconfigured, the presign endpoint returns **400 with an
explanation**. Everything else still works — handle it rather than blocking the
whole screen.

---

## 13. Deployment assumptions a client author must know

- **Same origin.** There is no CORS configuration. A browser client must be
  served from the same origin as the API, behind one reverse proxy. Native
  clients are unaffected. See [`../LIMITATIONS.md` §4.1](../LIMITATIONS.md).
- **HTTPS only.** The guest-cart cookie is `Secure` in any real deployment.
- **No public API documentation.** OpenAPI is off outside `dev`. Your reference
  is `e2e/endpoints.json` (all 432 operations with summaries) and
  `e2e/COLLECTION.md`.
- **Rate limiting is at the proxy**, not in the application, so limits are
  deployment-specific. Handle 429 anyway.

---

## 14. Getting a server to build against

```bash
mvn -o spring-boot:run -Dspring-boot.run.profiles=e2e
```

The whole application on `:8080`, in-memory database, development seed already
loaded. No MySQL, no Google key, no Cloudflare bucket, no credentials to obtain.
Twelve seconds.

Sign in as anyone in [`../TESTING.md` §2](../TESTING.md) — all passwords are
`Sujula123!`. The seed is a coherent world with a story attached to every row,
and it is the world every example in these documents refers to.

> **The `e2e` profile must never be deployed.** Every secret in it is a fixed,
> published test value.
