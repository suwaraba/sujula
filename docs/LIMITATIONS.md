# Sujula — Limitations, Gaps and Improvements

> **What this is.** An honest account of what this application does *not* do,
> what is only partly done, and what would have to happen next. It is written to
> be read before a release decision, not after one.
>
> **How to read the markers.**
>
> | | |
> |---|---|
> | 🔴 **Blocker** | Must be fixed before this serves real money or real accounts |
> | 🟠 **Major** | Real cost to users or operators; schedule it |
> | 🟡 **Known gap** | Understood, deliberate for now, documented |
> | 🔵 **Improvement** | Worth doing, nothing breaks without it |
>
> **Context.** The application is substantially built — 432 endpoints, 87
> entities, 1,233 passing tests. What follows is not a list of things nobody
> thought about. Nearly every item here is already named in the code's own
> comments; this document collects them and adds consequence and cost.

---

## 1. Blockers

### ✅ 1.1 An ordinary customer can modify and delete other accounts — **FIXED**

> All thirteen rules on `UserController` are now `@PreAuthorize`, with path
> rules in `SecurityConfig` as a second lock and `UserAccountSecurityTest`
> (10 tests) pinning it — asserting that the service is **never reached**, not
> merely that the status is 403. The account below is kept as the record.
>
> **One instance of the same defect remains**, in `VendorServiceImpl` lines 142
> and 205. It is not currently reachable (`VendorController` guards both routes
> with a correct `@PreAuthorize`), and it is a two-line fix —
> [`CODE-REVIEW.md` §2.2](CODE-REVIEW.md).


**Where.** `UserController` — 11 mutating endpoints under `/api/users/**` carry
`@PostAuthorize` instead of `@PreAuthorize`.

