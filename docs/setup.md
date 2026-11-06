# Development setup

DEMO / NO REAL MONEY. Phase 1 supplies the database and API foundation; authentication and payments follow later.

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

If Corepack is unavailable, use `npx --yes pnpm@9.12.3` instead of `corepack pnpm`.

Vite proxies `/api` to the API on port 8080. `API_HOST`/`API_PORT` configure the server. Endpoints implemented now:

- `GET /health/live`: 200 while the process is running, independent of database health.
- `GET /health/ready`: 200 only when the database responds and its migration history matches the versions/checksums validated at startup; otherwise 503.
- `GET /api/v1/status`: implementation phase `foundation`.
- `GET /api/v1/openapi.yaml`: complete planned v1 contract, with implementation phase annotations.

`X-Request-ID` is validated as a UUID or replaced, returned in the response and attached to safe JSON error envelopes and structured logs. Logs omit raw URLs, request bodies and credentials. The remaining API operations return the normal 404 envelope until their implementation phase.

## Seed synthetic demo accounts

Set `SEED_OWNER_PASSWORD`, `SEED_OPERATOR_PASSWORD` and `SEED_CUSTOMER_PASSWORD` to distinct values of 12–128 characters in the ignored `.env` or process environment. Never reuse the database password. Then run:

```sh
./infra/scripts/seed-demo.sh
```

This creates one `TapFlow Demo Store`, owner/operator merchant accounts, one customer account, memberships and a seed audit event. The synthetic emails are `owner@example.test`, `operator@example.test` and `customer@example.test`. Passwords are Argon2id hashes; plaintext passwords are never printed. Reruns preserve existing passwords and reject conflicting account roles. Seeding is restricted to development/test mode.

Seeding creates no virtual card, private key, wallet or funds. Card enrollment on the Android device will create the demo wallet in phase 3. No login endpoint is implemented in phase 1.

## Optional local PostgreSQL fallback

`LOCAL_DATABASE_PASSWORD` is separate from Railway credentials. The existing local volume is preserved.

```sh
docker compose --env-file .env -f infra/compose/dev.yml config --quiet
docker compose --env-file .env -f infra/compose/dev.yml up -d --wait
```

To use this fallback as the runtime database, set the root `.env` JDBC URL to `jdbc:postgresql://localhost:5432/tapflow`, user to `tapflow`, and database password to the local container's existing password. Changing `POSTGRES_PASSWORD` does not reset an initialized volume's password. Use `stop` to stop containers without deleting data.

Automated tests do not use either runtime database. Testcontainers starts fresh PostgreSQL instances with random mapped ports and removes only its own test containers.

## Android

Open the repository root in Android Studio, sync, then run either app module. Both APKs can coexist on one emulator. The screens identify the starter build and do not claim enrollment, pairing or an NFC tap.

When mobile API calls are added, emulators reach the host at `http://10.0.2.2:8080/api/v1`; physical phones need a reachable LAN/HTTPS endpoint. Release apps disallow cleartext traffic. Physical NFC acceptance still requires two compatible phones.
