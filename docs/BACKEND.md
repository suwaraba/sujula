# Sujula — Backend Technical Document

> **Audience.** Engineers joining the project, reviewers auditing it, and anyone
> who has to decide whether a change is safe. It describes what the backend
> *is*, not what it should be; where something is unfinished it says so and
> points at `LIMITATIONS.md`.
>
> **Companion documents.** [`CODE-REVIEW.md`](CODE-REVIEW.md) (quality findings),
> [`SECURITY.md`](SECURITY.md) (written for non-specialists),
> [`TESTING.md`](TESTING.md) (manual test plan),
> [`LIMITATIONS.md`](LIMITATIONS.md), [`OPERATIONS.md`](OPERATIONS.md),
> [`frontend/`](frontend/README.md) (one document per client application).

---

## 1. What this application is

Sujula is a **multi-vendor marketplace with a diaspora-remittance shape**. The
person who pays and the person who receives are routinely different people, in
different countries, on different continents.

The canonical journey, which every design decision below traces back to:

1. A vendor in Banjul lists a phone priced in **GMD**; a vendor in Ziguinchor
   lists cloth priced in **XOF**.
2. A buyer in Madrid browses. They must see prices in **EUR**, and see the
   catalogue ranked against **Serrekunda** — where the parcel is going — not
   against Madrid.
3. The buyer pays once, in EUR, with a European payment method.
4. The parcel is delivered to the buyer's sister in Serrekunda, or left at a
   pickup counter near her.
5. Money reaches each vendor only once delivery is proven, in that vendor's own
   currency, at the rate frozen on the day the order was placed.

### 1.1 The five constitutional rules

These are stated in `CLAUDE.md` at the repository root and are treated as
correctness conditions, not preferences. Code that breaks one of them is wrong
even when its tests pass.

| | Rule | What it forbids |
|---|---|---|
| **C1** | Two independent locations | Deriving a *delivery* answer from the payer's location (shipping cost, serviceability, pickup points, catalogue ranking), or a *payment* answer from the delivery location (payment methods, charged currency). |
| **C2** | Two currencies per order | Recomputing an FX rate after the fact; rounding to two decimal places for a currency that has none. Listing currency = payout currency = the vendor's; display currency = the buyer's. |
| **C3** | One payment, many vendors | Treating an order as a single shippable, cancellable, refundable unit. Sub-orders ship, cancel, refund and pay out independently. |
| **C4** | Custody is a chain, not a status | A settable status field. Every transfer is a verified event with proof; status is a *consequence*. |
| **C5** | The recipient may not have an account | Any delivery-verification step that requires the recipient to log in, install something, or click an email link. |

How each rule is realised in code is described in §6.

---

## 2. Shape of the system at a glance

```
                    ┌──────────────────────────────────────────────┐
  Browsers,         │  Spring Security filter chain                │
  mobile apps,      │   ├─ CSRF (cookie pattern, SPA handler)      │
  drivers' phones,  │   ├─ JwtAuthenticationFilter (bearer)        │
  a payment         │   └─ authorizeHttpRequests path rules        │
  provider ────────►│                                              │
                    │  @RestController × 47  (DTO in, DTO out)     │
                    │            │                                 │
                    │  @Service  × 47 impls (+ interfaces)         │
                    │    money · delivery · custody · catalogue    │
                    │    checkout · after-sales · admin · auth     │
                    │            │                                 │
                    │  Spring Data JPA repositories × 84           │
                    │    ownership pushed into the query           │
                    │            │                                 │
                    └────────────┼─────────────────────────────────┘
                                 ▼
                          MySQL 8 (prod) / H2 in MySQL mode (test, e2e)
                                 ▲
        Background workers ──────┘   Outbound: SMTP, Cloudflare R2,
        (7 @Scheduled jobs)          Google Geocoding, payment provider
```

### 2.1 Numbers

| Thing | Count |
|---|---|
| HTTP operations served | **432** (captured from a live server into `e2e/endpoints.json`) |
| `@RestController` classes | 47 (+2 helper components) |
| JPA entities | 87 (one `@Embeddable`: `FxSnapshot`) |
| Enum constants files | 69 |
| Spring Data repositories | 84, carrying **332** `@Query` declarations |
| Service implementations | 47 |
| DTO files | 110 (request + response, mostly nested records) |
| Java source, main | ~87,000 lines |
| Java source, test | ~26,000 lines / **1,233 test methods** across 90 classes |
| Scheduled background jobs | 7 |

### 2.2 Technology

| Layer | Choice | Note |
|---|---|---|
| Runtime | Java 21 (`-Djava.version=21`); the POM also builds on JDK 26 | |
| Framework | Spring Boot **4.1.0**, Spring Framework 7, Hibernate ORM 7.4 | |
| Packaging | **WAR** (`spring-boot-starter-tomcat` is `provided`) | deployable into an external Tomcat or run standalone |
| Database | MySQL 8 via `mysql-connector-j`; H2 in MySQL mode for tests and the `e2e` profile | dialect is **pinned** to `MySQLDialect`, never inferred |
| Security | Spring Security 6/7, `@EnableMethodSecurity`, BCrypt, Nimbus JOSE for JWT | |
| Docs | springdoc-openapi 3.1.1 — **off by default**, on under `dev` | |
| Observability | Spring Boot Actuator + Micrometer/Prometheus; only `health`, `info`, `prometheus` exposed | |
| Object storage | AWS SDK v2 S3 client pointed at Cloudflare R2 | |
| PDF | OpenPDF (LGPL/MPL fork of iText 4 — iText 5+ is AGPL and would oblige publishing this source) | |
| Spreadsheets | Apache POI for `.xlsx`; CSV parsed by hand | |
| Barcodes | ZXing, for the QR on a parcel label | |
| Geo | Google Maps Services (geocoding), MaxMind GeoIP2 (country from IP) | both optional — see §9 |

---

## 3. Layering, and the rules that hold across it

The package root is `com.sujula`.

