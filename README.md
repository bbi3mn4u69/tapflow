# TapFlow — Kotlin NFC Payment Simulator

**DEMO / NO REAL MONEY.** TapFlow is an educational closed-loop NFC simulator, not a real bank-card payment processor or a certified payment product.

Two Android apps exchange proprietary APDUs; a Kotlin/Ktor API will verify device-held signatures and maintain a PostgreSQL demo wallet ledger. A React merchant workspace will expose payments, terminals, refunds and audit events.

## Current milestone

Phase 0: repository bootstrap. The merchant workspace reads the running API's implementation status. The Android modules are clearly labelled starter shells. Enrollment, authentication, NFC communication, wallet mutations and refunds are **not implemented yet**. See [progress](docs/progress.md) and the governing [specification](overall.md).

## Run locally

Requirements: JDK 21, a current Android Studio supporting AGP 9.5, Android SDK 37.2 + Build Tools 36.0.0, Node 24 LTS, pnpm 9.12.3, Docker Compose v2.

```sh
# Run the API (no database needed for the bootstrap endpoints).
./gradlew :services:payment-api:run

# In another terminal, run the merchant workspace.
cd apps/web
corepack pnpm install --frozen-lockfile
corepack pnpm dev
```

Open [localhost:5173](http://localhost:5173). `/health/live` confirms the process is running; `/health/ready` intentionally returns 503 until database integration is implemented. The UI displays an error with a retry action if the API is unavailable.

Open this repository's **root** in Android Studio, select JDK 21 for Gradle, sync, then run `apps.android-card` or `apps.android-pos` on an emulator. Both apps can be installed on one AVD for UI work. Real NFC acceptance requires two compatible physical Android phones.

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
corepack pnpm api:generate
```

See [testing](docs/testing.md) for scope and hardware limitations. GitHub Actions runs JVM/web checks and builds both debug APKs.

## Repository layout

```text
apps/
  android-card/          Customer Compose app; HCE implementation follows in phase 3
  android-pos/           Merchant Compose app; reader implementation follows in phase 4
  web/                   React + TypeScript + Vite merchant workspace
packages/protocol-kotlin/ Pure Kotlin/JVM module; APDU contract follows in phase 3
services/payment-api/    Kotlin/Ktor application and HTTP tests
contracts/               OpenAPI source; web types are generated from it
infra/compose/           Pinned PostgreSQL development service
.github/workflows/       JVM/web and Android CI
docs/                    Setup, decisions, design, testing and progress
```

The backend runs on JDK 21. Shared protocol bytecode targets JVM 17 for Android consumption. Client secrets never belong in Vite configuration. Private HCE keys must never leave the customer phone.
