# `frontend/vendor-app` — Seller Application

> **One codebase, three targets.** A web app, an Android app and an iOS app,
> because the same seller uses all three and *"a phone behind the counter is the
> common case, not the exception."*
>
> This document covers **architecture and code quality**. For what each screen
> does, read [`frontend/vendor-app/README.md`](../../frontend/vendor-app/README.md)
> and its `NATIVE.md`. Read [`README.md`](README.md) first for the shared
> client contract.

| | |
|---|---|
| **Stack** | React · TypeScript · Vite · React Router · TanStack Query · **Capacitor** |
| **Size** | ~13,300 lines, 69 files |
| **Port** | 5174 |
| **Targets** | web · Android · iOS from one bundle |
| **Users** | Sellers, often on a phone, between customers |

---

## 1. Module map

```
src/
├── App.tsx                  27 routes; QueryClient configured at the top
├── api/
│   ├── client.ts            token · CSRF · refresh single-flight
│   ├── endpoints/           split by concern (catalogue, store, orders, money…)
│   ├── tokens.ts            where the two tokens live, and why differently
│   └── types.ts
├── auth/                    AuthProvider · guards · MFA
├── store/StoreProvider.tsx  the current shop as context
├── lib/
│   ├── format.ts            money at the currency's own scale
│   ├── catalogue.ts         listing-ladder helpers
│   ├── discounts.ts         what each promotion type actually asks for
│   ├── storage.ts           Keychain / SharedPreferences on device
│   ├── platform.ts          web vs native
│   ├── config.ts            where the API is, per target
│   └── hooks.ts             the currency catalogue, cached for the session
├── components/
├── styles/
└── screens/                 27 screens
```

**Routes:** `/sign-in` `/register` `/forgot-password` `/apply` `/`
`/orders` `/orders/:orderId` `/products` `/products/new` `/products/:id`
`/products-bulk` `/inventory` `/inventory/:variantId/history` `/handsets`
`/promotions` `/coupons` `/earnings` `/analytics` `/store` `/store/collection`
`/store/verification` `/store/payouts` `/store/staff` `/account` `/more`

---

## 2. The gate

`StoreProvider` is the architectural centre. It resolves the seller's shop once
and exposes the derived facts every screen needs — and the derivations are the
interesting part, because each one mirrors a server rule rather than inventing
one:

```ts
/** Approved, verified and not suspended — the same predicate checkout uses. */
canTrade: boolean;

/** The seller's own currency. Every listing price and every payout is in it. */
currency: string;

/**
 * Where a driver collects, which is where every listing's pin comes from.
 * The pickup address when one is set, the store address otherwise — the same
 * fallback `VendorCatalogueServiceImpl` applies when stamping a new product.
 */
collectionPoint: StoreAddress | null;

/** A pin nobody confirmed is a pin a driver gets sent to anyway. */
pinNeedsConfirming: boolean;
```

Each carries the server rule it mirrors. `collectionPoint` names the exact class
that applies the same fallback — so when that class changes, this line is
findable.

The routing gate follows from it: **no store → the application form; a store →
through, whatever its standing.** A suspended seller can still read their
orders and their money, which is correct: suspension stops trading, not access
to your own records.

---

## 3. One bundle, three targets

`capacitor.config.ts` carries two decisions that are easy to get wrong and are
explained where they are made:

```ts
/**
 * `server.url` is deliberately absent: a shipped app that loads its UI from a
 * URL is an app that shows a white screen when that host is down, and one whose
 * contents can be changed after review. The bundle is packaged, and only the
 * API is remote.
 *
 * `androidScheme: 'https'` matters more than it looks. Under the default
 * `http` scheme Android treats the WebView origin as insecure, which blocks
 * `Secure` cookies — and the CSRF token this application issues is one.
 */
```

The second is the kind of thing that costs a day to diagnose: writes failing in
the Android build and nowhere else, because the CSRF cookie was never stored.

`lib/config.ts` completes the picture: the API base is **empty in web
development**, where Vite proxies the API roots so the page and the API share an
origin, and an **absolute origin at build time** for native, which has no proxy
and no same-origin story.

`lib/storage.ts` is the third piece:

> *"Native goes to Capacitor Preferences, which is backed by the Keychain on iOS
> and SharedPreferences on Android — not `localStorage`, which a WebView is
> entitled to evict under storage pressure and which would sign a seller out
> because their phone was low on space."*

That is a real failure mode on a cheap Android phone, and it is anticipated.

---

## 4. Tokens

```
access token   → module variable, nowhere else
refresh token  → Keychain / SharedPreferences on device; storage on web
```

> *"It does not survive a reload, and does not need to: the refresh token
> rebuilds the session before the first screen paints."*

> *"The backend rotates it on every use and treats a second presentation of a
> spent token as theft, ending the session — so a stolen copy is good for one
> use and then announces itself."*

Correct reasoning, and it correctly identifies rotation as the property that
makes writing the refresh token down acceptable.

---

## 5. Three things this app does not do, on purpose

From its own README, and all three are correct readings of the API:

**It cannot mark a parcel delivered.** A seller's ladder ends at
`READY_FOR_PICKUP`. `SHIPPED` is what a driver produces by presenting the
collection code; `DELIVERED` is what the recipient produces with their own code
— including a recipient who has no account, which is the case this marketplace
exists to serve. `VendorOrderStatus.isVendorSettable()` allows `PREPARING`,
`READY_FOR_PICKUP` and `CANCELLED` and the server answers 400 to the other two.

> *"So the custody chain on an order screen **reports** those steps and offers
> no control for them. A button there would be a button that fails."*