```
com.sujula
├── SujulaApplication.java
├── config/          SecurityConfig, JwtAuthenticationFilter, OpenApiConfig,
│                    R2ClientConfig, AdminBootstrap, E2eSeedLoader,
│                    UserSecurityExpressions
├── controller/      47 controllers + AuthenticatedCaller, StaffCaller
├── service/         interfaces at the top of each package, impls in impl/
│   ├── admin/ aftersales/ analytics/ auth/ buyerorder/ cart/ catalogue/
│   ├── checkout/ delivery/ driver/ fulfilment/ geo/ idempotency/ inventory/
│   ├── invoice/ money/ notification/ payment/ pickup/ platform/ promotion/
│   ├── recipient/ reference/ security/ shipment/ store/ vendorcatalogue/
│   └── webhook/
├── repository/      84 Spring Data interfaces, mirroring the model packages
├── model/           87 entities + model/constant/ (69 enums)
├── dto/             dto/request/**, dto/response/**
├── exceptions/      BadRequestException, ResourceNotFoundException,
│                    GeocodingException, GlobalExceptionHandler
└── util/
```

### 3.1 Four conventions that are enforced, not merely preferred

**1. Ownership checks belong *in the query*.**
The repository method names carry the owner: `findLiveByIdAndUserId`,
`findByIdAndVendorId`, `findByIdAndBuyerId`, `findByIdAndDriverId`,
`findByIdAndOwnerId`, `findByReferenceAndVendorId`. The alternative —
`findById` followed by an `equals` comparison — is the version that ships with
the comparison missing. A handful of call sites still use the two-step form;
they are listed in [`CODE-REVIEW.md` §4](CODE-REVIEW.md).

**2. A not-found is the right answer for somebody else's row.**
`403 Forbidden` confirms the row exists, and on this platform the existence of
a row is itself worth withholding (an address, a delivery context, an FX quote
held by a guest). Reading another buyer's delivery context returns **404**, and
reading an *expired* one also returns 404 rather than 410 — an expired bearer
credential and one that never existed must be indistinguishable.

**3. Controllers bind DTOs and nothing else.**
Entity↔DTO conversion happens in the service layer. Two controllers still
break this and are named in the review document.

**4. Money arithmetic goes through `CurrencyCatalogue.round`.**
It knows each currency's real scale. This is the convention with the largest
outstanding gap — see §6.2 and `LIMITATIONS.md`.

### 3.2 Error handling

`GlobalExceptionHandler` (`@RestControllerAdvice`) turns every escaping
exception into one JSON shape. Two rules run through it:

- **Nothing internal reaches the client.** No SQL, no constraint names, no
  stack traces, no class names — those describe the schema to anyone who can
  provoke an error.
- **Nothing falls through to the container's default error page**, which
  answers in HTML and says more than the application does.

Notable mappings:

| Exception | Status | Note |
|---|---|---|
| `MethodArgumentNotValidException`, `ConstraintViolationException` | 400 | field-keyed map |
| `BadRequestException` | 400 | business-rule refusals, written as sentences a user can act on |
| `ResourceNotFoundException` | 404 | also the answer for somebody else's row |
| `BadCredentialsException` | 401 | message is **fixed**, so the endpoint cannot be used to test which addresses hold accounts |
| `DisabledException` / `LockedException` | 403 | credentials were right, the account is not usable |
| `ObjectOptimisticLockingFailureException` | 409 | concurrent edit, e.g. two people counting one shelf |
| `AccessDeniedException` | 403 |  |

---

## 4. HTTP surface

432 operations. The full machine-readable inventory is
`e2e/endpoints.json` (captured from a running server, with summaries); the
human-readable walkthrough is `e2e/COLLECTION.md`.

### 4.1 Two generations of API live side by side

This is the single most important structural fact about the surface, and a
client author must know it before writing a line.

| | **Current surface** (flat paths) | **Legacy surface** (`/api/**`) |
|---|---|---|
| Identity | `/auth/*`, `/me/*` | `/api/users/*` |
| Addresses | `/me/addresses/*` | `/api/user/addresses/*` |
| Catalogue | `/products`, `/categories`, `/stores`, `/search`, `/brands` | `/api/products/*`, `/api/categories/*` |
| Basket | `/carts/{cartToken}/*` (token-addressed) | `/api/cart/*` (cookie-addressed) |
| Checkout | `/checkout`, `/checkout/{orderId}/*` | `/api/user/orders/checkout-cart`, `/api/guest/orders` |
| Buyer orders | `/orders/*` | `/api/user/orders/*` |
| Vendor | `/vendor/**` (stores, products, inventory, orders, money, analytics) | `/api/vendors/*` |
| Admin | `/admin/**` (95 operations) | `/api/admin/*` (16 operations) |
| FX | `/currencies/*` | `/api/exchange-rates/*` |

The current surface is the one that carries the five rules: it is the one with
class-level Javadoc stating its ownership model, the one the end-to-end
collection exercises, and the one the constitution's invariants were built
into. The legacy surface predates it, still answers, and is **where every
finding in `CODE-REVIEW.md` §2 and §3 lives**, including one exploitable
authorization defect.

**Client authors: use the current surface.** Treat `/api/**` as deprecated.
See [`frontend/README.md`](frontend/README.md).

### 4.2 Surface by prefix

| Prefix | Ops | Who calls it |
|---|---|---|
| `/admin/**` | 95 | Admin console (ADMIN, SUPPORT) |
| `/vendor/**` | 66 | Vendor console (VENDOR) |
| `/driver/**` | 19 | Driver app (DELIVERY) |
| `/auth/*` + `/me/*` | 31 | Every signed-in client |
| `/carts/*` + `/checkout/*` | 14 | Buyer storefront, guests included |
| `/orders/*` | 8 | Buyer storefront |
| `/products`, `/categories`, `/stores`, `/search`, `/brands` | 13 | Buyer storefront, anonymous |
| `/returns`, `/disputes`, `/messages`, `/reviews` | 21 | Buyer and vendor, same rows, two ownership tests |
| `/pickup/**` | 10 | Pickup counter console (PICKUP_OPERATOR) |
| `/pickup-points` | 2 | Anonymous — a shopper chooses a counter before signing in |
| `/parcels/{trackingCode}/*`, `/track/{code}` | 6 | **The recipient, who has no account** |
| `/currencies`, `/countries`, `/locales`, `/config/public` | 7 | Every client, on first paint |
| `/delivery/*`, `/delivery-contexts/*`, `/geo/*` | 7 | Buyer storefront, anonymous |
| `/notifications/*` | 7 | Every signed-in client |
| `/invoices/{token}` | 1 | Anyone holding a signed link |
| `/webhooks/**`, `/api/payments/callback` | 5 | Machines |
| `/health/*` | 2 | Orchestrator |
| `/api/**` | 116 | Legacy |

