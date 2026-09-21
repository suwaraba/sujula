# Sujula for sellers

The vendor side of Sujula: a shop's catalogue, its fulfilment desk and its
money. One codebase, three targets — a web app, an Android app and an iOS app —
because the same seller uses all three and a phone behind the counter is the
common case, not the exception.

```
npm install
npm run dev          # http://localhost:5174, against a backend on :8080
```

The backend must be running. From the repository root:

```
mvn spring-boot:run -Dspring-boot.run.profiles=e2e
```

That boots the whole application against an in-memory database seeded from
`src/main/resources/db/seed/dev-seed.sql`. Sign in as `lamin.kombo@sujula.gm`
(an approved seller) or `modou.sanneh@example.gm` (a customer, who lands on the
application form). Every seeded password is `Sujula123!`.

## What it does

| | |
|---|---|
| **Getting in** | Sign in with MFA, register, password recovery, device list, sign out everywhere |
| **The gate** | No store → the application form. A store → through, whatever its standing |
| **Opening a shop** | Store application with address and pin, settlement currency, registration numbers |
| **Verification** | KYC documents, what is still missing, what was rejected and why |
| **Products** | Write, edit, variants, photos, the review-and-publish ladder, archive |
| **Bulk** | Import a spreadsheet into drafts with per-row errors; export the catalogue |
| **Stock** | Counts and movements, low-stock and out-of-stock views, per-item history |
| **Handsets** | Register phones by IMEI, grade them, move them — what a serialised listing's stock *is* |
| **Offers** | Promotions that apply themselves, and coupon codes a customer has to know |
| **Orders** | The queue by stage, what to pack, accept, reject, scan handsets, mark ready |
| **Handover** | The collection code a driver must present, its countdown, reissuing it |
| **Custody** | Where the parcel is and who is holding it — read-only, with the proof behind each step |
| **Money** | Balances per currency, the ledger, payout requests, the payout account |
| **Insights** | Sales over time, what sold against what was only looked at, delivery outcomes |
| **Staff** | Invitations and per-permission access to this shop |
| **Security** | Password, devices, and turning two-step sign-in on and off |

## Three things this app does not do, on purpose

**It cannot mark a parcel delivered.** A seller's ladder ends at
READY_FOR_PICKUP. SHIPPED is what a driver produces by presenting the
collection code; DELIVERED is what the recipient produces with the code texted
to their phone — including a recipient who has no account, which is the case
this marketplace exists to serve. `VendorOrderStatus.isVendorSettable()` allows
PREPARING, READY_FOR_PICKUP and CANCELLED, and the server answers 400 to the
other two. So the custody chain on an order screen *reports* those steps and
offers no control for them. A button there would be a button that fails.

**It does not offer a choice of courier.** Delivery mode is the buyer's choice
at checkout (home delivery, a pickup point, or collection from the shop), and
handover is always the release code. There is no vendor self-delivery concept
in the API, so there is none here.

**It never adds two currencies together.** A seller who lists in GMD and has
been paid for an order placed in EUR holds two balances, and adding them would
mean picking a rate — a number nobody was charged and nobody was paid. Balances,
revenue and sales charts all come back per currency and are shown per currency.

## Notes for whoever works on this next

**Money is formatted at the currency's own scale.** XOF has no minor units, so
`1250.50 CFA` is not an amount that exists. `formatMoney` reads `minorUnits`
from `GET /currencies` rather than defaulting to two places. Never hard-code 2.

**Every converted figure carries its rate and its moment.** An order's payout
shows the rate it was struck at and when that rate was published. The rate is
snapshotted on the order and never recomputed — an order re-priced after the
fact is an order nobody can explain.

**`commissionRate` is a percentage, not a fraction.** 10 means 10%. The ledger
computes `commission = goodsTotal * commissionRate / 100`.

**Mutations carry an `Idempotency-Key`.** A seller taps "Mark ready" standing in
a shop with one bar of signal; without a key, a lost response means a second
release code issued and the first one silently dead. `useIdempotencyKey` mints
one per action and resets it only on failure, so a retry is recognised as the
same action.

**The refresh token is single-flighted.** The backend rotates it on use and
treats a replay as theft by ending every session on the account. Six queries
that 401 together must perform *one* refresh, or the seller is signed out of
their own shop. See `refreshInFlight` in `src/api/client.ts`.

**CSRF is required on `/vendor/**`.** `SecurityConfig` exempts the bearer
surfaces (`/auth`, `/me`) and some public lookups, but not the seller's. The
client performs a handshake against `/config/public` and echoes the
`XSRF-TOKEN` cookie in `X-XSRF-TOKEN`. Without it, every save is a 403. The
exemption list in `src/api/csrf.ts` mirrors the server's and must stay in step.

