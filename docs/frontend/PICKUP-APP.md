# `frontend/pickup-app` — Counter Application

> **The tablet on a shop counter** that takes parcels in from drivers and hands
> them to the people they are for. An installable PWA, and *"the one that is
> used in front of a customer."*
>
> This document covers **architecture and code quality**. For what it does, read
> [`frontend/pickup-app/README.md`](../../frontend/pickup-app/README.md). Read
> [`README.md`](README.md) first for the shared client contract.

| | |
|---|---|
| **Stack** | React · TypeScript · Vite · React Router · TanStack Query |
| **Size** | ~4,700 lines, 32 files — the smallest React client |
| **Port** | 5176 |
| **Form** | Installable PWA |
| **Users** | Counter operators, on a tablet, with somebody waiting |

---

## 1. Module map

```
src/
├── App.tsx                  9 routes
├── api/     client.ts · endpoints.ts · types.ts
├── auth/    Gate.tsx · session.tsx
├── counter/CounterProvider.tsx     the current counter as context
├── lib/                     formatting, helpers
├── styles/
├── components/
└── screens/
    ├── Counter.tsx          the shelf — everything held
    ├── ParcelSheet.tsx      one parcel
    ├── ReleaseSheet.tsx     handing it over          ← §3
    ├── Incoming.tsx         what a driver is bringing
    ├── Overdue.tsx          nobody came
    ├── Earnings.tsx  Settings.tsx  SignIn.tsx  Apply.tsx
```

**Routes:** `/sign-in` `/apply` `/` `/incoming` `/overdue` `/earnings`
`/settings`.

`CounterProvider` resolves the operator's counter and exposes the derived
predicates, each mirroring a server rule rather than inventing one:

```ts
/** Approved and open: the server refuses custody actions otherwise. */
/** Why it cannot, in words an operator can act on. */
```

The second is the pattern that makes this app good: it does not carry a boolean,
it carries **the sentence to show**.

---

## 2. The one rule this whole app is shaped by

> **The operator is never shown a collection code.** Not on the shelf, not on
> the parcel, not after sending one.

And the reason is the platform's reason, not a policy:

> *"The person collecting may have no account, no app and no email — she was
> told six digits by the buyer, and reading them out is the whole of the proof
> that she is the right person. An operator who could read the code could hand
> the parcel to whoever happened to be standing there, and nobody downstream
> could tell the difference."*

`resend-code` puts it in the buyer's inbox and answers this screen with a
**masked hint at where it went** — enough for the operator to say *"we have sent
it to the address ending in …"* and nothing more.

This is C5 enforced in the client that has the most opportunity to break it, for
the right reason.

---

## 3. `ReleaseSheet` — the screen used a hundred times a day

> *"The end of the custody chain, and the thing that releases the seller's
> money."*

**Two checks, and the server requires both:** the code proves they were told it
by whoever sent the parcel; the name is what the operator writes down after
looking at the person in front of them. *"A code alone would let anybody who
overheard it collect; a name alone, anybody who read the label."*

Three implementation decisions, each worth keeping:

### 3.1 Nothing compares a code locally

> *"There is no reveal, no hint, and nothing here that compares the entered
> digits locally: the server decides, **because a client that could tell a right
> code from a wrong one is a client that can be asked until it says yes.**"*

That sentence is the whole argument against client-side credential validation,
in one line.

### 3.2 The keypad is not a text input, and must not become one

> *"An operator has a parcel in one hand and somebody reading numbers at them. A
> mobile keyboard covers two thirds of a tablet with letters, none of them
> wanted, and brings autocorrect, autofill and history with it — **none of which
> should ever touch somebody else's collection code.** The keys are digits in
> the layout every phone uses, so it works for someone who cannot read."*

Three separate correct reasons — ergonomics, security, and literacy — for one
component choice.

### 3.3 The name is recorded whether or not it matches

A brother collecting for his sister is the normal case in this market, not an
exception to route around. The name is written down and **the server says
whether it matched**, rather than the client refusing.

---

## 4. The rest follows from the same idea

| | |
|---|---|
| **Taking a parcel in** | Verifies the **driver's** code — the receiving party checking the giving party, *"which is the only thing a code can prove. Same shape as every other link in the chain, from the other side."* |
| **Turning one away** | **Does not move custody.** The driver still has it, so the server records a failed attempt rather than the end of the chain — *"and the screen says so: an operator who thinks they have handed the problem on will stop watching it."* |
| **A parcel cannot go back before its deadline** | *"Somebody may be travelling to collect it, and that is not the counter's decision to take."* |
| **Refusals name which of four reasons** | Closed, suspended, switched off, or full — and two of the four are things the operator can fix themselves |
| **Earnings per currency, never summed** | Same rule as every other client |