---

## 5. Authentication, sessions and authorization

### 5.1 Two credentials, deliberately different kinds of thing

`TokenService` mints and verifies both.

| | **Access token** | **Refresh token** |
|---|---|---|
| Form | Signed JWT (HMAC-SHA256, Nimbus) | Opaque random string |
| Stored server-side | **No** | Yes, as a SHA-256 **hash** only |
| Lifetime | `sujula.auth.jwt.access-token-ttl`, default **10 minutes** | `refresh-token-ttl`, default **30 days** |
| Revocable | Not directly — the TTL is the blast radius | Immediately, and rotated on every use |
| Carries | user id, session id, issuer, expiry — **nothing sensitive**; a JWT is signed, not encrypted | no meaning outside the database |

Sessions per account are capped at `max-sessions-per-user` (default 10); past
that the oldest is dropped.

**Replay is treated as theft.** `SessionReplayGuard`: presenting a refresh
token the session has already rotated past returns 401 *and revokes the
session*, recording `SessionRevocationReason.TOKEN_REPLAY`. An *expired* token
returns 401 and revokes nothing — an expired token is not evidence of anything.

### 5.2 `JwtAuthenticationFilter`

Runs before `UsernamePasswordAuthenticationFilter`. Three properties worth
knowing:

1. **One database query per authenticated request, and only one.** The filter
   has to load the user anyway, so the session-liveness check rides along in
   the same statement (`sessions.findAuthenticatedUser(sessionId, userId)`):
   the user comes back only if the session is still live and the account is
   neither disabled nor blocked. A remote sign-out therefore takes effect on
   the device's *next request*, not whenever its access token happens to lapse.
2. **It rejects nothing.** A missing or bad token leaves the request
   unauthenticated and passes it along; the authorization rules decide whether
   the endpoint needed authentication. Answering 401 in the filter would break
   every deliberately public route.
3. It publishes the verified session id as a request attribute
   (`sujula.sessionId`) so nothing downstream re-verifies the same HMAC.

An existing authentication wins, so a session login and a bearer token coexist
rather than compete; both leave the same `User` on the principal.

### 5.3 Roles

`UserRole`: `CUSTOMER`, `VENDOR`, `DELIVERY`, `PICKUP_OPERATOR`, `SUPPORT`,
`ADMIN`. Two of them are staff (`isStaff()`); only `ADMIN` `canDecide()`.

`StaffCaller` enforces the admin split in code rather than by annotation:
`staff(auth)` for reads, `decider(auth)` for every write. The rationale is
stated in its Javadoc — "the annotation that gets forgotten is on the one
endpoint that mattered". A non-staff caller gets `AccessDeniedException` with
the message *"Authentication is required"*: the admin surface does not confirm
its own shape to somebody who should not be on it.

`PermissionResolver` backs `GET /me/permissions`. It is **advisory** — it
describes what the server will allow so a client can render honestly (a vendor
whose application is pending has the VENDOR role and cannot list a product). It
grants nothing; every endpoint still enforces.

### 5.4 Step-up authentication

`StepUpVerifier` makes a caller prove, *again and now*, that they are who the
token says. A bearer token answers "was somebody holding this credential an
hour ago", which is the wrong question for operations that redirect money.

Used on `PUT /vendor/stores/{storeId}/bank-account`. It demands the password
**and** the authenticator code when the account has MFA — sending the password
alone on an MFA account is refused, because a step-up that is easier to pass
than the sign-in which reached it is not a step-up.

### 5.5 Multi-factor

`TotpService` implements RFC 6238 by hand (~40 lines: HMAC plus a truncation)
rather than pulling a dependency, so it can be proved against the RFC's
published test vectors — `TotpServiceTest` checks every SHA-1 vector. Secrets
are Base32 because that is what authenticator apps read. Recovery codes are
stored hashed, single-use, and a *spent* code is kept rather than deleted so
reuse is refused rather than silently accepted.

### 5.6 Login escalation

`LoginAttemptTracker` counts consecutive failures and walks the account up a
ladder: **warn** the owner → **email a reset link** → **lock**. Thresholds are
per role (`sujula.security.login.*`); admins climb faster and get no warning
step. Locks expire on their own (`lockout-duration`, default 15m) so support
is not in the loop for a mistyped password and nobody can keep an admin out
indefinitely.

The tracker is a **separate bean with its own transactions**, on purpose: a
failed sign-in ends in an exception, and a counter incremented in the same
transaction would be rolled back by the very failure it was counting.

### 5.7 CSRF

Cookie-based (`CookieCsrfTokenRepository.withHttpOnlyFalse()`), with a custom
`CsrfTokenRequestAttributeHandler` whose request-attribute name is set to
`null` so the **raw** cookie value validates. The default handler expects an
XOR-masked token in the header while a browser only has the raw cookie value —
the mismatch refused every POST, making login, registration and checkout
unreachable from a browser. The masking guards against BREACH on a token
rendered into an HTML body, which does not apply to a JSON API.

Exempt paths, each for a stated reason: `/webhooks/**` and
`/api/payments/callback` (machines with no token, authenticated by signature
instead), `/auth/**` and `/me/**` (bearer-token surface — an `Authorization`
header is never attached by the browser on its own, so there is nothing for a
forged request to ride, and requiring a cookie-delivered token would make the
API unusable from a native client), `/geo`, `/delivery`, `/delivery-contexts`,
`/currencies`, `/carts`, `/checkout` (read-only or guest-reachable; these take
POST because a destination is an address, too long for a query string and not
something to leave in access logs).

---

## 6. The five rules in code

### 6.1 C1 — Two independent locations

