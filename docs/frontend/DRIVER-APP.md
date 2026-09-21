# Driver App — Client Specification

> A mobile application for couriers. **19 operations under `/driver/**`.**
>
> This is the client with the hardest physical constraints in the system: it is
> used one-handed, outdoors, on a motorbike, in the dark, on a connection that
> comes and goes. It is also the client that produces the evidence the whole
> custody chain rests on.
>
> Read [`README.md`](README.md) first.

---

## 1. The model this client must hold

**A status is never set. It is derived from events.**

The server has no endpoint that sets a shipment's status. What the driver's app
sends are *events* — arrived, collected, delivered, failed, transferred — each
with proof, and the status is recomputed from the whole chain afterwards.

That is not a stylistic preference. A status field somebody can set is a custody
chain with a hole in it: it lets a parcel reach DELIVERED without anyone having
handed it to anyone.

So this client's job is: **capture evidence accurately, and get it to the server
eventually.** Not "update the status".

---

## 2. The working day

```
POST  /driver/profile                       apply to carry parcels
GET   /driver/profile                       where the application has got to
PATCH /driver/profile                       vehicle, area covered
PUT   /driver/availability                  go online / off
GET   /driver/assignments                   offers + accepted work
POST  /driver/assignments/{legId}/accept
POST  /driver/assignments/{legId}/decline
GET   /driver/shipments/{id}                one parcel
POST  /driver/shipments/{id}/arrived-at-origin
POST  /driver/shipments/{id}/collect
POST  /driver/shipments/{id}/request-recipient-code
POST  /driver/shipments/{id}/deliver
POST  /driver/shipments/{id}/delivery-failed
POST  /driver/shipments/{id}/deposit-at-pickup
POST  /driver/shipments/{id}/transfer
POST  /driver/custody-events/sync           offline batch
POST  /driver/location
GET   /driver/history   ·   GET /driver/earnings
```

Every path resolves the driver from the session. There is no driver id to send,
and no path variable that opens another driver's parcel.

---

## 3. Assignments

```http
GET /driver/assignments
```

Returns offers and accepted work. **Lapsed offers are deliberately absent.**

> A driver shown a dead offer will tap it and read the refusal as a broken app.
> So do not cache the list and re-render it from memory when the network is
> flaky — re-fetch, and if you cannot, mark the list stale rather than showing
> offers that may have gone.

Accepting a lapsed offer is refused with a message saying **the offer went back
to the pool** rather than blaming the driver. Show that message; it is the
difference between "you were too slow" and "somebody else has it".

**Accepting a job is not holding the parcel.** Several operations check that the
parcel was actually collected, not merely assigned.

---

## 4. The parcel screen, and the field that disappears

```http
GET /driver/shipments/{id}
```

**The destination block is present only while the driver is carrying the
parcel.** Once it is handed over, the block is **absent — not blank**. A driver
who delivered a parcel yesterday has no reason to still hold somebody's front
door.

> **Build for the field being absent.** Not empty-string, not null-rendered-as-a-dash:
> gone. A history screen that shows "Address: —" is a screen somebody will file a
> bug about, and a history screen that crashes on a missing key is worse.

While carrying, the block carries the recipient's name, street and number.
Treat it as the most sensitive data in the app: no screenshots in analytics, no
copying to the clipboard by default, no leaving it on screen when the app
backgrounds.

---

## 5. The custody events, in order

### 5.1 At the shop

```http
POST /driver/shipments/{id}/arrived-at-origin
{ "occurredAt": "…", "latitude": …, "longitude": …, "clientEventId": "…" }
```

### 5.2 Collecting

```http
POST /driver/shipments/{id}/collect
{ "code": "871460", "occurredAt": "…", "latitude": …, "longitude": …,
  "clientEventId": "…" }
```

The seller presents the collection code. **No code, no transfer.** The code is
burned in the same transaction, so it cannot open a second handover.

Collecting a parcel on a leg nobody has accepted is refused — the parcel is not
this driver's to move.

### 5.3 Asking for the recipient's code

```http
POST /driver/shipments/{id}/request-recipient-code
```

**The driver never sees the code.** One who could read it could mark a parcel
delivered without meeting anybody. The response confirms it was sent and to
where, masked.

Refused before collection, with a message saying so: emailing a delivery code
for something still on the seller's shelf tells the buyer their parcel is on its
way when it is not, and burns one of the few sends before the driver is anywhere
near the door.

**Rate limited per shipment per hour**, and the refusal explains that sending
more will not make the recipient answer faster. Show it verbatim and offer
"contact support" as the next step.

> ⚠ Today the code is emailed to **the buyer**, who relays it to the recipient.
> Design the screen's wording around that — *"we have asked the buyer to send
> the code"* — and be ready to change it when SMS arrives
> ([`../LIMITATIONS.md` §2.2](../LIMITATIONS.md)).

### 5.4 Delivering — three pieces of proof, all required

```http
POST /driver/shipments/{id}/deliver
{ "code": "540913",
  "latitude": …, "longitude": …,
  "photoKey": "…",
  "occurredAt": "…", "clientEventId": "…" }
```

**The code, a position, and a photograph. All three.** This is the link somebody
would forge if any one of them were enough alone.

