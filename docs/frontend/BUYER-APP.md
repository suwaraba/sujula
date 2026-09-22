# `frontend/buyer-app` — Shop

> **The only client whose users did not sign up to use it.** An administrator
> learns their console, a driver is trained on theirs, a seller has a reason to
> persevere. *"A shopper has a competitor one tap away, so every decision here
> is about the first thirty seconds."*
>
> This document covers **architecture and code quality**. For what it does, read
> [`frontend/buyer-app/README.md`](../../frontend/buyer-app/README.md) and its
> `NATIVE.md`. Read [`README.md`](README.md) first for the shared client
> contract.

| | |
|---|---|
| **Stack** | **Plain ES modules. No build step. No dependencies.** `npm install` has nothing to fetch |
| **Size** | ~4,500 lines, 23 files |
| **Port** | 5177 |
| **Targets** | web, and the same files wrapped by Capacitor |
| **Users** | Shoppers and guests, on the worst connections on the platform |

---

## 1. Why this one has no build step

The argument is made in its own README and it is a good one:

> *"This client's users are on the worst connections and the cheapest phones on
> the platform. An administrator's console loads once on an office desktop; a
> driver's phone is company-issued and the app is installed once; a seller has a
> reason to wait. **A shopper on a 3G connection in Serrekunda, or on hotel wifi
> in Madrid, has a competitor one tap away** — and the first paint here is
> markup, one stylesheet and a few kilobytes of module, with nothing to download
> and parse before a price can be read."*

And it states the cost without hedging:

> *"No types, no component model, and a check script standing in for a compiler.
> If this client grows a second maintainer or a fifth screen that needs shared
> state, port it to the house toolchain rather than keep bolting onto this."*

**`tools/check.mjs`** stands in for `tsc --noEmit`: *"the two mistakes a
compiler would catch are checked directly: a file that does not parse, and an
import of a name nothing exports."* That is an honest description of what it
does and does not cover.

**There is no `dist/`. The files here are the files that ship.**

---

## 2. Module map

```
index.html          the shell: two bars, the destination strip, the tab bar
styles.css          tokens, light and dark, safe areas — one file
dev-server.mjs      the dev server and the API proxy
src/
  main.js           boot order, routes, the first question
  config.js         where the API is; the only thing a native build overrides
  api.js            every server call, token refresh, idempotency keys
  state.js          what is remembered, and what survives being closed   §4
  delivery.js       the destination, and the context minted from it      §3
  money.js          amounts at the currency's own scale
  map.js            a slippy map and a draggable pin, with NO dependencies
  router.js         hash routing, ~40 lines
  ui.js             escaping, icons, modals, toasts                      §6
  components/       chrome, the destination question, cards, the basket
  screens/          home · browse · product · store · cartView ·
                    checkout · orders · track · account · pickupPoints
tools/check.mjs     the compiler this client does not have
```

**The house shape is kept in substance if not in syntax:** `api.js` is one
function per endpoint with a client owning the bearer token, the CSRF
double-submit and the refresh single-flight; `money.js` formats to each
currency's own scale and never does arithmetic.

---

## 3. The first question, which is the whole design

> *"The shop asks where the parcel is going before it shows anything, and that
> is the whole design rather than a nicety. The catalogue is ranked against the
> **delivery** location: a seller four kilometres from the recipient arrives
> sooner and costs less to send than one four hundred away, and a grid that
> ignores this is a grid in an arbitrary order."*

And critically:

> *"It is asked as a destination, **never as 'your address'**. The buyer is
> usually not the person the parcel is for, and a screen that assumes otherwise
> gets the wrong answer from the exact customer this marketplace exists for."*

**Three ways to answer, because in the delivery area they are not equivalent:**

| | |
|---|---|
| **Country and town** | Always available. Enough to filter, not enough to sort by distance |
| **Find the address** | `POST /geo/validate-address`, where a geocoder is configured and knows the address. Tried automatically when a town is typed and no pin is placed |
| **Drag the pin** | *"Not a fallback — for much of the region it is the real address, and the coordinate is what the driver is given"* |

That third line is the product insight the whole platform rests on, restated at
the point of data entry.

### 3.1 It is asked once, and not everywhere

The answer lives in `localStorage`; the server-side delivery context minted from
it expires in twelve hours and is **re-minted silently**, so nobody is asked
daily for something they already said.

It appears on shopping screens only — *"somebody who followed a link to sign in,
to their orders, or to a tracking page came for that, and a full-screen question
on top of it is how a popup stops being help."* Closing it means *not now* for
the visit; a strip under the navbar carries the prompt from then on.

`delivery.js` states the rule the platform is built on:

> *"It never asks twice for an answer it already has, and it never guesses a
> destination from the buyer's own whereabouts: the buyer is in Madrid and the
> parcel is going to Serrekunda, and reading one off the other would be wrong
> every time."*

---

## 4. `state.js` — C1 kept in two fields

