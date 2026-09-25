# Sujula: connecting the external services for testing

How to connect the backend to real providers using their free tiers and test
credentials, under the `staging` profile.

```
cp .env.example .env          # git-ignored; fill it in as you go
mvn -o spring-boot:run -Dspring-boot.run.profiles=staging
```

Only the database is required. Every other provider is independent: leave its
variables blank and the application falls back the way it does in `dev`. Texts
and pushes are logged, image uploads answer 400, and card payments go through
the mock. So you can connect providers one at a time and restart after each.

| # | Service | Used for | Free option | Required? |
|---|---|---|---|---|
| 1 | Neon (PostgreSQL) | everything | Neon free plan | **yes** |
| 2 | Gmail SMTP | account mail, password resets, release codes to the buyer | any Gmail account, ~500/day | recommended |
| 3 | Twilio | phone-verification codes, **release code texted to the recipient** (C5) | trial credit, verified numbers only | recommended |
| 4 | Firebase Cloud Messaging | push to the driver, pickup and buyer apps | free, unlimited | optional |
| 5 | Cloudflare R2 | product images | 10 GB and 1M writes/month free | for image upload |
| 6 | Stripe (test mode) | card payments | test mode is free, no money moves | optional; mock is the default |
| 7 | Secrets you generate | JWT signing, bank-detail encryption, invoice links | — | **yes** (see §7) |

---

## 1. Database: Neon (PostgreSQL)

1. console.neon.tech → *New project*. Pick the region closest to where the API
   will run (Frankfurt, `eu-central-1`, is the nearest to both Madrid and Banjul).
2. *Connect* → turn **Connection pooling off** so the host has no `-pooler`.
   The app keeps its own small pool, and the direct connection avoids
   PgBouncer's transaction-mode limits. You get a string like
   `postgresql://neondb_owner:abc123@ep-cool-rain-123456.eu-central-1.aws.neon.tech/neondb?sslmode=require`
3. Split it into `.env`:
   ```
   DB_URL=jdbc:postgresql://ep-cool-rain-123456.eu-central-1.aws.neon.tech/neondb?sslmode=require
   DB_USERNAME=neondb_owner
   DB_PASSWORD=abc123
   ```
   The `jdbc:` prefix is required, and user and password go in their own lines,
   not in the URL.

The `staging` profile selects the PostgreSQL driver and dialect by default.
Hibernate creates the tables on first start (`ddl-auto=update`). There is no
migration tool yet (OPERATIONS §4.1), so treat this database as disposable test
data. To fill it with a coherent dataset, add the `sample` profile **once**:

```
mvn -o spring-boot:run -Dspring-boot.run.profiles=staging,sample
```

This has been run end to end against PostgreSQL 16: the schema builds, all 89
entities are seeded, and search, filters and the nearby-pickup-point query
answer correctly.

**Neon's auto-suspend.** The free plan suspends the database after a few idle
minutes and wakes it on the next connection, and compute hours are metered
while it is awake. The staging pool therefore drains to zero idle connections
after a minute (`DB_POOL_MIN=0`), so the database can actually go to sleep. The
first request after a quiet spell waits briefly while it wakes.

**Branches.** Neon can branch a database like git. Make a branch before a
risky test, point `DB_URL` at it, and delete it afterwards.

**MySQL instead?** Set `DB_DRIVER=com.mysql.cj.jdbc.Driver` and
`DB_DIALECT=org.hibernate.dialect.MySQLDialect` with a `jdbc:mysql://` URL.
`dev` and `prod` still default to MySQL.

## 2. Mail: Gmail

