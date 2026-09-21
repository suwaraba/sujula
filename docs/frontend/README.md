# Sujula — Front-End Technical Documents

> **These document code that exists.** `frontend/` holds five separate client
> applications against one API. Each has its own README explaining *what it
> does*; the documents here explain **how each is built, what its architecture
> is, and how good the code is** — the front-end half of
> [`../CODE-REVIEW.md`](../CODE-REVIEW.md).
>
> Read this file first: it carries everything the five share.

| Application | Users | Stack | Source | Document |
|---|---|---|---|---|
| **`admin/`** | ADMIN and SUPPORT | React + TS + Vite, TanStack Query | ~16,300 lines, 64 files | [ADMIN-CONSOLE.md](ADMIN-CONSOLE.md) |
| **`vendor-app/`** | Sellers | Same, **+ Capacitor** for Android and iOS | ~13,300 lines, 69 files | [VENDOR-APP.md](VENDOR-APP.md) |
| **`driver-app/`** | Couriers | Same, **+ IndexedDB, ZXing** — installable PWA | ~7,900 lines, 42 files | [DRIVER-APP.md](DRIVER-APP.md) |
| **`pickup-app/`** | Counter operators | Same — installable PWA | ~4,700 lines, 32 files | [PICKUP-APP.md](PICKUP-APP.md) |
| **`buyer-app/`** | Shoppers and guests | **Plain ES modules, no build step, zero dependencies** | ~4,500 lines, 23 files | [BUYER-APP.md](BUYER-APP.md) |

**~46,700 lines of client code, zero runtime dependencies outside React and
TanStack Query, and zero tests.** That last figure is the headline finding of
§6.

---

## 1. Why five applications and not one

`frontend/README.md` states the position and it is the right one: *"They share
nothing but the API, deliberately: a driver's phone and an administrator's
desktop have almost no screen in common, and a shared component library between
them would be a library of things used once."*

The evidence supports it. The five overlap on **sign-in, the account screen and
notifications** and on nothing else. A shared design system would have had five
consumers and three components.

What *is* duplicated is the **API client**: each app has its own `api/client.ts`
(or `api.js`) implementing the same four things — bearer token, CSRF
double-submit, refresh single-flight, money formatting. That duplication is
discussed in §6.1; it is a defensible choice with one real cost.

---

## 2. Deployment: same origin, no exceptions

**The backend has no CORS configuration**, and its CSRF protection is a
double-submit cookie (`XSRF-TOKEN`, `httpOnly=false`) that only a same-origin
page can read. So every client is served **same-origin with the API** — through
Vite's proxy in development, and behind one reverse proxy in production.

This is not a workaround. It is what lets the session cookie, the CSRF token and
the bearer token all work with no cross-origin request anywhere, and the driver
app's README names it as one of three reasons it is a PWA rather than a native
build.

### 2.1 The proxy collision every client hits

> *"The dev proxy is not a list of the API's prefixes. It is a list of the
> prefixes **that client** calls."*

Spring serves the API at the root, so client routes collide with API paths.
`vendor-app/` has screens at `/orders` and `/products`; proxying either sent
deep links to the backend, which answered 401 instead of opening the app.

**The same collision reappears in production** if a client is served from the
API's origin. Each app's README says where it must live. Read it before
configuring a proxy.

### 2.2 Ports

`admin` 5173 · `vendor-app` 5174 · `driver-app` 5175 · `pickup-app` 5176 ·
`buyer-app` 5177 — so all five can run against one backend.

---

## 3. The shared client contract

Everything in this section is implemented in all five, and getting any of it
wrong breaks the app in a way that is hard to trace.

### 3.1 Two credentials

```jsonc
// POST /auth/login
{ "mfaRequired": false,
  "tokens": { "accessToken": "eyJ…", "tokenType": "Bearer", "expiresIn": 600,
              "refreshToken": "…", "sessionId": 1801, "user": { … } } }

{ "mfaRequired": true, "tokens": null }   // password right, second factor needed
```

`mfaRequired` is a **successful** response, not an error — *"your password was
right and I need one more thing"* is not a failure, and a client that cannot
tell it from a wrong password shows the wrong message. Branch on the flag, never
on the status code.

Access tokens last **10 minutes**. The server keeps no copy and cannot revoke
one, so that lifetime *is* the blast radius of a theft.

