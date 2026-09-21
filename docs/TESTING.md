# Sujula — Manual Test Plan

> **Purpose.** Everything a human needs to test this application by hand, end to
> end, before it goes anywhere near real money. Written so somebody who has not
> read the code can execute it.
>
> **How it is organised.** §1 gets a server running. §2 is the cast and the
> credentials. §3–§14 are the test suites, each a table of *do this → expect
> this*, with a **Pass/Fail** column to fill in. §15 is what the automated
> suites already cover, so you do not repeat them. §16 is a sign-off sheet.
>
> **The most important instruction in this document:** more than half the tests
> here check that the application **refuses** something. A refusal that does not
> happen is a defect, and it is the kind that never shows up in ordinary use.
> Do not skip the refusal rows.

---

## 1. Getting a server up

### 1.1 The fastest way — no database to install

```bash
cd e2e && ./run.sh
```

That boots a server under the `e2e` profile (whole application, in-memory H2,
development seed already loaded), runs the full 432-operation collection, and
stops it again. About twelve seconds to boot.

To keep the server up and poke at it yourself:

```bash
mvn -o spring-boot:run -Dspring-boot.run.profiles=e2e
# answering on http://localhost:8080
```

> **⚠ Restart the server between runs.** The seed holds one-shot credentials:
> refresh tokens that rotate when used, a phone challenge consumed on confirm,
> stock a checkout decrements. A second run against an already-used server is a
> run against a different world.

> **⚠ Never deploy the `e2e` profile.** Every secret in it is a fixed, published
> test value. The JWT key is in a file in this repository, so anybody holding the
> repository can mint a token for any account.

### 1.2 Against a real MySQL — required before release

A green `e2e` run is a statement about **behaviour**, not about the **schema**.
H2 in MySQL mode disagrees with real MySQL about reserved words and some date
functions. At least one full pass of this document must be done against MySQL.

```bash
# 1. Start the app once so Hibernate creates the schema (dev only)
export SPRING_PROFILES_ACTIVE=dev DB_URL='jdbc:mysql://localhost:3306/sujula?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8'
mvn -o spring-boot:run

# 2. Load the seed by hand (it is never auto-loaded)
mysql -u root -p sujula < src/main/resources/db/seed/dev-seed.sql

# 3. Check you got all 50 tables
mysql -u root -p -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='sujula';"
```

If the seed fails on its first `DELETE`, the database is older than the code:
start the application once on current code, then re-run the seed. The seed's own
header lists every error message and its cause.

### 1.3 Tools

Either import `e2e/sujula.postman_collection.json` and
`e2e/sujula.postman_environment.json` into Postman, or use `curl`. Both are used
below.

### 1.4 Signing in, for every test that follows

```bash
# Get a token
curl -s -X POST localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"aminata.ceesay@example.gm","password":"Sujula123!"}' \
  | tee /tmp/aminata.json

export AMINATA=$(jq -r .accessToken < /tmp/aminata.json)

# Use it
curl -s localhost:8080/me -H "Authorization: Bearer $AMINATA" | jq
```

Or skip the round trip entirely — the seed holds real refresh tokens:

```bash
curl -s -X POST localhost:8080/auth/refresh \
  -H 'Content-Type: application/json' \
  -d '{"refreshToken":"sujula-dev-refresh-aminata-phone"}'
```

> **CSRF.** Bearer-token calls to `/auth/**` and `/me/**` need no CSRF token.
> Cookie-session writes elsewhere do: read `XSRF-TOKEN` from the cookie jar and
> echo it back as the `X-XSRF-TOKEN` header. In Postman, run folder **00** first
> — it captures the token for the whole collection.

---

## 2. The cast

All passwords are **`Sujula123!`**

| Sign in as | Role | Who they are |
|---|---|---|
| `fatou.admin@sujula.gm` | ADMIN | The administrator |
| `lamin.kombo@sujula.gm` | VENDOR | Kombo Electronics, Serekunda — **settles GMD** |
| `awa.teranga@sujula.sn` | VENDOR | Teranga Textiles, Ziguinchor — **settles XOF** |
| `aminata.ceesay@example.gm` | CUSTOMER | Serekunda, shops in GMD. Two devices, authenticator enrolled |
| `oliver.bennett@example.co.uk` | CUSTOMER | **London, shops in GBP.** Google + Apple sign-in, no password he chose |
| `ebrima.driver@sujula.gm` | DELIVERY | The driver |
| `isatou.pickup@sujula.gm` | PICKUP_OPERATOR | Westfield counter |
| `sulayman.blocked@example.gm` | — | **Blocked and fraud-flagged. Sign-in is refused** |

Modou Sanneh is seeded mid-verification (neither email nor phone proved).

### 2.1 The reference numbers you will need

