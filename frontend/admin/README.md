# Sujula — administration console

The platform's own surface: every endpoint under `/admin`, plus the sign-in and
account screens the people who work it need.

One of five clients. This one is the desktop console — it is used all day by
people reading queues, so it is dense, keyboard-reachable, and deliberately
plain.

## Running it

The backend has **no CORS configuration**, and its CSRF protection is a
double-submit cookie (`XSRF-TOKEN`) that only a same-origin page can read. So
this app is served same-origin, in development through Vite's proxy and in
production behind whatever puts the two on one host.

```
cp .env.example .env          # the defaults are right for local work
npm install
npm run dev                   # http://localhost:5173
```

and a backend beside it:

```
cd ../..
mvn spring-boot:run -Dspring-boot.run.profiles=e2e
```

That profile boots against H2 in memory and loads
`src/main/resources/db/seed/dev-seed.sql`, so there is a world to look at
immediately. Sign in as `fatou.admin@sujula.gm` (an administrator) or
`binta.support@sujula.gm` (support, read-only). Every seeded password is
`Sujula123!`.

```
npm run build       # tsc + vite build into dist/
npm run typecheck
```

## What it covers

All 95 endpoints under `/admin`, grouped the way the server groups them:

| Section | Screens |
|---|---|
| Dispatch | orders, order detail, place-on-behalf, dispatch board, unassigned parcels, custody chain |
| Money | payments, refunds, ledger, reconciliation, balances, payout runs, FX rates and spread, revenue, exports |
| Moderation | stores, KYC queue, listings, reported reviews, policy cases |
| Logistics | drivers, pickup points, zones, rate cards |
| Platform | disputes, callbacks, announcements, feature flags, background jobs, audit log |
| Accounts | search, detail, sanctions, roles, sessions, impersonation |

Plus `/auth` and `/me` for sign-in, the operator's own second factor, and their
devices; `/currencies` and `/countries` for reference data; and the three
payment operations on the older `/api/admin` surface that `/admin/payments` does
not carry — confirming a bank transfer, cancelling a payment, marking one
failed.

## How the five rules show up here

**C1 — two independent locations.** Nothing in this console derives one from the
other. An order shows *Who paid* and *Where it goes* as two separate panels; the
payments table has a `paid from → goes to` column with both labelled; the order
filter is `destinationCountry` and is captioned "Where the goods go. Not where
the buyer paid from." The zone and rate-card screens say in as many words that
they answer a question about the delivery address. `CountrySelect` takes a
`purpose` of `ship` or `buy` so a caller has to say which it means.

**C2 — two currencies, snapshotted rates.** `money/currency.ts` contains no
arithmetic at all: every total on screen was computed by the server, and a sum
done here would be a second opinion about somebody's money. What the client does
own is scale — `GET /currencies` publishes each currency's `minorUnits`, so XOF
renders as `F CFA 14,500` and never `14,500.00`. The `<FxPair>` component always
carries the rate and the moment it was taken beside the two amounts, and when a
record does not carry the rate it says so rather than implying there was no
conversion. Per-currency figures are listed, never added: `<MoneyList>` exists
precisely so nothing offers a cross-currency total.

**C3 — one payment, many vendors.** Refunds are per sub-order, and the refund
dialog says so; there is no control anywhere that refunds "the order". The
cancel dialog asks which vendor's slice, defaulting to all but making the choice
explicit. Payout runs are per currency because a vendor is paid in their own.

**C4 — custody is a chain.** The chain screen renders each link as the event that
produced it, with its evidence: whether a code was presented, the photograph, the
signature, the distance from where it should have happened. Status is shown as a
consequence. The one endpoint that writes a link without evidence —
`override-handoff` — is styled as the dangerous thing it is, requires an
attesting person by name, and its events stay marked `Overridden` for good.

**C5 — the recipient may not have an account.** The callback queue and the
one-person notification form both exist for somebody reachable only by
telephone, and say so. The override-handoff dialog explicitly refuses "they
could not log in" as a reason, because the SMS code already works for someone
with a phone and nothing else.

## Security

- **Support versus administrator.** `StaffCaller.decider` on the server is
  mirrored by `canDecide` here. Support sees every queue with the decision
  buttons rendered and disabled, each carrying the same one-line explanation —
  rendered rather than hidden, so an agent knows what to escalate. This is a
  rendering rule; every endpoint still enforces its own `@PreAuthorize`.
  The audit log is the one *read* support cannot make, so it is hidden from the
  sidebar and explains itself if reached by URL.
- **Step-up.** The six operations that re-ask for the administrator's password —
  refund above the server's threshold, preparing and releasing a payout run,
  deciding a dispute, clearing a second factor, recording an unproven handover —
  collect it through one shared component. The refund form mirrors the server's
  threshold so a small refund does not train people to type their password
  without reading; `api/policy.ts` holds that mirror and is never allowed to
  block a request, because the server is the authority.
- **Tokens.** The access token lives in a module variable and is never written to
  storage. The refresh token is in `sessionStorage`, so a reload keeps you signed
  in and closing the tab does not. The console signs itself out after idle time,
  and signing out in one tab signs out the others.
- **CSRF.** Every mutating request echoes the `XSRF-TOKEN` cookie as
  `X-XSRF-TOKEN`. Without it the server answers 403 on every `/admin` write.
- **Idempotency.** Writes the server guards send a fresh `Idempotency-Key` per
  attempt, so a retry after a timeout replays the first answer rather than
  releasing a second payout run.
- **Refreshing.** The client refreshes on 401 — the server's answer to a caller
  who has not identified themselves, including one whose access token has aged
  out. It also refreshes on a 403 received while holding no live access token,
  which is not dead weight: before `FilterChainRefusals` split the two, this
  chain answered 403 to both, and a client refreshing only on 401 signed its
  user out on every token expiry and every reload. A 403 received *while*
  holding a live token is passed through untouched, because that is a genuine
  permission refusal — support reaching a decision endpoint. Refreshes are
  single-flight: refresh tokens rotate, so two concurrent exchanges would look
  like a replay and kill the session.

## Layout

```
src/
  api/        client (auth, CSRF, refresh, errors), one function per endpoint,
              request and response types, enums, mirrored server policy
  auth/       token store, session lifecycle, sign-in
  components/ the shared kit — tables, filters, modals, money, custody, step-up
  money/      currency formatting, and nothing else
  pages/      one folder per section of the API
```

`api/endpoints.ts` is the whole surface in one file, grouped to match the six
`Admin*Controller`s. Nothing above that layer builds a URL or picks a verb.
