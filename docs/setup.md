# Development setup

DEMO / NO REAL MONEY. Software through phase 6 includes sign-in, card enrollment, terminal pairing, payments, merchant reporting and refunds. Physical NFC and production deployment acceptance remain pending.

## Toolchain

- JDK 21 for Gradle and backend execution.
- Gradle 9.6.0 through the committed, checksum-verified wrapper.
- Android Gradle Plugin 9.5.1 with built-in Kotlin; Kotlin/Compose compiler 2.4.21.
- Android SDK Platform 37.2, target API 37, minimum API 28, Build Tools 36.0.0.
- Current Android Studio supporting AGP 9.5. Android Studio 2024.1 must be updated before IDE sync.
- Node 24 LTS and pnpm 9.12.3. Node 25 is also accepted locally.
- Docker for PostgreSQL integration tests, even when the runtime database is hosted on Railway.

On macOS with Homebrew:

```sh
brew install openjdk@21
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
android sdk install platforms/android-37.2 build-tools/36.0.0
```

Set Gradle JDK to JDK 21 in Android Studio. SDK paths belong in environment variables or an ignored `local.properties`.

## Runtime database: Railway

Preserve an existing local configuration:

```sh
test -f .env || cp .env.example .env
```

The development configuration already points at the user-provided Railway database. Its credentials live only in the ignored `.env`, with owner-only permissions. For another checkout, fill these keys locally:

```dotenv
DATABASE_URL=jdbc:postgresql://YOUR_HOST:YOUR_PORT/YOUR_DATABASE?sslmode=require
DATABASE_USER=YOUR_DATABASE_USER
DATABASE_PASSWORD=YOUR_DATABASE_PASSWORD
DATABASE_POOL_SIZE=5
DEMO_MODE=true
APP_ENV=development
```

Keep username/password outside the JDBC URL so logs cannot accidentally print them as part of the address. Remote connections require TLS (`require`, `verify-ca` or `verify-full`); use provider certificates with verification when available. `require` encrypts the connection but does not verify the server certificate.

Gradle's `run` and `seedDemo` tasks read plain `KEY=value` entries from the root `.env` using UTF-8 Java Properties syntax. Exported environment variables take precedence. Do not put shell commands, `export` prefixes or wrapping shell quotes in that file. Deployed distributions use environment variables directly and do not package `.env`.

On startup Flyway creates and migrates the dedicated `tapflow` schema. No other schema is used for application tables. Flyway is the only schema migration owner, and its clean operation is disabled. Do not edit an already-applied migration; add a new versioned file. Startup fails on missing credentials or migration errors.

## API and merchant workspace

From the root:

```sh
./gradlew :services:payment-api:run
```

From `apps/web` in another terminal:

```sh
corepack enable
corepack pnpm install --frozen-lockfile
corepack pnpm dev
```

If Corepack is unavailable, use `npx --yes pnpm@9.12.3` instead of `corepack pnpm`. Restart the API after rebuilding Kotlin classes; server autoreload is disabled. Reload the portal after changing shared session-client classes so Fast Refresh does not retain an older client instance.

Vite proxies `/api` to the API on port 8080. `API_HOST`/`API_PORT` configure the server. Endpoints implemented now:

- `GET /health/live`: 200 while the process is running, independent of database health.
- `GET /health/ready`: 200 only when the database responds and its migration history matches the versions/checksums validated at startup; otherwise 503.
- `GET /api/v1/status`: implementation phase `merchant-operations`.
- Web and mobile login/refresh/logout, `GET /api/v1/me`, and merchant metadata `GET /api/v1/merchants/{merchantId}`.
- Customer card enrollment/disable, wallet/activity and terminal pairing/list/disable.
- Payment create/authorise/status/cancel and merchant payment list/detail.
- Owner refunds/audit and member dashboard reporting.
- Optional customer registration only when its explicit environment flag is enabled.
- `GET /api/v1/openapi.yaml`: complete planned v1 contract, with implementation phase annotations.

`X-Request-ID` is validated as a UUID or replaced, returned in the response and attached to safe JSON error envelopes and structured logs. Logs omit raw URLs, request bodies and credentials. The remaining API operations return the normal 404 envelope until their implementation phase.

## Authentication configuration