**Payer context** and **delivery context** are separate concepts with separate
plumbing.

- `DeliveryContext` is a first-class entity with an **unguessable id** (256
  bits of base64url from a secure random). It is the destination that the cart,
  the shipping quote and checkout all price against, so the three cannot drift
  apart and quote three different figures.
- Catalogue reads take the **destination**: `deliverableTo` (a delivery-context
  id) or `deliveryLat`/`deliveryLng`/`deliveryCountry`. There is deliberately
  no `userLat`. Products that can reach the given country sort ahead of those
  that cannot; products near the given point sort ahead of those far from it.
  The response **echoes back which location it used**, under `delivery`.
- Currency comes from the **payer** — browser locale and IP
  (`BrowsingContextResolver`, `GeoLookupService`) or an explicit `currency`
  parameter.
- `/delivery/serviceability` and `/delivery/quote` take an explicit
  `origin`/`destination` pair; neither reads the caller's location.

`C1SeparationTest` proves this rather than asserting it in a comment: a buyer
in Madrid, paying in euro, sending a phone to Serrekunda, with each test a way
of getting the two crossed. Each failure it guards is a real product failure —
currency from the delivery country quotes a Spanish cardholder in dalasi;
ranking from the payer's IP buries the phone two miles from the recipient.

Address quality is modelled explicitly (`GeocodeConfidence`: `EXACT`,
`CENTROID`, `USER_CONFIRMED`, `NONE`), because a market stall with no street
number is most of the region rather than a failure case. An address with `NONE`
saves anyway and prices delivery from a scope fallback until somebody drops a
pin via `POST /me/addresses/{id}/confirm-pin`.

### 6.2 C2 — Two currencies per order

**`CurrencyCatalogue`** is the authority on what this deployment prices in and
how many decimal places each currency has. Ten currencies ship configured;
**XOF has zero minor units** — there is no centime of CFA in circulation, so
`1250.50 XOF` is not an untidy figure, it is an amount that does not exist.

```java
catalogue.round(amount, "XOF")   // scale 0, HALF_UP
catalogue.round(amount, "GMD")   // scale 2
catalogue.smallestUnit("XOF")    // 1
```

`HALF_UP` rather than `HALF_EVEN`: banker's rounding is right for summing many
figures without bias and wrong for a single price a person is about to be
charged, where the only defensible behaviour is what they would get doing it by
hand.

> ⚠️ **`round` is not yet used everywhere.** Order totals, delivery legs and
> payouts still scale to two places in their own code — correct for dalasi,
> sterling and euro, wrong for CFA. The class states this in its own Javadoc.
> See `LIMITATIONS.md` §2.1.

**`FxSnapshot`** (`@Embeddable`) is the C2 mechanism proper: the rate a figure
was converted at *and the moment that rate was taken*, frozen onto the row it
explains.

- Convention, fixed and stated once because getting it backwards is silent:
  **`display = native × rate`** — units of the buyer's currency per one unit of
  the vendor's.
- `precision = 18, scale = 8`. A rate is not money and must not be rounded like
  money; rounding it to the currency's own scale would flatten an XOF rate to
  zero places and the error would multiply across every line.
- `rateAt` is when the rate was *published*, not when the row was written — the
  difference matters when a dispute turns on which day's rate applied.
- Embedded rather than a table, because a snapshot has no identity apart from
  the row it explains and a join to answer "what rate was this" would be paid on
  every settlement report.

**Held quotes.** `POST /currencies/quote` holds a rate for 15 minutes and
returns an id. `FxQuote` rows carry their own state: live, consumed (still
readable — a client reloading a confirmation page should see it reported as
used, not missing), or expired (404, not 410). Changing the underlying
`exchange_rates` row does not move a held quote; a quote that re-read the table
would not be a quote.

Two rate endpoints make different promises and are named to keep them apart:
`/currencies/rates` is **indicative** (what the pair was last published at, with
its age, committing to nothing); `/currencies/quote` **commits**. A missing
direct rate comes back `inverted: true` with the reciprocal, disclosed, because
a reciprocal carries no spread in that direction. A pair with no rate at all
says so rather than guessing.

`FxSpread` and `FxSpreadRegistry` hold the platform's margin, effective from a
moment, with history — `GET /admin/fx/spread` returns every spread ever set and
which one is live.

### 6.3 C3 — One payment, many vendors

`Order` → many `VendorOrder`. The sub-order is the unit of everything after
payment.

- `POST /orders/{orderId}/vendor-orders/{vendorOrderId}/cancel` — one seller's
  items. The other seller's line is untouched.
- `POST /admin/payments/{paymentId}/refund` refunds **one seller's part** of a
  payment, not a proportion of the order.
- Payout is per vendor, in that vendor's own currency, frozen at the order's
  rate.
- `AfterSalesController` states it directly: there is no endpoint that returns
  or disputes "an order", because an order is not a thing anybody can be in
  dispute about.

The seeded order 1401 is the worked example: Oliver in London pays once in GBP
for goods from a GMD vendor and an XOF vendor. `GET /vendor/orders` as each
vendor returns that vendor's slice only, priced only in that vendor's own
currency — neither response contains GBP, London, or Oliver.

`MoneyLedger` / `VendorLedgerEntry` is the accounting substrate. **Nothing
stores a balance**; `GET /vendor/balance` sums ledger entries, which is the only
thing that makes a balance checkable. A refund appears as four rows — sale,
commission, refund, commission returned — that come to exactly nothing; netting
them into one would hide the thing a seller opens a refund to check. A failed
payout comes back as a **reversal** rather than being deleted, so the statement
explains the gap instead of hiding it.

### 6.4 C4 — Custody is a chain

**`CustodyChain` is the only thing that can move a parcel, and the only thing
that sets its status.** `Shipment` has no public status setter. Put the other
way round: if this class were deleted, nothing could change where a parcel is.

`append(shipment, event)` is the single door. It:

1. **Checks the clock.** An event dated more than 5 minutes ahead of the server
   is refused (a device clock is something its holder can set); one more than 14
   days old is refused as a mistake rather than a late upload. Everything
   between is believed — a driver out of signal for six hours is Tuesday here,
   not an attack.
