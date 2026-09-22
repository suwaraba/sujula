# `frontend/driver-app` — Courier Application

> **The phone in a courier's hand between a shop in Banjul and a doorway in
> Serrekunda.** An installable PWA, built so that losing signal is the normal
> case rather than the edge case.
>
> This document covers **architecture and code quality**. For what it does, read
> [`frontend/driver-app/README.md`](../../frontend/driver-app/README.md). Read
> [`README.md`](README.md) first for the shared client contract.
>
> **This is the best-engineered client of the five**, and the two modules that
> make it so — `offline/outbox.ts` and `auth/vault.ts` — are worth reading
> whatever you are working on.

| | |
|---|---|
| **Stack** | React · TypeScript · Vite · TanStack Query · **IndexedDB (`idb`)** · **ZXing** |
| **Size** | ~7,900 lines, 42 files |
| **Port** | 5175 |
| **Form** | Installable PWA, not React Native |
| **Users** | Couriers, outdoors, one-handed, on a connection that comes and goes |

---

## 1. Why a PWA and not a native build

Three reasons, all specific to this job:

1. **It installs from a link dispatch sends**, rather than from a store account
   a driver may not have.
2. **It updates the moment a fix is deployed**, rather than when somebody
   accepts an update over a metered connection.
3. **It is the same origin as the API**, which is what lets the session cookie,
   the CSRF token and the bearer token all work without a single cross-origin
   request.