The API now also requires `JWT_SIGNING_SECRET`, `JWT_ISSUER`, `JWT_AUDIENCE`, `APP_PUBLIC_URL` and `ALLOWED_ORIGINS`. Generate an independent random JWT secret (at least 32 bytes) directly into private configuration, without printing it or putting it in command arguments. Preserve an existing private key. Access tokens last exactly 900 seconds; refresh families expire after `REFRESH_TOKEN_TTL_DAYS` (default 14, maximum 30).

For local web development, use `APP_PUBLIC_URL=http://localhost:5173`, `ALLOWED_ORIGINS=http://localhost:5173`, and open [localhost:5173](http://localhost:5173/login). If you also use `127.0.0.1`, explicitly add that exact origin to the comma-separated allowlist. The public URL must itself be an allowed origin. Do not add a wildcard.

The web app sends `X-TapFlow-CSRF: 1`; cookie mutations also require allowed Origin/Referer. Direct HTTP tools must supply these headers for web endpoints. Android uses the separate mobile endpoints. Cookies are Secure on HTTPS; only loopback HTTP development relaxes that flag. Browser access tokens stay in memory and reload restores via the HttpOnly cookie. See [security behavior](security.md).

## Seed synthetic demo accounts

Set `SEED_OWNER_PASSWORD`, `SEED_OPERATOR_PASSWORD` and `SEED_CUSTOMER_PASSWORD` to distinct values of 12–128 characters in the ignored `.env` or process environment. Never reuse the database password. Then run:

```sh
./infra/scripts/seed-demo.sh
```

This creates one `TapFlow Demo Store`, owner/operator merchant accounts, one customer account, memberships and a seed audit event. The synthetic emails are `owner@example.test`, `operator@example.test` and `customer@example.test`. Passwords are Argon2id hashes; plaintext passwords are never printed. Reruns preserve existing passwords and reject conflicting account roles. Seeding is restricted to development/test mode.

Seeding creates no virtual card, private key, wallet or funds. Enrollment in the Android Card app creates the demo wallet once. Sign in with the corresponding private `SEED_*_PASSWORD` from your local configuration. Seed passwords are not changed by a rerun.

## Optional local PostgreSQL fallback

`LOCAL_DATABASE_PASSWORD` is separate from Railway credentials. The existing local volume is preserved.

```sh
docker compose --env-file .env -f infra/compose/dev.yml config --quiet
docker compose --env-file .env -f infra/compose/dev.yml up -d --wait
```

To use this fallback as the runtime database, set the root `.env` JDBC URL to `jdbc:postgresql://localhost:5432/tapflow`, user to `tapflow`, and database password to the local container's existing password. Changing `POSTGRES_PASSWORD` does not reset an initialized volume's password. Use `stop` to stop containers without deleting data.

Automated tests do not use either runtime database. Testcontainers starts fresh PostgreSQL instances with random mapped ports and removes only its own test containers.

## Android

Open the repository root in Android Studio, sync, then run either app module. Both APKs can coexist on one emulator. Card requires a CUSTOMER account; POS requires a MERCHANT account. The apps restore encrypted refresh sessions and provide server-side logout. Card enrollment, wallet/activity and disabling are available. HCE software is implemented; physical NFC acceptance, terminal pairing and POS reader remain pending.

Debug defaults to `http://10.0.2.2:8080/api/v1`, the emulator's route to the host API. HTTP is allowed only for that emulator host in the debug network-security file. To use a reachable HTTPS endpoint, supply `-PtapflowApiBaseUrl=https://YOUR_HOST/api/v1` to Gradle (or its Gradle configuration in Android Studio).

Release uses `-PtapflowReleaseApiBaseUrl=https://YOUR_HOST/api/v1` and rejects HTTP; the default reserved `.invalid` hostname deliberately requires deployment configuration. APK signing/distribution remains later work. Physical phones need the same reachable HTTPS backend and still require two compatible NFC phones for eventual tap acceptance.

To verify authentication with one running emulator, Python 3, JDK 21 and Docker:

```sh
python3 infra/scripts/test-auth-emulator.py
```

This builds an API distribution, starts a new PostgreSQL container and loopback API on random ports, seeds instrumentation-only accounts, then runs both live Android sign-in suites plus device enrollment, wallet restoration, disabling and the HCE engine harness. It does not load `.env` or use Railway. It cleans up its own API/container and anonymous test volume. Use a dedicated AVD: the command installs both debug apps and exercises their auth storage. The test APKs target a temporary port; run the normal Android Studio/Gradle debug build again to restore the normal API URL.

For UI/Keystore checks alone against the default debug URL, use the connected test commands in the README. Live tests skip unless `tapflowLiveAuth=true` is set by the disposable fixture script. Never set that flag against a runtime database with the fixture passwords.


## Customer card and wallet

Sign in to Android Card as `customer@example.test` using its private seed password.
Choose a label and enroll once. The device retains its AndroidKeyStore key and a
pending enrollment ID; retries reuse both. Reopening/signing in loads the existing
wallet rather than funding it again. The default demo balance is A$100.00.

`DEMO_INITIAL_BALANCE_MINOR` is integer cents (1–1,000,000). The new
`DEMO_CARD_ENROLLMENT_ENABLED` flag defaults true only in development/test;
staging/production must explicitly enable it. Changing initial funding affects
new cards only. Fresh keys are limited to three/hour/customer and 100 cards per
account; request attempts including retries are limited to 60/minute/customer.

An emulator can enroll and inspect its wallet without NFC hardware. The UI reports
missing NFC/HCE support and cannot enable physical tapping readiness there. On a
supported phone, turn on NFC, unlock and explicitly enable demo tapping. A login
or refresh pauses this session's consent until enabled again. Sign-out/pause also
stop local tapping. Disable is server authoritative; it never resets the balance.

If a stored key is lost, disable the old card, then choose replacement setup. No
private key recovery, wallet transfer or automatic replacement is performed.
See [protocol](nfc-protocol.md) and [testing](testing.md) for exact verification
scope. The physical NFC gate remains pending; POS software is implemented below.

## Terminal pairing and POS — phase 4

In the portal choose **Terminals**. Owners can create a one-time code or confirm
terminal disabling; operators can inspect status only. Keep the reveal dialog
open while pairing. It counts down from server expiry, lasts exactly ten minutes,
and clears the raw code from the UI when closed. A failed reveal retries its saved
idempotency key, without extending expiry. Codes never enter browser storage or
the query cache. Pairing attempts are limited to ten/minute/IP and user.

Sign in to Android POS with a member of that merchant, enter the 13-character code
and a terminal name, then pair. The app persists its installation ID and encrypts
pending code/request data with AndroidKeyStore AES-GCM before sending HTTP. Retry
reuses that setup after recreation or response loss. **Clear pending pairing**
removes only local unfinished setup; it never unregisters a server terminal.
A fresh owner code can reconcile the same active installation and label.

This build supports one merchant pairing per POS installation. Disabled or foreign
installations cannot be reactivated/reassigned by another code. For deliberate
replacement, an owner can disable the old terminal and authorize a newly installed
or cleared POS app with a fresh code; clearing app storage also signs the operator
out. Resolve any pending sale and reconcile its server history before clearing
storage; clearing it loses the recovery keys. The old terminal/history remain. Each merchant is limited to 100 terminal
records including disabled records; larger demo fleets need an explicit policy
change before lifting that bound.

Checkout validates integer AUD cents. **Charge** creates a real demo intent on the
server. Physical SELECT and the two-phone exchange remain pending by user choice.
The emulator can create, inspect and cancel intents; it cannot perform an NFC tap.

Pairing-code reconstruction uses a domain-separated HMAC with the existing random
JWT signing secret. After key rotation a creation retry can return
PAIRING_CODE_UNAVAILABLE; close the dialog and create a new code/request. Previously
revealed codes still match their stored hash until consumed or expired. See
[ADR-010](adr/ADR-010-pairing-and-pos-reader.md) for the replay/storage design.

## Demo payments and recovery — phase 5

Enter an amount (A$0.01–A$10,000.00) and choose **Charge**. The API creates a
90-second intent. With two compatible NFC phones, enable tapping in Android Card,
keep both phones unlocked and hold them together. A proof is submitted to the
server; only its decision can display APPROVED or DECLINED. A$25 from a freshly
funded A$100 wallet leaves A$75. NFC availability is checked independently of HTTP.

POS saves the sale and request keys before making requests. When the connection
fails, use **Check payment status** for that same sale. **Retry saved submission**
first checks status and reuses the encrypted original proof and idempotency key.
**Cancel payment** is safe to repeat, but cannot undo an approved debit. A local
countdown never decides expiry. Choose **New sale** only after the server confirms
a final result. Activity recreation, backgrounding and sign-out retain the journal.
Never clear storage or reinstall to resolve an unknown payment outcome. If the
journal becomes unreadable, reconcile the terminal's server payment history before
replacement; a different operator account cannot overwrite an unfinished sale.

Merchant members can read `GET /api/v1/merchants/{merchantId}/payments` and
`.../payments/{paymentId}`. List supports status, a 1–100 limit and keyset cursor;
UTC dates are inclusive `from`/exclusive `to` with a maximum 366-day range (default
last 30 days). No raw signature is returned. Full portal reporting/refunds are
implemented in phase 6 below. The emulator fixture script uses a disposable database and test-only
Keystore signer to verify an interrupted payment without claiming NFC acceptance.
See [ADR-011](adr/ADR-011-payments-and-recovery.md).

## Merchant reports and refunds — phase 6

Use **Overview**, **Transactions**, **Terminals**, **Audit log** (owner) and
**Settings**. Overview shows approved count, gross, refunded and net demo value,
daily approved counts with an accessible data table, and recent authorisations.
Calendar filters are UTC with a maximum 366-day range. Receipt/event times are
shown in Melbourne. Overview groups by authorisation time and includes all later
refunds; Transactions filters by creation time. Shareable filter/cursor values live
in the URL. Read failures show a retry; no static payments or fabricated totals
are supplied by the portal.

Open a transaction to inspect its demo receipt and refund history. Owners can
choose **Refund**, enter an exact AUD amount up to the remaining refundable value,
select a reason and confirm. For A$25, refund A$10 then A$15: the status becomes
PARTIAL then FULL and the original demo wallet returns to A$100. Operators can
view receipts but cannot submit refunds or read audit events; the API enforces it.

If a refund outcome is unknown, use **Retry saved refund**. Reloading this same tab
retains the original amount/reason/key in account-scoped sessionStorage. Do not
close the tab, clear storage or issue another refund elsewhere until history is
reconciled. A permission error during recovery does not prove the earlier refund
failed. Restore owner access and recover the saved request. Never clear an unknown
request to make the refund button available.

To run the full browser gate, first stop any API using `build/install` JARs (the
fixture rebuilds that distribution), then from the repository root:

```sh
npx --yes pnpm@9.12.3 --dir apps/web install --frozen-lockfile
npx --yes pnpm@9.12.3 --dir apps/web exec playwright install chromium
python3 infra/scripts/test-web.py
```

Set JAVA_HOME to JDK 21 and ANDROID_HOME as above; Docker must be running. CI uses
`playwright install --with-deps chromium`. The launcher starts its own loopback
API/Vite ports, random-secret PostgreSQL container and synthetic accounts. It
never loads `.env` or mutates Railway. Do not point the Playwright runner at the
runtime database. Restart the normal API after fixture runs. The emulator runner
uses the same disposable lifecycle and still requires exactly one emulator.

## Container deployment — phase 7

The opt-in development `stack` profile builds PostgreSQL with TLS, the API and
static web; default development Compose still starts only the database. Stop the
native API before binding port 8080. See [operations](operations.md) for commands,
secret-file configuration, Railway service settings and the manual deployment
workflow. The existing native `.env` and database volumes are preserved.

To verify the hardened stack without touching runtime data:

```sh
docker build -t tapflow-db:phase7 -f infra/postgres/Dockerfile .
docker build -t tapflow-api:phase7 -f services/payment-api/Dockerfile .
docker build -t tapflow-web:phase7 -f apps/web/Dockerfile .
python3 -m unittest discover -s infra/tests -v
python3 infra/scripts/test-deployment.py
```

Install age 1.3.2 and the existing Playwright Chromium prerequisite first. The
runner uses random loopback ports, a fixture-only CA, synthetic accounts and its
own disposable bind storage. It verifies real payment/refund browser flows,
non-root services, encrypted restore, wallet/ledger consistency and restart.
It never reads `.env`, trusts its CA globally, or removes existing named volumes.