2. **Checks the sequence** against the events that have already happened, not
   against a status column — the column is the thing being derived.
3. **Requires proof**: the code the receiving party presented, burned in the
   same transaction so it cannot open a second handover.
4. **Re-derives** status, failed-attempt count and timestamps from the *whole*
   chain rather than patching them. `rederive` is public so a repair job or a
   test can re-run it over existing rows and assert it changes nothing.

`deriveStatus` reads the chain **backwards**: the most recent event that moved
the parcel decides, because the status answers "who has it now", not "what is
the furthest it got".

Event types: `ARRIVED_AT_ORIGIN`, `COLLECTED`, `DEPOSITED`, `REDISPATCHED`,
`RELEASED`, `TRANSFERRED`, `FAILED_ATTEMPT`, `RETURNED`.
Shipment statuses (all derived): `AWAITING_COLLECTION`, `DRIVER_OFFERED`,
`DRIVER_ASSIGNED`, `AT_ORIGIN`, `IN_TRANSIT`, `AT_PICKUP_POINT`,
`OUT_FOR_DELIVERY`, `DELIVERED`, `ATTEMPT_FAILED`, `RETURNED`, `CANCELLED`.

**Geofencing is evidence, not a gate.** `Geofence` records how far from the
expected position an event was captured and sets `withinGeofence`. An event
recorded 4.3 km from the shop is *saved and flagged*, not refused — the parcel
may genuinely have changed hands and refusing would strand it, but the chain
says plainly that the position does not corroborate the handover.

**Offline capture.** `POST /driver/custody-events/sync` takes a batch,
deduplicated by the id the driver's app gave each event, so a phone that
uploads, loses signal before the reply, and uploads again records each event
once.

The one break-glass route, `POST /admin/shipments/{id}/override-handoff`, is
named for what it is — recording a handover that could not be proven the
ordinary way — and lands in the audit log.

### 6.5 C5 — The recipient may not have an account

Six endpoints serve somebody with a phone number and nothing else:

```
GET  /track/{trackingCode}                       a stranger-safe page
GET  /parcels/{trackingCode}                     the recipient's own view
POST /parcels/{trackingCode}/request-code
POST /parcels/{trackingCode}/choose-pickup-point
POST /parcels/{trackingCode}/reschedule
POST /parcels/{trackingCode}/authorise-safe-drop
```

All open at the filter chain, on purpose: requiring a sign-in here would hand
the decision to whoever has an account, which is exactly the wrong person — the
recipient is the only one who knows whether she will be home on Thursday.

**Two credentials, not a session.** The unguessable tracking code in the path
reads a page carrying a first name and a town; a six-digit code in the body
authorises the three changes.

**The public page is built to be worth nothing to a stranger holding it.**
`/track/{code}` carries a city, parcel counts, and *fixed phrases* rather than
the driver's free text. Where an internal row says "Assigned to Ebrima Bojang",
the public page says "A driver has been assigned." That substitution is the
whole of what stands between a driver's notes and somebody who was forwarded the
SMS.

**The driver never sees the release code.** `POST
/driver/shipments/{id}/request-recipient-code` sends it; a driver who could read
it could mark a parcel delivered without meeting anybody. Delivery then needs
the code **and** a position **and** a photograph — this is the link somebody
would forge if any one of them were enough alone.

> ⚠️ **The design target is not fully reached.** `NotificationChannel` has
> `IN_APP`, `EMAIL`, `PUSH` and **no SMS**, deliberately: a channel nothing
> sends on is a preference somebody switches on and then waits for a message
> that never comes. Today the release code travels by **email to the buyer**,
> who passes the six digits to the recipient. That works for the seeded
> scenario (Fatou in Madrid tells her sister) but it is not "an SMS code she
> reads out to the driver". No SMS provider is integrated; phone-verification
> codes are logged at INFO for a developer to read. See `LIMITATIONS.md` §2.2.

---

## 7. Domain walkthrough

### 7.1 Catalogue and listing lifecycle

`ProductStatus`: `DRAFT → IN_REVIEW → APPROVED → PUBLISHED` (plus
`SUSPENDED`, `ARCHIVED`). The last step is the **seller's**: being allowed to
sell and choosing to are different decisions, and approval arriving overnight
should not put a listing live before the seller has set the stock.

Every listing carries an **`approved_content_hash`** — a digest of exactly what
a moderator looked at (name, text, price, category, brand, condition). Change
any of those and the hash stops matching, so the listing returns to `IN_REVIEW`
and goes inactive. Change the **stock** instead and nothing happens: a seller
who had to re-enter a queue to restock would stop using the queue.

**One invariant worth checking by hand:** `products.active` is true **if and
only if** `products.status = PUBLISHED`. Every public catalogue query filters on
`active`; the ladder lives in `status`; `ProductLifecycle` is the only thing
allowed to write the first. Two fields describing one fact drift the moment
something sets one without the other, and the way they drift here is a listing
moderation pulled that carries on selling.

Deletion is conditional: a listing with order lines pointing at it can only be
**archived** (a buyer's receipt, invoice and review must keep resolving years
from now); one nobody ever ordered is genuinely deleted. The *order lines*
decide, not `total_sold` — a cancelled order leaves that at zero while a line
still points at the row.

**Translations** (`ProductTranslation`) carry a machine-translated flag and the
buyer is told. Nobody in the chain can pick the cloth up and look at it, so the
listing text is the whole of what they get, and a machine's version of "six
yards of wax print, cut to order" is usually fine and occasionally nonsense.

**Bulk import** is queued and drained by `CatalogueJobWorker`: a seller
uploading four hundred rows over a mobile connection loses the response long
before the work finishes, so a synchronous import would write half a catalogue
with nobody able to say which half. Row errors name the row as the seller's own
spreadsheet numbers it (header is row 1) and quote their text back — *"there is
no category called 'Phonez'"* rather than "invalid category". Every imported
row is a **DRAFT**; an import that could publish would be the way past
moderation. Job references are random, not sequential, because a countable job
id hands out other sellers' products, prices and SKUs.

### 7.2 Inventory