**The access token is never written to storage.** It lives in a module
variable and is rebuilt from the refresh token on start-up. The refresh token
is persisted — "stay signed in" is not optional for a seller — and goes to the
Keychain or SharedPreferences on a device rather than `localStorage`.

**A product's pin comes from the store, not the form.** The server stamps each
new listing with the store's pickup coordinates, falling back to the store
address, and fills the country the same way. A seller never retypes their own
address per product — which also means a store with a guessed pin quietly
produces a catalogue of listings with guessed pins. `CollectionPointNote` says
so on the forms that create them, and `StoreAlerts` says so everywhere else.

**Not-found is the right answer for someone else's row.** The API answers 404
rather than 403 for another shop's order, because "forbidden" would confirm the
id is real. The screens present that as "that is not here", not as an error.

**An IMEI carries its own check digit, and this app checks it.** Not
duplication for its own sake: the server refuses a mistyped one, and a seller
pasting forty IMEIs off a spreadsheet should find the typo before sending the
batch rather than after. Registration is partial by design — thirty-eight go
in, two come back named — so the sheet stays open on a rejection and re-offers
the refused ones.

**Two promotions cannot discount the same goods.** Activation is *refused* with
a message naming the promotion already covering them; the `conflicts` array on
the response is always empty. Two discounts on one item do not add up to a
price anybody can predict.

**MFA calls take different proofs, deliberately.** Activating takes the
authenticator's code, because that proves possession. Turning it off and
reissuing recovery codes take the account password, because those are exactly
what somebody holding a stolen session would want — a second factor the session
can remove protects nothing.

## Serving it

**The app must not be served from the API's origin.** Spring serves the API at
the root rather than behind one prefix, so `/orders` and `/products` are both
screens here *and* endpoints there. Served together, a deep link to `/orders`
reaches the backend and answers 401 instead of opening the app.

Give it its own host (`sellers.sujula.gm`), with:

- a rewrite of every unmatched path to `index.html`, so deep links work;
- `VITE_API_BASE_URL` set to the API's origin;
- CORS on the backend allowing that origin **with credentials**, since the CSRF
  cookie has to be readable. There is no CORS configuration in `SecurityConfig`
  today — it will need one, or the app has to be reverse-proxied under the API's
  host on a path prefix that no controller owns.

In development none of this applies: `vite.config.ts` proxies only the API
prefixes this client actually calls, which keeps the page and the API on one
origin so the cookie is readable. Point it elsewhere with `VITE_DEV_API_TARGET`.

## Android and iOS

The same `dist/` is packaged, not loaded from a URL — an app that fetches its UI
from a host shows a white screen when that host is down, and its contents can
change after review.

```
# once
npx cap add android
npx cap add ios          # needs macOS and Xcode

# every time
npm run android          # builds, syncs, opens Android Studio
npm run ios              # builds, syncs, opens Xcode
```

`npm run sync:native` does the build and `cap sync` without opening anything,
which is what CI wants.

Before the first native build, set `VITE_API_BASE_URL` to the API's real origin
in `.env.local`. A device has no dev proxy and no same-origin story.

Two settings in `capacitor.config.ts` are load-bearing:

- **`androidScheme: 'https'`** — under the default `http` scheme Android treats
  the WebView origin as insecure and blocks `Secure` cookies, and the CSRF token
  is one.
- **`CapacitorHttp.enabled`** — native HTTP keeps a cookie jar that survives a
  WebView restart, which the CSRF handshake depends on.

Permissions to declare: **location**, for the "use my current location" button
when a seller drops their collection pin. Nothing else is required — there is no
camera dependency, because IMEIs are typed and the presigned upload takes a file
from the system picker.

To point a device at a dev server on your LAN, set `SUJULA_NATIVE_DEV_URL`
before `cap sync`. The dev proxy's own target is `VITE_DEV_API_TARGET`.

## Layout

```
src/
  api/          the wire: client, tokens, CSRF, typed endpoints, DTO mirrors
  auth/         session state and the three route guards
  store/        the current shop, and what is blocking it from trading
  components/   shared UI, the custody chain, the release-code panel
  screens/      one file per screen
  lib/          money and date formatting, shared hooks, catalogue constants
  styles/       one stylesheet, tokens first, light and dark
```

## Known gaps

- **Image and document upload needs object storage configured.** With
  `sujula.r2.*` unset, `POST /api/products/images/presign` answers 400 and the
  photo and KYC uploads surface that message. Nothing to fix in this app.
- **A promotion cannot be narrowed to a variant**, only to listings or
  categories — which is all the API accepts.
- **Recovery codes are shown once and never stored.** If a seller closes that
  sheet without writing them down, the only way back is to reissue them, which
  needs the password.
- **`@capacitor/cli` pulls a `uuid` advisory** through `xcode`, used only when
  generating the iOS project. It does not ship in the app, and the "fix" is a
  downgrade of the CLI.
