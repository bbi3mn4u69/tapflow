# Development setup

DEMO / NO REAL MONEY. Phase 2 supplies sign-in, refresh sessions and merchant access. NFC, card enrollment and payments follow later.

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
- `GET /api/v1/status`: implementation phase `authentication`.
- Web and mobile login/refresh/logout, `GET /api/v1/me`, and merchant metadata `GET /api/v1/merchants/{merchantId}`.
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

Seeding creates no virtual card, private key, wallet or funds. Card enrollment on the Android device will create the demo wallet in phase 3. Sign in with the corresponding private `SEED_*_PASSWORD` from your local configuration. Seed passwords are not changed by a rerun.

## Optional local PostgreSQL fallback

`LOCAL_DATABASE_PASSWORD` is separate from Railway credentials. The existing local volume is preserved.

```sh
docker compose --env-file .env -f infra/compose/dev.yml config --quiet
docker compose --env-file .env -f infra/compose/dev.yml up -d --wait
```

To use this fallback as the runtime database, set the root `.env` JDBC URL to `jdbc:postgresql://localhost:5432/tapflow`, user to `tapflow`, and database password to the local container's existing password. Changing `POSTGRES_PASSWORD` does not reset an initialized volume's password. Use `stop` to stop containers without deleting data.

Automated tests do not use either runtime database. Testcontainers starts fresh PostgreSQL instances with random mapped ports and removes only its own test containers.

## Android

Open the repository root in Android Studio, sync, then run either app module. Both APKs can coexist on one emulator. Card requires a CUSTOMER account; POS requires a MERCHANT account. The apps restore encrypted refresh sessions and provide server-side logout. Card enrollment, terminal pairing and NFC are not enabled yet.

Debug defaults to `http://10.0.2.2:8080/api/v1`, the emulator's route to the host API. HTTP is allowed only for that emulator host in the debug network-security file. To use a reachable HTTPS endpoint, supply `-PtapflowApiBaseUrl=https://YOUR_HOST/api/v1` to Gradle (or its Gradle configuration in Android Studio).

Release uses `-PtapflowReleaseApiBaseUrl=https://YOUR_HOST/api/v1` and rejects HTTP; the default reserved `.invalid` hostname deliberately requires deployment configuration. APK signing/distribution remains later work. Physical phones need the same reachable HTTPS backend and still require two compatible NFC phones for eventual tap acceptance.

To verify authentication with one running emulator, Python 3, JDK 21 and Docker:

```sh
python3 infra/scripts/test-auth-emulator.py
```

This builds an API distribution, starts a new PostgreSQL container and loopback API on random ports, seeds instrumentation-only accounts, then runs both live Android sign-in suites. It does not load `.env` or use Railway. It cleans up its own API/container and anonymous test volume. Use a dedicated AVD: the command installs both debug apps and exercises their auth storage. The test APKs target a temporary port; run the normal Android Studio/Gradle debug build again to restore the normal API URL.

For UI/Keystore checks alone against the default debug URL, use the connected test commands in the README. Live tests skip unless `tapflowLiveAuth=true` is set by the disposable fixture script. Never set that flag against a runtime database with the fixture passwords.