### 3.2 Refresh must be single-flight

The server **rotates** refresh tokens and treats a second presentation of a
spent one as **theft: 401, and the session is revoked**.

So two concurrent 401s that both refresh will sign the user out for real. Every
app collapses refresh into one in-flight promise:

```ts
let refreshInFlight: Promise<boolean> | null = null;

async function refreshSession(): Promise<boolean> {
  if (refreshInFlight) return refreshInFlight;
  refreshInFlight = (async () => { /* … */ })();
  return refreshInFlight;
}
```

The admin implementation clears the flag on the **next tick** rather than
immediately, so every caller awaiting the promise sees the same result before a
new attempt can start. That is a real race, correctly closed.

### 3.3 Where the tokens live — and the one app that differs

| App | Access token | Refresh token |
|---|---|---|
| `admin` | **memory only** | `sessionStorage` — a reload keeps you in, closing the tab does not |
| `vendor-app` | **memory only** | Keychain / SharedPreferences on device; storage on web |
| `driver-app` | **memory only** | **encrypted at rest** — AES-GCM under a PBKDF2-SHA256 key from a 4-digit PIN, 310,000 iterations, destroyed after five wrong tries |
| `pickup-app` | **memory only** | storage |
| `buyer-app` | ⚠ **`localStorage`** | ⚠ **`localStorage`** |

The admin reasoning is worth quoting because it is the correct general rule:

> *"The access token lives in a module variable and nowhere else. It is never
> written to `localStorage`, because this surface can refund a payment,
> impersonate a customer and reset somebody's second factor, and a token that
> survives in storage is a token a single injected script can take away with it
> and use from anywhere."*

The driver vault is the most careful piece of security code in the whole
repository, front or back, and it is honest about its limits: *"it does not buy
protection from malicious code running in this origin. Nothing in a browser
does."*

`buyer-app` is the exception and is discussed as a finding in §6.2.

### 3.4 CSRF double-submit

```ts
function csrfToken(): string | null {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
  return match ? decodeURIComponent(match[1]) : null;
}
```

Echoed as `X-XSRF-TOKEN` on every mutating request. The admin client **primes**
the cookie on start-up with a cheap public GET (`/config/public`), and again if
a write ever finds no cookie — because the cookie is only minted once a request
has passed through the filter chain.

### 3.5 Errors

The backend answers one JSON shape everywhere, including from the filter chain
(`FilterChainRefusals`). Status meanings:

| Status | Means | The client should |
|---|---|---|
| **401** | **No credential, or a bad one.** *"Say who you are and try again"* | Refresh once, then sign in |
| **403** | **Signed in, not permitted.** *"Signing in will not help"* | Not retry. Re-check `/me/permissions` |
| **404** | Not there — **or not yours** | Say *"we could not find that"*, never *"it does not exist"* |
| **409** | Two people editing one row | Re-read, show both, let the user choose |

The admin `ApiError` models exactly this, including the subtle case:

```ts
/** The step-up endpoints answer 401 when the password or code is wrong.
 *  That is not a dead session, so it must not trigger a refresh. */
get isCredentialChallenge(): boolean {
  return this.status === 401 && /password|code|confirm/i.test(this.message);
}
```

> A 401 that means "wrong password" and a 401 that means "expired token" are
> distinguished by matching on the message text. It works, and it is the only
> signal the server gives — but it is a string match against prose, and it is
> the one place in these clients where a server wording change breaks a client
> silently. Noted as a finding in §6.5.

### 3.6 Money: format, never compute

Every client has a money module, and every one of them refuses to do
arithmetic:

> *"There is no arithmetic in this file on purpose. Every total this console
> shows was computed by the server, in one currency, against a rate that was
> snapshotted when the order was placed. A sum done here would be a second
> opinion about somebody's money, and the two would disagree the first time a
> rate moved."*

What the client **does** own is **scale**. `GET /currencies` publishes
`minorUnits`; **XOF has none**, so `1250.50 CFA` must never be rendered. The
fallback chain is careful:

```
catalogue.minorUnits  →  Intl's own table (also right for XOF and JPY)  →  2
```

*"A wrong scale is visible in the number, so this never silently guesses without
a source."*

### 3.7 Idempotency