---

## 5. Custody events carry a `clientEventId`

The server dedupes on it, so a tablet that submits, loses its connection before
the reply, and submits again records the release **once** — which matters
because the second attempt would otherwise be refused as a spent code, leaving
an operator holding a parcel the system thinks was never handed over.

---

## 6. Code review

### Strengths

1. **The code-invisibility rule is total**, and reasoned from the recipient's
   position rather than from policy.
2. **`ReleaseSheet` is the best-argued single screen in the front end.** Three
   independent correct reasons for the keypad; one sentence that disposes of
   client-side credential validation.
3. **`CounterProvider` carries the refusal sentence**, not just a boolean.
4. **Reject does not move custody**, and the UI says so — a subtle correctness
   point most implementations would get wrong.
5. **Zero `any`, zero `console.*`, zero `innerHTML`** in 4,700 lines.
6. **The smallest React client**, and it does not feel thin. Scope discipline.

### Findings

#### 6.1 The idempotency key defaults to per-attempt — **Medium**

```ts
apply: (input: ApplyInput, idempotencyKey?: string) =>
  api.post(…, { idempotent: idempotencyKey ?? true }),
```

Same shape as `vendor-app`: `true` mints one at send time, so a retry after a
timeout carries a **different** key. The optional parameter means every call
site has to remember.

**Mitigated here** more than elsewhere, because custody actions also carry a
`clientEventId` the server dedupes on — so the *custody* path is safe even when
the idempotency key is not. The exposure is on the non-custody writes: `apply`,
`resend-code`, and settings changes.

**Fix.** Make the parameter required, as `driver-app` does. Cross-referenced as
[`README.md` §6.3](README.md#63-the-idempotency-key-is-minted-per-attempt-in-three-of-five-apps).

#### 6.2 No error boundary — **Medium**

A render-time exception blanks the tablet **while a customer is standing at the
counter**. Of the four apps missing one, this is the worst place for it: the
operator cannot explain what happened and cannot complete a handover the
customer has travelled for.

**Fix.** Copy `driver-app/src/ErrorBoundary.tsx`. An hour, and it should be the
next thing done to this app.

#### 6.3 No tests — **Medium-High**

Zero test files. The three worth writing:

1. `ReleaseSheet` — the submit button is disabled until **both** the code and
   the collector name are present. This is the guarantee the whole app exists
   for, and nothing currently checks it.
2. `CounterProvider` — each counter standing produces the right predicate and
   the right refusal sentence. The seed gives you both cases for free: Isatou
   runs one open counter and one closed for a funeral.
3. The keypad component never renders a native text input, whatever props it is
   given. A regression here silently reintroduces autocorrect and autofill onto
   somebody else's collection code.

The third is unusual as a test and exactly right for this app: it encodes a
security decision that would otherwise be undone by a well-meaning refactor.

#### 6.4 No offline handling — **Low**

Unlike `driver-app`, there is no outbox. A counter has power and usually wifi,
so this is a defensible scope decision — but a dropped connection mid-release
leaves the operator unsure whether the handover was recorded, and the `clientEventId`
dedup means retrying is **safe**. The app should say so:

> *"That did not send. Try again — it will not hand the parcel over twice."*

A sentence, not a feature.

---

## 7. Running it

```bash
cd frontend/pickup-app
npm install
npm run dev          # http://localhost:5176

cd ../.. && mvn spring-boot:run -Dspring-boot.run.profiles=e2e
```

Sign in as `isatou.pickup@sujula.gm`, password `Sujula123!`.

She runs **two** counters in the seed, and the second is the one to open:

| | |
|---|---|
| **1096 Westfield Junction** | Open, holding parcel 1903 on shelf A-118 |
| **1097 Latrikunda Sabiji** | **Closed for a family funeral** — *"it is what every closed state on this surface looks like"* |

Worth trying, because each is a refusal the UI has to render well:

```
POST /pickup/points/1096/parcels/1903/return    → refused, deadline not passed
POST /pickup/points/1097/parcels/1903/accept    → refused, and says WHICH of four reasons
POST /pickup/points/1096/parcels/1903/release   → needs the code AND a name
```
