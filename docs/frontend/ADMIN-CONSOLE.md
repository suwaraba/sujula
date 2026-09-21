# `frontend/admin` — Administration Console

> **The platform's own surface.** All 95 endpoints under `/admin`, plus the
> sign-in and account screens the people who work it need.
>
> This document covers **architecture and code quality**. For what each screen
> does, read [`frontend/admin/README.md`](../../frontend/admin/README.md).
> For the shared client contract — auth, CSRF, refresh, money — read
> [`README.md`](README.md) first.

| | |
|---|---|
| **Stack** | React 18 · TypeScript · Vite · React Router · TanStack Query |
| **Size** | ~16,300 lines, 64 files — the largest client |
| **Port** | 5173 |
| **Users** | `ADMIN` and `SUPPORT`, on a desktop, all day |
| **Design posture** | *"dense, keyboard-reachable, and deliberately plain"* |

---

## 1. Module map

```
src/
├── App.tsx                  37 routes under one authenticated layout
├── main.tsx
├── hooks.ts
├── api/
│   ├── client.ts            ~310 lines: token · CSRF · refresh · error model
│   ├── endpoints.ts         one typed function per endpoint, grouped as the
│   │                        six Admin*Controllers are
│   ├── types.ts  enums.ts   response shapes; enums mirrored from the server
│   ├── requests.ts          request bodies
│   └── policy.ts            server constants the forms need to know
├── auth/
│   ├── tokenStore.ts        access token in memory, refresh in sessionStorage
│   ├── AuthContext.tsx      lifecycle, impersonation state, cross-tab logout
│   ├── guards.tsx           RequireStaff
│   └── LoginPage.tsx        password → MFA → recovery code
├── money/currency.ts        formatting only, never arithmetic
├── components/              DataTable · Decide · StepUp · Money · Modal ·
│                            ActionModal · Pagination · Time · Toast · forms
└── pages/                   35 screens in eight folders, mirroring the API
    ├── orders/  shipments/  money/  disputes/
    └── moderation/  users/  logistics/  platform/
```

**Routes (37)**, matching the server's grouping exactly:

```
dashboard
orders · orders/:orderId
shipments · shipments/unassigned · shipments/:id/custody-chain
payments · payments/:id · ledger · reconciliation · balances
payouts · payouts/:batchId · fx · reports/revenue · reports/exports
disputes · disputes/:id · callbacks
stores · kyc · moderation/products · moderation/reviews · moderation/cases
users · users/:userId
drivers · pickup-points · zones · zones/:zoneId · rate-cards
announcements · feature-flags · jobs · audit-log
account
```

---

## 2. The three ideas worth copying

### 2.1 `endpoints.ts` — the API as a list read twice

> *"Grouped the way the server groups them, so the six `Admin*Controller`s and
> the six objects below are the same list read twice. Nothing above this layer
> builds a URL or picks a verb."*

This is the single reason a 16,000-line console is auditable. Reviewing whether
the client calls the API correctly means reading one file against six
controllers — not grepping 35 screens for string literals.

`frontend/README.md` names this module as the shape the other four clients
should copy, and they do.

### 2.2 `tokenStore.ts` — two tokens, two places, one stated reason

```ts
/**
 * The access token lives in a module variable and nowhere else. It is never
 * written to `localStorage`, because this surface can refund a payment,
 * impersonate a customer and reset somebody's second factor, and a token that
 * survives in storage is a token a single injected script can take away with
 * it and use from anywhere.
 *
 * The refresh token is in `sessionStorage`, which is the compromise that makes
 * the console usable: a reload keeps you signed in, closing the tab does not.
 */
```

Three details beyond that:

- **Cross-tab sign-out.** A `sujula.admin.logout` key is written to
  `localStorage` on sign-out specifically because *"the storage event only
  crosses tabs for `localStorage`"* — `sessionStorage` would not propagate. An
  admin signing out of one tab signs out of all of them.
- **Impersonation is tracked in the store**, so every screen can know the
  session was opened as somebody else.
- **Nothing here talks to the network.** `client.ts` owns that, `AuthContext`
  owns the lifecycle, and this is only the box the values sit in. A clean
  separation that most token stores do not keep.

