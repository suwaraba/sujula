# Sujula

A multi-vendor marketplace with a **diaspora-remittance shape**: the person who
pays and the person who receives may be different people, in different countries,
on different continents.

The shape, concretely:

1. A vendor in Banjul or Dakar lists a phone priced in GMD or CFA.
2. A buyer in Madrid browses and must see that price in EUR, and see it ranked
   against the *delivery* location rather than their own.
3. The buyer checks out and pays in EUR with a European payment method.
4. The parcel is delivered to the buyer's sister in Serrekunda — or left at a
   pickup point near her.
5. Money is not released to the vendor until delivery is proven.

## The five rules

Everything in this system follows from that shape. These are not preferences;
code that breaks one of them is wrong even when it passes its tests.

### C1 — Two independent locations

The **payer context** (where the buyer is) and the **delivery context** (where
the goods go) are separate concepts and must never be collapsed into one field.

A buyer in Madrid shipping to Serrekunda is the normal case, not an edge case.
Anything that derives a delivery answer from the payer's location — shipping
cost, serviceability, pickup points, catalogue ranking — is wrong. Anything that
derives a payment answer from the delivery location — which payment methods are
offered, which currency is charged — is equally wrong.

When a method needs a location, its signature must say *which* location.

### C2 — Two currencies per order

- **Listing currency** = **payout currency** = the vendor's own.
- **Display currency** = the buyer's, which is what they are charged in.

**FX rates are snapshotted, never recomputed.** A rate read again later is a
different number, and an order re-priced after the fact is an order whose total
nobody can explain. Every converted figure that anyone is charged, paid, or owed
must carry the rate it was converted at and the moment that rate was taken.

Minor units are part of this. XOF has none — 1250.50 CFA is not an amount that
exists — so rounding is to the currency's own scale, never to two.
`CurrencyCatalogue` is the authority.

### C3 — One payment, many vendors

A single payment splits into per-vendor sub-orders that **ship, cancel, refund,
and pay out independently**.

One vendor cancelling must not touch another's line. A partial refund is a
refund of one vendor's sub-order, not a proportion of the order. A payout is per
vendor, in that vendor's own currency, frozen at the rate on the day the order
was placed.

### C4 — Custody is a chain, not a status

Vendor → driver → (pickup point) → recipient.

Every transfer is a **verified event with proof**, not a button someone clicks.
A status field that can be set directly is a custody chain with a hole in it: it
lets a parcel reach DELIVERED without anyone having handed it to anyone. Each
link produces evidence — a code presented by the receiving party, a signature, a
photograph, a position — and the status is a *consequence* of that evidence
rather than an input.

### C5 — The recipient may not have an account

Delivery verification must work for someone who has a phone number and nothing
else: no account, no app, no email.

An SMS code they read out to the driver is the design target. Anything that
requires the recipient to log in, install something, or click a link in an email
fails the case this marketplace exists to serve — the sister in Serrekunda did
not sign up for anything.

## How these show up in code

- Ownership checks belong **in the query**, not after it. `findLiveByIdAndUserId`,
  not `findById` followed by a comparison — the second is the version that ships
  with the comparison missing.
- Controllers bind DTOs and nothing else. Entity↔DTO conversion happens in the
  service layer; no entity crosses a controller boundary.
- A not-found is the right answer for someone else's row. "Forbidden" confirms
  the row exists, which is itself worth withholding.
- Money arithmetic goes through `CurrencyCatalogue.round`, which knows each
  currency's real scale.

## Build

```
mvn -o compile -Djava.version=21      # the pom targets 21; CI and JDK 26 both build it
mvn -o test -Djava.version=21
```

The context-load test boots the whole application against H2 in MySQL mode. It
is the cheapest test in the suite and finds the most: Spring Data parses every
`@Query` at startup and Hibernate resolves every association, so a broken mapping
or an invalid query fails there rather than on the first request that touches it.

Development seed: `src/main/resources/db/seed/dev-seed.sql` — run by hand, never
auto-loaded. Its own header explains how.

Sample data in Java: `com.sujula.config.seed`, off unless asked for.

```
mvn -o spring-boot:run -Dspring-boot.run.profiles=sample
```

Fills every entity in the model — all of them, checked against Hibernate's
metamodel by `SampleDataSeederTest` — with rows in every state each one can
hold: an order in each `OrderStatus`, a shipment in each `ShipmentStatus`, a
driver who was rejected and one out delivering, a dispute open and one resolved
either way. It runs against whatever database the application booted with, so it
works on MySQL and on the in-memory H2 alike, and it writes nothing when the
database already holds users. Prefer it over the SQL seed when what you need is
a coherent dataset rather than a description of a MySQL schema.
