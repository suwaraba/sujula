# Admin Console — Client Specification

> The staff back office. **95 operations under `/admin/**`** — the largest
> surface in the system — covering users, moderation, logistics, money,
> disputes, communications and the platform itself.
>
> Read [`README.md`](README.md) first.

---

## 1. Two roles, and the rule that shapes every screen

| Role | May |
|---|---|
| `SUPPORT` | **Read everything.** Answer people. Decide nothing |
| `ADMIN` | Everything, including moving money and suspending accounts |

**Support reads; admin decides.** The split is enforced per endpoint, not per
path — a read and a write sit next to each other under the same prefix, so a URL
rule could not tell them apart.

**Render the split, do not discover it.** A support agent should not see a button
that will refuse them. The refusal they get is deliberately the same one a
stranger gets — *"you may look but not touch" is a rule the client should already
be rendering, and repeating it in the error is how a message ends up telling
somebody what to try next.*

Get the role from `GET /me` and `GET /me/permissions` at sign-in, and gate every
write control on it.

> The admin surface also does not confirm its own shape to somebody who should
> not be on it. A non-staff caller gets "Authentication is required", not "you
> are not an admin". Do not try to be more helpful than the server.

---

## 2. Dashboard

```
GET /admin/dashboard        the platform at a glance
```

---

## 3. Users — 11 operations

```
GET   /admin/users                        search accounts
POST  /admin/users                        create an actor by hand
GET   /admin/users/{id}                   one account, with the shape of its history
PATCH /admin/users/{id}
POST  /admin/users/{id}/roles             change what kind of actor this is
POST  /admin/users/{id}/suspend           for a stated number of days
POST  /admin/users/{id}/deactivate        indefinitely
POST  /admin/users/{id}/activate          lift whatever is holding it out
POST  /admin/users/{id}/force-logout      end every open session
POST  /admin/users/{id}/reset-mfa         clear somebody's second factor
POST  /admin/users/{id}/impersonate       open a short session as somebody else
```

**Every account action requires a reason.** No account is locked without one, and
the reason is what a support agent reads three weeks later. Make the field
required in the UI and make it free text, not a dropdown of four options.

**`suspend` takes a number of days; `deactivate` does not.** They are different
decisions and the UI should not merge them into one "disable" toggle.

**Impersonation is audited.** Show that prominently on the confirmation — both
because it is true and because it changes how people use it. While impersonating,
the console should be visually unmistakable (a persistent banner, a different
colour) so nobody forgets which account they are acting as.

> ⚠ **Do not build against `/api/users/**`.** It is the legacy surface and it
> currently carries an authorization defect
> ([`../CODE-REVIEW.md` §2.1](../CODE-REVIEW.md)). Use `/admin/users/**`.

---

## 4. Moderation — 19 operations

```
GET  /admin/moderation/products               listings waiting for review
POST /admin/products/{id}/approve  ·  /reject  ·  /suspend
PATCH /admin/products/{id}                    edit somebody else's listing, with a reason
POST /admin/products                          list something on a seller's behalf
GET  /admin/moderation/reviews                reviews somebody has reported
POST /admin/reviews/{id}/publish  ·  /reject
GET  /admin/moderation/cases                  every policy case, soonest deadline first
POST /admin/moderation/cases/{id}/resolve     decide, and issue what it warrants
GET  /admin/kyc/queue
POST /admin/kyc/{documentId}/approve  ·  /reject
GET  /admin/stores
POST /admin/stores/{vendorId}/approve  ·  /reject  ·  /suspend
PATCH /admin/stores/{vendorId}/commission     from a date
```

**A rejection is worthless without a reason the seller can act on.** The API
takes a reason code **and** words. Require both. *"Rejected"* sends a seller back
to upload the same document again; *"the proof of address is for the depot, not
the shop"* does not.

**Queues are sorted by deadline, not age.** Preserve that order; do not re-sort
by "newest first" out of habit.

**Suspending a store cascades.** The response says what else it affected. Show
the cascade before confirming.

**Commission changes are effective from a date**, never retroactive. Make the
date mandatory and show what it means for orders already placed.

---

## 5. Logistics and dispatch — 29 operations

