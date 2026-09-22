# Sujula for buyers

The shop. A phone in Madrid, a seller in Banjul, and a parcel that arrives at
somebody else's door in Serrekunda.

This is the fifth front end on the marketplace and the only one whose users did
not sign up to use it. An administrator learns their console, a driver is
trained on theirs, a seller has a reason to persevere. A shopper has a
competitor one tap away, so every decision here is about the first thirty
seconds.

---

## The first question

The shop asks where the parcel is going before it shows anything, and that is
the whole design rather than a nicety. The catalogue is ranked against the
**delivery** location: a seller four kilometres from the recipient arrives
sooner and costs less to send than one four hundred away, and a grid that
ignores this is a grid in an arbitrary order. Until it is answered there is
very little the shop can honestly say.

It is asked as a destination, never as "your address". The buyer is usually not
the person the parcel is for, and a screen that assumes otherwise gets the
wrong answer from the exact customer this marketplace exists for.

**Three ways to answer, because in the delivery area they are not equivalent:**

| | |
|---|---|
| **Country and town** | Always available. Enough to filter, not enough to sort by distance. |
| **Find the address** | `POST /geo/validate-address`, where a geocoder is configured and the address is one it knows. Tried automatically when a town is typed and no pin is placed. |
| **Drag the pin** | The answer for the many places with no street address at all. Not a fallback — for much of the region it is the real address, and the coordinate is what the driver is given. |

**It is asked once, and it is not asked everywhere.** The answer lives in
`localStorage`; the server-side delivery context minted from it expires in
twelve hours and is re-minted silently, so nobody is asked daily for something
they already said. It appears on shopping screens only — somebody who followed
a link to sign in, to their orders, or to a tracking page came for that, and a
full-screen question on top of it is how a popup stops being help. Closing it
means "not now" for the visit; the strip under the navbar carries the prompt
from then on, and the next visit asks again.

---

## What it does

| | |
|---|---|
| **Browse and search** | Ranked against the destination, with facets counted against the current filter minus their own dimension. Typeahead on two characters. |
| **See a product** | Serviceability answered on the page: can this seller reach that address, how far, what the leg costs, how many days. |
| **Basket** | Grouped by seller, because that is what the order becomes — each seller ships, cancels and refunds on their own. |
| **Checkout** | Recipient, how it gets there, a held quote, and a payment method. Collection counters listed against the destination. |
| **Orders** | One payment, a panel per seller, each with its own custody timeline and its own cancel. |
| **Track** | A code and nothing else. No account, for the person the parcel is actually for. |

---

## Running it

```bash
npm run dev               # http://localhost:5177
```

No install, no build, no dependencies — `npm install` has nothing to fetch.
`dev-server.mjs` serves this directory and proxies the API prefixes **this
client calls** to `VITE_DEV_API_TARGET` (default `http://localhost:8080`), so
the app talks to the API on its own origin exactly as it does in production.

```bash
npm run check             # every file parses, every import resolves
```

That is this client's `tsc --noEmit`. There is no compiler here, so the two
mistakes a compiler would catch are checked directly: a file that does not
parse, and an import of a name nothing exports.

### Deploying

Serve this directory from the **same host as the API**, with the prefixes in
`dev-server.mjs` reverse-proxied to Spring. `SecurityConfig` publishes no CORS
configuration at all, so a separate front-end origin fails its first preflight.

There is no `dist/`: the files here are the files that ship.

### Android and iOS

`NATIVE.md`. The same files, wrapped by Capacitor, with the API base written
into `window.SUJULA_CONFIG`.

---

## Why this one has no build step

The other four clients are React and TypeScript on Vite, and this one is not.
That is a deliberate exception for one reason, and it is worth arguing with
before copying it anywhere else.

