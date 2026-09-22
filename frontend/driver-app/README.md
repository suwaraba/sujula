# Sujula Driver

The phone in a courier's hand between a shop in Banjul and a doorway in
Serrekunda.

This is one of the five front ends on the Sujula marketplace, and it is the one
that runs outdoors. It is an installable mobile web app (a PWA) rather than a
React Native build, for three reasons that matter on this platform: it installs
from a link dispatch sends rather than from a store account a driver may not
have, it updates the moment a fix is deployed rather than when somebody accepts
an update over a metered connection, and it is the same origin as the API, which
is what lets the session cookie, the CSRF token and the bearer token all work
without a single cross-origin request.

---

## What it does

| | |
|---|---|
| **Go on and off duty** | Nothing is offered and nothing is tracked until the driver says they are working. |
| **Take or turn down a job** | An offer shows a town, a distance and what it pays. Never an address — see *Privacy*. |
| **Live position** | `watchPosition` feeding `POST /driver/location`, throttled to what the server asks for. |
| **Navigate** | One tap into the Google Maps app, with the platform's own confirmed pin as the destination. |
| **Scan a label** | Native `BarcodeDetector` where the phone has it, ZXing everywhere else. Torch included. |
| **Record custody** | Arrival, collection, deposit at a counter, delivery, a failed attempt, a transfer to another driver. |
| **Work with no signal** | Every event is written to the phone first and uploaded later. This is the normal case, not the edge case. |
| **Earnings and history** | Per currency, never summed across one. |

---

## Running it

```bash
cp .env.example .env      # then read it; every setting is explained there
npm install
npm run dev               # http://localhost:5175
```

`vite dev` proxies `/auth`, `/me`, `/driver`, `/notifications`, `/config`,
`/countries` and `/currencies` to `VITE_DEV_API_TARGET` (default
`http://localhost:8080`), so the app talks to the API on its own origin exactly
as it does in production.

```bash
npm run build             # tsc -b && vite build  →  dist/
npm run preview
```

### Testing on a real phone

Geolocation, the camera and service workers all require a **secure context**.
`localhost` counts as one; `http://192.168.1.x:5175` does not — the camera and
the position will both fail, and the browser's error is indistinguishable from
the driver having denied permission. Tunnel instead (`cloudflared tunnel
--url http://localhost:5175`, `ngrok http 5175`, or anything equivalent) and
open the https address.

### Deploying

Serve `dist/` from the **same host as the API**, with `/auth`, `/me`, `/driver`,
`/notifications` and `/config` reverse-proxied to Spring and everything else
falling back to `index.html`. That is the supported shape, and it is what the
backend assumes: `SecurityConfig` publishes no CORS configuration at all, so a
separate front-end origin would fail its first preflight.

---

## The five rules, as they land here

The platform's constitution is in `CLAUDE.md` at the repository root. Three of
the five rules are visible in this app's code, and it is worth saying where.

### C1 — two independent locations

A driver has a **payer context** nowhere and a **delivery context** everywhere.
Every position this app sends is about the parcel: where the driver is relative
to the shop, or to the door. Nothing here reads the driver's own country to
decide anything, and nothing derives a delivery answer from where the phone
happens to be registered.

### C2 — two currencies per order

`lib/format.ts` formats money through `Intl.NumberFormat` with the currency's
own scale and never `toFixed(2)`. XOF has no minor unit: 1250.50 CFA is not an
amount that exists. Earnings arrive grouped by currency and are rendered
grouped by currency — a driver who has worked legs paid in dalasi and in CFA has
two earnings, and adding them would need a rate nobody agreed to.

Amounts stay strings from the wire to the formatter. Parsing a decimal into a
JavaScript number and back is how a figure somebody is owed becomes a figure
nobody can explain.

### C4 — custody is a chain, not a status

There is no screen in this app that sets a status. Every green button records an
**event with evidence** — a code the other party read out, a position, a
photograph — and the status that follows is the server's conclusion. The chain
on the job screen is rendered from `chain[]` and carries no codes, because a
driver looking at a parcel's history must not be able to read the code that
opens its next handover.

### C5 — the recipient may not have an account

`POST /driver/shipments/{id}/request-recipient-code` sends the code to the
buyer, who passes it to the recipient — who may have no account, no app and no
email of her own. The driver never sees it. The response says only that it went,
and to a masked address, which is why the screen shows "You never see the code.
They read it to you" rather than a field.

---

## Privacy