### 2.3 `policy.ts` — mirroring server rules without becoming a second authority

```ts
/**
 * Everything here mirrors a constant or a check in the backend, and every one
 * of them is named with where it lives. A mirror drifts, so nothing in this
 * file is allowed to *block* a request: the server decides, and these only
 * decide what the form shows before it asks. When a mirror is out of date the
 * worst outcome is a box that appears a moment late, never a refusal the
 * console invented on its own.
 */
export const REFUND_STEP_UP_ABOVE = 5000;   // AdminMoneyServiceImpl
```

This is the correct relationship between a client-side rule and a server-side
one, and it is almost never written down. The `refundNeedsStepUp` helper goes
further: when it **cannot tell** — a partial refund entered in the vendor's own
currency, where knowing the display amount would mean converting it in the
client, *"which is exactly what C2 forbids"* — it returns **true**, because
*"offering the box and not needing it costs a moment, and needing it and not
offering it costs the whole form."*

---

## 3. The support/admin split, rendered rather than discovered

The server draws the line at `StaffCaller.decider`: support reads every queue on
this surface and decides nothing on it. `Decide.tsx` renders that line.

```tsx
export const SUPPORT_CANNOT_DECIDE =
  'Support can read this but not decide it. Ask an administrator.';
```

`DecideButton` is disabled for a support principal, with that sentence as its
`title`. The reasoning is explicit and correct:

> *"Rendering the button and disabling it — rather than hiding it — is
> deliberate. An agent who cannot see the action does not know it exists and
> asks nobody; an agent who can see it greyed out knows exactly what to
> escalate."*

One component, one sentence, used everywhere. This is exactly the failure mode
the server guards against — *"the annotation that gets forgotten is on the one
endpoint that mattered"* — solved the same way on the client: by making the
correct thing the only convenient thing.

---

## 4. Step-up

`StepUp.tsx` re-asks for the administrator's own credentials on **six
operations**: a refund, preparing a payout run, releasing one, deciding a
dispute, clearing somebody's second factor, and recording a handover nobody
could prove.

> *"They are the ones that move money or that the next person to look cannot
> undo."*

Paired with `ApiError.isCredentialChallenge` (§3.5 of [README.md](README.md)),
which keeps a wrong password on a step-up from being mistaken for a dead session
and triggering a refresh.

---

## 5. Money, and the rule the console keeps

`money/currency.ts` formats and does nothing else. No totals, no conversions, no
sums across currencies — `GET /admin/balances` returns every seller's balances
in every currency they hold, and the console renders them as separate figures
because a combined one would require applying a rate after the fact to amounts
each frozen at their own.

Scale comes from `GET /currencies`, with a fallback chain that is careful rather
than convenient:

```
catalogue.minorUnits  →  Intl's own table (right for XOF and JPY too)  →  2
```

> *"A wrong scale is visible in the number, so this never silently guesses
> without a source."*

---

## 6. The screens that carry the most weight

| Screen | Why |
|---|---|
| `shipments/CustodyChainPage` | What somebody opens when a parcel is disputed. Renders the chain **with its evidence** — who recorded each event, when it *occurred*, the position and whether it corroborated, the proof presented — and shows flagged events **as flagged** rather than hiding them |
| `money/ReconciliationPage` | Escrow against payments against payouts. The screen that says whether the platform's books balance |
| `money/PayoutsPage` + `BatchDetailPage` | The four-eyes rule: approving your own batch is refused by the server, and the console shows who assembled it rather than letting an admin click into a refusal |
| `disputes/DisputeDetailPage` | Both currencies and both clocks — an agent working a Madrid–Serrekunda dispute needs to know that "yesterday" means different days to the two parties |
| `platform/AuditLogPage` | Append-only, and the screen says so |
| `platform/JobsPage` | Job history **including passes that found nothing** — a gap is how you notice a worker stopped |
| `orders/PlaceOrderOnBehalf` | A real workflow in this market: an order placed for somebody who telephoned |

---

## 7. Code review

### Strengths

1. **Zero `any` in 16,300 lines.** Response types written out, enums mirrored,
   `endpoints.ts` typed end to end.
