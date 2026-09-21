# Sujula — Code Quality Review

> **What this is.** A review of the backend as it stands, read against its own
> constitution (`CLAUDE.md`) and against ordinary Spring/JPA practice. Findings
> are ordered by consequence, not by how interesting they are. Each carries a
> file reference, what actually goes wrong, and a fix.
>
> **Method.** Full read of `SecurityConfig`, `JwtAuthenticationFilter`,
> `GlobalExceptionHandler`, the money/FX/custody/checkout services and the
> security package; systematic sweep of all 47 controllers for entity leakage
> and authorization annotations; sweep of all 84 repositories for
> ownership-in-query; sweep of every service for the `findById`-then-compare
> pattern; full endpoint inventory (432 operations) cross-checked against the
> filter-chain rules; `mvn test` executed.
>
> **Verified state at review time:** `mvn test` → **1,233 tests, 0 failures, 0
> errors, 0 skipped**, 90 test classes, build exit 0.

---

## 1. Summary

### What is genuinely good

This is an unusually disciplined codebase, and the discipline is *structural*
rather than cosmetic.

1. **The five rules are mechanised, not documented.** `CustodyChain` is the
   clearest example: `Shipment` has no public status setter, so there is no path
   through the codebase by which a parcel reaches `DELIVERED` other than an
   event saying who handed it to whom with what proof. That is a design that
   cannot be forgotten, as opposed to a convention that can.
2. **Dangerous states are made unrepresentable rather than validated against.**
   Store-staff permissions come from a store-only enum, so there is no value a
   seller can send that reaches another vendor's data. `EncryptedStringConverter`
   sits at the column mapping rather than in a service, so no future write path
   can put plaintext into an encrypted column.
3. **Derived figures are derived, every time.** Balances are sums of ledger
   entries; stock is the sum of movements; shipment status is a pure function of
   the custody chain; `stored_parcels` is recounted. Nothing that could drift is
   stored.