This is C4 respected by a client that could have undermined it, and the reason
is stated.

**It does not offer a choice of courier.** Delivery mode is the buyer's choice
at checkout. There is no vendor self-delivery concept in the API, so there is
none here.

**It never adds two currencies together.** A seller listing in GMD who has been
paid for an order placed in EUR holds two balances, and adding them would mean
picking a rate — *"a number nobody was charged and nobody was paid."* Balances,
revenue and sales charts all come back per currency and are shown per currency.

---

## 6. Details worth noting

**`lib/discounts.ts`** asks for exactly what each promotion type needs:

> *"The server validates per type — `BUY_X_GET_Y` needs quantities, `PERCENT`
> needs a percentage, `BUNDLE` needs a price — so the form asks for exactly
> those and nothing else. A form that shows every field for every type is a form
> where most of what you fill in is silently discarded."*

**`lib/format.ts`** reads `minorUnits` from the server's catalogue, and
explicitly *"never from the browser's locale data, which disagrees with the
catalogue for some of the"* currencies this platform trades in. That is a real
divergence and most clients would not have checked.

**Mutations never retry automatically:**

```ts
mutations: {
  // Never automatic. Every mutation on this surface carries an
  // Idempotency-Key minted by the screen that owns it, and a blind retry
  // here would bypass the reset that follows a failure.
  retry: false,
}
```

The comment describes the right design. The implementation is only partly there
— see §7.1.

**Queries retry, but never on a 4xx:**

```ts
if (error instanceof ApiError) {
  if (error.status >= 400 && error.status < 500) return false;
}
return failureCount < 2;
```

Correct: a 404 on somebody else's row and a 403 from a suspended store are both
answers, not failures.

---

## 7. Code review

### Strengths

1. **Zero `any`, zero `console.*`, zero `innerHTML`** in 13,300 lines.
2. **`StoreProvider` mirrors server predicates and names them**, including the
   exact class that applies the same fallback.
3. **The Capacitor configuration anticipates two failures** that are hard to
   diagnose from symptoms.
4. **Native storage rather than `localStorage`**, for a stated reason that is a
   real failure mode on the phones this app runs on.
5. **The three deliberate omissions** are correct readings of the API, not gaps.
6. **Per-type forms** rather than one form with hidden fields.

### Findings

#### 7.1 The idempotency key defaults to per-attempt — **Medium-High**

```ts
create: (input: ProductInput, idempotencyKey?: string) =>
  api.post<ProductDetail>('/vendor/products', input, { idempotent: idempotencyKey ?? true }),
```

`idempotent: true` means *"mint one"* — which happens at send time, so a retry
after a timeout carries a **different** key and the server does the work again.
A screen **may** pass a stable key, and the client's own comment says to:
*"Pass a string to reuse one across retries of…"*. The `App.tsx` comment goes
further and asserts it is already so: *"Every mutation on this surface carries
an Idempotency-Key minted by the screen that owns it."*

It is not. The default path mints per attempt, and the optional parameter means
each call site has to remember.

**Where it costs most:** `POST /vendor/products` (duplicate listings),
`POST /vendor/orders/{id}/accept`, `POST /vendor/payouts/request` (a duplicate
payout request), and bulk import submission.

**Fix.** Make the parameter **required**, so the type system forces each call
site to have a key, and mint it where the user acts:

```tsx
const [idemKey] = useState(() => newIdempotencyKey());
```

Then the `App.tsx` comment becomes true. Cross-referenced as
[`README.md` §6.3](README.md#63-the-idempotency-key-is-minted-per-attempt-in-three-of-five-apps).

#### 7.2 No error boundary — **Medium**

A render-time exception blanks the app. On a phone behind a counter with a
customer waiting, that is worse than on a desktop. `driver-app` has one; copy it.

#### 7.3 No tests — **High**

Zero test files across 13,300 lines and three shipping targets. The highest-value
three:

1. `format.ts` — XOF at 0 places, GMD at 2, catalogue before `Intl` before 2.
2. `StoreProvider` — `canTrade` for each store standing; `collectionPoint`
   falling back from pickup address to store address.
3. Refresh single-flight under two concurrent 401s.

#### 7.4 Native builds have no automated verification — **Medium**

`npm run sync:native`, `android` and `ios` exist, but nothing checks that the
Android build still stores the CSRF cookie or that the Keychain path works. Both
are exactly the kind of thing that regresses silently on a Capacitor upgrade,
and both are called out in the source as having been hard to diagnose.

**Fix.** A smoke checklist in `NATIVE.md` — sign in, place one write, force-quit,
reopen, confirm still signed in — run before each release. Cheaper than a device
test rig and covers the two documented failure modes.

---

## 8. Running it

```bash
cd frontend/vendor-app
npm install
npm run dev          # http://localhost:5174

cd ../.. && mvn spring-boot:run -Dspring-boot.run.profiles=e2e
```

| Sign in as | Lands on |
|---|---|
| `lamin.kombo@sujula.gm` | An approved seller's dashboard |
| `awa.teranga@sujula.sn` | A seller settling in **XOF** — the right account for checking §5's currency rule |
| `modou.sanneh@example.gm` | A customer, so **the application form** — the gate in §2 |

Password `Sujula123!`.

```bash
npm run typecheck
npm run build
npm run sync:native && npm run android   # or ios
```

> **Proxy note.** This app has screens at `/orders` and `/products`, which
> collide with API paths. Proxying either sends deep links to the backend, which
> answers 401 instead of opening the app. The same collision reappears in
> production if the app is served from the API's origin — the README says where
> it must live.