Stock is a **ledger**, not a number. `StockMovement` rows sum to the figure;
`0 + 10 − 3 − 4 = 3` is the whole design. A count that can be assigned directly
is a count nobody can explain.

Orders reserve and release through the **same ledger** as manual corrections —
an audit showing the seller's edits and quietly omitting the sales would be
wrong in exactly the case somebody opens it for. A `SALE` has no order number
(stock is reserved before the order exists, which is the right way round) and no
person; a `RETURN` has both.

Two ways to change stock, and only one needs a version:
`{"delta": -2}` is `-2` whoever else is writing; `{"setTo": 12, "version": 0}`
carries the version it was read at, so two people counting the same shelf and
saving 10 and 12 cannot leave whichever committed last with the other simply
wrong and nothing to say so. The second call is refused with 409.

**Handsets** (`ImeiUnit`) are the product a count cannot describe: most sold
here are second-hand, and two units of the same model are not interchangeable
when one was opened once and the other has a scratched screen. IMEIs are Luhn
checked. The units are the authority and the count follows them — a variant with
IMEI units refuses a typed stock figure outright. A seller cannot mark a unit
`SOLD` (the order does that, so record and sale cannot disagree) and cannot
touch `BLOCKED` in either direction (setting it would flag a rival's stock;
clearing it would launder a stolen handset).

### 7.3 Basket, quote, checkout

```
POST /carts                          open one, get a 256-bit token
PUT  /carts/{t}/delivery-context     WHERE the goods go   ← C1
PUT  /carts/{t}/currency             WHAT the prices read as ← C1
POST /carts/{t}/items                add
POST /carts/{t}/quote                price it and HOLD the figures
POST /checkout                       place the order
GET  /checkout/{orderId}/status      has the payment settled
```

The token is the credential and is 256 bits for that reason: a cart holds a
destination, a list of what somebody is buying and for whom, and what they are
about to spend. Guests are first-class — on this marketplace most baskets are
filled before anybody signs in.

The two `PUT`s are **deliberately separate and must stay so**: one says where
the goods go and re-prices shipping, the other says what the prices read as.

**What the quote guarantees today, and what it does not.** `CartQuote` freezes
the figures the buyer agreed to. `CheckoutServiceImpl` then re-prices the cart
through the same order-assembly path every other order uses (a second assembly
would eventually disagree with the first) and **reconciles** the result against
the quote. If they differ, the order is rejected and the transaction rolls back.

> That means a buyer is **never charged a figure they did not agree to**. It
> does *not* yet mean they are charged the quoted figure when the market moves
> underneath them — today they are asked to re-quote. Honouring the frozen rate
> through assembly is the acknowledged next step; the frozen lines are already
> on the quote for it. A reconciliation that refuses is safe; a freeze that is
> half-applied is not. See `LIMITATIONS.md` §2.3.

Order creation locks each product row before decrementing, so two shoppers
racing for the last unit cannot both succeed.

### 7.4 Payments

`PaymentMethod` / `PaymentChannel` cover card, PayPal, mobile money, bank
transfer and cash on delivery. Bank transfer is offered **only** once bank name,
account name and account number are all configured.

`PaymentGateway` is the abstraction. **`MockPaymentGateway`** is the only
implementation shipped: it marks orders PAID without any money moving, is
`@ConditionalOnProperty(sujula.payment.mock.enabled=true)`, **refuses to start
under a `prod` profile**, logs a banner at startup, and `application-prod.properties`
sets the flag false as a second lock.

`/api/payments/callback` is authenticated by a shared secret
(`sujula.payment.callback-secret`); blank disables the endpoint outright.

The rule the provider does not get to break: **a provider does not decide what
an order cost.** A callback confirms settlement against the order's own figures;
it cannot restate them.

### 7.5 Webhooks

`POST /webhooks/**` is open at the filter chain and defended inside the handler
by `WebhookSignature`: **HMAC-SHA256 over `<timestamp>.<body>`**.

Three rules, each of which has cost somebody a breach somewhere:

1. **Constant-time comparison.** `equals` on two hex strings returns as soon as
   they differ, and the time it takes leaks how much of a guess was right.
2. **Sign the raw bytes**, not a re-serialised object — the provider signed
   their own formatting, and any parse-and-print changes whitespace.
3. **No secret means no.** An unconfigured provider is refused rather than
   trusted, because the default state of configuration is absent.

The timestamp being *inside* the signature is what stops a captured request
being replayed tomorrow; `sujula.webhooks.tolerance` (5m) is the replay window.

Verdicts (`NO_SECRET`, `NO_SIGNATURE`, `NO_TIMESTAMP`, `BAD_TIMESTAMP`,
`STALE`, `MISMATCH`) all answer the caller with the single word **"Rejected"** —
telling a prober which one they got right tells them how to make progress — while
the detail is written to the `webhook_events` row.

Intake **stores before acting**: the event is persisted `RECEIVED`, and
`WebhookWorker` drains it with retries (`max-attempts`, default 5).

### 7.6 Fulfilment and the handover codes

```
POST /vendor/orders/{id}/accept            or /reject, with a reason
POST /vendor/orders/{id}/lines/{lineId}/assign-imei
POST /vendor/orders/{id}/ready             packed — mints the collection code
GET  /vendor/orders/{id}/handoff-code      no-store, never logged
GET  /vendor/orders/{id}/label             A6 PDF with a QR
```

`POST .../ready` is **idempotent in the way that matters**: called on an
already-packed slice it says so and leaves the collection code **unchanged** —
a driver is already on the way with the first code, and a seller tapping the
button twice on a patchy connection must not invalidate it.

What is on the parcel label is chosen by what is *not* on it: no street, no
price, no contents, and **not the collection code**. A code printed on the box
it protects protects nothing. The QR resolves the address for whoever scans it,
via `SignedTokens`.

What a seller learns about the people at either end of an order is a name, a
town, a country and three digits of a phone. No street — the platform routes the
parcel. No payer at all.

### 7.7 Pickup points

Public reads (`GET /pickup-points`, `/pickup-points/{id}`) need no account,
because a shopper chooses where to collect before signing in and often before
having an account. What they carry is chosen to be safe open: an address,
opening hours, and **how full the counter is as a band** rather than a count —
that a shop holds a hundred and ninety parcels is a fact about somebody's
business. No operator name, no contact email, no earnings.

