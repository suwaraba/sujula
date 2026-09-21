# Pickup Counter — Client Specification

> For the shop, kiosk or office that holds parcels until somebody collects them.
> **10 operations under `/pickup/**`, plus 2 public ones.**
>
> Usually a tablet or a phone on a counter, operated by somebody with other work
> to do, while a customer waits in front of them. Optimise for speed and for
> being read across a counter.
>
> Read [`README.md`](README.md) first.

---

## 1. Two surfaces, and the line between them

| | Who | Carries |
|---|---|---|
| **Public** `GET /pickup-points`, `/pickup-points/{id}` | Anyone, no account | Address, hours, occupancy **as a band** |
| **Operator** `/pickup/**` | Signed-in `PICKUP_OPERATOR` | Parcels, recipient names, codes, earnings |

**Finding a counter is public.** A shopper chooses where to collect before
signing in, and frequently before they have an account at all — requiring one
would make the delivery option invisible to exactly the people most likely to
want it.

**Running one is not.** These rows lead to recipients' names and phone numbers.

What the public endpoints deliberately do **not** carry: operator name, contact
email, parcel counts, earnings. Occupancy is a **band**, because *"this shop
holds a hundred and ninety parcels"* is a fact about somebody's business.

If you are also building the buyer storefront's counter picker, use the public
endpoints only — and note that **a closed counter does not appear in a nearby
search at all.** Sending somebody to a shuttered counter is worse than showing
nothing.

---

## 2. The operator's day

```
POST  /pickup/applications                  apply to run a counter
GET   /pickup/points                        the counters I run
PATCH /pickup/points/{id}                   hours, capacity, close for a while
GET   /pickup/points/{id}/parcels           incoming · on the shelf · overdue
POST  /pickup/points/{id}/parcels/{sid}/accept
POST  /pickup/points/{id}/parcels/{sid}/reject
POST  /pickup/points/{id}/parcels/{sid}/release
POST  /pickup/points/{id}/parcels/{sid}/resend-code
POST  /pickup/points/{id}/parcels/{sid}/return
GET   /pickup/points/{id}/earnings
```

The operator is resolved from the session and goes into every query beside the
point id. Another operator's counter is not found.

---

## 3. The main screen — three piles

```http
GET /pickup/points/{id}/parcels
```

Returns three groups, and they are three different screens' worth of work:

| Pile | Is | Note |
|---|---|---|
| **Incoming** | On the way here | **No recipient names.** They are not here yet |
| **On the shelf** | Held, with a shelf location (e.g. `A-118`) | The pile a customer is standing in front of |
| **Overdue** | Past the collection deadline | Needs a decision |

**Render the shelf location large.** It is the single piece of information the
operator actually acts on, and they are reading it while somebody waits.

---

## 4. Taking a parcel in

```http
POST /pickup/points/{id}/parcels/{shipmentId}/accept
```

Refused when the counter cannot take it — **and the refusal says which of four
reasons it is**: closed, suspended, switched off, or full.

> Show which. "Cannot accept" leaves an operator arguing with a driver at the
> counter; "you are at capacity" or "this counter is switched off in settings"
> tells them what to do next, and two of the four are things they can fix
> themselves.

`POST …/reject` turns a parcel away with a reason.

Capacity is real: `stored_parcels` is **recounted** from the shipments actually
held rather than asserted, so a count cannot drift into accepting parcels there
is no room for.

---

## 5. Handing a parcel over — the screen that matters most

```http
POST /pickup/points/{id}/parcels/{shipmentId}/release
{ "code": "540913", "collectedBy": "Ousman Ceesay" }
```

**Both fields are required, and both do a job:**

- A **code alone** would let anybody who overheard it take the parcel.
- A **name alone** would let anybody who read the label take it.

**A brother collecting for his sister is allowed, and written down.** That is the
normal case in this market, not an exception to route around — so the field is
*"who is collecting"*, plainly labelled, not a confirmation box saying "I am the
recipient".

Design the screen as:

```
┌─────────────────────────────────┐
│  Parcel A-118                   │
│  For: Isatou …                  │
│                                 │
│  Six-digit code   [ ______ ]    │  ← big, numeric keypad
│  Collected by     [ ________ ]  │  ← name, as they say it
│                                 │
│         [  Hand over  ]         │
└─────────────────────────────────┘
```

Large numeric input. No autocorrect on the name. One button.

If the recipient does not have the code:

```http
POST /pickup/points/{id}/parcels/{shipmentId}/resend-code
```

---

## 6. Sending one back

```http
POST /pickup/points/{id}/parcels/{shipmentId}/return
```

**Refused before the deadline has passed**, and that refusal is correct:
somebody may be travelling to collect, and sending a parcel back early takes a
decision that is not the counter's.

So do not offer the control on a parcel that is not yet overdue — or offer it
disabled, with the deadline shown. An operator tidying a shelf should be able to
see *why* they cannot clear an item.

---

## 7. Hours, capacity and closing

```http
PATCH /pickup/points/{id}
{ "capacity": …, "closedUntil": "…", "acceptingParcels": false, "hours": [ … ] }
```

**Closing is a normal operation, not a failure.** A counter closed for a funeral
simply stops appearing in nearby searches and stops accepting parcels. Make it
one obvious control, reversible, with a clear indication on the main screen of
the current state — an operator who cannot tell whether they are open will
discover it when a driver arrives.

---

## 8. Earnings

```http
GET /pickup/points/{id}/earnings
```

Per counter. If an operator runs several, never sum across them without saying
so.

---

## 9. Rules this client must not break

| Never | Because |
|---|---|
| Show recipient names on the incoming pile | They are not here yet, and the server does not send them |
| Release on a code alone | Anybody who overheard it takes the parcel |
| Release on a name alone | Anybody who read the label takes it |
| Treat a third-party collector as an error | A brother collecting for his sister is the normal case |
| Offer "return to sender" before the deadline | That decision is not the counter's |
| Show a parcel count or earnings on a public screen | Both are facts about somebody's business |
| Send a bare "cannot accept" | The server says which of four reasons, and two are fixable |
| Assume the counter is open | Check and display the state |

---

## 10. Build order

1. Sign-in and the counter list.
2. The three-pile parcel screen, with the shelf location dominant.
3. **The release screen** — it is the one used a hundred times a day.
4. Accept and reject, with the four-reason refusal.
5. Resend code.
6. Hours, capacity, closing.
7. Overdue handling and returns.
8. Earnings.
9. The public-facing counter picker, if you are also building the storefront.