`Idempotency-Key` on writes that may be retried. **Same key + same body** →
the recorded response; **same key + different body** → refused.

The driver app states the rule the others should follow:

> *"One idempotency key per request. Stored beside the event for the same
> reason: a key generated at send time is a new key every retry, which is the
> same as having none."*

Three of the five do not follow it. **See §6.3 — it is the most consequential
front-end finding.**

---

## 4. Architecture at a glance

The four React apps share one shape, and it is a good one:

```
src/
├── api/
│   ├── client.ts      bearer token · CSRF · refresh single-flight · errors
│   ├── endpoints.ts   ONE TYPED FUNCTION PER ENDPOINT. Nothing above builds a URL
│   ├── types.ts       response shapes
│   ├── enums.ts       mirrored from the server's enums
│   └── policy.ts      (admin) server constants the form needs to know
├── auth/              token store · context · route guards · sign-in
├── money/ or lib/     formatting only
├── components/        this app's primitives
└── pages/ or screens/ one file per screen, grouped as the server groups them
```

**`endpoints.ts` is the load-bearing idea.** One function per endpoint, grouped
the way the server groups them, so *"the six `Admin*Controller`s and the six
objects below are the same list read twice"*. Nothing above that layer builds a
URL or picks a verb. It is the reason these clients are legible.

**Server state is TanStack Query; UI state is local.** There is no Redux, no
Zustand, no global store beyond one context for auth and (in `vendor-app`) one
for the current store. For an application that is mostly server data, this is
the right call and it is applied consistently.

`buyer-app` deliberately opts out of the whole toolchain — see §5.

---

## 5. The one that is not on the house toolchain

`buyer-app/` is **plain ES modules, no build step, no dependencies**, where the
other four are React + TypeScript on Vite. Its README argues the case:

> *"Its users are on the worst connections and the cheapest phones on the
> platform, and it is the only client somebody abandons rather than persists
> with."*

That is a real argument and the right one for this marketplace. A shopper has a
competitor one tap away; an administrator learns their console, a driver is
trained on theirs, a seller has a reason to persevere.

It still follows the house shape — one function per endpoint, a client owning
the token, CSRF double-submit, refresh single-flight, money formatted to each
currency's own scale — and `npm run check` stands in for `tsc --noEmit`.

The index's own guidance is sound: *"If it grows past one maintainer, port it
rather than extend it."*

---

## 6. Cross-application code review

Everything here was verified by reading the source. Findings specific to one app
are in that app's document.

### Summary