This client's users are on the worst connections and the cheapest phones on the
platform. An administrator's console loads once on an office desktop; a
driver's phone is company-issued and the app is installed once; a seller has a
reason to wait. A shopper on a 3G connection in Serrekunda, or on hotel wifi in
Madrid, has a competitor one tap away — and the first paint here is markup, one
stylesheet and a few kilobytes of module, with nothing to download and parse
before a price can be read.

What the clients index says is worth copying from `admin/` **is** copied, in
substance if not in syntax: `src/api.js` is one function per endpoint with a
client that owns the bearer token, the CSRF double-submit and the refresh
single-flight; `src/money.js` formats to each currency's own scale and never
does arithmetic.

The cost is real and should be stated plainly: no types, no component model, and
a check script standing in for a compiler. If this client grows a second
maintainer or a fifth screen that needs shared state, port it to the house
toolchain rather than keep bolting onto this.

---

## How it is laid out

```
index.html          the shell: two bars, the destination strip, the tab bar
styles.css          tokens, light and dark, safe areas, one file
dev-server.mjs      the dev server and the API proxy
src/
  main.js           boot order, routes, the first question
  config.js         where the API is; the only thing a native build overrides
  api.js            every server call, token refresh, idempotency keys
  state.js          what is remembered, and what survives being closed
  delivery.js       the destination, and the context minted from it
  money.js          amounts at the currency's own scale
  map.js            a slippy map and a draggable pin, with no dependencies
  router.js         hash routing
  ui.js             escaping, icons, modals, toasts
  components/       the chrome, the destination question, cards, the basket
  screens/          one file per screen
tools/check.mjs     the compiler this client does not have
```

Markup is built as strings, so everything from the server goes through `esc()`
on the way in. There is no framework here doing it.

---

## The five rules, as they land here

The platform's constitution is in `CLAUDE.md` at the repository root. Four of
the five are visible in this client, and this is where.

### C1 — two independent locations

The one rule this client exists to honour, and it is kept apart in the state
itself:

- **`state.payer`** — where the buyer is, from `/geo/resolve-context`. It
  decides exactly one thing: `state.currencyCode`, what they are charged in.
- **`state.place`** — where the parcel goes, and the delivery context minted
  from it. It decides the ranking, the serviceability answer, the shipping
  quote and which counters are offered.

Neither is derived from the other anywhere. The catalogue calls take them as
separate parameters (`deliverableTo`/`deliveryLat`/`deliveryCountry` on one
side, `currency` on the other) because the server insists on the same split.
"Use my current position" says on screen that it is the buyer's position and
probably not the destination.

### C2 — two currencies per order

`src/money.js` formats through `Intl.NumberFormat` at the scale
`/currencies` gives for that code, never `toFixed(2)`. XOF has no minor unit,
so a CFA price is never shown with decimals.

The client does no money arithmetic at all — every total on every screen is a
figure the server sent. Where the server sends no converted price, because no
rate exists for that pair, the screen shows the seller's own price and says
there is no rate rather than printing something nothing stands behind. A quote
the server marks incomplete cannot be ordered against: the button is disabled
and says why.

The held quote shows its own countdown. A buyer is about to be charged a
converted figure, and how long that figure is good for is theirs to know; when
it lapses the screen takes a fresh one rather than posting against a stale rate.

### C3 — one payment, many vendors

The basket, the checkout summary and the order screen are all a panel per
seller. Per-seller subtotals and delivery, a cancel that names the seller it
applies to, and a receipt confirmation per seller. Nothing in this client can
express "cancel a proportion of the order", because that is not a thing that
can happen.

### C4 and C5 — custody, and the recipient with no account

There is no control anywhere in this client that sets a delivery status. The
order screen reports the custody chain and the evidence behind each step, and
the only thing a buyer can assert is that something reached them.

The recipient form asks for a **phone** before it asks for anything else, and
says why: the code goes to whatever phone they have. `#/track/<code>` is a
whole screen built for somebody with no account, no app and no email — and it
is one of the two screens the destination question never interrupts.