4. **Refusals are first-class.** A large fraction of the test suite asserts what
   the application *will not* do, and the error messages are written for the
   person who hit them ("there is no category called 'Phonez'", "money still held
   against parcels, rather than no money at all").
5. **Security reasoning is written down at the point of decision.**
   `SecurityConfig` justifies every path rule; `WebhookSignature` names the three
   rules and why each exists. A reviewer can check the reasoning, not just the
   code.
6. **Honest self-documentation.** `CurrencyCatalogue` states in its own Javadoc
   that `round` is not yet used everywhere. `CheckoutServiceImpl` states exactly
   what the quote does and does not guarantee. `NotificationChannel` explains why
   SMS is *absent*. This is rarer and more valuable than it sounds.
7. **Zero `TODO`, `FIXME`, `HACK` or `XXX` markers** in 87,000 lines of main
   source. Unfinished work is described in prose where it matters, not parked in
   a marker nobody greps for.

### What is wrong

One exploitable authorization defect, one structural debt that costs more every
month it stays, and a short tail of consistency issues. All of them live on or
near the **legacy `/api/**` surface**; the current flat-path surface is clean.

| # | Finding | Severity |
|---|---|---|
| [2.1](#21-postauthorize-on-mutating-endpoints-authorises-after-the-write-has-committed) | `@PostAuthorize` on 11 mutating endpoints — the write commits, *then* 403 | **Critical** |
| [2.2](#22-postauthorize-on-two-write-methods-in-vendorserviceimpl) | Same pattern in `VendorServiceImpl` (not currently exploitable) | High (latent) |
| [3.1](#31-two-parallel-api-generations) | Two parallel API generations, 116 legacy operations | High (structural) |
| [3.2](#32-ordercontroller-returns-jpa-entities-across-the-http-boundary) | `OrderController` returns JPA entities across the HTTP boundary | High |
| [4.1](#41-requireownedaddress-confirms-that-somebody-elses-address-exists) | `requireOwnedAddress` confirms another user's address exists, with the wrong status | Medium |
| [4.2](#42-findbyid-then-compare-where-the-house-rule-says-put-it-in-the-query) | `findById`-then-compare in five services | Low |
| [5.1](#51-currencycatalogueround-is-not-on-the-money-paths-that-need-it) | `CurrencyCatalogue.round` not on the money paths that need it (C2) | High |
| [5.2](#52-no-sms-sender-so-c5s-design-target-is-reached-by-proxy) | No SMS sender, so C5's design target is reached by proxy (C5) | Medium |
| [5.3](#53-the-quote-refuses-rather-than-holds) | Checkout re-prices and refuses rather than honouring the frozen quote (C2) | Medium |
| [6.1](#61-deliveryscope-regiional-is-a-typo-baked-into-configuration-keys) | `DeliveryScope.REGIIONAL` is a typo baked into config keys and data | Low |
| [6.2](#62-recoger-is-a-spanish-word-in-an-otherwise-english-enum) | `RECOGER` is Spanish in an otherwise-English enum | Low |
| [6.3](#63-no-cors-configuration) | No CORS configuration | Medium (operational) |
| [6.4](#64-no-explicit-security-response-headers) | No explicit CSP or security-header policy | Low |
| [6.5](#65-no-general-purpose-rate-limiting) | No general-purpose rate limiting | Medium |
| [6.6](#66-base-configuration-defaults-to-the-dev-profile) | Base configuration defaults to the `dev` profile | Low |
| [7.x](#7-test-coverage-gaps) | Coverage gaps: legacy cart, categories, product Q&A, six workers | Medium |

---

## 2. Correctness: authorization

### 2.1 `@PostAuthorize` on mutating endpoints authorises *after* the write has committed

**`src/main/java/com/sujula/controller/UserController.java`**, lines
**109, 115, 122, 128, 136, 145, 151, 157, 169, 175, 181, 187, 194**.

```java
@PutMapping("/{id}")
@PostAuthorize("hasRole('ADMIN') or @userSecurity.isSelf(authentication, #id)")
public ResponseEntity<UserResponse> update(@PathVariable Long id, ...) {
    return ResponseEntity.ok(userService.updateUser(id, request));   // ← already committed
}

@DeleteMapping("/{id}/permanent")
@PostAuthorize("hasRole('ADMIN')")
public ResponseEntity<Void> deletePermanently(@PathVariable Long id) {
    userService.deleteAccountPermanently(id);                        // ← already committed
    return ResponseEntity.noContent().build();
}
```

**Why this is a real defect, not a style point.** `@PostAuthorize` evaluates
*after* the annotated method returns. The transaction lives on the service
method (`UserServiceImpl.updateUser`, `.deleteAccountPermanently`,
`.blockUser` … are each `@Transactional`), so it **commits when the service
call returns** — before control gets back to the controller and long before
Spring Security's after-method interceptor runs. The caller receives 403; the
database keeps the change.

**Why it is reachable.** `SecurityConfig` permits only six specific `POST`
paths under `/api/users`; everything else falls through to
`.anyRequest().authenticated()`. There is no path rule granting `ROLE_ADMIN`
over `/api/users/**`, and `UserServiceImpl` carries no authorization of its
own. So **any authenticated account — an ordinary `CUSTOMER` — can reach these
handlers**, and the `@PostAuthorize` is the only check standing between them
and the write.

**Concrete failure.** Aminata signs in as a customer and sends:

```
DELETE /api/users/1007/permanent
Authorization: Bearer <her own valid access token>
```

She gets `403 Forbidden`. User 1007 is gone.

The same shape applies to `PUT /api/users/{id}` (edit any profile),
`PATCH /{id}/block`, `/unblock`, `/fraud`, `/enable`, `/disable`,
`/preferences` and `DELETE /{id}`. `PUT /{id}/password` is partially mitigated
because the service demands the current password; the rest are not mitigated at
all.

**Fix.** Change every mutating annotation from `@PostAuthorize` to
`@PreAuthorize`. The expressions are already written against `#id`, which is a
method *argument*, so they evaluate identically before the call:

```java
@PutMapping("/{id}")
@PreAuthorize("hasRole('ADMIN') or @userSecurity.isSelf(authentication, #id)")
```

The two read endpoints that genuinely need to inspect the returned object may
stay `@PostAuthorize`, but note that for reads, `@PostAuthorize` still executes
the query and only then refuses — acceptable, since nothing is written.

**Belt as well as braces**, and independently worth doing: add a path rule so
this can never be one forgotten annotation again.

```java
.requestMatchers("/api/users/*/block", "/api/users/*/unblock",
                 "/api/users/*/fraud", "/api/users/*/enable",
                 "/api/users/*/disable", "/api/users/*/unlock").hasRole("ADMIN")
.requestMatchers(HttpMethod.DELETE, "/api/users/**").hasRole("ADMIN")
.requestMatchers(HttpMethod.GET, "/api/users", "/api/users/by-email").hasRole("ADMIN")
```

This is exactly the argument `StaffCaller` makes for the `/admin` surface —
"the annotation that gets forgotten is on the one endpoint that mattered". The
legacy surface never got the same treatment.

**Regression test to add** (there is currently none for this controller):

```java
@Test
void aCustomerCannotDeleteAnotherAccount() throws Exception {
    mvc.perform(delete("/api/users/1007/permanent").with(user(aminata)))
       .andExpect(status().isForbidden());
    assertTrue(userRepository.findById(1007L).isPresent());   // ← the half that fails today
}
```

---

### 2.2 `@PostAuthorize` on two write methods in `VendorServiceImpl`

**`src/main/java/com/sujula/service/impl/VendorServiceImpl.java`**, lines
**142** (`updateProfile`) and **205** (`updateStatus`).

Same mechanism: both are `@Transactional`, both carry `@PostAuthorize`, and
Spring Security's after-method interceptor is *outside* the transaction
interceptor in the proxy chain, so the commit happens first.

**Not currently exploitable**, because `VendorController` guards both routes
with a correct `@PreAuthorize` (`PATCH /api/vendors/{id}/status` →
`hasRole('ADMIN')`; `PUT /api/vendors/user/{userId}/profile` → admin-or-self).
The service-level annotation is defence in depth that does not actually defend.

**Why fix it anyway.** It is a loaded gun pointing at the next caller. Any new
controller, scheduled job or admin tool that calls `updateStatus` directly
inherits the broken guarantee, and the annotation *reads* as though it protects.

**Fix.** `@PreAuthorize("hasRole('ADMIN')")` on line 205.
`updateProfile` (line 142) takes `userId` as an argument, so
`@PreAuthorize("hasRole('ADMIN') or #userId == authentication.principal.id")`
is a direct translation.

The five **read** methods (lines 51, 58, 65, 74, 81) are legitimate uses:
`returnObject.userId == authentication.principal.id` cannot be evaluated before
the object exists. They are fine as they are.

---

## 3. Structure

### 3.1 Two parallel API generations

**116 of 432 operations** sit under `/api/**` and duplicate, partially, what the
flat-path surface does:

| Concern | Current | Legacy |
|---|---|---|
| Sign-in | `/auth/login` | `/api/users/login` |
| Own account | `/me` | `/api/users/me`, `/api/users/{id}` |
| Addresses | `/me/addresses/*` | `/api/user/addresses/*` |
| Basket | `/carts/{token}/*` | `/api/cart/*` |
| Buyer orders | `/orders/*` | `/api/user/orders/*` |
| Catalogue | `/products`, `/search` | `/api/products/*` |
| Vendor | `/vendor/**` | `/api/vendors/*` |
| Admin | `/admin/**` | `/api/admin/*` |
| FX | `/currencies/*` | `/api/exchange-rates/*` |

The two generations are distinguishable at a glance: the current controllers
carry class-level Javadoc stating their ownership model and their place in the
five rules (`BuyerOrderController`, `MeController`, `CartsController`,
`CatalogueController`, `StoreController`, `CurrencyController`,
`MeAddressController`); the legacy ones — `UserController`, `VendorController`,
`ExchangeRateController`, `OrderController` — carry none.

**The cost is not aesthetic.** Every finding in this review with a severity
above Low is on the legacy side. Two cart implementations means two
guest-ownership models (`/api/cart` decides ownership from a server-issued
HttpOnly cookie; `/carts` from a 256-bit token in the path), two sets of
pricing calls, and two chances for one of them to skip a check the other makes.
Two order surfaces means two places that must both keep C3 and both freeze FX.

**Recommendation, in order:**

1. **Decide and write it down.** Add a `Deprecated` banner to each legacy
   controller's Javadoc naming its replacement. That is one commit and it stops
   new client work landing on the wrong surface.
2. **Fix §2.1 on the legacy surface now**, regardless of the deprecation
   timeline. Deprecated is not the same as unreachable.
3. **Instrument before deleting.** The Micrometer registry is already wired;
   tag request counts by path and you will know within a release which of the
   116 operations anything still calls.
4. **Delete in slices**, cart first (it is the one with two live ownership
   models), then orders, then users.

Nothing here is urgent in the way §2.1 is. But the review findings cluster on
the legacy surface for a reason: it is the code the constitution was written
after.

---

### 3.2 `OrderController` returns JPA entities across the HTTP boundary

**`src/main/java/com/sujula/controller/OrderController.java`** — **11
handlers** return `ResponseEntity<Order>`, and one returns
`List<OrderStatusHistory>`. It is the only controller in the codebase that
imports a non-enum entity for anything other than the authenticated principal.

`CLAUDE.md` states the rule: *"Controllers bind DTOs and nothing else.
Entity↔DTO conversion happens in the service layer; no entity crosses a
controller boundary."* `MeAddressController` restates it in its own Javadoc.

**Three concrete consequences:**

1. **The wire format is an accident of the schema.** Every column added to
   `Order` is published; every one renamed is a breaking API change nobody
   reviewed.
2. **Serialisation reaches through associations.** `spring.jpa.open-in-view` is
   `false` (correctly), so whether a nested `customer`, `vendorOrders` or
   `address` serialises at all depends on what the service happened to fetch
   before the transaction closed. That is a `LazyInitializationException` that
   appears when a query plan changes, not when the code changes.
3. **It publishes more than the endpoint means to.** `Order` reaches the buyer,
   the guest order lookup, and the admin surface through the same class. The
   rest of this application is careful about exactly this — a vendor's slice
   deliberately contains no GBP, no London and no Oliver — and this controller
   opts out.

**Fix.** `BuyerOrderResponses` and `CheckoutResponses` already exist and already
model the shapes needed. Map in the service and return the DTO. Do the buyer
paths first; the guest paths (`/api/guest/orders/*`) matter most, because that
response goes to an unauthenticated caller who presented only an order number
and an email.

---

## 4. Ownership checks

### 4.1 `requireOwnedAddress` confirms that somebody else's address exists

**`src/main/java/com/sujula/service/impl/OrderServiceImpl.java:1417-1424`**

```java
private Address requireOwnedAddress(Long addressId, Long userId) {
    Address address = addressRepository.findById(addressId)
            .orElseThrow(() -> new ResourceNotFoundException("Address", addressId));
    if (!address.getUser().getId().equals(userId)) {
        throw new BadRequestException("Address does not belong to this user");
    }
    return address;
}
```

Two house rules broken in six lines, and the second one has a real consequence.

1. **Not ownership-in-query.** `findById` followed by a comparison — the exact
   shape `CLAUDE.md` names as "the version that ships with the comparison
   missing".
2. **It discloses existence, and with the wrong status.** A caller who guesses
   an address id learns whether it exists: a missing row answers **404 "Address
   not found"**, somebody else's row answers **400 "Address does not belong to
   this user"**. An address carries a name, a phone number and a location;
   `MeAddressController`'s own Javadoc says confirming one exists is worth
   withholding. This method is the one place that tells you.

**Fix**, matching what the address book already does:

```java
private Address requireOwnedAddress(Long addressId, Long userId) {
    return addressRepository.findLiveByIdAndUserId(addressId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Address", addressId));
}
```

`findLiveByIdAndUserId` already exists on `AddressRepository`.

---

### 4.2 `findById` then compare, where the house rule says put it in the query

Behaviourally correct, but each is one deleted `if` away from a leak. Ordered
by how close to money or identity they sit.

| File | Line | Note |
|---|---|---|
| `service/aftersales/impl/ReviewModerationServiceImpl.java` | 172, 218, 242 | three separate comparisons, two ownership models in one class |
| `service/checkout/impl/CheckoutServiceImpl.java` | 196-204 | correct — throws `ResourceNotFoundException` for another buyer's order, and says why in a comment |
| `service/impl/NotificationServiceImpl.java` | 76-82 | correct — 404 for another user's notification; the comment notes reading the id off a lazy proxy does not load the row |
| `service/impl/VendorServiceImpl.java` | 216 | a lookup on a user already resolved from the vendor; harmless |
| `service/store/impl/StoreServiceImpl.java` | 701 | filter inside a larger query |

**Recommendation.** Only `ReviewModerationServiceImpl` is worth changing on its
own; the others are already returning the right status and their comments show
the reasoning was done. If you touch them, add the repository method rather than
tightening the `if`.

The fleet as a whole is in good shape here: ten distinct ownership-scoped finder
methods exist (`findLiveByIdAndUserId`, `findByIdAndVendorId`,
`findByIdAndBuyerId`, `findByIdAndDriverId`, `findByIdAndOwnerId`,
`findByReferenceAndVendorId`, `findByIdAndVendorIdWithImages`,
`findByIdAndOwnerIdWithHours`, `findByVendorIdAndUserId`, `findByIdAndUserId`)
and the `/vendor`, `/driver`, `/me` and `/orders` surfaces use them
consistently.

---

## 5. Constitution compliance

### 5.1 `CurrencyCatalogue.round` is not on the money paths that need it

`CurrencyCatalogue` is the authority on minor units and says so; it also states
plainly in its own Javadoc that it is **not yet used everywhere** — order
totals, delivery legs and payouts still scale to two places in their own code.

**C2 says minor units are part of the rule, not a formatting detail.** XOF has
no subdivision. A total computed to two places and shown to a buyer in
Ziguinchor is a total they cannot pay, and a payout figure carrying half a franc
is one that will never reconcile against what the bank actually moved. One of
the two seeded vendors settles in XOF, so this is on the main path, not an edge.

**Credit where due:** the class exists, it is correct, and the discrepancy is
named rather than left undiscoverable. That is the right intermediate state.
But it is an intermediate state.

**Fix, in order of risk.** Grep for `setScale(2` and `RoundingMode` across
`service/` and convert each to `currencies.round(amount, code)`:

1. **Payout assembly** — the figure a bank actually moves. Start here.
2. **Ledger entry amounts** — so `sum(entries) == balance` holds in XOF.
3. **Order and sub-order totals** — what the buyer is charged.
4. **Delivery legs** — smallest amounts, largest number of call sites.

Add a test per currency asserting `round` is idempotent and that an XOF total
has scale 0. `CurrencyCatalogueTest` already covers the class itself; what is
missing is a test that the *callers* use it.

---

### 5.2 No SMS sender, so C5's design target is reached by proxy

**C5's stated target:** *"An SMS code they read out to the driver is the design
target."*

**What is built:** `NotificationChannel` is `IN_APP | EMAIL | PUSH`, with SMS
deliberately absent and the reason given — a channel nothing sends on is a
preference somebody switches on and then waits for a message that never comes.
`AuthServiceImpl` logs phone-verification codes at INFO in non-prod, with the
comment *"No SMS provider is integrated … when a provider exists this is where
it is sent."*

**So the release code travels by email to the buyer**, who passes the six digits
to the recipient. For the seeded scenario that works: Fatou in Madrid receives
the code and tells her sister. But the case the marketplace exists for is a
recipient who *has a phone number and nothing else*, and today she depends on
the payer being reachable and awake.

**This is an integration gap, not a design flaw.** Everything around it is
already correct and already shaped for SMS:

- the recipient needs no account, no app and no email of her own;
- the driver never sees the code;
- the code is single-use and burned in the handover transaction;
- rate limits and re-send counts are in place.

**Fix.** Add an `SmsGateway` interface beside `PaymentGateway` (which already
demonstrates the pattern, including an unconfigured implementation that refuses
usefully), add `SMS` to `NotificationChannel`, and route
`requestRecipientCode` to the recipient's number with the buyer's email kept as
the fallback. Nothing else has to move.

---

### 5.3 The quote refuses rather than holds

`CheckoutServiceImpl` states it exactly: the quote is reconciled against a
re-priced order, and a difference **rejects the order** rather than honouring
the frozen figure.

**The safe half is done.** A buyer is never charged a figure they did not agree
to. The unfinished half is that when the market moves under them, they are asked
to re-quote rather than being charged what they were quoted — which on a
15-minute quote window during a rate move is a checkout that fails at the last
step for a buyer who did nothing wrong.

The class's own reasoning is sound and worth preserving: a reconciliation that
refuses is safe, a freeze that is half-applied is not, and there must be exactly
one order-assembly path. The frozen lines are already on `CartQuote` for it.

**Fix.** Pass the quote's `FxSnapshot` into order assembly so the *same* path
prices with the frozen rate, and keep the reconciliation as an assertion that
should now never fire. Log it if it does.

---

## 6. Consistency and configuration

### 6.1 `DeliveryScope.REGIIONAL` is a typo baked into configuration keys

**`model/constant/DeliveryScope.java:5`** — `REGIIONAL`, with two `I`s.

It is consistent, which is the only reason it is not worse: the same spelling
appears in `application.properties` as
`sujula.delivery.pricing.scope-multiplier.REGIIONAL` and
`sujula.delivery.pricing.fallback-km.REGIIONAL`, and in the database as a
persisted enum name.

**Cost.** Every operator who edits a rate card has to reproduce the typo; a
corrected spelling in a properties file silently fails to bind and the
multiplier falls back, which is a pricing change nobody sees. It also appears in
API responses, so client code carries it too.

**Fix, which must be done in one change or not at all:** rename the constant,
the two property keys, and migrate the persisted column
(`UPDATE ... SET delivery_scope = 'REGIONAL' WHERE delivery_scope = 'REGIIONAL'`)
in the same release. Grep first — there are four kinds of occurrence (Java,
properties, seed SQL, e2e fixtures).

### 6.2 `RECOGER` is a Spanish word in an otherwise-English enum

Same enum. *Recoger* means "to collect". Every other constant in the codebase is
English. It reads as a scope class but behaves as a collection mode, which is
also a small modelling smell — `DeliveryMode` already carries `VENDOR_PICKUP`.
Fold it into the same rename if 6.1 is done; otherwise leave it and document it
(it is documented in `GLOSSARY.md`).

### 6.3 No CORS configuration

There is no `CorsConfigurationSource` bean, no `.cors(...)` in the filter chain
and no `@CrossOrigin` anywhere.

**What this means in practice.** The API is same-origin only. A storefront
served from a different origin cannot call it from a browser: the preflight
`OPTIONS` gets no `Access-Control-Allow-Origin` and the browser refuses. Native
and server-side clients are unaffected.

**This may be deliberate** — a same-origin SPA behind one reverse proxy is a
perfectly good architecture, and it is what the invoice base-URL comment assumes
(*"Blank yields a relative URL, which is what a same-origin single-page client
wants"*). But it is nowhere stated, and it is the first thing a front-end team
will hit.

**Fix.** Either document "same-origin, deploy behind one proxy" in
`OPERATIONS.md` (this document does), or add an explicit allow-list driven from
configuration:

```java
@Bean
CorsConfigurationSource corsConfigurationSource(
        @Value("${sujula.cors.allowed-origins:}") List<String> origins) { ... }
```

Do **not** use `allowedOriginPatterns("*")` with `allowCredentials(true)`. The
CSRF cookie and the guest-cart cookie both make that a real cross-origin hole.

### 6.4 No explicit security response headers

Spring Security's defaults apply (`X-Content-Type-Options: nosniff`,
`X-Frame-Options: DENY`, `Cache-Control: no-store` on secured responses, HSTS on
HTTPS requests), and for a JSON API that is most of what matters. There is no
explicit `Content-Security-Policy` and no `Referrer-Policy`.

**Low severity here**, because this application serves JSON and PDFs rather than
HTML — the exception is Swagger UI, which is off outside `dev`. Worth adding a
one-line `frame-ancestors 'none'; default-src 'none'` CSP on API responses
before any HTML is ever served from this origin.

### 6.5 No general-purpose rate limiting

There is targeted throttling where somebody sat down and thought about a
specific abuse:

- failed sign-ins → `LoginAttemptTracker`'s warn/reset/lock ladder;
- phone codes → `sujula.auth.phone.max-per-hour`;
- recipient release codes → max per shipment per hour;
- catalogue imports → `max-queued-jobs-per-vendor`;
- store staff invitations → `max-staff`, explicitly so an invite loop cannot be
  used to send mail.

There is **no** per-IP or per-account limit on the surface as a whole. The
endpoints that would hurt are the public, unauthenticated, computation-heavy
ones: `POST /delivery/quote`, `POST /delivery/serviceability`,
`POST /geo/validate-address` (which spends a paid Google call), `GET /search`,
and `POST /carts` (which mints a row per call).

**Fix.** Rate limiting belongs at the edge — the reverse proxy or API gateway —
rather than in application code, and `OPERATIONS.md` specifies it there. If it
must be in-process, a bucket filter keyed on `X-Forwarded-For` (the prod profile
already configures `server.tomcat.remoteip.remote-ip-header`) in front of those
five paths is the minimum.

### 6.6 Base configuration defaults to the `dev` profile

**`application.properties:5`** — `spring.profiles.active=${SPRING_PROFILES_ACTIVE:dev}`.

A deployment that forgets `SPRING_PROFILES_ACTIVE` starts under `dev`, which
turns on `ddl-auto=update` (Hibernate rewriting the schema), the **mock payment
gateway** (orders marked paid without money moving), **Swagger UI**, and an
**insecure cart cookie**.

Three of those four are individually defended: the mock gateway refuses to start
under `prod` — but it is not `prod` in this scenario, it is `dev`. The defences
are all conditioned on `prod` being active, so the one failure mode they do not
cover is `prod` never being set.

**Mitigating:** `dev`'s database defaults point at `localhost:3306` with
`root/123456`, so in most deployments it fails to connect rather than starting
wrongly. That is luck, not design.

**Fix.** Default to no profile. The base file already "assumes nothing and
starts nothing" — `spring.datasource.url` has no default precisely so that a
wrong URL that happens to connect is impossible. Let the same discipline decide
the profile:

```properties
spring.profiles.active=${SPRING_PROFILES_ACTIVE:}
```

and have developers pass `-Dspring.profiles.active=dev`, which the dev profile's
own header already tells them to do.

---

## 7. Test coverage gaps

1,233 tests, 0 failures. The suite is strong where it matters most — custody,
money, FX, C1 separation, and per-surface routing/security tests asserted
against a booted application with the real filter chain.

**Classes no test mentions at all:**

| Area | Classes | Risk |
|---|---|---|
| Legacy cart | `CartServiceImpl`, `CartSessionServiceImpl`, `CartOwner`, `CartProvisioner`, `GuestCartCleanupJob` | **Medium.** `CartOwner` is the single place that decides whose cart a request touches, and its own Javadoc says so. It is untested. |
| Legacy categories | `CategoryService(Impl)` | Low |
| Product Q&A | `ProductQuestionService(Impl)` | Low — writes public text onto a seller's shopfront |
| Background workers | `WebhookWorker`, `CatalogueJobWorker`, `ReportExportWorker`, `AccountDataWorker`, `AccountDataProcessor`, `PlatformSweeper` | **Medium.** The *processors* are tested (`WebhookIntakeTest`, `CatalogueJobProcessorTest`, `ReportExportProcessorTest`); the scheduling, retry and failure-recording wrappers are not. |
| Geo | `GeoServiceImp` | Low — the degraded path is asserted elsewhere |
| Encryption wiring | `EncryptedStringConverter` | Low — `BankAccountEncryptionTest` covers the service; the converter's null/marker handling is not directly asserted |
| Mock gateway | `MockPaymentGateway` | **Low but sharp.** Its prod refusal is a safety property, and nothing asserts it |
| Analytics | `ProductViewRecorder` | Low |

**Highest-value additions, three tests:**

```java
// 1. The §2.1 regression — assert the row is still there after the 403.
// 2. MockPaymentGateway refuses to construct under the prod profile.
// 3. CartOwner: a guest cookie cannot reach a signed-in buyer's cart,
//    and a signed-in buyer's token never resolves to a guest cart.
```

Also worth noting as a deliberate non-gap: `OrderController` has **no** test
file, which is part of why §2.1 and §3.2 both live there.

---

## 8. Smaller observations

- **`GlobalExceptionHandler`** is exemplary: fixed message for bad credentials
  so the endpoint cannot enumerate accounts, no SQL or class names in any
  response, and `NoResourceFoundException` handled so nothing falls through to
  the container's HTML error page.
- **`LoginAttemptTracker`'s separate transactions** are a subtle correctness fix
  most codebases get wrong — a counter incremented in the same transaction as
  the failure it counts is rolled back by that failure, giving unlimited
  attempts.
- **`TotpService` implemented by hand** is the right call and is justified: ~40
  lines, and RFC 6238 publishes test vectors, so it is *proved* rather than
  trusted. `TotpServiceTest` checks every SHA-1 vector.
- **`CurrencyCatalogue.index()`** uses `Collections.unmodifiableMap(new
  LinkedHashMap<>(...))` rather than `Map.copyOf`, with the reason stated:
  `Map.copyOf` returns an unordered map whose iteration order varies between
  runs, which would have shuffled the currency picker on every restart. This is
  the kind of detail that is normally discovered in production.
- **Seed data quality.** `SeededContentHashTest` recomputes approved-content
  hashes from the seeded rows' own text, and the seeded IMEIs satisfy their own
  Luhn check digits — two of the four originally written did not, and the test
  caught it. A seed that cannot quietly become a lie is worth a lot.
- **`spring.jpa.open-in-view=false`** is set, which is the right default and one
  most Spring projects leave on.
- **Actuator exposure** names three endpoints rather than exposing the set and
  redacting — the right way round, given that this application's configuration
  holds webhook secrets and a field-encryption key.

---

## 9. Suggested order of work

| Order | Item | Effort |
|---|---|---|
| 1 | §2.1 — `@PreAuthorize` on `UserController`'s 11 mutating endpoints + path rules + regression test | hours |
| 2 | §2.2 — same on `VendorServiceImpl` lines 142, 205 | minutes |
| 3 | §4.1 — `requireOwnedAddress` via `findLiveByIdAndUserId` | minutes |
| 4 | §6.6 — stop defaulting to `dev` | minutes |
| 5 | §6.3 — decide and document the CORS posture | hours |
| 6 | §3.2 — DTOs for `OrderController`, guest paths first | days |
| 7 | §5.1 — `CurrencyCatalogue.round` on payouts, then ledger, then totals, then legs | days |
| 8 | §3.1 — deprecation banners, then instrument, then delete a slice at a time | weeks |
| 9 | §5.2 — `SmsGateway`, and C5 reaches its stated target | days |
| 10 | §5.3 — price order assembly from the quote's frozen snapshot | days |

Items 1–4 are small, independent, and each closes something that is currently
wrong. They are worth doing before anything else on this list.