| | |
|---|---|
| **Orders** | `1401` Oliver, GBP, **two vendors** · `1402` guest, cash on delivery, PENDING · `1403` Aminata, GMD, DELIVERED |
| **Sub-orders** | `1501` Lamin's slice of 1401 (SHIPPED) · `1502` Awa's slice of 1401 (PREPARING) · `1504` Aminata's (DELIVERED) · `1505` packed and ready (order 1404) · `1506` cancelled by Awa (order 1404) |
| **Stores** | `1101` Lamin (EXACT pin) · `1102` Awa (CENTROID + depot) · `1103` Mariama, Dakar, **settles GMD**, no coordinates at all |
| **Products** | `1301` published · `1305` with translations · `1307` archive-only (order lines point at it) |
| **Variants** | `1350` IMEI-tracked, stock 2 · `1351` stock 3, movements sum to it |
| **Shipments** | `1900` finished · `1901` in motion · `1902` no events at all |
| **Legs** | `1911` Ebrima's, in progress · `1912` an **offer that lapsed** |
| **Pickup points** | `1096` Westfield, open · `1097` Latrikunda, **closed for a funeral** |
| **Addresses** | `1050` USER_CONFIRMED · `1051` CENTROID · `1052` EXACT (London) · `1053` **NONE** · `1054` soft-deleted |
| **Tracking codes** | `K7MPQ4RTVX2ND9YH` (1401) · `B3WQHJ7FNXR5MTCD` (1402) · `Z9DKP2VMHT6RXQFB` (1403) |
| **Codes** | Lamin's collection code **871460** (old, dead: **304912**) · recipient release **540913** · Modou's phone code **445120** |
| **Recovery codes** | `7K2M-9QX4` unused · `B3TN-6RWZ` unused · `H8PD-2LVC` **already spent** |
| **Refresh tokens** | `…-aminata-phone` · `…-aminata-laptop` · `…-lamin-vendor` · `…-oliver-london` · `…-rotated-away` **(must fail)** · `…-modou-expired` **(must fail)** |
| **Carts / quotes** | `seed-cart-guest-gbp` · quotes: `seed-quote-live`, `-consumed`, `-expired`, `-incomplete` |
| **Delivery contexts** | `seed-ctx-aminata-home`, `-guest-brikama`, `-guest-serrekunda`, `-guest-pickup`, `-oliver-london`, `-expired` |
| **FX quotes** | `seed-fx-oliver-gbp` live · `seed-fx-guest-xof` · `seed-fx-consumed` · `seed-fx-expired` |
| **Import jobs** | `IMP-7QK2M4XR9DTB5VNC` (27 in, 3 rejected) · `IMP-3HJ8P6WZ2FKD7RQY` (unreadable file) · `EXP-5MNX9TQ2JVH4BKDW` |

---

## 3. Suite A — Identity and sessions

| # | Do | Expect | P/F |
|---|---|---|---|
| A1 | `POST /auth/login` as Aminata, correct password | 200, access + refresh token | |
| A2 | Same, **wrong** password | 401, message exactly `Invalid email or password` | |
| A3 | `POST /auth/login` with an email that **does not exist** | 401, **the identical message as A2**. Any difference makes this a tool for discovering which addresses have accounts | |
| A4 | Fail sign-in 3× in a row on one account | Warning email to the owner. **It must contain no reset token** | |
| A5 | Fail 5× | A real password-reset email | |
| A6 | Fail 7× | Account locked. Even the **correct** password is now refused | |
| A7 | Wait 15 minutes, try the correct password | Accepted — locks expire on their own | |
| A8 | Fail on an **admin** account 4× | Locked (admins climb faster: reset at 3, lock at 4, no warning step) | |
| A9 | `POST /auth/refresh` with `sujula-dev-refresh-aminata-phone` | 200, a **new** pair | |
| A10 | Send that **same** refresh token again | **401** | |
| A11 | `POST /auth/refresh` with `sujula-dev-refresh-rotated-away` | **401**, and then `GET /me/sessions` shows session 1805 **revoked, reason TOKEN_REPLAY**. This is the important one: replay is treated as theft | |
| A12 | `POST /auth/refresh` with `sujula-dev-refresh-modou-expired` | 401, and **nothing revoked** — an expired token is not evidence of anything | |
| A13 | Sign in as Aminata (MFA on) with password only | Refused / challenged for the second factor | |
| A14 | Sign in with recovery code `7K2M-9QX4` | Accepted | |
| A15 | Use `7K2M-9QX4` **again** | Refused — each works once | |
| A16 | Use `H8PD-2LVC` (already spent) | Refused. Spent codes are kept, not deleted, so reuse is refused rather than mistaken for a code that never existed | |
| A17 | `GET /me/sessions` as Aminata | Two live devices, each with its own detail | |
| A18 | `DELETE /me/sessions/{other}`, then use that device's access token | **Refused on its very next request** — not after the token expires | |
| A19 | Sign in as `sulayman.blocked@example.gm` | Refused. Credentials are right; the account is not usable | |
| A20 | `POST /auth/verify-phone/confirm` for Modou with `445120` | Accepted | |
| A21 | Repeat A20 | Refused — the challenge is consumed | |
| A22 | `GET /me/permissions` as a **pending** vendor | Lists what the server will actually allow, not what the role implies. A back office rendered from the role alone would show buttons the API then refuses | |
| A23 | `GET /me` with **no token** | **401**, with a JSON body — not 403 and not an empty body. `FilterChainRefusals` splits the two: 401 means *say who you are and try again*, 403 means *signing in will not help* | |
| A24 | `POST /me/export`, then `GET /me/export` | A request is created, the worker picks it up within the minute, a download link appears | |
| A25 | `DELETE /me` twice | The **same** erasure request comes back, not a second one | |

---

## 4. Suite B — C1: two locations, never collapsed

**This is the suite that proves the marketplace works for the people it is for.**
Oliver is in London. The phone is going to Serrekunda. Those are two questions.