2. **Zero `console.*`** and zero `innerHTML`/`dangerouslySetInnerHTML`.
3. **The token store is the strictest of the five clients**, and correctly so
   given what this surface can do.
4. **`policy.ts` refuses to be an authority.** Rare and correct.
5. **`Decide.tsx` makes the permission model visible** instead of letting users
   discover it through 403s.
6. **No global store.** TanStack Query for server state, one context for auth.
   For an application that is almost entirely server data, right.

### Findings

#### 7.1 The idempotency key is minted per attempt — **High**

```ts
function idem() {
  return { idempotencyKey: newIdempotencyKey() };
}
```

Called inside each endpoint function, so **every attempt gets a new key**. The
comment above it claims the opposite:

> *"Writes that the server idempotency-guards send a fresh `Idempotency-Key` per
> attempt. That is what stops a retry after a timeout from releasing a second
> payout batch or placing a second order."*

A fresh key per attempt is precisely what does **not** stop that. `driver-app`
states the rule correctly — *"a key generated at send time is a new key every
retry, which is the same as having none"* — and implements it by passing the key
in as a parameter.

**Affected here:** `POST /admin/payouts/batches`,
`…/batches/{id}/approve`, `…/items/{id}/retry`,
`POST /admin/payments/{id}/refund`, `POST /admin/orders`,
`POST /admin/disputes/{id}/resolve`. Every one of them moves money or is
irreversible.

**Mitigations that exist and do not cover it:** buttons disable while busy, and
TanStack Query mutations are `retry: false`. Neither helps when a request times
out or its response is lost and the operator tries again.

**Fix.** Mint the key where the operator acts, hold it in the modal's state for
that intention, pass it to every attempt:

```tsx
const [idemKey] = useState(() => newIdempotencyKey());   // once per modal open
// …
mutation.mutate({ …, idempotencyKey: idemKey });
```

Then change `idem()` to take a key rather than make one, so the type system
requires a caller to have one. Cross-referenced as
[`README.md` §6.3](README.md#63-the-idempotency-key-is-minted-per-attempt-in-three-of-five-apps).

#### 7.2 No error boundary — **Medium**

A render-time exception blanks the console with no message and no way back. This
is a surface used all day by people working queues; a white screen mid-dispute
is an incident.

**Fix.** One `<ErrorBoundary>` around the routed outlet in `App.tsx`, showing
the error, a reload and a link to the dashboard. An hour.

#### 7.3 No tests — **High**

Zero test files. `npm run typecheck` is real coverage of shape, but the
behaviour that matters is untested: refresh single-flight under concurrency,
CSRF priming when the cookie is absent, `Decide` gating for a SUPPORT principal,
and money scale per currency.

**Three tests, highest value first:**

1. Two concurrent 401s issue **one** `/auth/refresh`.
2. A SUPPORT principal renders every `DecideButton` disabled.
3. XOF formats to 0 places; an unknown code falls back to `Intl`, not to 2.

Vitest comes with Vite; this is an afternoon.

#### 7.4 `isCredentialChallenge` matches prose — **Low**

```ts
return this.status === 401 && /password|code|confirm/i.test(this.message);
```

Correct behaviour from a fragile signal: the server is free to reword that
message. The proper fix is server-side — a stable `error` code in the existing
JSON body. Until then, a comment naming the server strings it depends on would
make the coupling visible.

---

## 8. Running it

```bash
cd frontend/admin
cp .env.example .env          # defaults are right for local work
npm install
npm run dev                   # http://localhost:5173

# and a backend beside it
cd ../.. && mvn spring-boot:run -Dspring-boot.run.profiles=e2e
```

| Sign in as | Role |
|---|---|
| `fatou.admin@sujula.gm` | ADMIN — everything |
| `binta.support@sujula.gm` | SUPPORT — **read-only**, and the right account for checking §3 |

Password `Sujula123!`. Served **same-origin** through Vite's proxy; see
[`README.md` §2](README.md#2-deployment-same-origin-no-exceptions).

```bash
npm run build       # tsc + vite build into dist/
npm run typecheck
```