```js
/*
 * Two of these fields are the whole point of the marketplace and are kept
 * deliberately apart:
 *
 *   payer  — where the buyer is. Decides the currency they are charged in.
 *   place  — where the parcel is going. Decides what they are shown, what it
 *            costs to send, and whether it can be sent at all.
 *
 * A buyer in Madrid sending a phone to Serrekunda has both, and they are
 * different. Nothing in here may derive one from the other.
 */
```

`payer` is resolved from the browser and IP via `/geo/resolve-context`. `place`
is the delivery context, **kept as the inputs rather than as the server's id** —
because the server's context expires in twelve hours, and storing the id alone
would mean asking the buyer where their parcel is going every single day.

That is C1 implemented, not quoted, and the storage decision that makes it
usable is reasoned through.

Storage reads and writes are wrapped in `try/catch` for *"private mode, or a
half-written value"* and *"storage full or blocked — the session still works; it
just forgets."* Correct, and more than most clients do.

---

## 5. `money.js`

> *"XOF has no minor units: 1250.50 CFA is not an amount that exists, and a
> storefront that prints it has invented a price nobody can pay. The scale comes
> from `/currencies` — the server's `CurrencyCatalogue` is the authority and this
> is its client-side mirror, never a hardcoded two."*

Same rule as the other four, stated from the shopper's side.

---

## 6. Rendering, and the one thing to watch

There is no component model. Screens render with template literals into
`innerHTML` — **79 assignments across 428 interpolations** — and safety depends
entirely on one hand-applied helper:

```js
export function esc(value) {
  if (value === null || value === undefined) return '';
  return String(value)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}
```

**On inspection the discipline holds.** Every interpolation of free text — a
product name, a description, review text, a store name, a Q&A answer — goes
through `esc()`. The 235 unescaped interpolations are numbers, booleans,
`encodeURIComponent(...)`, ternaries producing fixed markup, and nested template
calls that escape internally. Sampling `product.js`, the app's most
content-heavy screen, found no unescaped free-text field.

`ui.js` also provides `el(html)`, which builds through a `<template>` — the
right primitive for the cases where `textContent` would be safer still.

**But it is hand-maintained and untested**, and this is the app that renders the
most seller-written and buyer-written text on the platform. See §8.1 and §8.2.

---

## 7. Screens

| | |
|---|---|
| **Browse and search** | Ranked against the destination, with **facets counted against the current filter minus their own dimension** — the correct facet-count semantics, and one most implementations get wrong. Typeahead on two characters |
| **Product** | Serviceability answered **on the page**: can this seller reach that address, how far, what the leg costs, how many days |
| **Basket** | **Grouped by seller, because that is what the order becomes** — each seller ships, cancels and refunds on their own |
| **Checkout** | Recipient, how it gets there, a held quote, a payment method. Collection counters listed against the destination |
| **Orders** | One payment, **a panel per seller**, each with its own custody timeline and its own cancel |
| **Track** | *"A code and nothing else. No account, for the person the parcel is actually for"* |

### 7.1 `track.js` is the recipient's surface

There is no separate recipient application. `GET /track/{code}` and the
`/parcels/{code}/*` actions live in this client, reached by a link in a message
— which is right: the recipient is not going to install anything, and the shop
is already the lightest thing on the platform.

What it must keep doing, and does:

- render **exactly** what the server sends, with no enrichment — the public page
  substitutes fixed phrases for the driver's free text, and that substitution is
  the only thing between a driver's notes and a stranger forwarded the SMS;
- take the six-digit code with `inputmode="numeric"`;
- require no account, no app, no email.

### 7.2 Checkout is guest-capable

Guests fill most baskets on this marketplace. The flow is open end to end, and
guest order lookup needs **both** the order number and the email used at
checkout, so an order number alone reveals nothing.

Note that guest checkout still runs against the legacy `/api/guest/orders`
surface, which returns JPA entities directly
([`../CODE-REVIEW.md` §3.2](../CODE-REVIEW.md)). It is worth keeping isolated in
`api.js` so the migration is one function.

---

## 8. Code review

### Strengths

1. **The trade-off is argued, not assumed**, and the exit condition is written
   down: *"If this client grows a second maintainer or a fifth screen that needs
   shared state, port it."*
2. **C1 is implemented in `state.js`** as two fields with a rule against
   deriving one from the other, and in `delivery.js` as a destination question
   that is never phrased as "your address".
3. **`money.js` mirrors the server catalogue**, never a hardcoded 2.
4. **Facet counts exclude their own dimension** — correct, and rarely done.
5. **The basket is grouped by seller**, matching what the order becomes.
6. **A slippy map and draggable pin with no dependencies**, for a region where
   the pin *is* the address.
7. **Storage access is defensive** — private mode and quota failures degrade to
   forgetting rather than breaking.
8. **`npm run check`** exists and is honest about its coverage.
9. **Zero `any`** is not meaningful here, but **zero dependencies** is: nothing
   to audit, nothing to update, no supply chain.

### Findings

#### 8.1 Both tokens are in `localStorage` — **High**

**`src/state.js`** persists the whole `auth` object — **access token and refresh
token** — under `sujula.auth`.

The other four clients keep the access token in a module variable, and the
admin store says why: *"a token that survives in storage is a token a single
injected script can take away with it and use from anywhere."*

