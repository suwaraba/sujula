# Sujula — Operations and Deployment

> For whoever has to run this. Configuration reference, deployment procedure,
> what to watch, and what to do when something breaks.
>
> Security-critical settings are covered in more depth, and in plainer language,
> in [`SECURITY.md`](SECURITY.md) — including a deployment checklist.

---

## 1. What you are deploying

A **WAR** built by Maven. `spring-boot-starter-tomcat` is `provided`, so it runs
either standalone (`java -jar`, `mvn spring-boot:run`) or inside an external
Tomcat.

```bash
mvn -B clean package -DskipTests=false -Djava.version=21
# target/Sujula-0.0.1-SNAPSHOT.war
```

**Requirements:** Java 21 (the POM targets it; JDK 26 also builds it), MySQL 8,
and a reverse proxy terminating TLS.

> ⚠ There is **no CI pipeline, no Dockerfile and no deployment artefact
> definition** in this repository. See [`LIMITATIONS.md` §4.3](LIMITATIONS.md).

---

## 2. Profiles

| Profile | Use | Database | Schema |
|---|---|---|---|
| *(none)* | assumes nothing, starts nothing | none | — |
| `dev` | local development | MySQL on localhost, created on first connect | `ddl-auto=update` — **disposable** |
| `test` | the JUnit suite | H2 in MySQL mode | generated |
| `e2e` | the end-to-end collection | in-memory H2 + seed | generated |
| `prod` | deployment | from the environment, **no fallbacks** | `ddl-auto=validate` |

> ### ⚠ Always set `SPRING_PROFILES_ACTIVE` explicitly
>
> `application.properties` currently defaults to `dev` when the variable is
> absent. A deployment that forgets it starts with the **mock payment gateway**
> on, Hibernate rewriting the schema, Swagger UI public and the cart cookie
> insecure. The protections against each of those are conditioned on `prod`
> being active, so the one case they do not cover is `prod` never being set.
>
> Verify it in the startup log:
> `The following 1 profile is active: "prod"`
>
> See [`CODE-REVIEW.md` §6.6](CODE-REVIEW.md) for the one-line fix.

> ### ⚠ Never deploy the `e2e` profile
>
> Every secret in it is a fixed, published test value. The JWT signing key is in
> a file in this repository.

---

## 3. Configuration reference

Everything below is set from the environment. Spring's relaxed binding means
`sujula.auth.jwt.secret` can be set as `SUJULA_AUTH_JWT_SECRET`.

### 3.1 Required in production

| Variable | Notes |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `DB_URL` | No default under `prod` — a missing value stops the deployment rather than starting a server that cannot serve |
| `DB_USERNAME`, `DB_PASSWORD` | Same |
| `SUJULA_AUTH_JWT_SECRET` | ≥32 bytes. **Refuses to start under `prod` without it** |
| `FIELD_ENCRYPTION_KEY` | base64 of **exactly 32 bytes**. Without it, payout details cannot be saved |
| `CART_COOKIE_SECURE` | `true` |

### 3.2 Strongly recommended

| Variable | Default | Why |
|---|---|---|
| `INVOICE_SIGNING_SECRET` | generated per process | Unset, invoice links die on restart and are invalid on a second instance |
| `BOOTSTRAP_ADMIN_PASSWORD` | generated and **logged once** | Fine on a laptop, wrong on a server whose logs are shipped |
| `BOOTSTRAP_ADMIN_EMAIL` | *(blank)* | Registration only ever creates customers and a vendor cannot be approved without an admin, so a fresh deployment needs one seeded or nobody can sell |
| `PUBLIC_BASE_URL` | *(blank → relative URLs)* | Set it if clients are not same-origin |

### 3.3 Database

| | Default |
|---|---|
| `DB_POOL_MAX` | 10 |
| `DB_POOL_MIN` | 2 |
| connection timeout | 10s |
| `spring.jpa.open-in-view` | `false` |
| dialect | **pinned** to `MySQLDialect`, never inferred from JDBC metadata |

### 3.4 Mail

`MAIL_HOST` (default `smtp.gmail.com`), `MAIL_PORT` (587), `MAIL_USERNAME`,
`MAIL_PASSWORD`. STARTTLS and auth are on.

**Timeouts are fixed at 5 seconds** (connect, read, write) and that is not
tuning: JavaMail waits forever by default and these sends happen on the request
thread. One unreachable mail host would consume the whole Tomcat pool holding
open registrations.

### 3.5 Payments