```
GET  /admin/shipments                              the dispatch board
GET  /admin/shipments/unassigned                   parcels nobody is carrying, with who could
POST /admin/shipments/{id}/assign  ·  /reassign  ·  /unassign  ·  /cancel
GET  /admin/shipments/{id}/custody-chain           the whole chain, with its evidence
POST /admin/shipments/{id}/override-handoff        break-glass
GET  /admin/drivers
POST /admin/drivers/{id}/approve  ·  /suspend
PATCH /admin/drivers/{id}/zones
GET|POST /admin/pickup-points  ·  PATCH /admin/pickup-points/{id}  ·  /suspend
GET|POST /admin/zones  ·  GET|PATCH /admin/zones/{id}
GET|POST /admin/rate-cards  ·  PATCH /admin/rate-cards/{id}
GET  /admin/rate-cards/preview
```

### 5.1 The custody chain viewer

This is the most important read-only screen in the console. It is what somebody
opens when a parcel is disputed.

Render it as a **timeline of events with their evidence**, not as a status
history:

- who recorded each event and when it **occurred** (not when it was uploaded);
- the position, and **whether it corroborated the handover**;
- the proof — the code presented, the photograph;
- **flagged events rendered as flagged, not hidden.** An event captured 4.3 km
  from the shop with `withinGeofence: false` was recorded deliberately. Showing
  it plainly is the whole point.

### 5.2 Break-glass

```http
POST /admin/shipments/{id}/override-handoff
```

Records a handover that could not be proven the ordinary way. **The one route
that bypasses the custody rules**, and it is named for exactly that.

Treat it like a destructive action: a reason, a confirmation that states what is
being overridden, and a visible mark on the chain afterwards. It is audited.

### 5.3 Zones and rate cards

`GET /admin/zones/{id}` returns **GeoJSON exactly as it was uploaded** — do not
normalise, reproject or re-order it before showing it back. A map view plus the
raw text.

Zones are polygons around a **destination**, not around a buyer.

`GET /admin/rate-cards/preview` shows what the live cards would charge for three
sample legs. **Put it next to the edit form**, live, so an operator sees the
effect before saving. Rate cards are effective from a date.

---

## 6. Money — 19 operations

```
GET  /admin/balances                        every seller, every currency they hold
GET  /admin/ledger                          the journal, across every seller
GET  /admin/ledger/reconciliation           escrow vs payments vs paid out
GET  /admin/payments  ·  GET /admin/payments/{id}
POST /admin/payments/{id}/refund            ONE seller's part
GET|POST /admin/payouts/batches
GET  /admin/payouts/batches/{id}
POST /admin/payouts/batches/{id}/approve    never your own
POST /admin/payouts/batches/{id}/cancel
POST /admin/payouts/items/{payoutId}/retry
GET|PATCH /admin/fx/spread                  every spread ever set, and which is live
GET  /admin/fx/rates  ·  POST /admin/fx/refresh
GET  /admin/reports/revenue                 per settlement currency
POST /admin/reports/{type}/export
GET  /admin/reports/exports
```

### 6.1 Never sum across currencies

`GET /admin/balances` returns **every seller's balances in every currency they
hold**. There is no total, and the console must not compute one. `GET
/admin/reports/revenue` is per settlement currency for the same reason.

A combined figure would require a rate, and a rate applied after the fact to
figures that were each frozen at their own rate is a number nobody can explain.

### 6.2 Payout batches — the four-eyes rule

```
assemble  →  approve (by a DIFFERENT admin)  →  release
```

**Approving your own batch is refused.** Build the UI for that: show who
assembled it, and disable the approve button for that person with an explanation
rather than letting them click into a 403.

`cancel` abandons a run before release. After release there is no undo — say so
on the confirmation.

### 6.3 Refunds

`POST /admin/payments/{id}/refund` refunds **one seller's part** of a payment,
never a proportion of the order. The screen must be per sub-order.

A refund priced at today's rate would hand the buyer a different number from the
one the vendor is not being paid, so refunds carry the **order's own frozen
rate**. Show the rate and its date on the confirmation.

### 6.4 FX spread

`GET /admin/fx/spread` returns **every spread ever set and which one is live**.
Render the history — a margin change is a thing somebody will need to explain
later. Changes are effective from a moment; make it explicit.

### 6.5 Reconciliation