The third is the one that generalises: the whole platform's client security
model assumes same origin ([`README.md` §2](README.md#2-deployment-same-origin-no-exceptions)),
and a native shell would have had to solve it separately.

---

## 2. Module map

```
src/
├── App.tsx                  7 routes — the app is one decision per screen
├── ErrorBoundary.tsx        ← the only client of the five that has one
├── api/     http.ts · endpoints.ts · types.ts
├── auth/
│   ├── session.tsx
│   └── vault.ts             refresh token encrypted under a PIN     §4
├── offline/
│   ├── db.ts                four IndexedDB stores                   §3
│   └── outbox.ts            the part that has to be right           §3
├── media/photos.ts          camera → downscale → upload → key       §5
├── location/
│   ├── position.ts          a fix, with its accuracy
│   └── tracker.ts           live position, only while on duty       §6
├── scan/    Scanner.tsx · qr.ts        label scanning                §7
├── maps/    LiveMap.tsx · loader.ts · navigate.ts
├── i18n/index.tsx           a map and a hook, no library             §8
├── lib/     connectivity.ts · format.ts · ids.ts
├── components/
└── screens/  Jobs · Job · Handover · FailedAttempt · Transfer ·
              Earnings · History · Me · SignIn · PinGate · Apply
```

**Seven routes:** `/` `/jobs` `/jobs/:id` `/earnings` `/history` `/me` `*`.

The route count is the design. Everything that happens to a parcel happens on
`/jobs/:id`, because a driver holding a phone in one hand does not navigate.

---

## 3. The outbox — the part that has to be right

> *"A driver works a round through an area with no coverage; every collection,
> every doorstep, every failed attempt is recorded on the phone and exists
> nowhere else until they come back within range. **Losing one is losing the
> evidence that a parcel changed hands, which on this platform is the evidence
> that a seller gets paid.**"*

Four rules, each of which the backend is built to meet halfway:

### 3.1 Write first, send second

Every action lands in IndexedDB **before** a request is attempted. The UI
confirms from the write, not from the response, *"so a driver in a dead spot
sees the same 'recorded' they see in town and does not tap twice."*

This is the correct inversion. A client that confirmed from the response would
teach drivers to double-tap, and double-tapping a handover is how a parcel gets
two collection events.

### 3.2 One id per event, for its whole life

`clientEventId` is minted when the driver taps and **never changes**, across
retries and across restarts. The server deduplicates on it, so *"a retry is a
retry rather than a second collection of the same parcel."*

This is the server's `POST /driver/custody-events/sync` contract honoured
exactly.

### 3.3 One idempotency key per request, stored beside the event

> *"A key generated at send time is a new key every retry, which is the same as
> having none."*

**This is the rule three of the other four clients get wrong**
([`README.md` §6.3](README.md#63-the-idempotency-key-is-minted-per-attempt-in-three-of-five-apps)).
Here the key is a required parameter on every endpoint function:

```ts
collect: (id: number, body: HandoverRequest, idempotencyKey: string) =>
  api.post<CustodyRecorded>(`/driver/shipments/${id}/collect`, body, { idempotencyKey }),
```

The type system forces the caller to have one. That is what makes it correct
rather than merely intended.

### 3.4 Oldest first, stop on the first network failure

> *"A delivery applied before its collection is refused by the chain —
> correctly, and for entirely the wrong reason. And a phone with one bar should
> not fire twelve parallel requests at it."*

The first half is the important one: the server's `CustodyChain` validates
sequence against the events already recorded, so uploading out of order produces
a refusal that looks like a bug and is not.

**Backoff:** `[0, 5s, 15s, 60s, 300s]`, capped — *"a driver is waiting."*

**Batching:** past three queued events, one batch round trip beats twelve. But
not everything may be batched:

```ts
/**
 * A transfer is not among them: `/custody-events/sync` has no field for the
 * other driver or for the second code, and a transfer attested by one person is
 * exactly the link that would be forged. It always goes on its own endpoint.
 */
```

That is a client refusing an optimisation because it would weaken a custody
guarantee. It is the clearest example in the repository of a rule surviving into
a layer that could have quietly broken it.

### 3.5 Four IndexedDB stores, split by purpose rather than tidiness

The `outbox` is the one that matters and is kept separate from cached reads
specifically so a cache clear cannot take the evidence with it.

---

## 4. The vault — a refresh token that survives a stolen phone

> *"A driver's phone is shared between shifts, left in a vehicle, and sometimes
> stolen with the parcels. A refresh token sitting in `localStorage` on such a
> phone is a working session for whoever picks it up — and the session it opens
> can read every recipient address on that driver's round."*

**The mechanism.** The refresh token is encrypted at rest under a key derived
from a four-digit PIN: **PBKDF2-SHA256, 310,000 iterations, AES-GCM**. The PIN
never leaves the device and is never stored, *"not even hashed: the only test of
whether it is right is whether the token decrypts."*

**Five wrong attempts destroy the ciphertext** — mirroring what the backend does
to a handover code after five wrong guesses, because *"a six-digit secret is a
hundred thousand tries to somebody determined and three to somebody who
mistyped, and the count is what tells them apart."*

**Why a PIN at all, when the driver already typed a password:**

> *"The password is long, is typed on a small keyboard in the sun, and unlocks a
> screen showing where people live. Asking for it every five minutes means it
> gets written on the case. Four digits on a huge keypad is the trade that
> actually holds."*

**And it is honest about its limits:**

> *"it does not buy: protection from malicious code running in this origin.
> Nothing in a browser does. The mitigations for that are same-origin
> deployment, no third-party scripts, and the access token living only in
> memory."*

Security code that states what it does not protect against is rarer than
security code that works.

The access token, correspondingly:

> *"An access token in persistent storage on a shared phone survives the driver
> handing it over; one in memory does not survive closing the app, which is the
> correct lifetime for a credential that the refresh token can always re-mint."*

A **60-second slack** is applied to expiry, because *"a token that expires while
the request is in the air is a 401 the driver sees as 'it did not save'."*

---

## 5. Photographs

A delivery is refused without one — it is what settles a dispute months later —
*"so this path has to work on the worst connection the platform runs on."*

The image is **downscaled and re-encoded on the phone before it is sent**, and
the outbox queues the resulting **key**, not the bytes. An event whose photo has
not uploaded is not yet sendable, and the UI shows that as *waiting to upload*
rather than *failed*.

---

## 6. Location

> *"Telling the platform where the driver is — live, and only while it is theirs
> to know."*

Two constraints taken straight from the backend and *"not the app's to soften"*:
`POST /driver/location` is refused while off duty, and the reporting cadence is
whatever the server asks for.

The privacy posture is correct and is the app's own: nothing is offered and
nothing is tracked until the driver says they are working. Where a driver has
been all day is, with recipients' addresses, the sharpest data on the platform.

---

## 7. Scanning

Native `BarcodeDetector` where the phone has it, **ZXing everywhere else**,
torch included. The fallback matters — `BarcodeDetector` is absent on iOS Safari
and on older Android.

`qr.ts` lifts the last path segment of the label's URL and sends it as
`qrToken` alongside the handover, where the backend verifies the signature. It
handles both the absolute form and the relative `/parcels/<token>` a deployment
with no configured base produces.

---

## 8. Words

> *"Deliberately tiny — a map and a hook, no library. The app is built so that
> the words are the smallest part of it: every action is an icon with a colour
> and a fixed position, the flow is one decision per screen, and **a driver who
> reads nothing can still work the whole round by the pictures.** The strings
> are here for everyone else."*

Designing for a driver who cannot read the interface, in a region where that is
a real constraint, is a product decision most teams would not make and this one
made first.

---

## 9. The handover screen

Collection, deposit and delivery are **one component**, because they are the
same shape with different parties — which is also why they are one request
record on the backend.

> *"What differs is what counts as enough evidence, and that is decided by the
> server — this screen gathers, it does not adjudicate."*

Three things are gathered, *"in this order, because that is the order they
happen in on a doorstep"*:

1. **The code the other person reads out.** *"The driver never sees it and is
   never sent it. That is the entire point of a code: it proves two people were
   in the same place at the same time, and a driver who could read it could mark
   a parcel delivered without meeting anybody."*
2. **A photograph**, required on delivery.
3. **The position**, taken **fresh at the moment of recording** rather than
   reused from the map, and shown to the driver **with its accuracy** so they
   know what is being attested to.

C5 and C4 both, enforced a second time in the client that had the most to gain
from cutting a corner.

---

## 10. Code review

### Strengths

1. **The outbox and the vault.** Jointly the most carefully reasoned code in the
   repository, front or back, and both honest about their limits.
2. **The only client with an `ErrorBoundary`.**
3. **The idempotency key is a required parameter.** The one client that gets
   this right, and it explains why in a comment the others should have read.
4. **It refuses an optimisation to protect a custody guarantee** — a transfer is
   never batched, for a stated reason.
5. **It declines to soften server constraints** rather than working around them.
6. **Designed for a driver who cannot read it.**
7. **Zero `any`.** One `console.*` in 7,900 lines.

### Findings

#### 10.1 No tests — **High**, and highest of any client

This is the app whose own source says *"This is the part of the app that has to
be right"* about a module with no test covering it.

**Untested behaviour, ranked by what it would cost:**

| Untested | Failure if it regresses |
|---|---|
| Outbox ordering, dedup, backoff, restart survival | **Lost custody evidence** — a seller is not paid and nobody can say why |
| `clientEventId` stability across retries and restarts | Duplicate collections of one parcel |
| Idempotency key stability | Same |
| Vault: wrong PIN, five-try wipe, decrypt after restart | A driver locked out mid-round, or a stolen phone that still works |
| Batch exclusion of transfers | A transfer attested by one person |
| Photo compression and the key-not-bytes queueing | Delivery refused, or a queue that never drains |

**Recommendation.** Three test files, and they are the highest-value tests
anywhere in this repository:

1. `outbox.test.ts` — queue three events, fail the first send, assert the retry
   reuses both ids; assert order is preserved; assert a transfer is never in a
   batch; assert a reload rehydrates the queue.
2. `vault.test.ts` — right PIN decrypts, wrong PIN fails, five wrong destroys.
   WebCrypto is available in Vitest's jsdom with a polyfill, or run this file in
   the browser runner.
3. `photos.test.ts` — an oversized image is downscaled below the threshold.

#### 10.2 The PIN is four digits with a five-try wipe — **Accepted, worth stating**

Four digits is 10,000 combinations, and 310,000 PBKDF2 iterations make an
offline attack expensive but not impossible against a determined attacker with
the ciphertext. The five-try wipe is what closes it in practice, and the source
reasons about exactly this.

**Not a finding, but worth stating in a threat model:** the wipe is client-side,
so an attacker with the raw IndexedDB file can copy it before attacking. The
real mitigation is the one the source names — the server rotates the refresh
token out from under them — plus the 30-day TTL. Worth noting explicitly in
`README.md` so nobody later "improves" the wipe and thinks the problem is
solved.

#### 10.3 Offline state is not surfaced as a first-class status — **Low**

`lib/connectivity.ts` exists and the screens show queued state, but there is no
single persistent indicator of *"n events waiting to upload"* on every screen. On
a long round out of coverage, a driver's confidence depends on seeing that the
phone is holding their work rather than losing it.

**Fix.** A persistent chip in the header bound to the outbox count, with a tap
target that lists what is waiting.

---

## 11. Running it

```bash
cd frontend/driver-app
cp .env.example .env      # then read it; every setting is explained there
npm install
npm run dev               # http://localhost:5175

cd ../.. && mvn spring-boot:run -Dspring-boot.run.profiles=e2e
```

Sign in as `ebrima.driver@sujula.gm`, password `Sujula123!`. He has leg **1911**
in progress and leg **1912** as a **lapsed offer that must not appear in the
list** — the seed is built to test exactly that.

`vite dev` proxies `/auth`, `/me`, `/driver`, `/notifications`, `/config`,
`/countries` and `/currencies`, so the app talks to the API on its own origin
exactly as it does in production.

**To exercise the part that matters:** open DevTools, go offline, record a
collection and a failed attempt, close the tab, reopen it, come back online, and
watch the outbox drain in order.