| | |
|---|---|
| `PAYMENT_CALLBACK_SECRET` | Shared secret for `/api/payments/callback`. **Blank disables the endpoint** |
| `PAYMENT_MOCK_ENABLED` | Default false. **Refuses to start under `prod`** |
| `sujula.payment.checkout-ttl-minutes` | 60 |
| `PAYMENT_BANK_NAME`, `PAYMENT_BANK_ACCOUNT_NAME`, `PAYMENT_BANK_ACCOUNT_NUMBER` | Bank transfer is offered to buyers **only once all three are set** |
| `PAYMENT_BANK_BRANCH`, `PAYMENT_BANK_SWIFT` | Optional |

### 3.6 Webhooks

| | Default |
|---|---|
| `SUJULA_WEBHOOKS_SECRETS_<PROVIDER>` | **unset = that provider is refused** |
| `sujula.webhooks.tolerance` | `5m` — the replay window |
| `sujula.webhooks.max-body-bytes` | 1 MiB |
| `sujula.webhooks.max-attempts` | 5 |
| `sujula.webhooks.interval-ms` | 10000 |

### 3.7 Object storage (Cloudflare R2)

`R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET_NAME`,
`R2_PUBLIC_URL`, `R2_REGION` (default `auto`).

**Unset is a supported state**: `UnconfiguredStorageService` is bound instead
and the upload endpoints return 400 explaining what to set. Everything else
runs.

### 3.8 Geocoding

`GOOGLE_GEOCODING_API_KEY`; `sujula.google.geocoding.timeout-ms` 3000,
`max-distance-meters` 250, `sujula.geocoding.default-language` `en`.

**Unset is a supported state**: `/geo/validate-address` answers
`available: false` — *"nobody looked"*, which is different from "we looked and
found nothing". Addresses save without coordinates and delivery prices from a
scope fallback.

**Note: every call costs money.** Rate-limit `/geo/**` at the proxy (§6).

### 3.9 Delivery pricing

Each product is priced as its own leg:
`base + per-km beyond included-km + per-kg beyond included-kg`, scaled by scope
and mode, clamped to `[min, max]`.

| | Default |
|---|---|
| `sujula.delivery.pricing.currency` | `GMD` |
| `base-fee` / `min-fee` | 50.00 |
| `included-km` / `per-km` | 3 / 12.00 |
| `included-kg` / `per-kg` | 1 / 25.00 |
| `default-weight-kg` | 0.50 |
| `max-fee`, `free-above` | unset = uncapped / off |
| `scope-multiplier.*` | `REGIIONAL` 1.00, `RECOGER` 0.50, `NATIONAL` 1.40, `GLOBAL` 2.50 |
| `mode-multiplier.*` | `HOME_DELIVERY` 1.00, `PICKUP_POINT` 0.75, `VENDOR_PICKUP` 0.00 |
| `fallback-km.*` | 15 / 5 / 120 / 800 — used only when neither end has usable coordinates |

> ⚠ **`REGIIONAL` is spelled with two `I`s in the enum, the property keys and the
> persisted data.** Reproduce the typo exactly. A *corrected* spelling silently
> fails to bind and the multiplier falls back — a pricing change nobody sees.
> See [`CODE-REVIEW.md` §6.1](CODE-REVIEW.md).

### 3.10 Sign-in escalation

`sujula.security.login.default-policy.warn-at` 3, `.reset-email-at` 5,
`.lock-at` 7; `roles.ADMIN.warn-at` 0, `.reset-email-at` 3, `.lock-at` 4;
`lockout-duration` `15m`. A step set to 0 never fires.

### 3.11 Tokens and sessions

`sujula.auth.jwt.access-token-ttl` `10m` (this is the blast radius of a stolen
token), `refresh-token-ttl` `30d`, `max-sessions-per-user` 10,
`sujula.auth.jwt.issuer` `sujula`.

### 3.12 Invoices

`sujula.invoice.link-ttl` `15m`, `download-path` `/invoices`,
`INVOICE_ISSUER_NAME`, `INVOICE_ISSUER_ADDRESS`, `INVOICE_ISSUER_TAX_ID`,
`INVOICE_FOOTER_NOTE`.

### 3.13 Carts, catalogue, stores

`sujula.cart.guest-ttl-days` 7, `default-currency` `GMD`, `cleanup.cron`
`0 17 * * * *`; `sujula.catalogue.max-images-per-product` 12,
`jobs.interval-ms` 30000, `max-queued-jobs-per-vendor` 3,
`max-recorded-row-errors` 200, `export-link-ttl` `7d`;
`sujula.store.staff-invite-ttl` `7d`, `max-staff` 25.

