# Sujula — Glossary

Domain vocabulary, the enums that carry it, and the words this codebase uses
deliberately. Security terms are in [`SECURITY.md`](SECURITY.md#glossary).

---

## The core distinctions

| Term | Means | Do not confuse with |
|---|---|---|
| **Payer context** | Where the buyer is. Decides **display currency** and which payment methods are offered | Delivery context |
| **Delivery context** | Where the goods go. Decides **shipping cost, serviceability, pickup points and catalogue ranking**. A first-class entity with an unguessable id | Payer context |
| **Listing currency** | The vendor's own. What a product is priced in | Display currency |
| **Payout currency** | The vendor's own. Always equals the listing currency | Display currency |
| **Display currency** | The buyer's. What they are actually charged in | Listing currency |
| **Order** | One payment. Belongs to the buyer | Vendor order |
| **Vendor order** (**sub-order**, **slice**) | **One seller's part of an order.** Ships, cancels, refunds and pays out independently. *The* unit of everything after payment | Order |
| **Shipment** | A parcel moving through the custody chain | Vendor order |
| **Leg** | One driver's portion of a shipment's journey | Shipment |
| **Custody event** | A verified transfer with proof. The **only** thing that moves a parcel | Status |
| **Recipient** | The person the goods are for. **May have no account** | Buyer |
| **Buyer** | The person who paid | Recipient |

---

## Money

| Term | Means |
|---|---|
| **Minor units** | How many decimal places a currency actually has. **XOF has 0** — there is no centime of CFA in circulation. `CurrencyCatalogue` is the authority |
| **FX snapshot** | The rate a figure was converted at **and the moment that rate was taken**, frozen onto the row it explains. Convention: `display = native × rate` |
| **Held quote** | A rate committed for 15 minutes, with an id. States: live, consumed (still readable), expired (404) |
| **Indicative rate** | What a pair was last published at, with its age. Commits to nothing |
| **Inverted rate** | A reciprocal used where no direct rate exists. Always disclosed, because a reciprocal carries no spread in that direction |
| **Spread** | The platform's FX margin, effective from a moment, with full history |
| **Escrow** | Money held until delivery is proven. A vendor's "pending" balance |
| **Ledger entry** | A row in `vendor_ledger_entries`. **Balances are sums of these; nothing stores a balance** |
| **Reversal** | A failed payout coming back as a new entry rather than a deletion, so the statement explains the gap |
| **Commission** | What the platform charges. Set per store, effective from a date |
| **Refund request** | What a buyer's cancel produces. **Not a refund** — money leaving the platform needs an administrator |
| **Cart quote** | The cart priced, with figures and rates frozen for 15 minutes |

---

## Delivery

| Term | Means |
|---|---|
| **Delivery leg (pricing)** | Each *product* priced as its own leg: base + per-km + per-kg, scaled by scope and mode |
| **Scope** | Distance class. `REGIIONAL` ⚠ (typo, preserved), `RECOGER` (Spanish, "to collect"), `NATIONAL`, `GLOBAL` |
| **Mode** | How the buyer receives it: `HOME_DELIVERY` (×1.00), `PICKUP_POINT` (×0.75), `VENDOR_PICKUP` (×0.00) |
| **Serviceability** | Whether goods can get from an origin to a destination at all |
| **Rate card** | Admin-set carriage pricing, effective from a date |
| **Zone** | A GeoJSON polygon around a **destination**, not around a buyer |
| **Pickup point** / **counter** | A shop or kiosk holding parcels for collection |
| **Occupancy band** | How full a counter is, published publicly as a band rather than a count |
| **Geocode confidence** | How well an address is located: `EXACT`, `USER_CONFIRMED`, `CENTROID`, `NONE` |
| **Scope fallback** | The per-scope distance used when neither end has usable coordinates |
| **Geofence** | Whether an event's position corroborates the handover. **Evidence, not a gate** — a failing event is flagged, never refused |
| **Safe drop** | Authorisation to leave a parcel with somebody else, with a written instruction |

---

## Custody

| Event type | Means |
|---|---|
| `ARRIVED_AT_ORIGIN` | The driver is at the shop |
| `COLLECTED` | Taken from the seller, against the collection code |
| `DEPOSITED` | Left at a pickup point |
| `REDISPATCHED` | Picked back up from a counter |
| `RELEASED` | **Handed to the recipient.** Needs code + position + photograph |
| `TRANSFERRED` | Driver to driver, both attesting |
| `FAILED_ATTEMPT` | Delivery did not work |
| `RETURNED` | Back to the seller |

| Shipment status (all **derived**) | |
|---|---|
| `AWAITING_COLLECTION` · `DRIVER_OFFERED` · `DRIVER_ASSIGNED` · `AT_ORIGIN` · `IN_TRANSIT` · `AT_PICKUP_POINT` · `OUT_FOR_DELIVERY` · `DELIVERED` · `ATTEMPT_FAILED` · `RETURNED` · `CANCELLED` | Recomputed from the whole chain on every append. **Never set directly** |

| Code | Means |
|---|---|
| **Collection code** (handoff) | Six digits the *seller* presents to the driver. Served `no-store`, never logged, **never on the label** |
| **Release code** (recipient) | Six digits the *recipient* presents. **The driver never sees it.** Single-use |
| **Tracking code** | 16 characters from a 30-symbol alphabet. Possession is the only credential for the public page |

---

## Catalogue

| Term | Means |
|---|---|
| **Product status** | `DRAFT → IN_REVIEW → APPROVED → PUBLISHED`, plus `SUSPENDED`, `ARCHIVED`. **The last step is the seller's** |
| **`active`** | True **if and only if** `status = PUBLISHED`. Every public query filters on it |
| **Approved content hash** | A digest of what a moderator actually looked at — name, text, price, category, brand, condition. Changing any of them sends the listing back; changing stock does not |
| **Archive vs delete** | A listing with order lines is archived; one nobody ordered is deleted |
| **Variant** | A specific buyable configuration (size, colour, capacity) |
| **IMEI unit** | An individual handset. **The units are the authority; the count follows them** |
| **Stock movement** | A ledger row. Stock is the sum of movements, never a typed figure |
| **Promotion** | A price the shop is charging. Cannot overlap another on the same item |
| **Coupon** | A credential a buyer presents. Can be capped per customer, because there is a customer to count |

---

## Accounts and staff

| Role | May |
|---|---|
| `CUSTOMER` | Buy |
| `VENDOR` | Sell |
| `DELIVERY` | Carry parcels |
| `PICKUP_OPERATOR` | Run a counter |
| `SUPPORT` | **Read** the admin surface. Decide nothing |
| `ADMIN` | Everything |

| Term | Means |
|---|---|
| **Staff** | `SUPPORT` or `ADMIN` (`UserRole.isStaff()`) |
| **Decider** | `ADMIN` only (`canDecide()`). Every admin *write* goes through `StaffCaller.decider()` |
| **Store staff** | Someone invited to work in a seller's shop. Permissions come from a **store-only** enum. The owner is not a staff row |
| **KYC** | Identity and business verification. What is required depends on what the store claims to be |
| **Break-glass** | `override-handoff` and `force-status` — the two routes that bypass the platform's guarantees. Both audited |
| **Step-up** | Re-proving identity *now* before something irreversible |
| **Impersonation** | An admin opening a short session as somebody else. Audited |

---

## Platform

| Term | Means |
|---|---|
| **Idempotency key** | A client-supplied key making a retry safe. Same key + same body → the recorded response; same key + **different** body → refused |
| **Managed job** | A background worker registered with `JobRegistry`, visible and runnable from the admin surface. **Every pass leaves a row, including passes that found nothing** |
| **Audit log** | Append-only. No endpoint edits or removes an entry |
| **Feature flag** | A switch, with who last moved it and why |
| **Webhook verdict** | Why an inbound webhook was rejected. Written to the row; the caller always gets the single word "Rejected" |
| **Delivery context id** | 256 bits of base64url. For a guest it is the **only** thing between a stranger and their home address |
| **Cart token** | 256 bits of secure random. The cart's credential |

---

## Words this codebase uses precisely

| Word | Means here |
|---|---|
| **Derived** | Recomputed from its inputs every time, so it cannot drift. Not cached, not patched |
| **Refused** | The server declined, with a sentence explaining why, written for the person who hit it |
| **Frozen** | Copied at a moment and never re-read. Applies to FX rates, quote lines and refund amounts |
| **Escrow** | Money held, not money missing |
| **Not found** | Either "does not exist" **or** "not yours". The two are deliberately indistinguishable |
| **Evidence** | Recorded and possibly flagged, but not used to refuse. Geofences are evidence |
| **Advisory** | Describes what the server will allow so a client can render honestly. Grants nothing (`/me/permissions`) |
| **Idempotent** | Calling it twice is the same as calling it once — and **must not** invalidate what the first call produced |
| **Fail closed** | Unconfigured means refuse, never means trust |
| **Outer gate** | Authentication at the filter chain. The real check is ownership inside the query |

---

## Places and people in the seed

The seed is a coherent world, and the documentation refers to it throughout.

| | |
|---|---|
| **Banjul / Serekunda / Serrekunda / Brikama** | The Gambia. GMD, the dalasi |
| **Ziguinchor / Dakar** | Senegal. XOF, the CFA franc — **no minor unit** |
| **London / Madrid** | Where buyers pay from. GBP, EUR |
| **Lamin Kombo** | Kombo Electronics, Serekunda, settles GMD |
| **Awa Teranga** | Teranga Textiles, Ziguinchor, settles XOF |
| **Mariama** | Store 1103 — a Dakar stall that **settles GMD**, because she banks in Banjul. The proof that address and settlement currency are two questions |
| **Aminata Ceesay** | Buyer in Serekunda, shops in GMD |
| **Oliver Bennett** | Buyer in London, shops in GBP, orders from **both** vendors |
| **Ebrima Bojang** | The driver |
| **Isatou** | Runs the Westfield counter — and, in order 1505, is also a recipient |
| **The sister in Serrekunda** | The person this marketplace exists for. She has a phone number and nothing else |