1. Turn on **2-Step Verification** for the Google account.
2. Go to myaccount.google.com/apppasswords, create an App Password named
   "Sujula", and copy the 16 characters (spaces don't matter).
3. In `.env`:
   ```
   MAIL_USERNAME=you@gmail.com
   MAIL_PASSWORD=abcdefghijklmnop
   ```
   `MAIL_FROM` defaults to `MAIL_USERNAME`. Gmail rewrites any other `From`.

To check it, register a new account. The verification mail should arrive
within seconds. If you see `535 Authentication failed` in the log, you used the
account password instead of an App Password.

## 3. SMS: Twilio trial

1. Sign up at twilio.com. The trial comes with free credit.
2. *Console → Phone Numbers → Verified Caller IDs*: add every phone you will
   test with (for example the recipient's +220 number). **A trial account only
   sends to verified numbers.**
3. *Get a trial phone number* (or create a Messaging Service).
4. In `.env`, from the console home page:
   ```
   TWILIO_ACCOUNT_SID=AC...
   TWILIO_AUTH_TOKEN=...
   TWILIO_FROM_NUMBER=+1...
   ```
   If the SID is set but the token or sender is missing, the application refuses
   to start and says which one is missing.

Check *Messaging → Settings → Geo permissions* allows Gambia (+220) and
Senegal (+221). Trial messages start with "Sent from your Twilio trial
account".

What gets texted:

- `POST /auth/verify-phone/request`: the 6-digit code, to the number being verified.
- A driver's `request recipient code`: the release code, **to the recipient's
  own number** on the shipment. It is still emailed to the buyer as well.

## 4. Push: Firebase Cloud Messaging

1. console.firebase.google.com → *Add project* (Analytics not needed).
2. *Project settings → Service accounts → Generate new private key*. This
   downloads a JSON file. **Do not commit it.**
3. In `.env`, pick one:
   ```
   FCM_CREDENTIALS=/absolute/path/to/firebase-key.json
   FCM_CREDENTIALS=<output of: base64 -w0 firebase-key.json>
   ```
4. The apps register their device token with `POST /notifications/devices`.
   Each client needs Firebase's web or mobile config to obtain a token. That
   config is public, unlike the service-account key.

For iOS, upload an APNs key under *Project settings → Cloud Messaging*. Android
and web need nothing more.

## 5. Images: Cloudflare R2

1. dash.cloudflare.com → *R2* → enable it (a card is asked for, but nothing is
   charged inside the free tier) → *Create bucket* `sujula-images`.
2. *Bucket → Settings → Public access*: enable the **r2.dev subdomain** (fine
   for testing) or connect a custom domain. Copy the public URL.
3. *R2 → Manage R2 API Tokens → Create API token* with **Object Read & Write**
   on that bucket. Copy the Access Key ID and Secret Access Key. The secret is
   shown only once.
4. **CORS**: uploads go straight from the browser to R2 via a presigned PUT, so
   under *Bucket → Settings → CORS policy*:
   ```json
   [{"AllowedOrigins":["http://localhost:5173","http://localhost:5174","http://localhost:5177"],
     "AllowedMethods":["PUT","GET"],"AllowedHeaders":["*"],"MaxAgeSeconds":3600}]
   ```
   Add your deployed client origins later.
5. In `.env`:
   ```
   R2_ACCOUNT_ID=<the account id in the dashboard URL / R2 overview>
   R2_ACCESS_KEY_ID=...
   R2_SECRET_ACCESS_KEY=...
   R2_BUCKET_NAME=sujula-images
   R2_PUBLIC_URL=https://pub-xxxx.r2.dev
   ```

## 6. Payments: Stripe test mode (mock by default)

The mock gateway stays on under `staging` (`PAYMENT_MOCK_ENABLED=true`). Card
orders are marked PAID with no money moving, which is enough to test
fulfilment, custody and payouts. Switch to Stripe when you want to test the
real checkout:

1. Sign up at stripe.com. You start in **test mode**.
2. *Developers → API keys*: copy the `sk_test_...` secret key.
3. Webhooks:
   - **Locally**: install the Stripe CLI, then
     `stripe listen --forward-to localhost:8080/webhooks/psp/stripe`.
     It prints a `whsec_...` secret.
   - **Deployed**: *Developers → Webhooks → Add endpoint*
     `https://<your-api>/webhooks/psp/stripe`, with events
     `checkout.session.completed`, `checkout.session.async_payment_succeeded`,
     `checkout.session.async_payment_failed`, `checkout.session.expired` and
     `charge.refunded`. Copy its signing secret.
4. In `.env`:
   ```
   PAYMENT_MOCK_ENABLED=false
   STRIPE_SECRET_KEY=sk_test_...
   STRIPE_WEBHOOK_SECRET=whsec_...
   ```
5. Pay with `4242 4242 4242 4242`, any future expiry, any CVC. Use
   `4000 0000 0000 9995` to see a decline.

How it behaves:

- The buyer is sent to Stripe's hosted page. Card numbers never reach this
  server.
- The buyer is charged in the **payment's currency**, which is their display
  currency (C2). Amounts are converted to minor units at `CurrencyCatalogue`'s
  scale, so XOF goes over with no decimals.
- The order becomes PAID **only when the signed webhook arrives**. Coming back
  to the success URL does nothing, because anybody can type a URL. If the order
  stays unpaid, check the `stripe listen` window and the `webhook_events` table.
- Refunds from the admin go through Stripe's refund API. A refund made in the
  Stripe dashboard is recorded as evidence but **not applied**. Refund through
  the platform so the ledger rows are written (C3).
- If both the mock and Stripe are configured, only the mock is used.

## 7. Secrets you generate (no sign-up)

```
JWT_SECRET=$(openssl rand -base64 48)                   # else sessions die on every restart
FIELD_ENCRYPTION_KEY=$(head -c 32 /dev/urandom | base64) # else vendor bank details cannot be saved
INVOICE_SIGNING_SECRET=$(openssl rand -hex 32)           # else invoice links die on restart
BOOTSTRAP_ADMIN_EMAIL=you@gmail.com
BOOTSTRAP_ADMIN_PASSWORD=<something long>                # else one is generated and printed in the log
```

Keep `FIELD_ENCRYPTION_KEY` once data exists. Changing it makes stored bank
details unreadable.

## 8. Not required, but worth knowing about

| Service | What happens without it | Free option |
|---|---|---|
| **Google Geocoding** (`GOOGLE_GEOCODING_API_KEY`) | addresses save with `NONE` confidence and delivery pricing uses the fallback distances, so fees are rougher | Google Maps Platform free monthly usage; restrict the key to the Geocoding API |
| **Google / Apple sign-in** (`sujula.auth.oauth.*`) | email and password sign-in only | both free |
| **MaxMind GeoLite2** | display currency is guessed from locale, then the base currency, instead of the buyer's IP | free account and database download |
| **Public HTTPS host for the API** | Stripe webhooks need the CLI tunnel; phones on another network can't reach the API | Render, Railway, Fly.io free or low tiers |
| **Wave / Orange Money** | not integrated. The mobile-money wallets vendors in The Gambia and Senegal actually use for payouts | business accounts only |