### 3.14 Documentation

`sujula.docs.enabled` — default **false**. `prod` also sets
`springdoc.api-docs.enabled=false` and `springdoc.swagger-ui.enabled=false`.

### 3.15 Observability

`management.endpoints.web.exposure.include=health,info,prometheus` —
**three, named by hand**. The default set includes `/env` and `/configprops`,
which would print webhook signing secrets and the field-encryption key.
`show-details=never`. `/actuator/**` additionally requires `ROLE_ADMIN`.

---

## 4. Database

### 4.1 ⚠ There is no migration tool

No Flyway, no Liquibase, no `db/migration`. `prod` runs `ddl-auto=validate`,
which **checks** an existing schema and never creates one.

Until that is fixed ([`LIMITATIONS.md` §1.2](LIMITATIONS.md)), the only path to
a production schema is:

1. Start a **throwaway** instance under `dev` (`ddl-auto=update`) against an
   empty database.
2. `mysqldump --no-data` the result.
3. **Read it.** Hibernate writes no index you did not annotate, and the 332
   `@Query` declarations filter on columns that will want them.
4. Apply the reviewed DDL to production by hand, and keep it in version control.
5. Run production on `validate`.

Adopt Flyway before the second schema change.

### 4.2 The development seed

`src/main/resources/db/seed/dev-seed.sql` — 4,435 lines, all 50 tables, one
coherent dataset. **Never auto-loaded**, and it **deletes before it inserts**.

```bash
mysql -u root -p sujula < src/main/resources/db/seed/dev-seed.sql
```

Seeded ids are ≥ 1000; application rows start at 1, so the two never collide and
the bootstrap admin (id 1) survives a re-seed. The delete block removes exactly
`id >= 1000` in reverse foreign-key order, so running it twice is the same as
once.

**Never load it into production.** Passwords are published in the file.

### 4.3 Backups

Back up the database **and** `FIELD_ENCRYPTION_KEY`, **separately**. Bank
details are AES-256-GCM encrypted; losing the key means losing them, with no
recovery. A backup stored beside its own key is not a backup.

---

## 4A. Serving the five clients

`frontend/` holds five separate applications. Four build to static files
(`npm run build` → `dist/`); `buyer-app` has **no build step** — the files in
the directory are the files that ship.

```bash
cd frontend/admin       && npm ci && npm run build   # → dist/
cd frontend/vendor-app  && npm ci && npm run build
cd frontend/driver-app  && npm ci && npm run build
cd frontend/pickup-app  && npm ci && npm run build
# buyer-app: serve the directory as it is
```

**Every client must be served same-origin with the API.** There is no CORS
configuration, and the CSRF protection is a double-submit cookie only a
same-origin page can read. One reverse proxy fronts both.

> ### ⚠ Client routes collide with API paths
>
> Spring serves the API at the **root**. `vendor-app` has screens at `/orders`
> and `/products`; both are also real API paths. Proxying either to the backend
> sends deep links to Spring, which answers 401 instead of opening the app.
>
> **Proxy only the prefixes that client calls**, and give each client its own
> path prefix or its own hostname. Each client's README states where it must
> live — read it before writing the proxy config.

Development ports, so all five can run against one backend:
`admin` 5173 · `vendor-app` 5174 · `driver-app` 5175 · `pickup-app` 5176 ·
`buyer-app` 5177.

`driver-app` and `pickup-app` are **installable PWAs**; they must be served over
HTTPS with their manifests and service workers at the app root.

`vendor-app` and `buyer-app` also ship as **native builds** via Capacitor. Those
bundle their UI and take an **absolute API origin** at build time
(`SUJULA_NATIVE_DEV_URL` / `window.SUJULA_CONFIG`), since a packaged app has no
proxy. Android must use `androidScheme: 'https'`, or the WebView origin is
treated as insecure and the `Secure` CSRF cookie is blocked — every write then
fails on Android and nowhere else.

---

## 5. Reverse proxy

The application does not terminate TLS. `prod` sets:

```properties
server.tomcat.remoteip.remote-ip-header=X-Forwarded-For
server.forward-headers-strategy=native
```

> **The proxy must set `X-Forwarded-For` itself and strip any the client sent.**
> Otherwise a client forges its own IP, and the sign-in ladder and any
> proxy-level rate limiting are both defeated.

**Same origin.** There is no CORS configuration, so browser clients must be
served from the same origin as the API — one proxy fronting both. Native clients
are unaffected. See [`LIMITATIONS.md` §4.1](LIMITATIONS.md).

Also at the proxy:

