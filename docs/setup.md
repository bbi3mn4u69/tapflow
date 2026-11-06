# Development setup

DEMO / NO REAL MONEY. This document covers the bootstrap milestone only.

## Toolchain

- JDK 21 for Gradle and backend execution.
- Gradle 9.6.0 through the committed, checksum-verified wrapper.
- Android Gradle Plugin 9.5.1 with built-in Kotlin; Kotlin/Compose compiler 2.4.21.
- Android SDK Platform 37.2, target API 37, minimum API 28, Build Tools 36.0.0.
- A current Android Studio version supporting AGP 9.5. Android Studio 2024.1 is too old to sync this toolchain; update it before using IDE builds.
- Node 24 LTS and pnpm 9.12.3. Node 25 is also accepted for local development; CI uses 24.

On macOS with Homebrew:

```sh
brew install openjdk@21
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Install SDK components through Android Studio SDK Manager or the Android CLI:

```sh
android sdk install platforms/android-37.2 build-tools/36.0.0
```

The platform package includes a minor version: `platforms/android-37` is not its package name. Alternatively use `sdkmanager 'platforms;android-37.2' 'build-tools;36.0.0'`.

Set Gradle JDK to JDK 21 in Android Studio's Gradle settings. SDK paths belong in environment variables or an ignored `local.properties`, never in the committed build configuration.

## API and workspace

From the repository root:

```sh
./gradlew :services:payment-api:run
```

From `apps/web` in another terminal:

```sh
corepack enable
corepack pnpm install --frozen-lockfile
corepack pnpm dev
```

If Corepack is unavailable, use `npx --yes pnpm@9.12.3` instead of `corepack pnpm`. The lockfile must stay unchanged during a frozen installation.

The API binds to `127.0.0.1:8080` by default. Vite proxies `/api` to that address. `GET /api/v1/status` identifies the implementation phase. `GET /health/live` is process liveness; readiness remains 503 until phase 1 supplies real migration/database checks.

## PostgreSQL development service

```sh
cp .env.example .env
# Set DATABASE_PASSWORD to your own disposable local password.
docker compose --env-file .env -f infra/compose/dev.yml config --quiet
docker compose --env-file .env -f infra/compose/dev.yml up -d --wait
```

The database is pinned to PostgreSQL 17.11 with an image digest, uses a named volume, and publishes port 5432 on loopback only. The API has no database connection yet. Do not treat a healthy database container as API readiness.

Use `docker compose --env-file .env -f infra/compose/dev.yml stop` to stop it without deleting data. Do not delete the volume to troubleshoot startup.

## Emulator and physical devices

Run either app module from Android Studio. Both APKs have distinct application IDs and can coexist on one emulator. The screens identify this as a starter build and do not claim enrollment, terminal pairing or a successful NFC tap.

When mobile API calls are added, emulator builds will use `http://10.0.2.2:8080/api/v1` to reach the host. Physical devices need a reachable LAN or HTTPS endpoint and the same API/database. Release builds disallow cleartext traffic; any HTTP development allowance must be debug-only and scoped to the intended host.

Emulators cover screens, lifecycle, backend integration and testable protocol logic. They do not validate phone-to-phone NFC, OS AID routing, device unlock behavior or antenna placement. The hardware acceptance gate requires two NFC phones.