| # | Finding | Severity |
|---|---|---|
| [6.1](#61-no-tests-in-any-of-the-five-applications) | **No tests in any of the five applications** | **High** |
| [6.2](#62-buyer-app-keeps-both-tokens-in-localstorage) | `buyer-app` keeps **both** tokens in `localStorage` | **High** |
| [6.3](#63-the-idempotency-key-is-minted-per-attempt-in-three-of-five-apps) | Idempotency key minted **per attempt** in three of five apps | **High** |
| [6.4](#64-no-error-boundary-in-four-of-five-apps) | No error boundary in four of five apps | Medium |
| [6.5](#65-a-401-is-classified-by-matching-prose) | A 401 is classified by matching prose | Low |
| [6.6](#66-five-api-clients-that-must-stay-in-step) | Five API clients that must stay in step | Low (structural) |

### 6.1 No tests in any of the five applications

```
admin        0 test files
vendor-app   0
driver-app   0
pickup-app   0
buyer-app    0
```

Against a backend with **1,233 passing tests**, this is the sharpest asymmetry
in the repository. Type-checking (`npm run typecheck`, `npm run check`) is real
coverage of *shape*, and `any` appears **zero times** across all five — genuinely
rare. But type-checking cannot test behaviour, and these apps contain behaviour
that is difficult and consequential:

| Untested, and hard to get right | Where |
|---|---|
| **Refresh single-flight under concurrency** | all five. The failure mode is *signing the user out for real* |
| **The offline outbox** — ordering, backoff, batching, dedup, restart | `driver-app`. Its own comment: *"This is the part of the app that has to be right"* |
| **The PIN vault** — PBKDF2, AES-GCM, the five-try wipe | `driver-app` |
| **Money formatting per `minorUnits`** | all five. An XOF total with two decimals is a visible bug and a one-line test |
| **The support/admin decide split** | `admin` |
| **CSRF priming** when the cookie is absent | all five |
| **HTML escaping** in 428 template interpolations | `buyer-app` |

**Recommendation.** Vitest is already available through Vite in four of the five.
Six test files would cover most of the risk:

1. `refreshSession` — two concurrent 401s issue exactly **one** `/auth/refresh`.
2. `outbox` — a retry after a failed send reuses `clientEventId` **and** the
   idempotency key; order is preserved; a transfer is never batched.
3. `vault` — a wrong PIN fails, five wrong PINs destroy the ciphertext.
4. `money` — XOF formats to 0 places, GMD to 2, and an unknown code falls back
   to `Intl` rather than to 2 blindly.
5. `Decide` — a SUPPORT principal renders every decide button disabled.
6. `esc` — the escaping helper, plus a snapshot of one rendered product card
   with a hostile name.

### 6.2 `buyer-app` keeps both tokens in `localStorage`

**`frontend/buyer-app/src/state.js`** persists the whole `auth` object —
**access token and refresh token** — under `sujula.auth`.

The other four keep the access token **in memory only**, and the admin store
explains exactly why. `buyer-app` is also the app that:

- renders the most **user-generated content** — product names and descriptions
  written by sellers, review text, Q&A, store names;
- does so through **79 `innerHTML` assignments** across 428 template
  interpolations;
- depends for its safety entirely on one hand-applied helper, `esc()`.

The escaping discipline is, on inspection, **good**: every interpolation of free
text goes through `esc()`, and the unescaped ones are numbers, booleans,
`encodeURIComponent`, or nested calls that escape internally. But it is
hand-maintained, there are no tests, and **one missed `esc()` is an XSS that can
now lift a 30-day refresh token** rather than only a 10-minute access token.

**Fix, in order of value:**

1. Move the **access token out of `localStorage`** into a module variable, as
   the other four do. Small change, removes the sharper half of the exposure.
2. Add a test for `esc()` and one rendered card with a hostile name.
3. Consider `el()` + `textContent` for the free-text fields specifically. The
   helper already exists in `ui.js`.

### 6.3 The idempotency key is minted per attempt in three of five apps

The server's idempotency support only works if the key is **stable across
retries of one user intention**. `driver-app` states this correctly and
implements it: the key is a **parameter**, minted when the driver taps and
stored in IndexedDB beside the event.

The others mint it **inside the call**:

```ts
// admin/src/api/endpoints.ts
function idem() {
  return { idempotencyKey: newIdempotencyKey() };   // ← a new key every call
}
```

```js
// buyer-app/src/screens/checkout.js — the highest-consequence write on the platform
const placed = await api.checkout({ quoteId, addressId, paymentMethod, notes: null },
                                  idempotencyKey());   // ← minted here, per attempt
```

`vendor-app` and `pickup-app` sit in between: their signature is
`idempotent: idempotencyKey ?? true`, so a screen **may** pass a stable key and
gets a per-attempt one if it does not.

**Why this matters.** The comment above `idem()` claims *"That is what stops a
retry after a timeout from releasing a second payout batch or placing a second
order"*. It does the opposite: a retry after a timeout carries a **different**
key, so the server does the work again. The driver app's own comment is the
authority — *"a key generated at send time is a new key every retry, which is
the same as having none."*

Mitigations that exist: buttons are disabled while busy, and TanStack Query
mutations are `retry: false`. Neither covers the actual case — a request that
times out or whose response is lost, after which the user tries again.

**Fix.** Mint the key when the **user acts**, hold it in component state for
that intention, and pass it to every attempt. Exactly what `driver-app` does.
Priority order: `POST /checkout`, payout batch approve/release, refunds, cancels.

### 6.4 No error boundary in four of five apps

Only `driver-app` has one. In the other four, a render-time exception blanks the
screen with no message and no way back — and `admin` is used all day by people
working queues.

**Fix.** One `<ErrorBoundary>` at the route level per app, showing the error, a
reload and a link home. Perhaps an hour each.

### 6.5 A 401 is classified by matching prose

```ts
get isCredentialChallenge(): boolean {
  return this.status === 401 && /password|code|confirm/i.test(this.message);
}
```

Distinguishing *"wrong password on a step-up"* from *"dead session"* matters —
the wrong branch triggers a refresh the user did not need, or worse, signs them
out for typing a password wrongly. But this matches a regex against a
human-readable message the server is free to reword.

**Fix, and it belongs on the server:** give the step-up refusal a stable
machine-readable discriminator — an `error` code in the existing JSON body, say
`"STEP_UP_FAILED"`. Then every client branches on a field rather than on prose.
Until then the regex is the pragmatic choice; it is worth a comment naming the
server strings it depends on.

### 6.6 Five API clients that must stay in step

Each app implements bearer-token handling, CSRF double-submit, refresh
single-flight, error mapping and money formatting **separately**. That is
~250 lines duplicated five ways.

The decision is defensible — the apps ship independently, `buyer-app` has no
build step and could not import a TypeScript package, and a shared package
introduces a version skew of its own. `frontend/README.md` addresses it directly
by naming `admin/src/api` as the shape to copy.

**But the correctness rules live in those 250 lines**, and §6.3 shows what
happens when one copy drifts: the driver app has the rule right and says so, and
three others have it wrong. A shared package would not have prevented that
(the key is minted at the call site), but a shared **test suite** would.

**Recommendation.** Do not extract a package. Do write the six tests in §6.1
once and run them against each client's module — the interfaces are close enough
that a parameterised suite covers four of the five.

---

## 7. What the front end does well

Stated plainly, because the findings above are a short list against a large
body of careful work.

1. **Zero `any` in ~46,700 lines**, across four TypeScript applications. The
   enums are mirrored from the server's, the response types are written out, and
   `endpoints.ts` is fully typed end to end.
2. **`endpoints.ts` as a discipline.** One typed function per endpoint, grouped
   as the server groups them, and nothing above that layer builds a URL. It
   makes each client auditable against the API in an afternoon.
3. **The constitution reaches the clients.** `buyer-app/src/state.js` keeps
   `payer` and `place` as two fields with a comment saying *"Nothing in here may
   derive one from the other"*. Every money module refuses arithmetic and cites
   C2. `pickup-app` never displays a collection code. `driver-app` never
   requests one. These are not restatements of the backend's rules — they are
   the same rules enforced a second time, where a client could otherwise
   undermine them.
4. **`policy.ts`** in the admin console mirrors server constants and is
   explicitly **not allowed to block a request**: *"A mirror drifts, so nothing
   in this file is allowed to block a request: the server decides, and these
   only decide what the form shows before it asks."* That is the correct
   relationship between a client-side rule and a server-side one, and it is
   almost never written down.
5. **`Decide.tsx`** renders actions support cannot take as **disabled with an
   explanation**, not hidden: *"An agent who cannot see the action does not know
   it exists and asks nobody; an agent who can see it greyed out knows exactly
   what to escalate."*
6. **The driver vault and outbox** are, jointly, the most carefully reasoned
   code in the repository — and honest about what they do not buy.
7. **No `console.log` noise** — 7 occurrences across 46,700 lines, all
   deliberate.
8. **Each app's README argues its own trade-offs** rather than describing its
   folders.

---

## 8. Running all five

```bash
# One backend for all of them
mvn -o spring-boot:run -Dspring-boot.run.profiles=e2e     # :8080, H2, seeded

# Then, in five terminals
cd frontend/admin       && npm install && npm run dev   # :5173
cd frontend/vendor-app  && npm install && npm run dev   # :5174
cd frontend/driver-app  && npm install && npm run dev   # :5175
cd frontend/pickup-app  && npm install && npm run dev   # :5176
cd frontend/buyer-app   && npm install && npm run dev   # :5177
```

Sign in with anyone from [`../TESTING.md` §2](../TESTING.md). Every seeded
password is `Sujula123!`.

| App | Sign in as |
|---|---|
| `admin` | `fatou.admin@sujula.gm` (admin) or `binta.support@sujula.gm` (support, read-only) |
| `vendor-app` | `lamin.kombo@sujula.gm` (approved) or `modou.sanneh@example.gm` (lands on the application form) |
| `driver-app` | `ebrima.driver@sujula.gm` |
| `pickup-app` | `isatou.pickup@sujula.gm` — runs one open counter and one closed for a funeral |
| `buyer-app` | `oliver.bennett@example.co.uk` (London, GBP) or browse as a guest |