- Block `/actuator/**` from the internet entirely. The `ROLE_ADMIN` rule is the
  second lock, not the only one.
- Block `/v3/api-docs` and `/openapi.json`.
- Set HSTS.

---

## 6. Rate limiting

There is none in the application beyond targeted throttles (sign-ins, phone
codes, release codes, queued imports, staff invitations). Do it here.

| Path | Suggested | Why |
|---|---|---|
| `POST /auth/login` | 10/min per IP | Ladder handles per-account; this handles spraying |
| `POST /auth/refresh` | 30/min per IP | |
| `POST /geo/**` | **5/min per IP** | **Each call spends a paid Google request** |
| `POST /delivery/quote`, `/delivery/serviceability` | 30/min per IP | Computation-heavy, unauthenticated |
| `GET /search`, `/search/suggest` | 60/min per IP | |
| `POST /carts` | 10/min per IP | Mints a row per call |
| `POST /parcels/*/request-code` | 5/min per IP | Server also limits per shipment |
| `/webhooks/**` | do **not** limit aggressively | Providers retry in bursts; the signature is the gate |

---

## 7. Background jobs

| Job | Cadence | Property |
|---|---|---|
| `WebhookWorker` | 10s | `sujula.webhooks.interval-ms` |
| `CatalogueJobWorker` | 30s | `sujula.catalogue.jobs.interval-ms` |
| `ReportExportWorker` | 45s | `sujula.money.exports.interval-ms` |
| `AccountDataWorker` | 60s | `sujula.auth.data-requests.interval-ms` |
| `PlatformSweeper` | 15m | `sujula.platform.sweeper.interval-ms` |
| `GuestCartCleanupJob` | cron `0 17 * * * *` | `sujula.cart.cleanup.cron` |

All are visible at `GET /admin/jobs` and `GET /admin/jobs/history`, and can be
triggered with `POST /admin/jobs/{name}/run`. **Every pass leaves a row,
including passes that found nothing** — a gap in the history is how you notice a
worker stopped.

> ⚠ **These use plain `@Scheduled` with no distributed lock.** Running more than
> one instance means every instance runs every job. Add ShedLock or equivalent
> before scaling out. `INVOICE_SIGNING_SECRET` must also be set explicitly
> before a second instance exists.

---

## 8. Health and metrics

```
GET /health/liveness      is the process alive
GET /health/readiness     should it receive traffic
```

Both public, both deliberately carrying nothing a stranger can use. They answer
**before anything is ready, including before a database connection exists** —
which is the moment an orchestrator most needs an answer. Readiness reports not
ready when the database has gone, so the instance is taken out of rotation
rather than left answering with errors.

`GET /actuator/prometheus` — admin-gated, and it should also be unreachable from
the internet. Metrics are tagged `application=sujula` so two deployments scraped
into one Prometheus can be told apart.

**Worth alerting on:**

| Signal | Why |
|---|---|
| Readiness failing | Database or startup |
| 5xx rate | |
| `webhook_events` stuck in `RECEIVED` | The worker has stopped, or a provider's signature is failing |
| A job with no row in `job_runs` for > 2× its interval | A worker died |
| p99 on `/products` and `/search` | The first things to slow down |
| Mail send failures | Password resets and **release codes** travel on email |
| `/geo/**` call volume | It is a billed API |

---

## 9. Deploying

```bash
# 1. Build and test the backend
mvn -B clean package -Djava.version=21          # 1,233 tests must pass

# 2. End-to-end, needs no external services
cd e2e && ./run.sh

# 3. Build the clients (§4A). No tests exist for them — see LIMITATIONS §5A.1
for app in admin vendor-app driver-app pickup-app; do
  (cd frontend/$app && npm ci && npm run typecheck && npm run build)
done
(cd frontend/buyer-app && npm run check)

# 4. Apply any reviewed DDL (§4.1)

# 5. Deploy the WAR and the client bundles

# 5. Verify
curl -s  https://host/health/readiness
curl -si https://host/v3/api-docs        | head -1   # must NOT be 200
curl -si https://host/actuator/prometheus | head -1   # must NOT be 200
grep "profile is active" application.log              # must say "prod"
grep -i "MOCK PAYMENT GATEWAY" application.log        # must find nothing
```