| # | Do | Expect | P/F |
|---|---|---|---|
| B1 | `GET /products?deliverableTo=seed-ctx-aminata-home` as Oliver | Ranked against **Serrekunda**. Prices in **GBP** (his). The response echoes the location it used under `delivery` | |
| B2 | Change **only** the delivery context, re-run | **Ranking moves. Currency does not** | |
| B3 | Change **only** the caller's apparent country, re-run | **Currency moves. Ranking does not** | |
| B4 | `GET /api/products/search?q=wax&deliveryLat=13.4383&deliveryLng=-16.6781&deliveryCountry=GM&currency=GBP` | Coordinates are the **recipient's**; currency is the **payer's** | |
| B5 | Look for a `userLat` parameter anywhere in the catalogue API | **There is none.** If one appears, C1 has been broken | |
| B6 | `GET /carts/seed-cart-guest-gbp` | Grouped by store, each group with its own shipping, each line its own delivery leg — a multivendor basket has no single origin | |
| B7 | `PUT /carts/{t}/delivery-context` | Shipping re-prices. **Currency untouched** | |
| B8 | `PUT /carts/{t}/currency` | Prices change. **Delivery untouched** | |
| B9 | `POST /delivery/serviceability` with `origin: {vendorId: 1101}` and a Serrekunda destination | Answers, **with no account** | |
| B10 | `POST /delivery/quote` to Oliver in GBP with no seeded rate | `complete: false` — **not** a number. Quoting the rate card's currency under someone else's symbol is how a buyer is charged £50 for a D50 delivery | |
| B11 | `GET /delivery-contexts/seed-ctx-aminata-home` as **anyone else** | **404**, not 403. Confirming it exists tells whoever guessed the id that they guessed right | |
| B12 | `GET /delivery-contexts/seed-ctx-expired` | **404**, not 410. An expired bearer credential and one that never existed must be indistinguishable | |
| B13 | `POST /vendor/stores` with a **Senegalese** address and **no** currency | Settles in **GMD** (the platform default) — **not** XOF inferred from the country. Store 1103 is the seeded proof: Dakar address, GMD settlement | |
| B14 | `GET /me/addresses` as Aminata | Each address carries a confidence: 1050 USER_CONFIRMED, 1051 CENTROID with `needsPinConfirmation: true` | |
| B15 | `POST /me/addresses/1053/confirm-pin` with coordinates | Becomes USER_CONFIRMED and dispatchable, **and stays so through later edits** | |
| B16 | With **no** Google key configured, `POST /geo/validate-address` | `available: false` — *"nobody looked"*, which is different from "we looked and found nothing" and leads to different advice. Addresses still save; delivery prices from a scope fallback | |

---

## 5. Suite C — C2: money, currency and frozen rates

