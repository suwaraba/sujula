# Sujula Counter

The tablet on a shop counter that takes parcels in from drivers and hands them
to the people they are for.

This is the fifth front end on the Sujula marketplace, and it is the one that is
used in front of a customer. An installable web app (a PWA) rather than a native
build: it installs from a link, it updates the moment a fix is deployed, and it
is the same origin as the API, which is what lets the session cookie, the CSRF
token and the bearer token all work without a cross-origin request.

```
npm install
npm run dev          # http://localhost:5176, against a backend on :8080
```

The backend must be running. From the repository root:

```
mvn spring-boot:run -Dspring-boot.run.profiles=e2e
```

Sign in as `isatou.pickup@sujula.gm` (password `Sujula123!`), who runs two
counters in the seed — Westfield Junction, open, and Latrikunda Sabiji, closed
for a family funeral. The second one is worth opening: it is what every closed
state on this surface looks like.

## What it does

| | |
|---|---|
| **The shelf** | Everything being held, searchable by name, reference or shelf label |
| **Hand over** | The recipient's code on a keypad, plus the name of whoever is actually collecting |
| **Coming in** | What a driver is bringing, and taking it in against the driver's code |
| **Turn away** | A coded reason — damaged, no room, wrong parcel, too large, closing |
| **To return** | Parcels nobody came for, and sending one back once its time is up |
| **Send a code** | To the buyer, who passes it on. Never shown here |
| **Earnings** | A commission per parcel, per currency, never summed across one |
| **The counter** | Hours, capacity, how long a parcel may sit, and closing for a while |
| **Apply** | Opening a counter, with a position as well as an address |

## The one rule this whole app is shaped by

**The operator is never shown a collection code.** Not on the shelf, not on the
parcel, not after sending one. The only thing this screen does with a code is
send digits somebody read aloud to the server and find out whether they were
right.

That is not caution for its own sake. The person collecting may have no account,
no app and no email — she was told six digits by the buyer, and reading them out
is the whole of the proof that she is the right person. An operator who could
read the code could hand the parcel to whoever happened to be standing there,
and nobody downstream could tell the difference. `resend-code` puts it in the
buyer's inbox and answers this screen with a masked hint at where it went.

The rest follows from the same idea:

- **Taking a parcel in verifies the *driver's* code** — the receiving party
  checking the giving party, which is the only thing a code can prove. Same
  shape as every other link in the chain, from the other side.
- **Turning one away does not move custody.** The driver still has it, so the
  server records a failed attempt rather than the end of the chain, and the
  screen says so — an operator who thinks they have handed the problem on will
  stop watching it.
- **Handing one over needs the code *and* a name.** A code alone would let
  anybody who overheard it collect; a name alone, anybody who read the label.
  The name is written down whether or not it matches the parcel, and the server
  says which.
- **A parcel cannot go back before its deadline.** Somebody may be travelling to
  collect it, and that is not the counter's decision to take.

## Notes for whoever works on this next

**The keypad is not a text input, and should not become one.** An operator has a
parcel in one hand and somebody reading numbers at them. A mobile keyboard
covers two thirds of a tablet with letters, none of them wanted, and brings
autocorrect, autofill and history with it — none of which should ever touch
somebody else's collection code. The keys are digits in the layout every phone
uses, so it works for someone who cannot read.

**Nothing compares a code locally.** The server decides. A client that could
tell a right code from a wrong one is a client that can be asked until it says
yes.

**Every custody action carries a `clientEventId`.** The server dedupes on it and
answers `duplicate: true` for a replay, which is how a second press or a retry
after the wifi dropped comes back as *"already handed over"* rather than doing
it twice. The id is held across retries and reset only after a refusal, so a
genuine second attempt is a new event.

**Money is formatted at the currency's own scale**, read from `GET /currencies`.
XOF has no minor units. A commission is small and real, and rounding it to the
wrong number of places is a discrepancy somebody has to chase.

**Earnings come back per currency and are never added together.** A counter
that has held parcels priced in dalasi and in CFA has two earnings, and one
number would need a rate nobody agreed to.

**Another operator's counter answers 404, not 403.** These rows lead to
recipients' names and phone hints, and "forbidden" would confirm the id is real.

**The operator's half of the API is served `no-store`,** because a tablet on a
shop counter is shared. The service worker matches: it caches the app shell so a
dropped connection shows this app's own offline state rather than the browser's
error page, and it caches no API response at all. A cached shelf is also a wrong
shelf — parcels move.

**The gate asks "do you run a counter", not "what is your role".** Only
`/pickup/points` answers it. A counter still awaiting approval gets through on
purpose: the operator needs to see where the application has got to, and the
server refuses every custody action until it is approved, which is the right
place for that rule.

**A counter closed for a week is still worked.** It keeps the parcels it holds —
the people waiting on those did not choose the closure — so the shelf and the
hand-over flow stay live while "coming in" is refused with the reason.

## Serving it

**It must not be served from the API's origin.** Spring serves the API at the
root, so a client's own routes can collide with endpoints. Give it its own host,
with:

- a rewrite of every unmatched path to `index.html`, so a deep link works;
- `VITE_API_BASE_URL` set to the API's origin;
- CORS on the backend allowing that origin **with credentials**, since the CSRF
  cookie has to be readable. There is no CORS configuration in `SecurityConfig`
  today; it will need one.

In development none of that applies: `vite.config.ts` proxies only the API
prefixes this client calls, which keeps the page and the API on one origin.

Serve it over HTTPS. The service worker needs it, and so does installing to a
home screen.

## Layout

```
src/
  api/          client, tokens, CSRF, typed endpoints, DTO mirrors
  auth/         session state and the three route guards
  counter/      which counter is open, and what is on its shelf
  components/   shared UI, the keypad, sheets
  screens/      one file per screen
  lib/          money and date formatting, shared hooks
  styles/       one stylesheet, tokens first, light and dark
public/
  sw.js         app shell only, never the API
  manifest.webmanifest
```

## Known gaps

- **No photo capture.** `accept`, `reject` and `release` all take a `photoUrl`,
  and a damaged parcel is exactly the case for one. It needs the presigned
  upload the seller app uses for images, which is not wired up here.
- **No signature capture.** `release` takes a `signatureUrl`; the name written
  down is what this app records instead.
- **No barcode scan.** `accept` takes a `qrToken` from the parcel label. The
  driver app has the scanner; this one asks for the reference.
- **No offline queue.** Custody actions need the network. The `clientEventId`
  plumbing is already in place for one, but there is no pickup equivalent of
  the driver's `/driver/custody-events/sync`, so a queue here would be inventing
  a protocol rather than using one.