The exposure is sharper here than anywhere else, because this is also the app
that renders the most user-generated content through `innerHTML` (§6). One
missed `esc()` would lift a **30-day refresh token**, not merely a 10-minute
access token.

**Fix, in order of value:**

1. **Move the access token out of `localStorage`** into a module variable.
   `refreshTokens()` already rebuilds the session from the refresh token on
   boot, so nothing else has to change. This is a small change that removes the
   sharper half of the exposure.
2. Keep the refresh token in storage — *"stay signed in"* is not optional for a
   shopper — and rely on server-side rotation, which detects a stolen copy on
   its second use.

#### 8.2 XSS safety rests on one hand-applied helper, with no test — **Medium**

The discipline currently holds (§6), and that is a real achievement across 428
interpolations. But nothing enforces it. A new screen, or one added field in an
existing template, is one missed `esc()` away from an XSS — and §8.1 makes the
payoff for an attacker larger.

**Fix, cheapest first:**

1. A test for `esc()` plus a snapshot of one rendered product card with a
   hostile name (`<img src=x onerror=…>`). Ten minutes, and it fails loudly the
   day somebody removes the helper.
2. A `check.mjs` rule that flags an interpolation of a known free-text field
   name (`name`, `description`, `title`, `text`, `answer`, `note`) that is not
   wrapped in `esc(`. The check script already parses every file.
3. For the free-text fields specifically, prefer `el()` + `textContent` over
   interpolation. The primitive already exists.

#### 8.3 The idempotency key is minted per attempt — **High**

```js
const placed = await api.checkout({ quoteId, addressId, paymentMethod, notes: null },
                                  idempotencyKey());   // ← minted here, per attempt
```

**This is the highest-consequence write on the platform** and it is the call
that most needs a stable key. A shopper on a bad connection whose checkout times
out, and who tries again, sends a **different** key — so the server places a
second order and takes a second payment.

The server built idempotency for exactly this case. The seed file says so:
*"Send the same key twice and the second call is answered from the first rather
than placing a second order, **which on a mobile network is a certainty rather
than a risk.**"*

Mitigation that exists and does not cover it: `setBusy(button, true)` disables
the button during the request — which handles a double-tap and not a timeout.

**Fix.** Mint the key when the buyer commits to the order, hold it in the
checkout view's state, and reuse it for every attempt:

```js
if (!view.idemKey) view.idemKey = idempotencyKey();
const placed = await api.checkout({ … }, view.idemKey);
```

Clear it only once an order is successfully placed. Four lines, and it closes
the case the server's whole idempotency layer was built for. Cross-referenced as
[`README.md` §6.3](README.md#63-the-idempotency-key-is-minted-per-attempt-in-three-of-five-apps).

#### 8.4 No error boundary — **Medium**

There is no framework to provide one, but there is no top-level handler either.
An exception during a screen render leaves a blank outlet.

**Fix.** Wrap the router's dispatch in `try/catch`, render a *"something went
wrong"* panel with a reload, and add `window.onerror` /
`unhandledrejection` handlers that do the same. Twenty lines.

#### 8.5 No tests — **High**

Zero. Vitest would need adding (there is no build step, but it runs on plain ES
modules). The four worth writing:

1. `esc()` and one hostile-input render (§8.2).
2. `money.js` — XOF at 0 places, GMD at 2.
3. `refreshTokens` — two concurrent 401s issue one `/auth/refresh`. The
   single-flight is implemented; nothing proves it.
4. `state.js` — `payer` and `place` never derive from one another; a corrupt
   `localStorage` value degrades to the fallback rather than throwing.

#### 8.6 `retryOn401` recursion depth — **Low**

`request()` retries once with `retryOn401: false`, so it cannot loop. Correct as
written; worth a one-line comment stating that the flag is the recursion guard,
since it is the kind of thing a later refactor removes as redundant.

---

## 9. Running it

```bash
cd frontend/buyer-app
npm run dev               # http://localhost:5177
```

No install, no build, no dependencies. `dev-server.mjs` serves this directory
and proxies **the API prefixes this client calls** to `VITE_DEV_API_TARGET`
(default `http://localhost:8080`), so the app talks to the API on its own origin
exactly as it does in production.

```bash
npm run check             # every file parses, every import resolves
```

Sign in as `oliver.bennett@example.co.uk` (London, shops in **GBP**) — the right
account for checking §3 and §4, since his parcel goes to Serrekunda — or browse
as a guest, which is how most baskets on this platform are filled.

Tracking codes to try with no account at all:

```
#/track/K7MPQ4RTVX2ND9YH      order 1401
#/track/Z9DKP2VMHT6RXQFB      order 1403
```

### Deploying

Serve this directory from the **same host as the API**, with the prefixes in
`dev-server.mjs` reverse-proxied to Spring. `SecurityConfig` publishes no CORS
configuration, so a separate front-end origin fails its first preflight.

### Android and iOS

`NATIVE.md` — the same files wrapped by Capacitor, with the API base written
into `window.SUJULA_CONFIG`.