This is the sharpest data on the platform: recipients' home addresses and phone
numbers, and where a driver has been all day. Both belong to people who never
agreed to be visible to anybody but the person bringing a parcel.

**The address is a nullable object, not a nullable field.** `ShipmentDetail`
types `destination` as `Destination | null`, mirroring the backend, which builds
the whole object or none of it. So there is no field a screen could forget to
blank, and no empty address where a real one used to be. The job screen renders
a padlock and the server's own sentence instead.

**Nothing is cached.** The backend serves every `/driver/**` response
`no-store, private`. This app does not undo that:

- `fetch` is called with `cache: 'no-store'`;
- the service worker is an **allow-list**, not a deny-list — it caches the HTML
  document, the hashed `/assets/` files and the icons, and every other request
  goes straight to the network with its response untouched (`public/sw.js`);
- the React Query cache lives in memory with a five-minute `gcTime` and is never
  persisted;
- IndexedDB holds queued custody events, photographs waiting to upload, and the
  encrypted refresh token. **It never holds a name, a phone number or an
  address.**

**Crash reports go nowhere.** `ErrorBoundary` logs to the console and stops
there. A component stack from this app is a component stack from a screen with
somebody's front door on it.

---

## Security

### Tokens

The **access token lives in a module variable** and nowhere else — not
`localStorage`, not a cookie, not IndexedDB. It is re-minted from the refresh
token, so closing the app is the right lifetime for it.

The **refresh token is encrypted at rest under a PIN the driver chooses**
(PBKDF2-SHA256, 310,000 iterations → AES-GCM; `auth/vault.ts`). The PIN is never
stored, not even hashed: the only test of whether it is right is whether the
token decrypts, which is what AES-GCM's authentication tag gives for free. Five
wrong attempts destroys the ciphertext — the same rule the backend applies to a
handover code, and for the same reason: a four-digit secret is ten thousand
guesses to somebody patient and three to somebody who mistyped, and the count is
the only thing that tells them apart.

This exists because of the phone. It is shared between shifts, left in vehicles,
and sometimes stolen with the parcels. It does **not** defend against malicious
code running in this origin — nothing in a browser does; the defences for that
are same-origin deployment, no third-party scripts, and the access token being
in memory.

The app locks itself after `VITE_LOCK_AFTER_MINUTES` of no touches, and whenever
it has been backgrounded for longer than that.

### Refresh

Refresh tokens are single-use and every exchange invalidates the last, so two
parallel refreshes mean the second presents a spent token — which the backend
correctly reads as theft and answers by ending the session. `api/http.ts`
single-flights the refresh across every caller, refreshes *before* expiry rather
than after a 401, and re-seals the vault on each exchange.

### CSRF

`/driver/**` is **not** on the backend's CSRF exemption list (`/auth` and `/me`
are). Every custody event therefore needs `X-XSRF-TOKEN`, read from the
`XSRF-TOKEN` cookie — which the backend only issues in response to a request. So
the app makes one cheap `GET /config/public` on startup to obtain it, and
re-fetches it once on a 403 before giving up. Get this wrong and every write in
the app answers 403 while every read works, which is a confusing afternoon.

### Idempotency

Everything that moves a parcel carries an `Idempotency-Key`, and the events that
matter most carry the app's own `clientEventId` in the body as well. **Both are
minted when the driver taps and stored with the queued event**, not generated at
send time — a key generated per attempt is a new key on every retry, which is
the same as having none.

---

## Working with no signal

This is the case the platform actually runs in, and the design follows from it.

```
driver taps ──► IndexedDB ──► UI says "recorded"
                    │
                    └── flush loop ──► /driver/shipments/{id}/…   (one event)
                                   └─► /driver/custody-events/sync (a backlog)
```

- **Write first, send second.** The confirmation comes from the write, so a
  driver in a dead spot sees the same "recorded" they see in town and is not
  tempted to tap twice.
- **Oldest first, and stop on the first network failure.** A delivery applied
  before its collection is refused by the chain — correctly, and for entirely
  the wrong reason.
- **A backlog goes in one request.** Three or more queued events that the batch
  endpoint can express are sent to `/driver/custody-events/sync`, which applies
  them oldest-first, deduplicates on `clientEventId` and reports entry by entry,
  so one bad record does not throw away a day's work. On the connection a driver
  comes back into range on, one round trip instead of twelve is the difference
  between uploading the day and giving up on it.