**What happens.** `@PostAuthorize` evaluates *after* the method returns, and the
service's transaction has already committed by then. The caller gets `403
Forbidden`; the database keeps the change. `SecurityConfig` has no path rule
granting ADMIN over `/api/users/**`, so **any authenticated account** reaches
these handlers.

**Concretely:** a signed-in customer sends
`DELETE /api/users/1007/permanent`, receives 403, and user 1007 is gone. The
same applies to editing any profile and to block/unblock/fraud/enable/disable.

**Fix applied.** Swapped the annotation on every rule, added path rules as a
second lock, and added a regression test. Full detail in
[`CODE-REVIEW.md` §2.1](CODE-REVIEW.md#21-postauthorize-on-mutating-endpoints-authorises-after-the-write-has-committed).

---

### 🔴 1.2 There is no way to create a production database schema

**Where.** No Flyway, no Liquibase, no `db/migration` directory. The only SQL in
the repository is the development seed.

**What happens.** `application-prod.properties` sets
`spring.jpa.hibernate.ddl-auto=validate` — correctly, because Hibernate cannot
drop, rename or roll anything back, and gives nobody a chance to review a change
before it reaches live data. But `validate` **only checks an existing schema**.
There is nothing that *creates* one.

Today the only path to a schema is running under `dev` with `ddl-auto=update`,
which the dev profile's own comment calls **disposable**, and then switching the
profile. That is not a deployment procedure; it is a way to end up with a
production schema nobody reviewed and nobody can reproduce.

The dev profile already anticipates this — *"so there is something to run
against before the Flyway baseline exists"*. The baseline does not exist.

**Fix.**

1. Add Flyway. Generate `V1__baseline.sql` by starting a clean `dev` instance,
   dumping the schema Hibernate produced, and **reading it by hand** — Hibernate
   writes no index you did not annotate, and a production schema wants indexes
   on the columns the 332 `@Query` declarations filter on.
2. Set `spring.flyway.baseline-on-migrate=true` for existing databases.
3. Keep `ddl-auto=validate` in prod. Flyway creates; Hibernate checks.

**Effort:** days, mostly review.

---

### 🟠 1.3 Stripe is integrated for cards, untested against live Stripe; no PayPal

> **Partly fixed.** `StripePaymentGateway` takes card payments through Stripe
> Checkout when `STRIPE_SECRET_KEY` is set and the mock is off, refunds through
> Stripe, and is settled by its signed webhook on `/webhooks/psp/stripe`
> (`Stripe-Signature` is read there). Covered by unit and routing tests, **not
> yet run end-to-end against Stripe's test mode** — do that before trusting it
> (see [`INTEGRATIONS.md`](INTEGRATIONS.md)). PayPal still has no gateway.
>
> The original entry follows.


**Where.** `PaymentGateway` has exactly one implementation: `MockPaymentGateway`,
which marks orders PAID without any money moving. It refuses to start under a
`prod` profile.

**What happens.** A production deployment **cannot take card or PayPal payments
at all**. Cash on delivery and bank transfer work (bank transfer only once bank
name, account name and account number are configured); everything else needs a
provider.

**Not a design gap.** The surrounding machinery is built and verified: the
gateway interface, the signed callback endpoint, the webhook intake with
store-before-act, HMAC verification with a replay window, retries, and the rule
that a provider does not get to restate what an order cost.

**Fix.** Implement `PaymentGateway` against a real provider. `MockPaymentGateway`
is a working example of the contract, including how to refuse when
unconfigured. Then `sujula.webhooks.secrets.<provider>` and
`PAYMENT_CALLBACK_SECRET` from the environment.

**Effort:** days per provider, plus whatever the provider's onboarding takes.

---

## 2. The constitution, where it is not fully reached

Each of these is a rule the codebase takes seriously and has not finished
applying. All three are named in the code's own comments.

### 🟠 2.1 `CurrencyCatalogue.round` is not on the money paths that need it — **C2**

**The rule.** *"Minor units are part of this. XOF has none — 1250.50 CFA is not
an amount that exists — so rounding is to the currency's own scale, never to
two. `CurrencyCatalogue` is the authority."*

**What is built.** `CurrencyCatalogue` is correct and complete: ten currencies,
per-currency `minorUnits` (XOF = 0), `round()` with `HALF_UP`, `smallestUnit()`,
and a startup refusal if the base currency is not in the catalogue.

**What is not.** The class says so itself: *"It is not yet used everywhere.
Order totals, delivery legs and payouts still scale to two places in their own
code, which is correct for dalasi, sterling and euro and wrong for CFA."*

**Consequence.** One of the two seeded vendors settles in XOF, so this is the
main path rather than an edge:

- A CFA total shown to a buyer in Ziguinchor is a figure they cannot tender.
- A payout carrying half a franc never reconciles against what the bank moved.
- Ledger sums in XOF accumulate fractional residue that no statement can
  explain.

**Fix, in order of risk:** payout assembly → ledger entry amounts → order and
sub-order totals → delivery legs. Grep `setScale(2` and `RoundingMode` across
`service/`. Add a test asserting that an XOF total has scale 0 — the class is
tested, the *callers* are not.

**Effort:** days. Touches money arithmetic across checkout, pricing and
settlement, which is why it was deferred rather than done half-way.

---

### 🟡 2.2 SMS is a sender, not yet a notification channel — **C5**

> **Partly fixed.** `SmsSender` exists, with `TwilioSmsSender` bound when
> `TWILIO_ACCOUNT_SID` is set. `requestRecipientCode` now texts the release code
> to the recipient's own number and still emails it to the buyer;
> `requestPhoneVerification` texts its code. With no provider configured,
> behaviour is exactly as described below. What remains: `SMS` is still not a
> `NotificationChannel`, so the status notices (out for delivery, at pickup
> point) do not reach a recipient by text, and nothing yet records Twilio's
> delivery receipts against the code they carried.
>
> The original entry follows.

**The rule.** *"An SMS code they read out to the driver is the design target.
Anything that requires the recipient to log in, install something, or click a
link in an email fails the case this marketplace exists to serve."*

**What is built.** Everything except the sender, and all of it is already the
right shape:

- the recipient needs no account, no app, no email;
- the tracking code reads, a six-digit code authorises;
- the public page carries fixed phrases, never anybody's free text;
- the driver cannot read the code, only request it;
- delivery needs code **and** position **and** photograph;
- the code is single-use and burned in the handover transaction;
- rate limits on re-sends are in place.

**What is not.** `NotificationChannel` is `IN_APP | EMAIL | PUSH`, with SMS
deliberately absent and the reason given: *"a channel that nothing actually
sends on is a preference somebody switches on and then waits for a message that
never comes. When there is a sender, there will be a value here."*

So the release code is **emailed to the buyer**, who relays the digits.

**Consequence.** For the seeded story this works — Fatou in Madrid tells her
sister. But the case the marketplace exists for is a recipient with a phone
number and nothing else, and today she depends on the payer being reachable,
awake, and in a timezone that overlaps with the delivery. A parcel arriving at
09:00 in Serrekunda is 01:00 in Toronto.

Phone verification has the same shape: `AuthServiceImpl` logs the code at INFO
in non-prod, noting *"when a provider exists this is where it is sent"*.

**Fix.** Add `SmsGateway` beside `PaymentGateway` — which already demonstrates
the pattern, including an implementation that refuses usefully when
unconfigured. Add `SMS` to `NotificationChannel`. Route
`requestRecipientCode` to the recipient's number, keeping the buyer's email as
fallback. Nothing else moves.

**Effort:** days.

---

### 🟡 2.3 Checkout refuses rather than honours the frozen quote — **C2**

**The rule.** *"FX rates are snapshotted, never recomputed."*

**What happens.** `CartQuote` freezes the figures and the rate each line was
converted at. `CheckoutServiceImpl` then re-prices through the single
order-assembly path and **reconciles** against the quote. A difference **rejects
the order** and rolls back.

The class states the trade-off exactly: *"a buyer is never charged a figure they
did not agree to … It does not yet mean they are charged the quoted figure when
the market moves underneath them: today they are asked to re-quote. Honouring
the frozen rate through assembly is the next step, and the frozen lines are
already on the quote for it — but a reconciliation that refuses is safe, whereas
a freeze that is half-applied is not."*

**Consequence.** During a rate move, a buyer who did nothing wrong reaches the
last step of checkout and is told to start the pricing again. On a 15-minute
window that is rare; on a volatile pair with a slow mobile connection it is not.

**Fix.** Pass the quote's `FxSnapshot` into order assembly so the same path
prices with the frozen rate, and keep the reconciliation as an assertion that
should now never fire — logging loudly if it does.

**Effort:** days. The safe half is already done; this is the second half.

---

## 3. Structural debt

### 🟠 3.1 Two parallel API generations

**116 of 432 operations** sit under `/api/**` and duplicate the flat-path
surface: two identity surfaces, two address books, two catalogues, two basket
implementations with **two different guest-ownership models**, two order
surfaces, two admin surfaces, two FX surfaces.

**Consequence.** Every finding above Low severity in `CODE-REVIEW.md` is on the
legacy side, including blocker 1.1. Two cart implementations means two chances
for one to skip a check the other makes. Two order surfaces means two places
that must both keep C3 and both freeze FX. Client authors cannot tell which to
use without reading the controllers.

**Fix, in order.** Deprecation banners in the Javadoc (one commit, stops new
client work landing wrongly) → fix 1.1 regardless of timeline → instrument with
the Micrometer registry already wired, to learn within a release what still
calls the 116 operations → delete in slices, cart first.

**Effort:** weeks, and mostly safe to do incrementally.

---

### 🟠 3.2 `OrderController` returns JPA entities over HTTP

Eleven handlers return `ResponseEntity<Order>`. `CLAUDE.md` forbids it and every
other controller obeys.

**Consequence.** The wire format is an accident of the schema, so every added
column is published and every rename is an unreviewed breaking change. With
`open-in-view=false` (correct), whether a nested association serialises depends
on what the service happened to fetch — a `LazyInitializationException` that
appears when a *query plan* changes rather than when the code does. And it
publishes more than the endpoint means to: the same class reaches the buyer, the
**unauthenticated guest order lookup**, and the admin surface.

**Fix.** `BuyerOrderResponses` and `CheckoutResponses` already model the shapes.
Guest paths first.

**Effort:** days.

---

### 🔵 3.3 `DeliveryScope.REGIIONAL` and `RECOGER`

A typo (`REGIIONAL`, two `I`s) baked into an enum, two property keys, seed SQL
and persisted data; and one Spanish word (*recoger*, "to collect") in an
otherwise-English enum.

**Consequence.** Every operator editing a rate card must reproduce the typo, and
a *corrected* spelling in a properties file silently fails to bind — the
multiplier falls back and nobody sees a pricing change. Both appear in API
responses, so client code carries them.

**Fix.** Rename the constants, the property keys, the seed and the e2e fixtures,
and migrate the persisted column — all in one release, or not at all.

**Effort:** hours, but it must be atomic.

---

## 4. Operations

### 🟠 4.1 No CORS policy

No `CorsConfigurationSource`, no `.cors(...)`, no `@CrossOrigin`. The API is
**same-origin only**: a browser client on a different domain cannot call it.

This may well be deliberate — a same-origin SPA behind one reverse proxy is a
good architecture, and the invoice base-URL comment assumes exactly that
(*"Blank yields a relative URL, which is what a same-origin single-page client
wants"*). But it is nowhere stated, and it is the first thing a front-end team
hits.

**Fix.** Either document "same-origin, one reverse proxy" (this documentation
set does, in `OPERATIONS.md`) or add a configured allow-list. **Never**
`allowedOriginPatterns("*")` with credentials — the CSRF cookie and the
guest-cart cookie both make that a real hole.

---

### 🟠 4.2 No general rate limiting

Targeted limits exist and are thoughtful: sign-in escalation, phone codes per
hour, recipient release codes per shipment per hour, queued imports per vendor,
staff invitations per store (explicitly so an invite loop cannot be used to send
mail).

Nothing limits the surface as a whole. The exposed endpoints are the
unauthenticated, computation-heavy ones: `POST /delivery/quote`,
`/delivery/serviceability`, `POST /geo/validate-address` (**which spends a paid
Google call per request**), `GET /search`, and `POST /carts` (a row per call).

**Fix.** At the reverse proxy or gateway, not in application code.
`OPERATIONS.md` gives a starting configuration.

---

### 🟠 4.3 No CI, no container, no deployment artefact

No `.github/`, no `Dockerfile`, no `docker-compose.yml`, no pipeline of any
kind. The build is `mvn` on somebody's machine, producing a WAR.

**Consequence.** Nothing guarantees the 1,233 tests ran before a change landed.
Nothing pins the JDK, the MySQL version, or the deployed artefact's provenance.

**Fix.** A pipeline that runs `mvn -B test`, then the `e2e` collection (it needs
no external services — that is precisely why the `e2e` profile exists), then
builds the WAR. A `Dockerfile` on a JDK 21 base. Both are a day's work and both
pay for themselves on the first regression.

---

### 🟡 4.4 No secret rotation procedure

Everything comes from environment variables, which is right. Nothing documents
how to rotate one.

The sharp case: **rotating `FIELD_ENCRYPTION_KEY` requires re-encrypting every
existing row**, and there is no tooling for it. Losing that key means bank
details are unreadable, with no recovery — so it must be backed up separately
from the database, and that is currently only stated in a comment.

Rotating the JWT signing key invalidates every live access token (acceptable, they last
ten minutes) but not refresh tokens, which are hashed independently.

**Fix.** Write the procedure. Add a re-encryption command for the field key,
with a two-key transition period.

---

### 🔵 4.5 Single-instance assumptions

- **Scheduled jobs** use plain `@Scheduled` with no distributed lock, so **every
  instance runs every job**. Two instances means two webhook drains, two export
  workers and two cart cleanups. The processors look idempotent, but this has
  not been tested under concurrency.
- **`INVOICE_SIGNING_SECRET`**, unset, is generated per process — links issued by
  one instance are invalid on another. The config comment says so; it is easy to
  miss.
- **Login attempt counts** are in the database, so those are fine.

**Fix.** ShedLock or an equivalent before running more than one instance, and
make `INVOICE_SIGNING_SECRET` a startup requirement under `prod` the way
`jwt.secret` already is.

---

## 5. Test coverage

1,233 tests, 0 failures, and the suite is strong where it matters — custody,
money, FX, C1 separation, and per-surface routing tests asserted against a
booted application with the real filter chain.

**What no test mentions at all:**

| Area | Risk | Why it matters |
|---|---|---|
| `CartServiceImpl`, `CartSessionServiceImpl`, **`CartOwner`**, `CartProvisioner` | 🟠 | `CartOwner` is *the* place that decides whose cart a request touches. Its own Javadoc says a client-supplied id is a bearer token for whichever cart it names. It is untested. |
| `WebhookWorker`, `CatalogueJobWorker`, `ReportExportWorker`, `AccountDataWorker`, `PlatformSweeper`, `GuestCartCleanupJob` | 🟠 | The *processors* are tested; the scheduling, retry and failure-recording wrappers are not. |
| `MockPaymentGateway` | 🟡 | Its refusal to start under `prod` is a safety property and nothing asserts it. |
| `UserController` | 🔴 | **No test file exists**, which is part of why blocker 1.1 is there. |
| `CategoryService`, `ProductQuestionService`, `GeoServiceImp`, `EncryptedStringConverter`, `ProductViewRecorder` | 🔵 | |

**Three tests worth writing first:**

1. The 1.1 regression — assert the row is **still there** after the 403.
2. `MockPaymentGateway` refuses to construct under `prod`.
3. `CartOwner`: a guest cookie cannot reach a signed-in buyer's cart, and a
   signed-in buyer's token never resolves to a guest cart.

**Also missing as a category:** no load or concurrency testing at all. The
optimistic-lock path (`InventoryServiceTest`) and the last-unit race are
asserted logically but never under real contention.

---

## 5A. The front end

Five client applications, ~46,700 lines, reviewed in full in
[`frontend/README.md` §6](frontend/README.md#6-cross-application-code-review).

### 🟠 5A.1 No tests in any of the five applications

Zero test files across `admin`, `vendor-app`, `driver-app`, `pickup-app` and
`buyer-app`. Type-checking is real coverage of *shape* — **zero `any` in 46,700
lines**, which is genuinely rare — but it cannot test behaviour, and the
behaviour here is difficult and consequential:

| Untested | Cost if it regresses |
|---|---|
| The **driver outbox** — ordering, dedup, backoff, restart survival | **Lost custody evidence.** A seller is not paid and nobody can say why. Its own source calls it *"the part of the app that has to be right"* |
| The **PIN vault** — PBKDF2, AES-GCM, the five-try wipe | A driver locked out mid-round, or a stolen phone that still works |
| **Refresh single-flight** (all five) | Two concurrent 401s sign the user out for real |
| **Money scale per currency** (all five) | An XOF total with two decimal places |
| **HTML escaping** in `buyer-app` | XSS, with a 30-day refresh token to steal (§5A.2) |
| The **support/admin decide split** | Support offered actions the server will refuse |

Six test files would cover most of it, and Vitest ships with Vite in four of the
five. See each app's document for the specific ones.

### 🟠 5A.2 `buyer-app` keeps both tokens in `localStorage`

The other four keep the access token in a module variable, for a reason the
admin store states plainly: *"a token that survives in storage is a token a
single injected script can take away with it and use from anywhere."*

`buyer-app` is also the app that renders the most user-generated text, through
**79 `innerHTML` assignments**, with safety resting on one hand-applied `esc()`
helper. The discipline currently holds — every free-text interpolation is
escaped — but it is untested, and one omission would lift a 30-day refresh token
rather than a 10-minute access token.

**Fix:** move the access token out of storage (small — the refresh token already
rebuilds the session on boot), and add a test for `esc()`.

### 🟠 5A.3 The idempotency key is minted per attempt in three of five apps

The server's idempotency layer exists for one case: *"a request that times out
on a slow connection is indistinguishable, from the client's side, from one that
never arrived."* It only works if the key is **stable across retries of one user
intention**.

`driver-app` gets this right — the key is a required parameter, minted when the
driver taps and stored in IndexedDB beside the event — and says why: *"a key
generated at send time is a new key every retry, which is the same as having
none."*

`admin` and `buyer-app` mint inside the call; `vendor-app` and `pickup-app` do
so by default unless a screen passes one. The worst instance is
`POST /checkout` in `buyer-app`: a shopper whose checkout times out and who
tries again places a **second order** and takes a **second payment**.

**Fix:** mint where the user acts, hold it for that intention, pass it to every
attempt. Four lines in `buyer-app`.

### 🟡 5A.4 No error boundary in four of five apps

Only `driver-app` has one. A render-time exception blanks the screen with no
message and no way back — worst on `pickup-app`, where it happens with a
customer standing at the counter.

### 🔵 5A.5 Five API clients that must stay in step

~250 lines of token handling, CSRF, refresh and error mapping duplicated five
ways. The decision is defensible — the apps ship independently and `buyer-app`
has no build step — but the correctness rules live in those 250 lines, and
§5A.3 is what drift looks like. A shared **test suite** would have caught it
where a shared package would not.

---

## 6. Features that are modelled but not driven

The schema and services exist ahead of the endpoints or the integrations that
would exercise them. This is deliberate in most cases — the seed file notes
"even though no endpoint writes them yet" — but a reviewer should know.

| Area | State |
|---|---|
| **Push notifications** | `PushDevice` registration exists (`POST /notifications/devices`); `FcmPushSender` sends through Firebase when `FCM_CREDENTIALS` is set, and `LoggedPushSender` stands in otherwise. Not yet exercised against a live Firebase project. |
| **OAuth sign-in** | Google and Apple callbacks exist and are seeded (Oliver signs in with both). Provider credentials are deployment configuration; an account with no chosen password is handled. Not exercised against live providers. |
| **GraphQL codegen** | The POM runs `graphqlcodegen-maven-plugin` over `src/main/resources/graphql-client`. Nothing in `src/main/java` consumes the generated classes yet. Either wire it or remove the plugin — a build step producing dead code is a build step nobody maintains. |
| **MaxMind GeoIP2** | Dependency present; falls back to locale then base currency when no database file is supplied. |
| **`delivery_tracking`** | Seeded and read; the buyer's own confirm-receipt writes it. Some rows exist only because the seed wrote them. |
| **Sanctions / moderation cases** | Fully modelled with its own registry and tests; the admin surface drives it. Exercised in tests, lightly in the collection. |

---

## 7. Product-level gaps

Things the marketplace does not do, which a reviewer may reasonably expect.

| | |
|---|---|
| 🟡 **Recipient confirmation of receipt** | Only the buyer can `confirm-receipt`. The recipient — who actually has the goods — cannot, although she can do three other things without an account. Worth considering, since she is the one who knows. |
| 🟡 **Escrow release policy** | Money is released on proven delivery; there is no automatic time-based release for a parcel delivered but never confirmed, so escrow can sit indefinitely. `PlatformSweeper` exists and would be the place. |
| 🟡 **Partial shipment within one vendor's slice** | C3 splits by vendor. A single vendor shipping two of three items in two parcels is not modelled. |
| 🔵 **Multi-currency reporting** | Correctly refused — a two-currency shop gets two figures and a note saying why they are not added. Operators will eventually want a converted view; it must carry its rate and date if it is ever added. |
| 🔵 **Search** | Database `LIKE`-based with location ranking. No full-text index, no typo tolerance, no synonyms. Fine at current scale; the first thing to feel slow. |
| 🔵 **Product reviews** | Only from an order line (correct — verifies purchase). No image reviews, no helpfulness voting. |
| 🔵 **Driver routing** | Legs are offered and accepted; there is no route optimisation or batching. `DeliveryRoute` exists as a model. |
| 🔵 **Returns shipping** | A return is approved and the goods come back; the return leg is not a custody chain of its own. |

---

## 8. Documentation

| | |
|---|---|
| ✅ | In-code documentation is exceptional. Class Javadoc explains *why*, and the seed file's closing 900 lines are a guided tour of the whole system. |
| 🟡 | The OpenAPI document is generated but off outside `dev`, so there is no published API reference for client teams. `e2e/endpoints.json` is the practical substitute and is checked in. |
| 🟡 | No architecture decision records. The reasoning exists but is distributed across Javadoc, so "why is it like this" requires knowing which class to open. This `docs/` set is a first pass at collecting it. |
| 🔵 | No changelog and no API versioning scheme — which is part of why two API generations could grow side by side without anyone having to decide. |

---

## 9. What to do, in order

| # | Item | Why now | Effort |
|---|---|---|---|
| ~~1~~ | ~~🔴 Fix the `@PostAuthorize` writes (§1.1)~~ | | ✅ done |
| ~~3~~ | ~~🔵 Stop defaulting to the `dev` profile~~ | | ✅ done |
| ~~4~~ | ~~🔵 Fix `requireOwnedAddress`~~ | | ✅ done |
| **1** | 🟠 The last `@PostAuthorize` write, `VendorServiceImpl` 142 and 205 ([`CODE-REVIEW.md` §2.2](CODE-REVIEW.md)) | Not reachable today, but the annotation reads as though it protects | minutes |
| 2 | 🔴 Add Flyway and a reviewed baseline (§1.2) | There is otherwise no way to deploy a schema | days |
| 2b | 🟠 `UpdatePreferencesRequest` binds nothing a client sends ([`CODE-REVIEW.md` §6.7](CODE-REVIEW.md)) | A settings screen that answers 200 and changes nothing | minutes |
| 2c | 🟠 Orders disclose their existence ([`CODE-REVIEW.md` §4.3](CODE-REVIEW.md)) | 400 for somebody else's order, 404 for a missing one | hours |
| 5 | 🟠 Decide and document the CORS posture (§4.1) | Front-end work is blocked on the answer | hours |
| 6 | 🟠 CI pipeline running tests + the e2e collection (§4.3) | Nothing currently guarantees the suite ran | 1 day |
| 7 | 🟠 Rate limiting at the proxy (§4.2) | `/geo/validate-address` spends real money per call | hours |
| 8 | 🔴 Integrate a payment provider (§1.3) | No card payments are possible | days+ |
| 9 | 🟠 `CurrencyCatalogue.round` on payouts, then ledger, then totals (§2.1) | XOF figures are wrong where it counts | days |
| 10 | 🟠 DTOs for `OrderController`, guest paths first (§3.2) | The schema is the public API today | days |
| 11 | 🟠 `SmsGateway`, and C5 reaches its stated target (§2.2) | The recipient stops depending on the payer | days |
| 12 | 🟡 Price order assembly from the quote's snapshot (§2.3) | Checkout stops failing on rate moves | days |
| 13 | 🟠 Tests for `CartOwner` and the workers (§5) | The cart ownership decision is untested | days |
| 14 | 🟠 Deprecate, instrument, then delete `/api/**` (§3.1) | Removes the surface every finding lives on | weeks |
| **F1** | 🟠 **Stable idempotency keys in the clients (§5A.3)** | A timed-out checkout places a second order | hours |
| **F2** | 🟠 Move `buyer-app`'s access token out of `localStorage` (§5A.2) | Halves the payoff of any XSS | hours |
| **F3** | 🟠 The six front-end tests (§5A.1) | The outbox and the vault are untested | days |
| **F4** | 🟡 An error boundary in the four apps missing one (§5A.4) | A blank screen at a counter | hours |

Items 1, 3 and 4 are each under an hour and each close something that is wrong
right now. They are worth doing before anything else on this list.

---

## 10. A closing note for reviewers

It would be easy to read this document as a list of failures. It is not.

Almost every gap here is one the codebase **names itself**, in the class that
owns it, in prose, before anyone audited it. `CurrencyCatalogue` says `round` is
not used everywhere. `CheckoutServiceImpl` says exactly what the quote does and
does not guarantee. `NotificationChannel` explains why SMS is absent and what
will happen when a sender exists. There are **zero** `TODO` or `FIXME` markers
in 87,000 lines, because the unfinished work is described where it will be read
rather than parked where it will not.

The items that were genuinely unnoticed rather than deferred — §1.1, and the
two found while fixing it (`CODE-REVIEW.md` §4.3 and §6.7) — all sit on the
legacy `/api/**` surface, the part of the code written before the constitution
the rest of the application is built to. §1.1 is now fixed.

That pattern — deliberate scope, named gaps, and the defects clustered where the
current discipline has not yet reached — is a good sign about the codebase, not
a bad one.
