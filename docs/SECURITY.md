# Sujula — Security, Explained From Scratch

> **Who this is for.** Somebody who has to make a decision about this
> application's security and does not already know the vocabulary. A founder, a
> reviewer, a new developer, an operations person setting up a server.
>
> **How to read it.** Part I explains what security *is* in plain language, with
> no Sujula in it. Part II shows what this application actually does, point by
> point. Part III is the practical part: the settings you must get right, and
> how to change things safely. Part IV is the honest list of what is missing.
>
> **If you read nothing else,** read [§9, the deployment checklist](#9-the-deployment-checklist),
> and then [§2.1 of `CODE-REVIEW.md`](CODE-REVIEW.md#21-postauthorize-on-mutating-endpoints-authorises-after-the-write-has-committed),
> which is a real bug that needs fixing before this serves real accounts.

---

# Part I — What "security" actually means

## 1. Five questions, and nothing else

Every security mechanism in every system ever built is an answer to one of
five questions. There is no sixth. If you can hold these five in your head you
can read the rest of this document and judge it.

### 1.1 Who are you? — *authentication*

Someone arrives and claims to be Aminata. How do we know?

Usually: they know a secret only Aminata should know (a password), or they hold
something only Aminata should hold (a phone with a code on it), or both.

The common mistakes: storing the password in a form somebody who steals the
database can read; letting someone guess forever; treating "you proved it an
hour ago" as "you are proving it now".

### 1.2 What may you do? — *authorization*

We now believe this is Aminata. May she read order 1401? May she delete user
1007?

Authentication and authorization are different questions, and mixing them up is
the classic failure. *Being signed in* is not the same as *being allowed*. A
signed-in customer is still a stranger to every other customer's data.

The common mistakes: checking the role but not the *ownership* (any vendor can
open any vendor's order); checking too late (see §1.6); checking in the client,
which anybody can bypass by talking to the server directly.

### 1.3 Can somebody listen in, or change it on the way? — *confidentiality and integrity in transit*

Data travelling between a phone in Serrekunda and a server somewhere crosses
equipment nobody in this story controls. Without protection, anyone on that path
can read it and alter it.

The answer is **HTTPS/TLS**. There is no clever alternative and no situation
where "it's only a small app" makes plain HTTP acceptable. If a password ever
crosses plain HTTP, treat it as published.

### 1.4 What if somebody steals the database? — *confidentiality at rest*

Backups get copied. Laptops get lost. Hosting accounts get breached. Assume one
day somebody has a copy of your database file, and ask what they can do with it.

Passwords: nothing, if they were stored correctly (§2.2).
Bank account numbers: everything, unless they were encrypted (§2.8).

### 1.5 What happened, and who did it? — *audit*

When money moves the wrong way, "the system did it" is not an answer. You need a
record of who did what and when, that the person who did it could not edit
afterwards.

### 1.6 Can somebody make you do something you did not mean to?

This is the subtlest one and it has several faces:

- **CSRF** (cross-site request forgery). You are signed in to Sujula in one
  browser tab. In another tab you open a page that quietly tells your browser to
  `POST /orders/…/cancel` on Sujula. Your browser helpfully attaches your cookies.
  The server sees a perfectly valid, signed-in request. **§2.6.**
- **Replay.** An attacker captures one valid message — a payment confirmation, a
  refresh token — and sends it again later. **§2.5, §2.10.**
- **Ordering.** The check runs, but *after* the thing it was supposed to
  prevent. This is not theoretical here: it is finding
  [§2.1 in `CODE-REVIEW.md`](CODE-REVIEW.md#21-postauthorize-on-mutating-endpoints-authorises-after-the-write-has-committed).

## 2. Three ideas that make the rest make sense

**Defence in depth.** Never one lock. If the front door fails, the safe should
still be locked. Throughout this document you will see "this is the second lock,
not the only one" — that is the idea.

**Fail closed.** When something is not configured, refuse rather than carry on.
A webhook from an unconfigured provider is *rejected*, not trusted. If the
encryption key is missing, storing bank details is *refused* rather than saved
in the clear. A system that looks like it protects something and does not is
worse than one that admits it cannot.

**Don't confirm what you don't have to.** "Forbidden" tells the asker the thing
exists. "Not found" tells them nothing. On a platform where a row is somebody's
home address, that difference matters. This application answers **404** for
somebody else's row, on purpose, everywhere it matters.

---

# Part II — What Sujula actually does

## 2.1 Signing in, and the two kinds of key

When Aminata signs in she gets **two** different things, and they are different
on purpose.

**The access token** is like a day pass. It is a small signed string (a JWT) the
server does *not* keep a copy of. It says "this is user 1050, session 1801, good
until 10:47". Anyone holding it can act as Aminata until it expires.

Because the server keeps no copy, it cannot be torn up. So it is deliberately
**short-lived: ten minutes** (`sujula.auth.jwt.access-token-ttl`). Ten minutes
is the *blast radius* of a stolen one.

**The refresh token** is like the key to get a new day pass. It is a long random
string with no meaning. The server stores only a **hash** of it — a one-way
scramble — so somebody reading the database cannot use what they find. It lasts
**30 days** and can be cancelled instantly.

> **A note on the JWT, because people get this wrong.** A JWT is *signed*, not
> *encrypted*. Anyone holding it can read every field inside. Signing means they
> cannot *change* those fields without the server noticing. So the token here
> carries an id, a session id and an expiry — and nothing sensitive. Never add
> anything to it you would not print on a postcard.

**Rotation, and what happens if a token is stolen.** Every time a refresh token
is used it is replaced. If somebody presents an old one that has already been
rotated past, that is not a mistake — either the real user's token was stolen
and used, or the thief's was. The server cannot tell which, so it treats it as
theft: **401, and the whole session is revoked**, recorded as `TOKEN_REPLAY`.
The real user signs in again; the thief gets nothing.

An *expired* token is different and is treated differently: 401, and nothing is
revoked. An expired token is not evidence of anything.

**Signing out actually signs you out.** Every authenticated request checks the
session is still live, in the same database query that loads the user — so it
costs nothing extra. Sign a device out from `DELETE /me/sessions/{id}` and it
stops working on its very next request, not ten minutes later. "Remote sign-out
that leaves the device working for ten more minutes is not one."

## 2.2 Passwords

Stored with **BCrypt**. What that means in plain terms:

- It is **one-way**. There is no "decrypt the password" operation, for anyone,
  including us. Checking a password means scrambling what was typed the same way
  and comparing the results.
- It is **deliberately slow**. A fast scramble lets an attacker with a stolen
  database try billions of guesses per second. BCrypt makes each guess cost real
  time.
- It is **salted automatically**. Two people with the same password get
  different stored values, so cracking one does not crack the other.

**If somebody steals the database, they do not have anybody's password.** That
is the whole point, and it is done correctly here.

## 2.3 Guessing is made expensive

Failed sign-ins on one account climb a ladder (`sujula.security.login.*`):

| Step | Default (normal account) | Admin | What happens |
|---|---|---|---|
| Warn | 3 failures | *off* | The owner gets an email pointing at password recovery. **No token in it** — an email that gives access is an email worth triggering deliberately. |
| Reset | 5 failures | 3 | A real reset link |
| Lock | 7 failures | 4 | The account stops accepting sign-ins |

Admins climb faster and get no warning step, because an admin account moves
money, approves vendors and can read every customer's details.

Locks **expire on their own** after 15 minutes. Two reasons: support should not
be in the loop for a mistyped password, and nobody should be able to keep an
admin locked out by deliberately failing to sign in as them.

**A detail that matters more than it looks.** The counter is committed in its
own separate transaction. A failed sign-in ends in an error, and an error
normally undoes everything done during that request — including a counter
incremented alongside it. Get this wrong and the lockout silently never fires.
This code gets it right, with a comment explaining why.

**Wrong email and wrong password give the same answer**, always: `401 Invalid
email or password`. Different answers would turn the sign-in page into a tool
for discovering which email addresses have accounts here.

## 2.4 The second factor (MFA)

Aminata can enrol an authenticator app. After that, signing in needs her
password **and** a six-digit code from her phone. Someone who steals only the
password cannot get in.

**Recovery codes** exist for the day her phone is lost. Each works once. Used
ones are **kept**, not deleted — so reusing one is *refused* rather than
mistaken for a code that never existed.

The code-generating algorithm (TOTP, RFC 6238) is implemented here by hand
rather than pulled from a library. That is deliberate and defensible: it is
about forty lines, and the standard publishes official test vectors, so this
implementation is **proved correct against the specification** rather than
trusted. `TotpServiceTest` checks every published vector.

## 2.5 Asking again, for the things that really matter

Consider `PUT /vendor/stores/{id}/bank-account` — the endpoint that says where a
shop's money goes.

A valid access token proves *somebody held a credential within the last ten
minutes*. For reading a product list that is the right question. For redirecting
a shop's entire income it is not: a laptop left open in an internet café, a
phone handed to a cousin, or a stolen token all pass that test.

So this endpoint asks again, **now**: the password, and the authenticator code
too when the account has MFA. Sending only the password on an MFA account is
refused — a step-up that is easier to pass than the sign-in which reached it is
not a step-up.

Sujula calls this a **step-up**. It is used exactly where the loss is
irreversible.

## 2.6 CSRF protection, and why some paths skip it

The attack was described in §1.6. The defence: the server hands the browser a
random value in a cookie named `XSRF-TOKEN`, and requires that value **echoed
back in a header**. A malicious page can make your browser *send* a request with
your cookies, but it cannot *read* your cookies to copy the value into a header.

Some paths are deliberately exempt, and each exemption has a reason worth
knowing:

| Exempt | Why it is safe |
|---|---|
| `/auth/**`, `/me/**` | These use a **bearer token in a header**, and a browser never attaches an `Authorization` header on its own. There is nothing for a forged request to ride. Requiring a cookie-delivered token would also make the API unusable from a phone app, which has no cookie jar. |
| `/webhooks/**`, `/api/payments/callback` | A payment provider's server has no browser, no cookies and no token. It is authenticated by a **cryptographic signature over the message**, which is *stronger* than CSRF protection: it proves the sender knows a secret **and** that nothing was altered in transit. |
| `/geo`, `/delivery`, `/delivery-contexts`, `/currencies` | Read-only lookups. They use POST because an address is too long for a URL and not something to leave in server logs — but they change nothing a forged request could exploit, and a shopper with no account has no token to present. |
| `/carts`, `/checkout` | Reachable by guests, who by definition have no session. |

> **A historical note worth keeping.** The default Spring configuration expects
> the header to carry a *masked* version of the token, while a browser only has
> the raw cookie value. The two halves disagreed and **every POST was refused** —
> login, registration and checkout were all unreachable from a browser. The fix
> (`spaCsrfTokenRequestHandler`) turns the masking off, which is safe here
> because the masking guards against an attack on tokens rendered into HTML
> pages, and this server only ever returns JSON.

## 2.7 Who may reach which URL

`SecurityConfig` is one long, commented list of rules. Rather than reproduce it,
here is the shape.

**Open to anybody, deliberately:**

| Path | Why |
|---|---|
| `/products`, `/categories`, `/search`, `/stores/*`, `/brands` | Browsing precedes signing in, always. A catalogue that demands an account is a catalogue nobody reaches. |
| `/currencies`, `/countries`, `/locales`, `/config/public` | A storefront needs these to draw its first screen. `/config/public` is an **allow-list assembled by hand**, never a filtered view of configuration — a filtered view is one careless rename away from publishing a secret. |
| `/geo/**`, `/delivery/quote`, `/delivery/serviceability` | A shopper asks "do you deliver to Brikama, and what does it cost" before signing up. |
| `/carts/**` | Most baskets here are filled before anyone signs in. |
| `/pickup-points` (GET) | A shopper chooses a counter before having an account. |
| `/track/{code}`, `/parcels/{code}/**` | **The recipient has no account.** See §2.9. |
| `/invoices/{token}` | The token *is* the credential; an invoice legitimately travels to a bank or whoever is reimbursing the buyer. |
| `/health/liveness`, `/health/readiness` | An orchestrator needs an answer before the database exists. Neither returns anything a stranger can use. |

**Closed:**

- Everything under `/admin/**` needs `ADMIN` or `SUPPORT`. Which of the two may
  do what is decided *inside*, by `StaffCaller`: **support reads, admin
  decides**. A read and a write sit next to each other under the same prefix, so
  a path rule could not tell them apart.
- `/vendor/**`, `/driver/**`, `/pickup/**`, `/orders/**`, `/me/**`,
  `/notifications/**` need a signed-in account — **and that is only the outer
  gate.** Inside, every query carries the caller's own id, so changing a number
  in the URL reaches nothing.
- `/actuator/**` needs `ADMIN`. The metrics scrape carries request counts, error
  rates and timings per endpoint — enough to tell an outsider when the platform
  is struggling and which path to press on. Restrict it at the network too; this
  is the second lock.

**The one that surprises people:** posting a *question* on a product requires an
account, while reading the catalogue does not. A question writes public text
onto a seller's shopfront, which without an account behind it is a spam channel
with no cost to the sender.

## 2.8 Bank details are encrypted; almost nothing else is

Most columns are stored as they are. Encrypting everything sounds safer and is
not: you end up with a database nothing can query and a key kept somewhere
convenient, which is to say somewhere that leaks with everything else.

So encryption is applied where **a leaked backup is a direct loss to the person
the row is about**: a vendor's account number, IBAN and mobile-money line. Each
is written once and read only when a payout runs.

- **AES-256-GCM**, a fresh random nonce per value. GCM rather than CBC because
  GCM *authenticates*: a ciphertext somebody edited fails to decrypt rather than
  decrypting into something else.
- The key is `sujula.security.field-encryption.key` — base64 of exactly 32
  bytes. Generate one with `head -c 32 /dev/urandom | base64`.
- **Without the key, the payout-details endpoint refuses**, rather than storing
  an account number in the clear. This is the "fail closed" principle, stated in
  the config file itself.
- The encryption sits at the **column mapping** (`EncryptedStringConverter`),
  not in a service. That is the one place it cannot be forgotten: a service that
  encrypts on the way in leaves every future write path — an admin tool, a
  migration, a repository method somebody adds next year — free to put plaintext
  into the same column, with nothing about the column saying it should not.

**What the API gives back about a payout destination:** four digits, a holder
name, a currency. Nothing that could be used to send money anywhere.

## 2.9 The recipient, who has no account

This is the part of the design most specific to what Sujula is for, and the part
most worth understanding.

Aminata's sister in Serrekunda never signed up for anything. She has a phone
number. She is the only person who knows whether she will be home on Thursday.
So she can act, without an account:

```
GET  /parcels/{trackingCode}                     her parcel
POST /parcels/{trackingCode}/reschedule          come on a different day
POST /parcels/{trackingCode}/choose-pickup-point send it to a counter instead
POST /parcels/{trackingCode}/authorise-safe-drop leave it with the neighbour
```

**Two credentials instead of a session.** The tracking code in the URL is
unguessable and lets her *read* a page with a first name and a town on it. A
six-digit code sent separately authorises the three *changes*.

**The public page is built to be worth nothing to a stranger.**
`GET /track/{code}` shows a city, parcel counts, and **fixed phrases** rather
than anything anybody typed. Where the internal record says *"Assigned to Ebrima
Bojang"*, the public page says *"A driver has been assigned."* That substitution
is the entire distance between a driver's notes and somebody who was forwarded
the SMS.

**The driver never sees the release code.** They can *request* that it be sent,
but they cannot read it — a driver who could read it could mark a parcel
delivered without meeting anybody.

**Handing over needs three things at once:** the recipient's code, a position,
and a photograph. This is the link somebody would forge if any one of them were
enough alone.

> ⚠️ **Where this falls short today.** There is no SMS sender integrated, so the
> code is emailed to **the buyer**, who passes the digits on. That works for the
> intended story — Fatou in Madrid tells her sister — but it means the recipient
> depends on the payer being reachable. See
> [`LIMITATIONS.md` §2.2](LIMITATIONS.md).

## 2.10 Machines talking to machines

A payment provider's server posts to `/webhooks/**` saying "this payment
succeeded". Anyone on the internet can send that message. How do we know it is
really them?

**A signature over the message.** The provider and Sujula share a secret. The
provider computes `HMAC-SHA256` over `<timestamp>.<body>` and sends the result
in a header. Sujula computes the same thing and compares.

Three rules, each of which has cost somebody a breach somewhere:

1. **Compare in constant time.** An ordinary string comparison stops as soon as
   two characters differ, and *how long it took* reveals how much of a guess was
   right. A few thousand requests turn that into the whole signature.
2. **Sign the raw bytes.** Not a re-formatted copy — the provider signed their
   own formatting, and any parse-and-reprint changes whitespace and breaks the
   match.
3. **No secret configured means no.** An unconfigured provider is refused, never
   trusted, because the default state of configuration is *absent*.

**The timestamp is inside the signature**, which is what stops yesterday's
"payment succeeded" being replayed tomorrow. Anything older than
`sujula.webhooks.tolerance` (5 minutes) is rejected.

**Every rejection answers the same single word: "Rejected."** Telling a prober
whether they got the signature right but the timestamp stale is telling them how
to make progress. The real reason is written to the database, where it belongs.

And the rule a provider does not get to break: **a provider does not decide what
an order cost.** A callback confirms settlement against the order's own figures;
it cannot restate them.

## 2.11 Nothing internal escapes in an error

`GlobalExceptionHandler` catches everything and returns one JSON shape. No SQL,
no constraint names, no stack traces, no Java class names — each of those
describes the schema to anybody who can provoke an error. Nothing falls through
to the server's default error page either, which answers in HTML and says more
than this application does.

## 2.12 The audit log

`AuditLog` records who did what and when. It is **append-only**: there is no
endpoint to edit or delete an entry, and that is stated as a design decision
rather than an omission.

Impersonation (`POST /admin/users/{id}/impersonate`, which opens a short session
as somebody else) is audited, because it is precisely the power that needs a
record.

## 2.13 Things that are off by default because they help an attacker

- **The API documentation.** Swagger UI and the OpenAPI document map every path,
  every role-gated write and every request shape. That is a gift to anybody
  probing. Off unless `sujula.docs.enabled=true`; `dev` turns it on, `prod`
  turns it off twice.
- **Actuator endpoints.** Only `health`, `info` and `prometheus` are exposed. The
  default set includes `/env` and `/configprops`, which print configuration — and
  this configuration holds webhook signing secrets and the field-encryption key.
  Naming three is the right way round; exposing the set and then trying to redact
  it is not.
- **The mock payment gateway.** It marks orders paid without any money moving.
  Off by default, **refuses to start under a `prod` profile**, prints a warning
  banner when it is on, and the prod profile sets it false again as a second
  lock.

---

# Part III — Changing things safely

## 3. The settings that matter most

Every one of these is an environment variable in production. Nothing sensitive
should ever be typed into a file that goes into git.

| Variable | What it is | Get this wrong and… |
|---|---|---|
| `SUJULA_AUTH_JWT_SECRET` (→ `sujula.auth.jwt.secret`) | The key that signs access tokens. **At least 32 bytes.** Note: unlike most settings here there is no short `${...}` alias in the properties files, so set the relaxed-binding name or pass `-Dsujula.auth.jwt.secret`. | Unset under `prod`, the app **refuses to start** — deliberately. A generated key would differ per server and every token would break on restart. Too short and the signature is weaker than it looks. Anyone who learns it can mint a token for **any account**. |
| `FIELD_ENCRYPTION_KEY` | Base64 of exactly 32 bytes. Encrypts bank details. | Unset, payout details **cannot be saved**. Lost, existing bank details **cannot be read** — and there is no recovery. Back it up separately from the database, or losing one means losing both. |
| `PAYMENT_CALLBACK_SECRET` | Shared with the payment provider. | Blank disables the callback endpoint. Leaked, somebody can mark any order paid. |
| `SUJULA_WEBHOOKS_SECRETS_<PROVIDER>` (→ `sujula.webhooks.secrets.<provider>`) | One per provider. | Unset means that provider is **refused**, which is the safe default. |
| `INVOICE_SIGNING_SECRET` | Signs invoice download links. | Unset, one is generated per process: links die on restart and are invalid on a second server. Fine on a laptop, wrong behind a load balancer. |
| `DB_PASSWORD` | Database password. | No default under `prod` — the deployment stops rather than starting a server that cannot serve. |
| `BOOTSTRAP_ADMIN_PASSWORD` | The first administrator. | Leave it blank and one is **generated and printed to the log once**. Acceptable for a first local run; wrong on a server whose logs are shipped anywhere. |
| `CART_COOKIE_SECURE` | Whether the guest-cart cookie requires HTTPS. | **Must be `true`** anywhere reachable from the internet. `false` is for local HTTP development only. |
| `SPRING_PROFILES_ACTIVE` | Which profile. | **Must be `prod`.** See §3.1. |

**Generating secrets** (run on the machine, never in a chat window, never in a
ticket):

```bash
# JWT signing key
head -c 48 /dev/urandom | base64

# Field-encryption key — must be exactly 32 bytes before encoding
head -c 32 /dev/urandom | base64
```

### 3.1 The one configuration mistake to watch for

`application.properties` currently reads:

```properties
spring.profiles.active=${SPRING_PROFILES_ACTIVE:dev}
```

**If you deploy and forget to set `SPRING_PROFILES_ACTIVE`, the server starts as
`dev`** — which enables the mock payment gateway (orders marked paid without
money moving), lets Hibernate rewrite the schema, serves the API map publicly,
and sends the cart cookie over plain HTTP.

The protections against each of those are conditioned on `prod` being *active*,
so the one case they do not cover is `prod` never being set.

**Always set it explicitly, and check it after deploying:**

```bash
curl -s localhost:8080/actuator/info    # as an admin
grep "The following .* profile" application.log
```

This is written up as finding §6.6 in [`CODE-REVIEW.md`](CODE-REVIEW.md), with a
one-line fix.

## 4. How to make common changes safely

### 4.1 Making an endpoint public

1. Add the path to `authorizeHttpRequests` in `SecurityConfig` — **above**
   `.anyRequest().authenticated()`, and above any broader rule that would match
   it first.
2. **Write the comment.** Every rule in that file says why. A rule with no
   reason is one nobody can review later.
3. Decide whether it also needs CSRF exemption. It does **only** if unauthenticated
   browsers or machines must POST to it.
4. **Look at what the response contains**, carefully. The pickup-point endpoints
   are the model: public, and they return occupancy as a *band* rather than a
   count, because "this shop holds 190 parcels" is a fact about somebody's
   business.
5. **Add a routing test.** `BuyerOrderSecurityTest` is the pattern — a booted
   application with the real filter chain, asserting both who may and who may
   not.

### 4.2 Making an endpoint stricter

Same file, but remember **path rules and method annotations are different
tools**:

- A **path rule** (`SecurityConfig`) is right for a whole prefix.
- A **method annotation** (`@PreAuthorize`) is right when a read and a write sit
  side by side under one path.

> **Use `@PreAuthorize`, not `@PostAuthorize`, on anything that writes.**
> `@PostAuthorize` runs *after* the method, so the write has already happened and
> usually already committed. The caller gets a 403 and the change stays. This is
> not hypothetical — it is
> [finding §2.1](CODE-REVIEW.md#21-postauthorize-on-mutating-endpoints-authorises-after-the-write-has-committed),
> present in `UserController` today.
>
> `@PostAuthorize` has exactly one legitimate use: a **read** whose permission
> depends on the object that came back (`returnObject.userId == authentication.principal.id`).

### 4.3 Adding a field that touches somebody's identity

Ask three questions before writing the column:

1. **If the database leaked, would this hurt the person the row is about?** If
   yes, it belongs behind `EncryptedStringConverter` like the bank fields.
2. **Does it reach an API response that a *different* person can see?** A
   vendor's view of an order carries a name, a town, a country and three digits
   of a phone — no street, no payer. Match that standard.
3. **Would it end up in a log?** Handover codes are served `no-store` and never
   logged. New secrets need the same treatment.

### 4.4 Changing a session or token lifetime

`sujula.auth.jwt.access-token-ttl` (default 10m) is the **blast radius of a
stolen access token**, because the server keeps no copy and cannot tear one up.
Lengthening it to reduce refresh traffic trades directly against that. Do not
exceed an hour without a specific reason written down.

`refresh-token-ttl` (30d) is how long a device stays signed in while unused.
`max-sessions-per-user` (10) caps concurrent devices; past it the oldest is
dropped.

### 4.5 Adding a webhook provider

1. Set `sujula.webhooks.secrets.<provider>` from the environment.
2. **Do not** add a fallback or a default. Unconfigured must stay *refused*.
3. **Do not** parse the body before verifying the signature. The signature is
   over the raw bytes.
4. Keep the reply vague. "Rejected" for everything; the detail goes to the
   database.

### 4.6 What not to do

| Don't | Because |
|---|---|
| Put anything sensitive in the JWT | It is signed, not encrypted. Anyone holding it reads every field. |
| Return 403 for somebody else's row | It confirms the row exists. Return 404. |
| Add `allowedOrigins("*")` with credentials | The CSRF cookie and the guest-cart cookie both make that a real hole. |
| Store a secret with a default value in a properties file | Fail closed. Unset must mean refuse. |
| Trust a client-supplied cart id, session id or user id | `CartOwner` exists for exactly this reason: a client-supplied id is a bearer token for whichever cart it names. |
| Log a code, token or account number | Handover codes are `no-store` and never logged. Keep it that way. |
| Turn on `sujula.docs.enabled` in production | It publishes a complete map of every endpoint. |

---

# Part IV — What is missing

Stated plainly, because a security document that lists only strengths is not
useful.

| Gap | What it means | Where to fix it |
|---|---|---|
| **`@PostAuthorize` on writes** ([§2.1](CODE-REVIEW.md)) | An ordinary signed-in customer can delete or edit other accounts through `/api/users/**`. The response is 403; the change persists. | **Fix this first.** Swap to `@PreAuthorize` and add path rules. |
| **No CORS policy** | The API is same-origin only. A browser client on a different domain simply cannot call it. **All five clients are built assuming this**, and it is what lets the cookie, the CSRF token and the bearer token work with no cross-origin request. | Deploy behind one reverse proxy (see `OPERATIONS.md`). If you must add CORS, use an explicit configured allow-list. Never `*` with credentials. |
| **`buyer-app` stores both tokens in `localStorage`** | The other four clients keep the access token in memory. `buyer-app` is also the one rendering the most seller- and buyer-written text through `innerHTML`, so one missed escape would lift a 30-day refresh token. | Move the access token to a module variable, and add a test for the `esc()` helper. [`frontend/BUYER-APP.md` §8.1](frontend/BUYER-APP.md) |
| **Clients defeat the idempotency layer** | Three of five mint a new `Idempotency-Key` on each attempt, so a retried checkout places a second order and takes a second payment. | Mint the key where the user acts and reuse it across retries, as `driver-app` does. [`frontend/README.md` §6.3](frontend/README.md#63-the-idempotency-key-is-minted-per-attempt-in-three-of-five-apps) |
| **No general rate limiting** | Targeted limits exist (sign-ins, phone codes, release codes, imports, staff invites) but nothing limits the surface as a whole. `POST /geo/validate-address` spends a paid Google call per request. | Do it at the reverse proxy or gateway, not in application code. |
| **No SMS** | C5's stated target is a code the recipient reads out. Today it is emailed to the buyer, who relays it. | Add an `SmsGateway` beside `PaymentGateway`. |
| **No real payment provider** | Only the mock gateway exists. It refuses to run under `prod`, so a production deployment cannot take card payments at all yet. | Integrate a provider; the webhook and callback plumbing is already built and verified. |
| **No explicit CSP** | Spring Security's defaults apply (`nosniff`, `X-Frame-Options: DENY`, HSTS over HTTPS). There is no `Content-Security-Policy`. | Low priority for a JSON API; add before serving any HTML from this origin. |
| **Secrets management** | Everything comes from environment variables, which is correct, but there is no rotation procedure written down. | Write one. Note that rotating `FIELD_ENCRYPTION_KEY` requires re-encrypting existing rows. |
| **TLS** | Terminated outside the application. `prod` trusts `X-Forwarded-For`. | So the **reverse proxy must set that header itself** and strip any the client sent — otherwise a client can forge its own IP. |

---

# 9. The deployment checklist

Before this application serves a single real account:

**Blockers**

- [ ] **Fix `CODE-REVIEW.md` §2.1.** A signed-in customer can currently delete other accounts.
- [ ] `SPRING_PROFILES_ACTIVE=prod` is set, and verified in the startup log.
- [ ] `SUJULA_AUTH_JWT_SECRET` set, ≥32 bytes, generated from a secure random, unique to this deployment.
- [ ] `FIELD_ENCRYPTION_KEY` set, exactly 32 bytes base64-encoded, **backed up separately from the database**.
- [ ] `DB_PASSWORD` set; the database is not reachable from the internet.
- [ ] HTTPS terminating in front; `CART_COOKIE_SECURE=true`.
- [ ] The reverse proxy **sets** `X-Forwarded-For` itself and strips any the client sent.
- [ ] `sujula.payment.mock.enabled` is false (the `prod` profile does this; verify anyway).
- [ ] `sujula.docs.enabled=false`; `/v3/api-docs` and `/openapi.json` return nothing to an anonymous caller.
- [ ] `/actuator/**` unreachable from the internet at the network level, as well as requiring `ADMIN`.

**Should be done**

- [ ] `BOOTSTRAP_ADMIN_PASSWORD` set explicitly, so nothing is printed to a log.
- [ ] `INVOICE_SIGNING_SECRET` set — required if more than one instance runs.
- [ ] Rate limiting configured at the proxy for `/geo/**`, `/delivery/**`, `/search`, `/carts`, `/auth/login`.
- [ ] Webhook secrets set per provider; none left blank while that provider is live.
- [ ] Database backups encrypted, and the encryption key stored somewhere the backup is not.
- [ ] Logs reviewed for anything that should not be in them, once, by a person.

**Client-side, before release**

- [ ] All five clients served **same-origin** with the API, behind one proxy.
- [ ] Client routes do not collide with API paths in the proxy config (`OPERATIONS.md` §4A).
- [ ] `buyer-app`'s access token is no longer in `localStorage`, or the risk is accepted in writing.
- [ ] `POST /checkout` sends a **stable** `Idempotency-Key` across retries.
- [ ] Android build performs a write successfully (`androidScheme: 'https'`).

**Verify by hand after deploying**

```bash
# The API map must not be public
curl -si https://host/v3/api-docs  | head -1     # expect 401/403/404, never 200
curl -si https://host/openapi.json | head -1

# Metrics must not be public
curl -si https://host/actuator/prometheus | head -1

# Probes must answer
curl -s  https://host/health/readiness

# Someone else's order must be "not found", never "forbidden"
curl -si https://host/orders/1401 -H "Authorization: Bearer <a different user's token>" | head -1
```

---

## Glossary

| Term | Plain meaning |
|---|---|
| **Authentication** | Proving who you are. |
| **Authorization** | Deciding what you may do. |
| **Bearer token** | A string that grants access to whoever holds it — like a cinema ticket. It does not care who you are. |
| **JWT** | A token whose contents anyone can read and nobody can alter without detection. Signed, **not** encrypted. |
| **HMAC** | A way of proving a message came from someone who knows a shared secret, and was not altered. |
| **BCrypt** | A deliberately slow one-way scramble for passwords. |
| **AES-256-GCM** | Strong two-way encryption that also detects tampering. |
| **Nonce** | A number used once, so encrypting the same value twice does not produce the same ciphertext. |
| **CSRF** | Tricking your browser into sending a request you did not intend, using cookies it attaches automatically. |
| **Replay attack** | Capturing a valid message and sending it again later. |
| **Constant-time comparison** | Comparing two secrets in a way whose duration does not reveal how much matched. |
| **Step-up** | Asking for a credential again, now, before something irreversible. |
| **Fail closed** | When unsure or unconfigured, refuse. |
| **Defence in depth** | More than one lock, so one failure is not a breach. |
| **TOTP** | The six-digit code from an authenticator app, derived from a shared secret and the current time. |
| **Escrow** | Money held by the platform until delivery is proven. |
