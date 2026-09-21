# Recipient Page — Client Specification

> **The client for somebody who has no account, no app and no email.**
>
> Six operations. It is the smallest client in the system and, measured against
> what this marketplace exists to do, the one it can least afford to get wrong.
>
> Read [`README.md`](README.md) first.

---

## 1. Who this is for

Aminata's sister in Serrekunda. She did not sign up for anything. Somebody in
Madrid bought her a phone and she has a message with a link and six digits in it.

She is also **the only person who knows whether she will be at home on
Thursday** — which is why she can change the delivery without an account. The
buyer is a continent away and asleep. Requiring a sign-in here would hand the
decision back to whoever has an account, which is exactly the wrong person.

**Design constraints that follow, and they are hard ones:**

- A phone, probably not a recent one, probably on a slow connection.
- No app to install. No account to make. **No email to click a link in.**
- The page may be opened by somebody the message was forwarded to.

---

## 2. Two pages, two audiences

| | Endpoint | For |
|---|---|---|
| **Public tracking** | `GET /track/{trackingCode}` | Anyone holding the code, including a stranger it was forwarded to |
| **Recipient's parcel** | `GET /parcels/{trackingCode}` | The recipient — a first name, a town, and the actions |

Both open. No token, no account, no sign-in.

### 2.1 The public page is built to be worth nothing to a stranger

`GET /track/{code}` carries a city, parcel counts, and **fixed phrases**.

> Where the internal record says *"Assigned to Ebrima Bojang"*, the public page
> says *"A driver has been assigned."*
>
> **That substitution is the only thing standing between a driver's notes and
> somebody who was forwarded the SMS.**

No name, no street, no phone number, no price, no order number, and never the
driver's free text.

**Render exactly what the server sends.** Do not enrich it, do not join it with
anything, do not add a map pin the server did not give you. The restraint is the
feature.

### 2.2 The recipient's page

`GET /parcels/{trackingCode}` returns the parcel, where it has got to, the
counters nearby, and — importantly — **which actions are currently available**:

```jsonc
{ "parcel": { … },
  "steps": [ { "stage": "…", "description": "…", "at": "…" } ],
  "actions": { "choosePickupPoint": true, "reschedule": true,
               "authoriseSafeDrop": false },
  "counters": [ { "id": 1096, "name": "…", "addressStreet": "…", "city": "…" } ] }
```

**Render the buttons from `actions`.** Do not compute availability yourself from
the status — the server already knows, and a button that refuses is worse than a
button that is not there.

---

## 3. Two credentials, and the difference matters

| Credential | Opens | Is |
|---|---|---|
| **The tracking code** in the URL | Reading | 16 characters from a 30-symbol alphabet. Possession is the only claim |
| **A six-digit code** in the body | The three changes | Requested separately, single-use |

So the flow is: she opens the link and can *see*. To *change* anything she asks
for the six digits.

```http
POST /parcels/{trackingCode}/request-code
```

```http
POST /parcels/{trackingCode}/reschedule
{ "code": "540913", "requestedDate": "2026-09-24", "window": "…" }

POST /parcels/{trackingCode}/choose-pickup-point
{ "code": "540913", "pickupPointId": 1096 }

POST /parcels/{trackingCode}/authorise-safe-drop
{ "code": "540913", "instruction": "Leave with the neighbour at number 14" }
```

> ⚠ **Where the code actually goes today.** There is no SMS sender, so it is
> **emailed to the buyer**, who relays the digits. Word the page for that —
> *"we have asked the person who sent this parcel to give you the code"* — and
> be ready to change it when SMS arrives
> ([`../LIMITATIONS.md` §2.2](../LIMITATIONS.md)).

---

## 4. Designing for the actual conditions

This is the section that matters more than the endpoint list.

**Build it as a plain page.** Server-rendered or a very small bundle. No
framework that costs 300 KB before it draws anything. She is on a phone on a
mobile connection and she wants to know when her parcel is coming.

**Six digits deserve the whole screen.**

```html
<input type="text" inputmode="numeric" pattern="[0-9]*"
       autocomplete="one-time-code" maxlength="6">
```

Large, numeric keypad, no autocorrect, no autocapitalise. `one-time-code` lets
the phone offer it from the message.

**The tracking code is long and may be typed.** 16 characters from a 30-symbol
alphabet — the alphabet excludes confusable glyphs, so accept input
case-insensitively and strip spaces and hyphens before sending.

**Three languages, and one of them may be right-to-left.** Fetch `GET /locales`
and honour `rtl`. Default from the browser, and offer a switcher that does not
require reading the current language to find.

**Dates in her timezone, not the buyer's.** The parcel is arriving where she is.

**A reschedule must be a date she can actually pick.** The server refuses a date
in the past; do not let the UI offer one.

**Offline is normal.** If a request fails, say *"that did not send — try again"*
and keep what she typed. Never lose a six-digit code to a dropped connection.

---

## 5. Safe drop needs a sentence, not a checkbox

```http
POST /parcels/{trackingCode}/authorise-safe-drop
{ "code": "…", "instruction": "Leave with the neighbour at number 14" }
```

*"Leave it somewhere"* is not an instruction a driver can follow. *"Leave with
the neighbour at number 14"* is. Make the free-text field the main part of the
form, with examples, and do not offer a bare "yes, leave it" option.

---

## 6. Rules this client must not break

| Never | Because |
|---|---|
| Require an account, an app or an email | It fails the case this marketplace exists for |
| Show the public page anything beyond what the server sends | It may have been forwarded to a stranger |
| Render a driver's free text on the public page | The server substitutes fixed phrases for exactly this reason |
| Compute which actions are available | The server sends `actions`; a refusing button is worse than an absent one |
| Treat a 404 as "this parcel does not exist" | It also means the code is wrong or spent |
| Lose a typed six-digit code on a network failure | She may not be able to get another quickly |
| Make it heavy | A slow page on a slow phone is a page nobody reads |
| Assume the buyer's timezone or language | The parcel is arriving where **she** is |
| Log or forward the tracking code | Possession of it is the only credential |

---

## 7. Build order

1. `GET /parcels/{code}` — the page, rendered from the server's own fields.
2. `request-code`, with the six-digit input built properly.
3. Reschedule.
4. Choose a pickup point, from the counters the server returned.
5. Safe drop, with a real instruction field.
6. `GET /track/{code}` — the stranger-safe page, rendered verbatim.
7. Locales and `rtl`.
8. Offline behaviour and input preservation.

---

## 8. A note on why this page is small

Six endpoints is not a small feature. It is the point of the platform.

Everything else in this system — the custody chain, the escrow, the two
currencies, the ownership checks — exists so that this page can be opened by
somebody with no account and still be trusted to move a parcel. If it is slow,
confusing, or asks her to sign in, none of the rest of it matters.