`GET /admin/ledger/reconciliation` compares escrow, payments and payouts. **This
is the screen that tells an operator whether the platform's books balance.**
Give it a prominent place and make a mismatch loud.

---

## 7. Disputes and support — 12 operations

```
GET  /admin/disputes                        sorted by DEADLINE, not age
GET  /admin/disputes/{id}                   both currencies and both clocks
POST /admin/disputes/{id}/assign            take it, or give it to somebody
POST /admin/disputes/{id}/notes             for the next agent, never for the parties
POST /admin/disputes/{id}/request-callback  arrange for somebody to be telephoned
POST /admin/disputes/{id}/resolve           decide it, and move the money that follows
GET  /admin/callbacks                       calls somebody still owes, soonest first
POST /admin/callbacks/{id}/outcome
```

**"Both currencies and both clocks"** is the design of the dispute detail screen:
the buyer's currency and the vendor's, the buyer's timezone and the vendor's.
Show both, side by side, labelled. An agent working a Madrid–Serrekunda dispute
needs to know that "yesterday" means different days to the two parties.

**Notes are internal.** Mark the field unmistakably — *"the next agent will read
this; the buyer and the seller will not"* — and keep it visually distinct from
the message composer.

**Resolving moves money.** Show exactly what will move, in which currency, at
which rate, before confirming.

---

## 8. Platform — 17 operations

```
GET   /admin/audit-log                      who did what, and when
GET   /admin/feature-flags  ·  PATCH /admin/feature-flags/{key}
GET   /admin/jobs  ·  GET /admin/jobs/history  ·  POST /admin/jobs/{name}/run
POST  /admin/announcements                  a role, a country, or everybody
POST  /admin/notifications/send             one person
GET   /admin/orders  ·  POST /admin/orders  ·  GET /admin/orders/{id}
POST  /admin/orders/{id}/cancel
POST  /admin/orders/{id}/vendor-orders/{vid}/force-status
```

**The audit log is append-only.** There is no edit and no delete, and the console
should say so on the screen. It is the answer to "who did this", and a log that
could be edited is not.

**Feature flags record who last moved each one, and a reason is required.**

**Job history includes passes that found nothing** — *a job that runs is a job
that leaves a row*. Do not filter empty passes out; a gap in the history is the
signal that a worker stopped.

**`force-status` is break-glass**, like `override-handoff`. It sets a sub-order's
status by hand, bypassing the rules. Reason required, confirmation that names
what is being bypassed, and a visible mark afterwards.

**`POST /admin/orders`** places an order for somebody who telephoned. This is a
real workflow in this market. Build it as a full checkout on the customer's
behalf, honouring the same two locations — the caller's delivery address, and a
currency and payment method appropriate to whoever is paying.

---

## 9. Rules this client must not break

| Never | Because |
|---|---|
| Show a write control to SUPPORT | The refusal is deliberately uninformative |
| Sum balances or revenue across currencies | It requires a rate applied after the fact |
| Let an admin approve their own payout batch | Refused, and the four-eyes rule is the point |
| Offer a refund at the order level | Refunds are per sub-order, at the order's frozen rate |
| Re-sort a deadline-ordered queue by date | The deadline is the priority |
| Hide flagged custody events | The flag is the information |
| Filter empty job passes out of history | A gap is how you notice a worker stopped |
| Normalise GeoJSON before showing it back | It is returned exactly as uploaded, on purpose |
| Make a reason field optional | No account is locked without one |
| Treat break-glass as an ordinary action | `override-handoff` and `force-status` bypass the platform's core guarantees |
| Build against `/api/admin/**` | Legacy; `/admin/**` is the real surface |

---

## 10. Build order

1. Sign-in, role detection, and **write-control gating on `SUPPORT` vs `ADMIN`**.
2. Dashboard.
3. Users and account actions with mandatory reasons.
4. Moderation queues (products, reviews, KYC, stores) with deadline ordering.
5. The **custody chain viewer** — highest value per hour of the read-only screens.
6. Dispatch board, assignment, zones with a map, rate cards with live preview.
7. Money: balances, ledger, **reconciliation**, refunds.
8. Payout batches with the four-eyes rule.
9. Disputes with both currencies and both clocks.
10. Platform: audit log, feature flags, jobs.
11. Break-glass actions, built last and deliberately awkward.