So the delivery screen is a three-step wizard with no skip:

1. Ask the recipient for their six digits.
2. Capture position (the phone already has it).
3. Take the photograph — upload it to storage first, send the key.

Each piece is refused independently. Validate locally before sending so a driver
standing at a door does not discover the photo failed after typing the code.

### 5.5 When it does not work

```http
POST /driver/shipments/{id}/delivery-failed
{ "reason": "…", "latitude": …, "longitude": …, "occurredAt": "…", "clientEventId": "…" }
```

Counts toward the shipment's attempt count, which is derived from the chain like
everything else.

### 5.6 Leaving it at a counter

```http
POST /driver/shipments/{id}/deposit-at-pickup
{ "pickupPointId": 1096, … }
```

The counter must then accept it. A counter that is closed, suspended, switched
off or full **refuses, and says which of the four it is.** Surface that so the
driver goes somewhere else rather than waiting.

### 5.7 Driver to driver

```http
POST /driver/shipments/{id}/transfer
```

**Both drivers attest.** Neither side can record a handover alone.

---

## 6. Offline — the part that decides whether this app is usable

A driver out of signal for six hours is Tuesday here, not an exception. Build
offline-first.

### 6.1 Queue everything, send when you can

```http
POST /driver/custody-events/sync
{ "events": [ { "clientEventId": "…", "type": "COLLECTED", "shipmentId": …,
                "occurredAt": "…", "latitude": …, "longitude": …,
                "code": "…", "photoKey": "…" }, … ] }
```

**`clientEventId` is mandatory and must be stable.** Generate it once when the
driver taps, persist it with the queued event, and reuse it across every retry.
The server deduplicates on it, so a phone that uploads, loses signal before the
reply, and uploads again records each event **once**.

Never regenerate the id on retry. That is the whole mechanism.

### 6.2 The clock rules, and what they mean for your UI

| Event timestamp | Server |
|---|---|
| More than **5 minutes ahead** of the server | **Refused.** A device clock is something its holder can set |
| More than **14 days** old | **Refused** — that is a mistake, not a late upload |
| Anything in between | **Accepted** |

So:

- **Stamp `occurredAt` when the driver acts**, not when you upload. A six-hour-old
  event is accepted and is the honest record.
- **Never stamp a future time.** If the phone's clock is fast, every event is
  refused and the driver sees an app that does not work. Measure the offset
  against a server response's `Date` header at sign-in and correct for it.
- Warn the driver if the queue holds anything approaching 14 days old.

### 6.3 Position is evidence, not a gate

An event captured 4.3 km from where the parcel was expected is **recorded and
flagged**, not refused — the parcel may genuinely have changed hands, and
refusing would strand it. The chain simply says the position does not corroborate
the handover.

> **Do not block the driver on a weak GPS fix.** Send the best position you
> have, with its accuracy. A refused handover leaves a parcel in limbo; a flagged
> one is a note for an investigator who may never need it.

### 6.4 Photos

Upload to object storage first and queue the **key**, not the bytes. A queued
event whose photo has not uploaded is not yet sendable — show it as "waiting to
upload" rather than "failed".

---

## 7. Location reporting

```http
POST /driver/location
{ "latitude": …, "longitude": …, "at": "…" }
```

Where the driver has been all day is, with recipients' addresses, **the sharpest
data on the platform** — and it belongs to somebody who never agreed to be
visible to anyone but the person bringing the parcel.

- Report only while **on shift**. Stop the moment availability goes off.
- Make the on/off state unmistakable on screen.
- Batch rather than streaming. It is a battery cost on a phone that also has to
  last the round.

---

## 8. Earnings and history

```
GET /driver/earnings
GET /driver/history
```

History is finished jobs — **with destination details absent**, per §4.

---

## 9. Rules this client must not break

| Never | Because |
|---|---|
| Try to set a shipment status | There is no such endpoint. Send events |
| Regenerate `clientEventId` on retry | Deduplication depends on it; the driver ends up with duplicate events |
| Stamp `occurredAt` at upload time | The honest record is when it happened |
| Send a future timestamp | Refused outright — check the clock offset |
| Show a lapsed offer | The driver taps it and reads the refusal as a broken app |
| Expect a destination on a delivered parcel | The field is **absent**, by design |
| Block delivery on a weak GPS fix | Position is evidence, not a gate |
| Try to read the recipient's code | The driver is not shown it, on purpose |
| Report location while off shift | It is somebody's whole day |
| Cache the destination block beyond the trip | Same reason the server drops it |
| Let a driver skip a delivery step | All three pieces of proof are required together |

---

## 10. Build order

1. Sign-in, profile, application status.
2. **The offline queue with stable `clientEventId`s.** Build this before any
   screen that produces an event — retrofitting it is a rewrite.
3. Clock-offset measurement against the server.
4. Assignments with re-fetch and staleness.
5. The parcel screen, built for an absent destination block.
6. Collect (code capture).
7. The delivery wizard — code, position, photo, no skip.
8. Failed attempt, deposit at pickup, transfer.
9. Location reporting tied hard to the shift switch.
10. Earnings and history.

Steps 2 and 3 are the ones that decide whether this app works on the road.