Full pre-flight list: [`SECURITY.md` §9](SECURITY.md#9-the-deployment-checklist).

---

## 10. Startup refusals — and what each one means

The application refuses to start rather than starting wrongly. Each refusal is
deliberate.

| Message | Cause | Fix |
|---|---|---|
| `sujula.auth.jwt.secret is not set…` | No signing key under `prod` | Set `SUJULA_AUTH_JWT_SECRET`, ≥32 bytes |
| `sujula.payment.mock.enabled is true under the 'prod' profile` | Mock gateway in production | Set it false |
| `sujula.reference.base-currency is X, which is not among the configured currencies` | Every fallback resolves to the base | Add it, or change the base |
| `Unable to determine Dialect without JDBC metadata` | Should not occur — the dialect is pinned. If it does, the database is unreachable | Check `DB_URL`, credentials, network |
| Context fails on a repository query | A `@Query` no longer matches the entities | The context-load test catches this; run `mvn test` |

---

## 11. Troubleshooting

| Symptom | Likely cause |
|---|---|
| Every browser POST returns 403 | Missing `X-XSRF-TOKEN`. Bearer-token calls do not need it |
| Uploads return 400 with an explanation | R2 unconfigured — by design. Set `R2_*` |
| `validate-address` says `available: false` | No Google key — by design |
| Invoice links die after a restart | `INVOICE_SIGNING_SECRET` unset |
| Payout details refused before anything is read | `FIELD_ENCRYPTION_KEY` unset — by design |
| Bank transfer not offered to buyers | One of the three bank fields is blank |
| A webhook provider's events all rejected | No secret configured for it, a clock skew beyond 5m, or a proxy re-serialising the body. **The body must reach the app byte-for-byte** |
| Registrations hang | Mail host unreachable; the 5s timeouts should cap it |
| Orders marked paid with no money | **The mock gateway is on.** Check the profile |
| Two of everything from the workers | More than one instance and no distributed lock |
| An XOF total with two decimal places | Known: [`LIMITATIONS.md` §2.1](LIMITATIONS.md) |
| A client deep link answers 401 instead of opening the app | The proxy is sending a client route to Spring. See §4A |
| Writes fail only in the Android build | `androidScheme` is not `https`, so the `Secure` CSRF cookie is blocked. See §4A |
| A browser client cannot reach the API at all | It is being served cross-origin. There is no CORS configuration — see §5 |
| A seller is signed out after their phone ran low on storage | A WebView evicted `localStorage`. The native build should be using Capacitor Preferences |

---

## 12. Runbooks

### 12.1 Rotate the JWT signing key

Access tokens last 10 minutes, so the blast radius is small. Refresh tokens are
hashed independently and survive.

1. Set the new value.
2. Restart.
3. Clients refresh; users notice nothing beyond one refresh round trip.

### 12.2 Rotate the field-encryption key

**There is no tooling for this, and it is not a restart.**

Existing bank details are encrypted with the current key. Rotating it makes them
unreadable. Until a re-encryption command exists, plan a maintenance window:
decrypt with the old key, re-encrypt with the new, in one transaction, with a
verified backup taken first. See [`LIMITATIONS.md` §4.4](LIMITATIONS.md).

### 12.3 Onboard a webhook provider

1. `SUJULA_WEBHOOKS_SECRETS_<PROVIDER>` from the environment. **No default.**
2. Confirm the proxy passes the body **unmodified** — the signature is over raw
   bytes, and any re-serialisation breaks it.
3. Confirm clocks are within 5 minutes.
4. Send a test event; check `webhook_events` for the stored verdict. The
   response to the caller is always the single word "Rejected" — the reason is in
   the row.

### 12.4 A worker has stopped

1. `GET /admin/jobs/history` — find the last row.
2. `POST /admin/jobs/{name}/run` to trigger it manually.
3. Check the log for the exception at that timestamp.
4. Workers are `fixedDelay`, so one uncaught exception does not stop the
   schedule — but a hung call does. Check the outbound dependency it uses.

### 12.5 Restore from backup

1. Restore the database.
2. **Restore `FIELD_ENCRYPTION_KEY` from wherever it is kept**, which must not be
   the same place as the backup.
3. Verify a payout destination reads back — four digits and a holder name. If it
   does not, the key is wrong; stop before anything writes.
4. Check `GET /admin/ledger/reconciliation`.

---

## 13. Scaling notes

- **Stateless request handling.** Sessions are database-backed, so instances
  need no affinity.
- **The guest-cart cookie** names a database row, so it works across instances.
- **Before a second instance:** add a distributed lock for the schedulers and set
  `INVOICE_SIGNING_SECRET` explicitly. Nothing else blocks it.
- **First bottlenecks, in order:** `/products` and `/search` (database `LIKE`
  with location ranking, no full-text index), then geocoding (billed, external,
  3s timeout), then PDF rendering (invoices and statements, synchronous).