- **A transfer never goes in a batch.** `/custody-events/sync` has no field for
  the receiving driver or the second code, and half a transfer recorded is worse
  than none.
- **Refusals stop.** A 4xx that will not change marks the entry rejected, shows
  the server's sentence on the *Me* screen, and stops retrying. Only 5xx, 429
  and 408 back off and try again.
- **Signing out warns first**, and names the count, because it destroys the
  queue.

Positions are the deliberate exception: they are not queued. A position is only
interesting live, and a batch of them uploaded an hour later is a list of where
a person was.

---

## Google Maps

Two connections, and the important one is free.

**Navigation** is a URL — `google.com/maps/dir/?api=1&destination=…` — which
opens the Google Maps app itself on Android and iOS, with voice guidance in the
driver's own language, live traffic, and whatever offline maps they already have
for their area. It needs no API key, no script and no tiles.

**The in-app map** (`maps/LiveMap.tsx`) shows the shop, the door and the driver
on one view, and needs `VITE_GOOGLE_MAPS_API_KEY`. It is **optional**: with no
key the map is simply not rendered and the address card stands in its place.
That is a supported configuration, not a degraded one — a driver on a prepaid
bundle may be paying more for tiles than the job pays them.

Destinations are given by coordinates wherever the platform has them, and by
text only when it does not. A street name in Serrekunda that geocodes to the
wrong compound is worse than no address; the platform's own pin was confirmed by
the person who lives there.

Route rendering (`<LiveMap route />`) is off by default: every route draw is a
billed Directions request, and the driver is about to open the Maps app anyway.

---

## Built for someone who may not read

The screen is being read in direct sunlight, through a scratched protector, by
someone wearing a helmet with a parcel under one arm — and possibly by someone
who cannot read the language the platform is administered in.

- **One decision per screen.** Recording a handover takes over the whole display
  (`FocusLayer`): there is one action and no tab bar to wander into.
- **The action is at the bottom, and it is 88px tall.** That is the part of a
  phone a thumb reaches when the other hand is holding a box.
- **Codes are a keypad, not a text field.** Big fixed keys can be worked by
  someone who cannot read at all; a mobile keyboard would cover two thirds of
  the screen with letters, none of which are wanted.
- **Colour is never the only signal.** Every state carries an icon and a word.
  Roughly one man in twelve cannot tell the amber from the green.
- **Touch and sound as well as sight.** Every keypress buzzes, a completed
  record buzzes twice, and confirmations are announced through `aria-live` — a
  driver recording a handover is looking at a person, not at the phone.
- **Dark by default, light by choice.** These rounds run at dusk; the switch
  exists for midday, when a dark screen is a mirror.
- **English and French**, with the dictionary in one file (`i18n/index.tsx`).
  Adding Wolof or Mandinka is adding one object; nothing else changes. The
  backend's own sentences — *"That code is not right. Ask them to read it out
  again"* — arrive in English and are shown as written, because a mistranslated
  instruction about a parcel is worse than a clear one in the wrong language.
  Translating those is a backend change.

---

## Layout

```
src/
  api/          types mirroring the backend DTOs, the fetch wrapper, the endpoints, the query hooks
  auth/         the session state machine, and the PIN-encrypted token vault
  offline/      IndexedDB, and the outbox that survives a day with no coverage
  location/     the position feed, and the throttled ping behind it
  maps/         the Google Maps loader, the live map, the deep links
  scan/         the camera, and reading a parcel label's signed token
  media/        photographs: downscale, presign, upload
  components/   the pieces every screen is built from
  screens/      one file per screen
  i18n/         the dictionary
  lib/          ids, timestamps, formatting, connectivity
```

---

## The one backend change this needed

A delivery is refused without a photograph — it is what settles a dispute months
later about whether a parcel actually arrived — and there was no endpoint a
driver's app could use to produce a `photoUrl`. The platform's other uploads all
presign, so this one does too:

```
POST /driver/evidence/presign?contentType=image/jpeg   →  { uploadUrl, publicUrl }
```

It is in `DriverController`, restricted to `DELIVERY` and `ADMIN`, allows only
JPEG, PNG and WebP, and names objects after the driver and a random id — never
after a shipment, because a key carrying a shipment reference would let anybody
who knew the scheme walk the bucket for pictures of other people's doorways. The
bytes go straight from the phone to storage and never pass through the
application server.

It needs object storage configured (`sujula.r2.*`). Without it the endpoint
answers 400 with a message naming the properties to set, and a driver can do
everything except close a delivery.
