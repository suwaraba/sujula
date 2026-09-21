# Sujula — Documentation

Sujula is a **multi-vendor marketplace with a diaspora-remittance shape**: the
person who pays and the person who receives may be different people, in
different countries, on different continents.

A buyer in Madrid pays in euro for a phone listed in dalasi by a seller in
Banjul, which is delivered to their sister in Serrekunda — who has no account —
and the seller is not paid until delivery is proven, in dalasi, at the rate
frozen on the day the order was placed.

Everything in this repository follows from that sentence.

---

## Start here

| If you are… | Read, in this order |
|---|---|
| **Reviewing the application** | [Reviewer's route](#a-reviewers-route) below |
| **New to the codebase** | [`../CLAUDE.md`](../CLAUDE.md) → [`BACKEND.md`](BACKEND.md) → the code |
| **Building a client** | [`frontend/README.md`](frontend/README.md) → your app's document |
| **Testing it by hand** | [`TESTING.md`](TESTING.md) |
| **Deploying it** | [`SECURITY.md` §9](SECURITY.md#9-the-deployment-checklist) → [`OPERATIONS.md`](OPERATIONS.md) |
| **Deciding whether to ship** | [`LIMITATIONS.md`](LIMITATIONS.md) → [`CODE-REVIEW.md`](CODE-REVIEW.md) |
| **Not a specialist, but responsible for security** | [`SECURITY.md`](SECURITY.md) — written from first principles |

---

## The documents

### [`BACKEND.md`](BACKEND.md) — Technical reference
Architecture, layering, the domain model, every module, the HTTP surface, how
each of the five rules is realised in code, persistence, external dependencies,
build and test. **Start here for "how does this work".**

### [`CODE-REVIEW.md`](CODE-REVIEW.md) — Quality review
A read of the codebase against its own constitution and against ordinary
Spring/JPA practice. What is genuinely good and why, then findings ordered by
consequence, each with a file reference, a concrete failure and a fix.
**Contains one critical authorization defect.**

### [`SECURITY.md`](SECURITY.md) — Security, explained from scratch
Part I explains what security *is*, in plain language, with no Sujula in it.
Part II is what this application actually does. Part III is how to change things
safely. Part IV is what is missing. Ends with a deployment checklist and a
glossary. **Written for somebody who does not already know the vocabulary.**

### [`TESTING.md`](TESTING.md) — Manual test plan
211 numbered checks across 12 suites, with a Pass/Fail column, every one
referencing real seeded data. Gets a server running in one command. **More than
half the rows check that the application refuses something** — those are the
ones that matter.

### [`LIMITATIONS.md`](LIMITATIONS.md) — Gaps and improvements
What is not built, what is half-built, what is deliberate, and what it would
take. Marked 🔴 blocker / 🟠 major / 🟡 known gap / 🔵 improvement, and ordered.

### [`OPERATIONS.md`](OPERATIONS.md) — Running it
Profiles, the full configuration reference, database and backups, reverse proxy,
rate limiting, background jobs, health and metrics, deployment steps, startup
refusals, troubleshooting and runbooks.

### [`frontend/`](frontend/README.md) — Client specifications
**There is no front-end code in this repository.** These are build
specifications, one per client application the API is shaped to serve.

| | |
|---|---|
| [`frontend/README.md`](frontend/README.md) | **Shared contract** — auth, refresh, CSRF, errors, idempotency, money formatting, first paint. Read before any of the others |
| [`BUYER-STOREFRONT.md`](frontend/BUYER-STOREFRONT.md) | Shoppers and guests |
| [`VENDOR-CONSOLE.md`](frontend/VENDOR-CONSOLE.md) | Sellers — 66 operations |
| [`DRIVER-APP.md`](frontend/DRIVER-APP.md) | Couriers — offline-first, evidence capture |
| [`PICKUP-COUNTER.md`](frontend/PICKUP-COUNTER.md) | Shop counters holding parcels |
| [`ADMIN-CONSOLE.md`](frontend/ADMIN-CONSOLE.md) | Staff — 95 operations |
| [`RECIPIENT-PAGE.md`](frontend/RECIPIENT-PAGE.md) | **Somebody with no account** — six operations, and the point of the platform |

### [`GLOSSARY.md`](GLOSSARY.md)
Domain terms, statuses, enums and the vocabulary this codebase uses.

---

## Also in the repository

| | |
|---|---|
| [`../CLAUDE.md`](../CLAUDE.md) | **The constitution.** The five rules, stated as correctness conditions. Read it first; everything else assumes it |
| [`../e2e/COLLECTION.md`](../e2e/COLLECTION.md) | Every endpoint, called by every kind of person who can call it |
| `../e2e/endpoints.json` | All **432** operations with summaries, captured from a live server |
| `../src/main/resources/db/seed/dev-seed.sql` | The development seed. Its closing 900 lines are a guided tour of the whole system, row by row |
| Class Javadoc | The real documentation. `CustodyChain`, `CurrencyCatalogue`, `SecurityConfig` and `WebhookSignature` are each worth reading end to end |

---

## The five rules

Stated in [`../CLAUDE.md`](../CLAUDE.md). Code that breaks one is wrong even
when its tests pass.

| | | Where it lives |
|---|---|---|
| **C1** | **Two independent locations.** The payer's and the delivery's, never collapsed | `CatalogueController`, `DeliveryContextService`, `C1SeparationTest` |
| **C2** | **Two currencies per order.** Rates are snapshotted, never recomputed. Minor units are part of the rule | `CurrencyCatalogue`, `FxSnapshot`, `FxQuoteService` |
| **C3** | **One payment, many vendors**, shipping and refunding independently | `VendorOrder`, `AfterSalesController`, `MoneyLedger` |
| **C4** | **Custody is a chain, not a status.** Every transfer is a verified event with proof | `CustodyChain` — 290 lines, read it whole |
| **C5** | **The recipient may not have an account** | `RecipientParcelController`, `PublicTrackingController` |

---

## The system in numbers

| | |
|---|---|
| HTTP operations | **432** |
| Controllers · services · repositories · entities | 47 · 47 · 84 · 87 |
| `@Query` declarations | 332 |
| Main source | ~87,000 lines |
| Tests | **1,233**, 90 classes — **0 failures, 0 errors, 0 skipped** |
| Scheduled background jobs | 7 |
| `TODO` / `FIXME` markers | **0** |

---

## A reviewer's route

Four hours, in this order:

1. **[`../CLAUDE.md`](../CLAUDE.md)** — 10 minutes. Nothing else makes sense
   without it.
2. **[`BACKEND.md` §1–§6](BACKEND.md)** — 45 minutes. The shape, and how each
   rule is mechanised.
3. **`CustodyChain.java`** — 15 minutes. 290 lines, and the clearest example of
   this codebase's method: the dangerous operation is made *impossible* rather
   than *forbidden*.
4. **`SecurityConfig.java`** — 20 minutes. Every access rule with its reasoning
   beside it.
5. **The seed's closing notes** (`dev-seed.sql`, from "What you now have") — 30
   minutes. A guided tour of the whole system through real data, explaining what
   each row is for and what it refuses.
6. **[`CODE-REVIEW.md`](CODE-REVIEW.md)** — 30 minutes. What is good, and the
   findings.
7. **[`LIMITATIONS.md`](LIMITATIONS.md)** — 20 minutes. Especially §1 and §9.
8. **Run it** — 15 minutes. `cd e2e && ./run.sh`, then poke at it as the people
   in [`TESTING.md` §2](TESTING.md).

### Three things to know before you decide anything

**🔴 One critical defect.** `@PostAuthorize` on 11 mutating endpoints in
`UserController` authorises *after* the write has committed. An ordinary
signed-in customer can delete other accounts; the response is 403 and the row is
gone. [`CODE-REVIEW.md` §2.1](CODE-REVIEW.md#21-postauthorize-on-mutating-endpoints-authorises-after-the-write-has-committed).
Hours to fix.

**🔴 Two things block production regardless of code quality.** There is no
migration tool, so there is no reviewed way to create a production schema
([`LIMITATIONS.md` §1.2](LIMITATIONS.md)); and no payment provider is
integrated, so card payments are impossible
([`LIMITATIONS.md` §1.3](LIMITATIONS.md)).

**✅ The codebase is unusually disciplined, and the discipline is structural.**
Dangerous states are made unrepresentable rather than validated against.
Derived figures are derived every time — balances are sums of ledger entries,
stock is the sum of movements, shipment status is a pure function of the custody
chain. A large fraction of the test suite asserts what the application *refuses*.
And nearly every gap in [`LIMITATIONS.md`](LIMITATIONS.md) was named by the code
itself, in the class that owns it, before anyone audited it.

The defects cluster on the **legacy `/api/**` surface** — the part written
before the constitution the rest of the application is built to. That is a good
sign about the trajectory, not a bad one.