A **closed** counter does not come back in a nearby search. Sending somebody to
a shuttered counter is worse than showing nothing.

`POST /pickup/points/{id}/parcels/{shipmentId}/release` needs the recipient's
code **and** the name of whoever is collecting. A code alone would let anybody
who overheard it take the parcel; a name alone anybody who read the label. A
brother collecting for his sister is allowed, and written down.

`stored_parcels` is **recounted** from the shipments actually held, not
asserted — a count that could drift would drift into accepting parcels there is
no room for.

### 7.8 After-sales

Returns, disputes, messaging and reviews share one surface with **no role
check**, deliberately: a buyer is a seller on somebody else's platform and both
write to the same rows. The two sides are found by two different ownership
tests.

`ContactDetailFilter` strips phone numbers and email addresses out of thread
messages — the platform routes the parcel, and a channel that let either side
take the conversation off it takes the protection with it.

Disputes freeze the relevant money; withdrawing one lifts the freeze.
`GET /admin/disputes` sorts by **deadline** rather than age.

### 7.9 Admin

95 operations across users, moderation, operations/logistics, money, disputes,
comms and platform. Two rules shape it:

- **Support reads; ADMIN decides.** Enforced by `StaffCaller.decider()`.
- **Never your own.** `POST /admin/payouts/batches/{id}/approve` refuses a
  batch the caller assembled.

`AuditLog` is append-only: there is no endpoint to edit or remove an entry.
`POST /admin/users/{id}/impersonate` opens a short session as somebody else and
is audited.

`JobRegistry` / `ManagedJob` make the background workers visible and runnable:
`GET /admin/jobs`, `GET /admin/jobs/history` (every pass, **including the ones
that found nothing** — a job that runs is a job that leaves a row), and
`POST /admin/jobs/{jobName}/run`.

### 7.10 Delivery pricing

Each product is priced as **its own delivery leg**:

```
base + per-km beyond included-km + per-kg beyond included-kg
      × scope multiplier × mode multiplier,  clamped to [min, max]
```

Configured under `sujula.delivery.pricing.*` in a single currency (GMD by
default) and converted into the buyer's display currency when quoted.
`DeliveryScope` scales by distance class; `DeliveryMode` scales by how the buyer
receives it (`HOME_DELIVERY` 1.00, `PICKUP_POINT` 0.75, `VENDOR_PICKUP` 0.00).
When neither end has usable coordinates and geocoding is unavailable, a
per-scope `fallback-km` is used.

Admin rate cards (`DeliveryRateCard`, effective from a date, with
`GET /admin/rate-cards/preview`) and zones (`DeliveryZone`, GeoJSON polygons
round a *destination*) sit above this.

A quote into a currency with no published rate returns **`complete: false`**
rather than a number. Quoting the rate card's own currency under someone else's
symbol is how a buyer is charged fifty pounds for a fifty-dalasi delivery.

### 7.11 Idempotency

`IdempotencyService` + `IdempotencyRecord`. A request that times out on a slow
connection is indistinguishable, from the client's side, from one that never
arrived — so clients retry, and without a key the shopper ends up with the
address twice.

Send `Idempotency-Key: <key>` and a repeat returns the **recorded response**
without saving anything. The same key with a **different body** is *refused*
rather than served: a key reused for different content is not a retry, and
replaying the first answer would silently discard the second request.

### 7.12 Background workers

| Worker | Cadence (property) | Does |
|---|---|---|
| `WebhookWorker` | 10s (`sujula.webhooks.interval-ms`) | drains stored webhook events, retries to `max-attempts` |
| `CatalogueJobWorker` | 30s (`sujula.catalogue.jobs.interval-ms`) | bulk imports and exports |
| `ReportExportWorker` | 45s (`sujula.money.exports.interval-ms`) | finance file exports |
| `AccountDataWorker` | 60s (`sujula.auth.data-requests.interval-ms`) | GDPR-style export and erasure requests |
| `PlatformSweeper` | 15m (`sujula.platform.sweeper.interval-ms`) | deadline sweeps |
| `GuestCartCleanupJob` | cron `0 17 * * * *` | expires guest carts past `guest-ttl-days` |

All are registered with `JobRegistry` and visible at `GET /admin/jobs`.

---

## 8. Persistence

- **Schema is never generated by Hibernate outside `dev`.** Base config sets
  `ddl-auto=none`; `prod` sets `validate`; `dev` sets `update` and the schema it
  produces is explicitly disposable.
- **`open-in-view=false`.** Lazy loading does not leak into the view layer;
  everything a response needs is fetched in the service.
- **Dialect is pinned** to `MySQLDialect`. Hibernate 7 otherwise infers it from
  live JDBC metadata, so *any* connection problem is reported as "Unable to
  determine Dialect without JDBC metadata" with the real `SQLException` buried
  up the log.
- **332 `@Query` declarations.** Spring Data parses every one at startup, which
  is why the context-load test is the cheapest and most valuable test in the
  suite (§10).
- Reserved words are respected: `notifications.is_read`, not `read`. The H2
  test profile re-permits `VALUE` and `READ` via `NON_KEYWORDS` so the schema
  generated in test is the schema mapped in production.
- Connection pool: Hikari, `DB_POOL_MAX` (10) / `DB_POOL_MIN` (2), 10s timeout.

The development seed (`src/main/resources/db/seed/dev-seed.sql`, 4,435 lines)
populates all 50 tables with one coherent dataset. It is **never auto-loaded**;
its own header explains how to run it. Seeded rows use ids ≥ 1000 so they never
collide with application-created rows, and the file deletes `id >= 1000` in
reverse foreign-key order before inserting, which makes it re-runnable. No
`FOREIGN_KEY_CHECKS=0` anywhere: if the delete order is wrong the database says
so, which is the point.

---

## 9. External dependencies, and what happens without them

Every outbound integration degrades to a stated answer rather than an error.

