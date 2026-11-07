# TapFlow — Kotlin NFC Payment Simulator

**DEMO / NO REAL MONEY.** TapFlow is an educational closed-loop NFC simulator, not a real bank-card payment processor or a certified payment product.

Two Android apps exchange proprietary APDUs; a Kotlin/Ktor API will verify device-held signatures and maintain a PostgreSQL demo wallet ledger. A React merchant workspace will expose payments, terminals, refunds and audit events.

## Current milestone

Phase 2: authentication and merchant access. The portal and both Android apps sign in against Ktor, restore rotating refresh sessions and sign out. Merchant membership and owner guards run in PostgreSQL queries. Browser tokens stay in memory; Android refresh tokens use Keystore-backed encryption. Runtime development uses Railway; tests use isolated PostgreSQL. Card enrollment, NFC, payments, terminal pairing and refunds are **not implemented yet**. See [progress](docs/progress.md) and the governing [specification](overall.md).

## Run locally

Requirements: JDK 21, a current Android Studio supporting AGP 9.5, Android SDK 37.2 + Build Tools 36.0.0, Node 24 LTS, pnpm 9.12.3, Docker Compose v2.

```sh
# Configure database and JWT credentials in the ignored .env (preserve an existing file).
test -f .env || cp .env.example .env
# Run the API; migrations finish before it accepts requests.
./gradlew :services:payment-api:run

# In another terminal, run the merchant workspace.
cd apps/web
corepack pnpm install --frozen-lockfile
corepack pnpm dev
```

Open [localhost:5173](http://localhost:5173/login) and sign in with your seeded owner/operator account. `/health/live` confirms the process is running; `/health/ready` returns 200 only when PostgreSQL is reachable and migrations validate, otherwise 503. [OpenAPI](http://localhost:8080/api/v1/openapi.yaml) describes the full planned v1; `x-implementation-phase` identifies which milestone implements each operation. System, authentication, identity and merchant metadata endpoints run in phase 2.

Set three distinct `SEED_*_PASSWORD` values locally, then run `./infra/scripts/seed-demo.sh` to create `owner@example.test`, `operator@example.test`, `customer@example.test` and one demo merchant. Seed reruns preserve existing passwords and create no device keys or funded wallets. Passwords never belong in Git or command-line arguments.

Open this repository's **root** in Android Studio, select JDK 21 for Gradle, sync, then run `apps.android-card` or `apps.android-pos` on an emulator. Both apps can be installed on one AVD. Card uses a customer account; POS uses a merchant account. Real NFC acceptance requires two compatible physical Android phones.

Detailed SDK installation, API connectivity and database instructions: [setup](docs/setup.md).

## Checks

```sh
./gradlew help :packages:protocol-kotlin:test :services:payment-api:test
./gradlew :apps:android-card:assembleDebug :apps:android-pos:assembleDebug
./gradlew :apps:android-card:lintDebug :apps:android-pos:lintDebug
./gradlew :apps:android-card:connectedDebugAndroidTest :apps:android-pos:connectedDebugAndroidTest --no-parallel

cd apps/web
corepack pnpm lint
corepack pnpm typecheck
corepack pnpm test
corepack pnpm build
corepack pnpm format:check
corepack pnpm api:lint
corepack pnpm api:generate
```

See [testing](docs/testing.md) for scope and hardware limitations. GitHub Actions runs JVM/web checks and builds both debug APKs.

## Repository layout

```text
apps/
  android-card/          Customer Compose app; HCE implementation follows in phase 3
  android-pos/           Merchant Compose app; reader implementation follows in phase 4
  web/                   React + TypeScript + Vite merchant workspace
packages/android-auth/    Shared native sign-in, encrypted storage and session UI
packages/protocol-kotlin/ Pure Kotlin/JVM module; APDU contract follows in phase 3
services/payment-api/    Kotlin/Ktor application and HTTP tests
contracts/               OpenAPI source; web types are generated from it
infra/compose/           Pinned PostgreSQL development service
infra/scripts/           Demo seed and isolated emulator auth verification
.github/workflows/       JVM/web and Android CI
docs/                    Setup, decisions, design, testing and progress
```

The backend runs on JDK 21. Shared protocol bytecode targets JVM 17 for Android consumption. Client secrets never belong in Vite configuration. Private HCE keys must never leave the customer phone.

Live authentication on an emulator, using a disposable API/database and instrumentation-only accounts:

```sh
python3 infra/scripts/test-auth-emulator.py
```

See [setup](docs/setup.md) for required JWT/origin settings, Android API URLs and the isolated emulator test workflow. [Security behavior](docs/security.md) records the implemented controls and remaining deployment work.