| # | Do | Expect | P/F |
|---|---|---|---|
| C1 | `GET /currencies` | Every entry carries `minorUnits`. **XOF is 0.** GMD, GBP, EUR, USD are 2 | |
| C2 | Any total in **XOF**, anywhere | **No decimal places.** `1250.50 CFA` is an amount that does not exist. ⚠ *This is the known gap — see `LIMITATIONS.md` §2.1. Record where you see two decimals on an XOF figure* | |
| C3 | `GET /currencies/rates?base=GMD&quote=GBP` | A published rate, direct | |
| C4 | `GET /currencies/rates?base=GBP&quote=XOF` | `inverted: true` with the reciprocal — **disclosed**, because a reciprocal carries no spread in that direction | |
| C5 | `GET /currencies/rates?base=GMD&quote=SEK` | Says there is no rate. **Does not guess** | |
| C6 | `POST /currencies/quote {"base":"GMD","quote":"GBP","amount":4500}` | Held for 15 minutes, returns an id | |
| C7 | **Change rate row 1220** in `exchange_rates`, then `GET /currencies/quote/seed-fx-oliver-gbp` | **Still 0.011.** A quote that re-read the table would not be a quote | |
| C8 | `GET /currencies/quote/seed-fx-oliver-gbp` as **anyone but Oliver** | **404**, not 403 | |
| C9 | `GET /currencies/quote/seed-fx-consumed` | Readable, **reported as used** — a client reloading a confirmation page must not see "missing" | |
| C10 | `GET /currencies/quote/seed-fx-expired` | 404, not 410 | |
| C11 | `POST /carts/seed-cart-guest-gbp/quote`, then check the arithmetic | `8500 GMD × 0.011 = 93.50 GBP`. **A stored rate that does not reproduce the stored amount is decoration, not evidence** | |
| C12 | `POST /checkout` with `seed-quote-expired` | **404** | |
| C13 | `POST /checkout` with `seed-quote-incomplete` (one vendor's currency had no rate) | **Refused.** A total that silently dropped a vendor's goods would undercharge and the platform would owe the difference | |
| C14 | `GET /vendor/balance` as Lamin | 1000 GMD available, 17,640 pending. **Pending is escrow** — parcels not yet confirmed delivered | |
| C15 | `GET /vendor/transactions`, add the amount column up | **Equals the balance.** Nothing stores either figure; that is the only thing that makes a balance checkable | |
| C16 | `GET /vendor/transactions?currency=XOF` as Awa, look at slice 1506 | **Four rows** — sale, commission, refund, commission returned — summing to exactly nothing. Netting them into one would hide the thing a seller opens a refund to check | |
| C17 | `GET /vendor/balance` as Awa | 0 available, 11,419 **XOF** pending. Her failed payout came back as a **reversal**, not a deletion, so the statement explains the gap | |
| C18 | `POST /vendor/payouts/request` as Awa | Refused, and the message says **which kind of nothing** it is: money held against parcels, not no money at all | |
| C19 | `GET /vendor/statements/2026-09?currency=GMD` as Lamin | A PDF where **brought-forward + movements = carried-forward**. Add `&format=csv` for the same figures | |
| C20 | `GET /vendor/analytics/overview` for a two-currency shop | **Two figures and a note saying why they are not added.** Never one total | |
| C21 | `GET /admin/fx/spread` | Every spread ever set, and which is live | |

---

## 6. Suite D — C3: one payment, many vendors

| # | Do | Expect | P/F |
|---|---|---|---|
| D1 | `GET /orders/1401` as Oliver | Grouped by seller, **each group totalling on its own** | |
| D2 | `GET /orders/1401` as **Aminata** | **404**, not 403 | |
| D3 | `GET /vendor/orders` as **Lamin** | His slice only, **priced only in GMD**. The response contains **no GBP, no London, no Oliver** | |
| D4 | `GET /vendor/orders` as **Awa** | Her slice only, **priced only in XOF**. Same order, a different world | |
| D5 | `POST /orders/1401/cancel` (the whole order) | **Refused**, and it **names Lamin's store** — slice 1501 is SHIPPED, and stopping goods already with a courier is a return, not a cancellation | |
| D6 | `POST /orders/1401/vendor-orders/1502/cancel` | **Allowed** (1502 is still PREPARING — nothing has left the shop) | |
| D7 | Immediately re-check slice **1501** | **Exactly where it was.** That is C3, and it is worth checking rather than assuming | |
| D8 | Look at what D6 returned | A refund **request**, not a refund: 16.70 GBP against slice 1502 alone, waiting on an administrator. **Money leaving the platform is the one action no later API call can undo**, so nothing in the buyer surface completes it | |
| D9 | Call D6 **again** | **Still one refund request row.** A buyer who taps cancel, sees nothing on a slow connection and taps again must not queue two refunds against the same goods | |
| D10 | Inspect refund request 1450 (`RFN-SEED-0001`) | It carries **the slice's own rate, copied**, not looked up again. 13,050 XOF is 16.70 GBP at this order's rate and no other | |
| D11 | `POST /admin/payments/{id}/refund` | Refunds **one seller's part**, never a proportion of the order | |
| D12 | Look for any endpoint that returns or disputes "an order" as a whole | **There is none**, deliberately — an order is not a thing anybody can be in dispute about | |
| D13 | `GET /vendor/orders/1506` as Awa | The slice she rejected, **with her reason on it**. Then re-check 1505: untouched, still going | |

---

## 7. Suite E — C4: the custody chain

**The rule under test: no parcel reaches DELIVERED without somebody handing it
to somebody.**

| # | Do | Expect | P/F |
|---|---|---|---|
| E1 | Find any API route that sets a shipment status directly | **There is none.** `Shipment` has no public status setter | |
| E2 | `PATCH /vendor/orders/{id}/status` to DELIVERED | **Refused.** Order 1403 is DELIVERED only because the seed file wrote it that way | |
| E3 | `GET /driver/assignments` as Ebrima | Leg **1911** (his, in progress). Leg **1912 is deliberately absent** — a driver shown a dead offer will tap it and read the refusal as a broken app | |
| E4 | `POST /driver/assignments/1912/accept` | Refused, and it says the offer went **back to the pool** rather than blaming him | |
| E5 | `POST /driver/shipments/1902/collect` | Refused — nobody has accepted that leg, so the parcel is not his to move | |
| E6 | `GET /driver/shipments/1901` (he is carrying it) | Destination block present: Isatou's name, street, number | |
| E7 | `GET /driver/shipments/1900` (he handed it over) | **Destination block ABSENT, not blank.** A driver who delivered yesterday has no reason to still hold somebody's front door | |
| E8 | `POST /driver/shipments/1901/request-recipient-code` | Code sent. **The driver never sees it** — one who could read it could mark a parcel delivered without meeting anybody | |
| E9 | `POST /driver/shipments/1901/deliver` with the code but **no photo** | Refused | |
| E10 | Same, with photo but **no position** | Refused | |
| E11 | Same, with a **wrong** code | Refused | |
| E12 | Same, with code `540913` + position + photo | Accepted. **This is the link somebody would forge if any one of them were enough alone** | |
| E13 | Present code `540913` a **second** time | Refused — burned in the same transaction as the handover | |
| E14 | Send an event dated **1 hour in the future** | Refused. A device clock is something its holder can set | |
| E15 | Send an event dated **20 days ago** | Refused — that is a mistake, not a late upload | |
| E16 | Send an event dated **6 hours ago** | **Accepted.** A driver out of signal for six hours is Tuesday here, not an attack | |
| E17 | Inspect custody event **1923** | Collected 4.3 km from the shop, `withinGeofence: false`. **Recorded and flagged, not refused** — the parcel may genuinely have changed hands, and refusing would strand it | |
| E18 | `POST /driver/custody-events/sync` with the same batch **twice** | Each event recorded **once**, deduplicated by the id the driver's app gave it | |
| E19 | `GET /admin/shipments/1901/custody-chain` | The whole chain with its evidence | |
| E20 | Walk 1901 backwards: does its status follow only from events? | Yes — status is derived from the whole chain on every append, never patched | |

---

## 8. Suite F — C5: the recipient with no account

| # | Do | Expect | P/F |
|---|---|---|---|
| F1 | `GET /track/K7MPQ4RTVX2ND9YH` with **no token, no account, no sign-in** | A page. This is the sister in Serrekunda with a text message and nothing else | |
| F2 | Compare F1 against `GET /orders/1401/tracking` (the buyer's view) | The public page has **no name, no street, no phone, no price, no order number** | |
| F3 | Read the event descriptions on the public page | **Fixed phrases.** Row 1712 says *"Assigned to Ebrima Bojang"*; the public page says *"A driver has been assigned."* **That substitution is the only thing between a driver's notes and a stranger who was forwarded the SMS** | |
| F4 | `POST /parcels/{code}/reschedule` to tomorrow | Accepted, with no account | |
| F5 | `POST /parcels/{code}/choose-pickup-point` | Accepted | |
| F6 | `POST /parcels/{code}/authorise-safe-drop` | Accepted | |
| F7 | Try F4–F6 with a **wrong six-digit code** | Refused. The tracking code reads; the six digits authorise | |
| F8 | Try a **random** 16-character tracking code | Not found | |
| F9 | `POST /pickup/points/1096/parcels/1903/release` with the code but **no collector name** | Refused | |
| F10 | Same with a name but **no code** | Refused. A code alone lets anybody who overheard it take the parcel; a name alone anybody who read the label | |
| F11 | Same with **both**, collector = the recipient's brother | **Allowed, and written down.** A brother collecting for his sister is the normal case | |
| F12 | **Where does the release code actually go?** | ⚠ **Today: email, to the buyer**, who relays it. Not an SMS to the recipient. Confirm this matches what you expect, and read `LIMITATIONS.md` §2.2 | |

---

## 9. Suite G — Vendor: store, catalogue, stock

| # | Do | Expect | P/F |
|---|---|---|---|
| G1 | `POST /vendor/stores` | Opens in `PENDING_KYC` | |
| G2 | `GET /vendor/stores/1101` as **Awa** | Not found. The store id is in the path, the owner never is | |
| G3 | `GET /vendor/stores/1102/kyc` | Awa's first national ID (1364) **refused as too dark**; the second (1365) accepted. **Both survive** — the sequence is the record of why onboarding took three weeks | |
| G4 | Check what 1101 vs 1103 are asked for | 1101 gave registration + tax numbers → asked for both certificates. 1103 gave neither → asked for an ID and a proof of address. **Demanding a company's paperwork from a market trader is how a marketplace turns away the sellers it exists for** | |
| G5 | `GET /vendor/stores/{id}/kyc` — look for a document URL | **Not returned.** Only storage keys are stored; a URL to somebody's passport in a JSON response is a URL in a browser cache | |
| G6 | `PUT /vendor/stores/1101/bank-account` with password only, on an **MFA** account | **Refused.** A step-up easier to pass than the sign-in that reached it is not a step-up | |
| G7 | Same with password **and** authenticator code | Accepted | |
| G8 | Same on a deployment with **no** `field-encryption.key` | **Refused before it reads anything.** A system that looks like it encrypts bank details and does not is worse than one that admits it cannot | |
| G9 | Read the raw `bank_accounts` column after G7 | **AES-GCM ciphertext.** The digits are not there | |
| G10 | `GET` the destination back through the API | Four digits, a holder name, a currency. **Nothing that could send money anywhere** | |
| G11 | `POST /vendor/stores/1101/staff` inviting `binta@…`, who **has no account here** | Invitation created, keyed on the email, waiting for her to register. The usual invitee is a relative who has never used the platform | |
| G12 | Check staff row 1372 (removed) | Digest null, permissions gone — otherwise a link mailed last week still opens the shop. **The row stays**, which is what makes "who could see this, and when" answerable | |
| G13 | Try to grant a staff permission that reaches another vendor | **Unrepresentable** — the enum is store-only. The difference between a check that can be forgotten and one that cannot | |
| G14 | `POST /vendor/products`, then publish it immediately | **Refused.** A draft cannot put itself on sale — that refusal is the only thing making the review queue real rather than advisory | |
| G15 | `PATCH /vendor/products/1301` changing the **price** | Goes back to `IN_REVIEW`, `active` → false. The approved content hash no longer matches | |
| G16 | `PATCH /vendor/products/1301` changing **stock** | **Nothing happens.** A seller who had to re-enter a queue to restock would stop using the queue | |
| G17 | `SELECT id, status, active FROM products` | **`active` is true if and only if `status = PUBLISHED`**, on every row. Two fields describing one fact drift the moment something sets one without the other — and the way they drift is a listing moderation pulled that carries on selling | |
| G18 | `DELETE /vendor/products/1307` (order lines point at it) | **Archived, not deleted.** A buyer's receipt, invoice and review must keep resolving years from now | |
| G19 | `DELETE` a product **nobody ever ordered** | Genuinely deleted | |
| G20 | `GET /vendor/imports/IMP-7QK2M4XR9DTB5VNC` | 27 in, 3 rejected. Each error names the row **as the seller's own spreadsheet numbers it** (header is row 1) and quotes their text: *"there is no category called 'Phonez'"* | |
| G21 | `GET /vendor/imports/IMP-3HJ8P6WZ2FKD7RQY` | A **file-level** failure (somebody uploaded a PDF), said once — not four hundred identical row errors | |
| G22 | Check what an import creates | **Every row is a DRAFT.** An import that could publish would be the way past moderation | |
| G23 | Try a **sequential** import reference | Not found. Countable job ids hand out other sellers' products, prices and SKUs | |
| G24 | `GET /vendor/inventory/1351/movements`, add them up | `0 + 10 − 3 − 4 = 3` = its stock. **A count that can be assigned directly is a count nobody can explain** | |
| G25 | Look for **SALE** rows in that trail | Present. An audit showing manual edits and omitting the orders that took the stock would be wrong in exactly the case somebody opens it for. A SALE has no order number (stock is reserved before the order exists) and no person | |
| G26 | `PATCH /vendor/inventory/1351 {"setTo":12,"version":0,…}` **twice** | Second refused (**409**) — the first moved the version. Two people counting one shelf must not leave whichever committed last with the other simply wrong | |
| G27 | `PATCH /vendor/inventory/1351 {"delta":-2,…}` twice | **Both succeed.** `−2` is `−2` whoever else is writing, which is why only the absolute form asks for a version | |
| G28 | `PATCH /vendor/inventory/1350` (IMEI-tracked) with a typed figure | **Refused outright.** The units are the authority; the count follows them | |
| G29 | `POST /vendor/imei-units` with `490154203237519` (one digit off Luhn) | **That line alone rejected**; the others register | |
| G30 | Try to mark an IMEI **SOLD** | Refused — the order does that, so the record and the sale cannot disagree | |
| G31 | Try to set or clear **BLOCKED** | Refused both ways. Setting it would flag a rival's stock; clearing it would launder a stolen handset | |
| G32 | `POST /vendor/promotions/1472/activate` | **Refused, and it names 1470** — both cover product 1301 and their windows touch. "Conflicts with an existing promotion" would leave a seller hunting through their own list | |
| G33 | `POST /vendor/promotions/1471/activate` | Allowed | |
| G34 | Create a `FREE_SHIPPING` promotion overlapping an existing goods discount on the same product | **Allowed.** One discounts the delivery leg, the other the goods, so they are not competing for the same number (seeded example: 1473 is FREE_SHIPPING on Awa's cloth) | |
| G35 | Check promotion 1471's currency | **500 GMD**, Lamin's own. A buyer paying GBP sees it converted at their order's rate. Striking it in GBP would leave him funding an amount that moves with the market | |

---

## 10. Suite H — Fulfilment and handover codes

| # | Do | Expect | P/F |
|---|---|---|---|
| H1 | `GET /vendor/orders/1505` as Lamin | The `shipping` block is a name, a town, a country, three digits of a phone. **No street** (the platform routes it), **no payer at all** | |
| H2 | `GET /vendor/orders/1505/handoff-code` | **871460**. Served `no-store` and **never logged** — check the log to confirm | |
| H3 | Present the **old** code **304912** | Dead | |
| H4 | `GET /vendor/orders/1505/label` | An A6 PDF. **Read what is NOT on it:** no street, no price, no contents, **and not the collection code**. A code printed on the box it protects protects nothing | |
| H5 | Scan the QR on the label | Resolves the address for whoever scans it | |
| H6 | `POST /vendor/orders/1505/ready` (already packed) | Idempotent — says so, and **the collection code is UNCHANGED**. A driver is already on the way with the first code, and a seller tapping twice on a patchy connection must not invalidate it | |
| H7 | `POST /vendor/orders/{id}/ready` on a slice whose **phone is unscanned** | Refused, **naming the line and the count** | |
| H8 | `POST /vendor/orders/{id}/handoff-code/regenerate` | A new code; the old one dies | |

---

## 11. Suite I — Pickup counter

| # | Do | Expect | P/F |
|---|---|---|---|
| I1 | `GET /pickup-points?lat=13.4429&lng=-16.6776&radius=5` with **no account** | Westfield (1096) comes back | |
| I2 | Same search — is Latrikunda (1097) there? | **No.** It is closed for a funeral, and **sending somebody to a shuttered counter is worse than showing nothing** | |
| I3 | `GET /pickup-points/1096` | Address, hours, and occupancy **as a BAND**. **No operator name, no contact email, no parcel count** — that this shop holds 190 parcels is a fact about somebody's business | |
| I4 | `GET /pickup/points/1096/parcels` as Isatou | Three piles: incoming, on the shelf (1903 on A-118), overdue | |
| I5 | Check the **incoming** pile | **No recipient names** — they are not here yet | |
| I6 | `POST /pickup/points/1096/parcels/1903/return` | **Refused** — its deadline has not passed. Somebody may be travelling to collect, and sending it back early is not the counter's decision | |
| I7 | `POST /pickup/points/1097/parcels/1903/accept` | Refused, and it says **which of the four reasons**: closed, suspended, switched off, or full | |
| I8 | `SELECT stored_parcels FROM pickup_points WHERE id=1096` | **1** — and it is **recounted** from the shipments held, not asserted. A count that could drift would drift into accepting parcels there is no room for | |
| I9 | `GET /pickup/points/1096` as **a different operator** | Not found | |

---

## 12. Suite J — Admin

| # | Do | Expect | P/F |
|---|---|---|---|
| J1 | Sign in as **SUPPORT**, read any admin queue | Allowed | |
| J2 | As SUPPORT, attempt **any** admin write | **Refused, with the same message a stranger gets.** "You may look but not touch" is a rule the client should already render; repeating it in the error tells somebody what to try next | |
| J3 | As a **CUSTOMER**, `GET /admin/dashboard` | Refused, and the refusal **does not confirm the surface's shape** | |
| J4 | `POST /admin/payouts/batches`, then approve **your own** batch | **Refused.** Never your own | |
| J5 | Approve it as a **different** admin | Allowed | |
| J6 | `GET /admin/disputes` | Sorted by **deadline**, not age | |
| J7 | `GET /admin/audit-log` after any admin action | The action is there, with actor and time | |
| J8 | Try to edit or delete an audit entry | **No endpoint exists** | |
| J9 | `POST /admin/users/{id}/impersonate` | A short session as somebody else — **and an audit entry** | |
| J10 | `GET /admin/jobs` and `/admin/jobs/history` | Every pass listed, **including the ones that found nothing**. A job that runs is a job that leaves a row | |
| J11 | `POST /admin/jobs/{name}/run` | Runs now, leaves a row | |
| J12 | `GET /admin/ledger/reconciliation` | Escrow vs payments vs paid out, and they agree | |
| J13 | `GET /admin/zones/{id}` | GeoJSON **exactly as uploaded** | |
| J14 | `GET /admin/rate-cards/preview` | What the live cards would charge for three sample legs | |
| J15 | `POST /admin/shipments/{id}/override-handoff` | Recorded as an override **and audited**. The one break-glass route, named for what it is | |
| J16 | `GET /admin/balances` | Every seller's balances, **in every currency they hold** — never summed across currencies | |

---

## 13. Suite K — Security

These are the rows to run **twice**: once as written, once trying to break them.

| # | Do | Expect | P/F |
|---|---|---|---|
| K1 | ⚠ **As an ordinary CUSTOMER:** `DELETE /api/users/{someone else}/permanent` | **Should be 403 with the account intact.** **Today the response is 403 and the account is deleted.** This is `CODE-REVIEW.md` §2.1 — **must be fixed before release** | |
| K2 | As a CUSTOMER: `PUT /api/users/{someone else}` with a changed name | Same defect. Check the row afterwards, not just the status code | |
| K3 | As a CUSTOMER: `GET /api/users?role=ADMIN` | 403 — signed in, not permitted (a read, so nothing is written) | |
| K3b | Anonymous: `GET /orders` | **401**, JSON body, not an empty 403 | |
| K4 | `GET /v3/api-docs` and `/openapi.json` on a **prod-profile** server, anonymously | **Not 200.** The document maps every path and request shape | |
| K5 | `GET /actuator/prometheus` anonymously | Refused. Request counts, error rates and timings per endpoint tell an outsider when the platform is struggling and which path to press on | |
| K6 | `GET /health/liveness`, `/health/readiness` anonymously | 200, and **nothing a stranger can use** | |
| K7 | `POST /webhooks/{provider}` with **no** signature | Rejected | |
| K8 | With a **valid** signature and a timestamp **10 minutes old** | Rejected (tolerance is 5m) | |
| K9 | Capture a valid webhook and **replay it** 10 minutes later | Rejected. The timestamp is inside the signature, which is what stops yesterday's "payment succeeded" arriving tomorrow | |
| K10 | Send a valid body with **one byte changed** | Rejected | |
| K11 | Compare the response text for K7–K10 | **All say the same thing: "Rejected."** Telling a prober which they got right tells them how to make progress | |
| K12 | Configure **no secret** for a provider, send a perfectly signed request | **Rejected.** An unconfigured provider is refused, not trusted | |
| K13 | Provoke a database error (e.g. a huge string in a bounded field) | The response carries **no SQL, no constraint name, no stack trace, no class name** | |
| K14 | Request a URL that does not exist | JSON, **not** the container's HTML error page | |
| K15 | Browser `POST` to a CSRF-protected path with **no** `X-XSRF-TOKEN` | Refused | |
| K16 | Same with the token | Accepted | |
| K17 | `POST /auth/login` with a bearer token and **no** CSRF token | **Accepted** — the bearer surface is exempt, and correctly so | |
| K18 | `GET /orders/1401` as Aminata (Oliver's order) | **404**, not 403 | |
| K19 | `GET /vendor/orders/1501` as Awa (Lamin's slice) | **404/not found**, not 403 | |
| K20 | Start the app with `sujula.payment.mock.enabled=true` and `SPRING_PROFILES_ACTIVE=prod` | **Refuses to start** | |
| K21 | Start under `prod` with **no** `sujula.auth.jwt.secret` | **Refuses to start** | |
| K22 | Start with a `base-currency` not in the configured list | **Refuses to start** | |
| K23 | ⚠ Start with **no** `SPRING_PROFILES_ACTIVE` at all | Today it silently becomes **`dev`** — mock payments on, schema auto-updated, docs public, insecure cookie. See `CODE-REVIEW.md` §6.6 | |
| K24 | Search the whole log for a handover code, a release code, a token, an account number | **Nothing found** | |
| K25 | Call `POST /geo/validate-address` 200 times in a minute | ⚠ No limit today. Each call spends a paid Google request. Confirm your proxy limits it — see `OPERATIONS.md` | |
| K26 | From a different origin, `fetch()` any endpoint in a browser | Blocked (no CORS is configured). Confirm this matches your deployment plan | |

---

## 14. Suite L — Resilience and degraded operation

| # | Do | Expect | P/F |
|---|---|---|---|
| L1 | Start with **no** Google geocoding key | Everything works. `validate-address` answers `available: false`; addresses save without coordinates; delivery prices from a scope fallback | |
| L2 | Start with **no** R2 credentials | Everything works except image upload, which returns **400 explaining what to set** | |
| L3 | Point SMTP at an **unreachable** host, then register | Fails within ~5 seconds, **not forever**. JavaMail waits indefinitely by default and these sends are on the request thread — one bad mail host would consume the whole Tomcat pool | |
| L4 | Stop the database while the app runs, hit `/health/readiness` | Reports not ready, so an orchestrator takes the instance out of rotation instead of leaving it answering with errors | |
| L5 | Send the same `Idempotency-Key` twice with the **same** body | Second returns the **recorded response**; nothing saved twice | |
| L6 | Same key with a **different** body | **Refused**, not served. A key reused for different content is not a retry, and replaying the first answer would silently discard the second request | |
| L7 | `POST /checkout` twice with one key on a flaky connection | One order. On a mobile network this is a certainty, not a risk | |
| L8 | Two clients buy the **last unit** simultaneously | One succeeds, one is refused. Rows are locked before decrementing | |
| L9 | Queue 4 catalogue imports as one vendor | The 4th is refused (`max-queued-jobs-per-vendor=3`). A seller queueing twenty has made a mistake, and the second is almost always the same file again | |
| L10 | Restart the app with **no** `INVOICE_SIGNING_SECRET`, then use an invoice link issued before the restart | Dead. Fine on a laptop, **wrong behind a load balancer** — set the secret | |

---

## 14A. Suite M — The client applications

Five applications now exist under `frontend/`, with **no automated tests of
their own**, so every row here has to be done by hand. Run each against a
backend on `:8080` under the `e2e` profile.

```bash
mvn -o spring-boot:run -Dspring-boot.run.profiles=e2e
cd frontend/<app> && npm install && npm run dev
```

| # | App | Do | Expect | P/F |
|---|---|---|---|---|
| M1 | all | `npm run typecheck` (or `npm run check` for `buyer-app`) | Clean | |
| M2 | all | Sign in, then open DevTools → Application → Local Storage | **Only `buyer-app` should hold an access token.** The other four keep it in memory. This is finding `frontend/README.md` §6.2 | |
| M3 | all | Sign in, then in two tabs let both hit a 401 at once (expire the token) | **One** `/auth/refresh` request in the network tab. Two would revoke the session | |
| M4 | all | View any XOF amount (sign in as `awa.teranga@sujula.sn` in `vendor-app`) | **No decimal places** | |
| M5 | all | Force a render error (edit a screen to throw) | **Only `driver-app` shows a message.** The other four blank — finding §6.4 | |
| M6 | `admin` | Sign in as `binta.support@sujula.gm` | Every decide button **rendered and disabled**, with *"Support can read this but not decide it"* on hover. Not hidden | |
| M7 | `admin` | Open a shipment's custody chain | Timeline with evidence; a **flagged** event shown as flagged, not hidden | |
| M8 | `admin` | Approve a payout batch you assembled | Refused — and the console should show who assembled it rather than let you click into it | |
| M9 | `admin` | Start a refund, then **kill the network** mid-request and retry | ⚠ Today a **new** idempotency key is sent — finding §6.3. Check the request header in DevTools | |
| M10 | `vendor-app` | Sign in as `modou.sanneh@example.gm` (a customer) | Lands on the **application form**, not an error | |
| M11 | `vendor-app` | Open a delivered order | The custody chain **reports** SHIPPED and DELIVERED and offers **no control** for them | |
| M12 | `vendor-app` | As a two-currency seller, open Earnings | **Two figures**, never summed | |
| M13 | `vendor-app` | Android build: sign in and perform a write | Succeeds. Under the default `http` scheme the CSRF cookie is blocked — `androidScheme: 'https'` is what fixes it | |
| M14 | `vendor-app` | Native: force-quit and reopen | Still signed in (Keychain / SharedPreferences, not `localStorage`) | |
| M15 | `driver-app` | Set a PIN, then enter a wrong one five times | The stored session is **destroyed** | |
| M16 | `driver-app` | **Go offline. Record a collection and a failed attempt. Close the tab. Reopen. Come back online.** | Both upload, **in order**, each exactly once. **This is the most important manual check in this document** | |
| M17 | `driver-app` | Offline, record the same event twice by tapping twice | One event. The UI confirms from the local write, so a second tap should not be possible | |
| M18 | `driver-app` | Open a job you already delivered | **No destination block** — absent, not blank | |
| M19 | `driver-app` | Try to transfer a parcel while three other events are queued | The transfer goes on **its own endpoint**, never in the batch | |
| M20 | `pickup-app` | Open the release sheet | A **numeric keypad**, not a text input. No autocorrect, no autofill, no history | |
| M21 | `pickup-app` | Enter a code with no collector name | Submit disabled. And a name with no code: also disabled | |
| M22 | `pickup-app` | Look anywhere for a collection code | **Nowhere.** Not on the shelf, not on the parcel, not after `resend-code` — only a masked hint at where it went | |
| M23 | `pickup-app` | Sign in as Isatou and open counter **1097** | The closed state, rendered as a state rather than an error | |
| M24 | `buyer-app` | Load it fresh | It asks **where the parcel is going** before anything else — and never phrases it as "your address" | |
| M25 | `buyer-app` | Change the destination only | Ranking moves, **currency does not**. Then change country only: currency moves, ranking does not | |
| M26 | `buyer-app` | Set a product name in the seed to `<img src=x onerror=alert(1)>` and view it | **Rendered as text.** If it executes, `esc()` was missed — finding §6.2/§8.2 | |
| M27 | `buyer-app` | At checkout, kill the network after tapping Place order, then retry | ⚠ Today a **new** idempotency key is sent, so the server places a **second order** — finding §8.3. **The highest-consequence client bug** | |
| M28 | `buyer-app` | Open `#/track/K7MPQ4RTVX2ND9YH` with no account | The parcel page, with **fixed phrases** and no driver free text | |
| M29 | `buyer-app` | Throttle to slow 3G and load the home screen | First paint is markup, one stylesheet and a few kilobytes. This is the app's entire reason for having no build step | |

---

## 15. What the automated suites already prove

Do not repeat these by hand.

```bash
mvn -o test -Djava.version=21     # 1,233 tests, 90 classes
cd e2e && ./run.sh                # 432 operations against a live server
```

**Verified at the time of writing: 1,233 tests, 0 failures, 0 errors, 0 skipped.**

| Layer | Covers |
|---|---|
| `SujulaApplicationTests` | Boots the whole application: Spring Data parses all 332 `@Query` declarations and Hibernate resolves every association. **The cheapest test in the suite and the one that finds the most** |
| Service tests | Custody rules, money ledger, FX snapshots, C1 separation, returns/disputes, pickup, fulfilment, moderation, promotions, inventory |
| `*SecurityTest` / `*RoutingTest` | **Who may reach which URL** — asserted against a booted application with the real filter chain, not read off the config |
| `e2e/` | Every endpoint, called by every kind of person who can call it, against the seed |

**The five client applications have no automated tests at all**, so the whole of
Suite M is manual. See
[`frontend/README.md` §6.1](frontend/README.md#61-no-tests-in-any-of-the-five-applications)
for the six test files that would cover most of that risk.

**Gaps the backend suites do not cover** (so test these by hand — `CODE-REVIEW.md` §7):

- The legacy `/api/cart` surface and `CartOwner`, which is the single place that
  decides whose cart a request touches.
- `/api/categories` and product Q&A.
- The **scheduling and retry wrappers** around the background workers. Their
  processors are tested; the workers are not.
- `MockPaymentGateway`'s refusal to start under `prod`.
- The `/api/users` authorization defect (**K1, K2**) — no test file exists for
  that controller at all.

---

## 16. Sign-off

| Suite | Rows | Run by | Date | Pass | Fail | Notes |
|---|---|---|---|---|---|---|
| A — Identity & sessions | 25 | | | | | |
| B — C1 two locations | 16 | | | | | |
| C — C2 money & FX | 21 | | | | | |
| D — C3 multi-vendor | 13 | | | | | |
| E — C4 custody chain | 20 | | | | | |
| F — C5 recipient | 12 | | | | | |
| G — Vendor | 35 | | | | | |
| H — Fulfilment | 8 | | | | | |
| I — Pickup | 9 | | | | | |
| J — Admin | 16 | | | | | |
| K — Security | 26 | | | | | |
| L — Resilience | 10 | | | | | |
| M — Client applications | 29 | | | | | |
| **Total** | **240** | | | | | |

**Environment tested against:**

- [ ] `e2e` profile (H2, in-memory) — behaviour
- [ ] All five clients against that backend (Suite M)
- [ ] `dev` or staging against **real MySQL** — schema. **Required before release**
- [ ] Behind the real reverse proxy, over HTTPS

**Release gate — all of these must be true:**

- [ ] **K1 and K2 pass** (`CODE-REVIEW.md` §2.1 is fixed)
- [ ] Every row in Suite K passes
- [ ] Every **refusal** row across all suites refuses
- [ ] The `SECURITY.md` §9 deployment checklist is complete
- [ ] **M16 and M27 pass** — the driver outbox drains correctly, and a retried
      checkout does not place a second order
- [ ] `LIMITATIONS.md` has been read by whoever is signing off, and every ⚠ item
      in it is either fixed or knowingly accepted in writing

**Signed:** ______________________  **Date:** ____________