| Dependency | Configured by | Without it |
|---|---|---|
| **MySQL** | `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` | prod refuses to start (no fallbacks); dev points at localhost |
| **SMTP** | `MAIL_*` | sends fail; **timeouts are set to 5s** because JavaMail waits forever by default and these sends are on the request thread — one unreachable mail host would consume the whole Tomcat pool |
| **Google Geocoding** | `GOOGLE_GEOCODING_API_KEY` | `POST /geo/validate-address` answers `available: false` — *"nobody looked"*, which is a different thing from "we looked and found nothing" and leads to different advice. Addresses save without coordinates; delivery prices from a scope fallback |
| **Cloudflare R2** | `R2_*` | `UnconfiguredStorageService` is bound instead and the upload endpoints return 400 explaining what to set; everything else runs |
| **Payment provider** | `PAYMENT_CALLBACK_SECRET` | only the mock gateway exists; blank secret disables the callback endpoint |
| **SMS** | — | **not integrated at all**; codes are logged at INFO in non-prod |
| **MaxMind GeoIP2** | database file | currency falls back to locale, then to the base currency |

---

## 10. Build and test

```bash
mvn -o compile -Djava.version=21     # the POM targets 21; CI and JDK 26 both build it
mvn -o test    -Djava.version=21
```

> `-o` (offline) requires a populated local Maven repository. On a clean
> machine, run once without it.

**Current state, verified for this document:** `mvn test` → **1,233 tests, 0
failures, 0 errors, 0 skipped**, across 90 test classes. Build exits 0.

### 10.1 The context-load test

`SujulaApplicationTests` boots the whole application against H2 in MySQL mode.
It is the cheapest test in the suite and finds the most: Spring Data parses
every one of the 332 `@Query` declarations at startup and Hibernate resolves
every association, so a broken mapping or an invalid query fails *there* rather
than on the first request that touches it.

### 10.2 Test layers

| Layer | Example | What it can prove |
|---|---|---|
| Unit / service | `CustodyChainTest`, `MoneyLedgerTest`, `FxSnapshotTest`, `C1SeparationTest` | business rules and refusals |
| Routing / security | `BuyerOrderSecurityTest`, `StoreSecurityTest`, `VendorOperationsSecurityTest`, `*RoutingTest` | **who may reach which URL** — booted app, real filter chain |
| End-to-end | `e2e/` (Newman/Postman, 432 operations) | the whole application answering over HTTP against the seed |

The routing tests are asserted against a booted application with its real filter
chain rather than read off the configuration, because "an open `/orders`
publishes everybody's address, and a closed `/track` makes the whole
accountless-recipient case unreachable".

### 10.3 The end-to-end collection

```bash
cd e2e && ./run.sh          # boots a server, waits, runs the collection, stops it
```

Boots under the `e2e` profile: whole application, in-memory H2, development seed
already loaded. No MySQL to install, no Google key, no Cloudflare bucket. Every
id, code, password and tracking number it sends is read out of the seed —
nothing is invented — so a red assertion means the application's answer changed
rather than that a fixture drifted.

**Run the folders in order.** Folder 00 captures the CSRF token; folder 01
captures each persona's access token.

**Start the server fresh for each run.** The seed holds one-shot credentials
(refresh tokens that rotate, a phone challenge consumed on confirm, stock a
checkout decrements). Where a one-shot fixture is unavoidable the collection
asserts *both* outcomes.

A green run here is a statement about behaviour, **not about the schema**: H2 in
MySQL mode disagrees with the real thing about reserved words and some date
functions. Point `DB_URL` at a MySQL and re-run when that is the question.

---

## 11. Configuration

Full reference: [`OPERATIONS.md`](OPERATIONS.md). Profiles:

| Profile | Purpose | Notable |
|---|---|---|
| *(none)* | assumes nothing, starts nothing | base defaults to `dev` via `SPRING_PROFILES_ACTIVE` |
| `dev` | local MySQL on localhost | `ddl-auto=update`, mock gateway on, Swagger UI on, insecure cart cookie |
| `test` | H2, for the JUnit suite | |
| `e2e` | whole app on in-memory H2 + seed, one command | **every secret is a fixed published test value** — must never be a deployment profile |
| `prod` | everything from the environment, **no fallbacks** | `ddl-auto=validate`, mock gateway off, docs off, forwarded-headers native |

Refusals wired into startup, each because the silent alternative is worse:

- `sujula.auth.jwt.secret` unset under `prod` → **refuse to start** (a generated
  key would invalidate every token on restart and differ per instance).
- `sujula.payment.mock.enabled=true` under `prod` → **refuse to start**.
- `sujula.security.field-encryption.key` unset → the payout-details endpoint
  **refuses** rather than storing an account number in clear. A system that
  looks like it encrypts bank details and does not is worse than one that admits
  it cannot.
- `sujula.reference.base-currency` not among the configured currencies →
  **refuse to start**. Every currency fallback resolves to the base.

Actuator exposes exactly `health`, `info`, `prometheus`. The default set
includes `/env` and `/configprops`, which would print webhook signing secrets
and the field-encryption key; exposing the set and then trying to redact it is
the wrong way round. `/actuator/**` additionally requires `ROLE_ADMIN`.

---

## 12. Where to start reading

| If you want to understand… | Read |
|---|---|
| Why anything is the way it is | `CLAUDE.md` |
| The whole API as a person would exercise it | `e2e/COLLECTION.md`, `e2e/endpoints.json` |
| The data, with a story attached to every row | `src/main/resources/db/seed/dev-seed.sql` — its closing 900 lines are a guided tour |
| C1 | `CatalogueController`, `DeliveryContextService`, `C1SeparationTest` |
| C2 | `CurrencyCatalogue`, `FxSnapshot`, `FxQuoteService`, `MoneyLedger` |
| C3 | `VendorOrder`, `AfterSalesController`, `VendorMoneyServiceImpl` |
| C4 | `CustodyChain` — read it end to end, it is 290 lines |
| C5 | `RecipientParcelController`, `PublicTrackingController`, `DriverCustodyServiceImpl` |
| Who may reach what | `SecurityConfig` — it is commented rule by rule |
| What is *not* done | [`LIMITATIONS.md`](LIMITATIONS.md) |
