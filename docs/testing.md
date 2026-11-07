# Testing strategy

DEMO / NO REAL MONEY.

## Bootstrap coverage

- Backend: JUnit 5 with Ktor `testApplication`, plus real PostgreSQL Testcontainers tests for migrations, constraints, readiness, transaction rollback and seed behavior.
- Portal: Vitest + Testing Library; checks live response parsing, HTTP failure, invalid demo metadata, visible demo notice and offline recovery UI.
- Android: Compose instrumentation tests on an emulator/device verify each app's identity and demo boundary. Activity layouts are not duplicated in JVM unit tests.
- Shared protocol: module is configured for JUnit 5. APDU implementation and behavioral tests begin in phase 3; no placeholder tests are counted as protocol coverage.
- CI: both APKs build, Android lint runs, JVM/web tests run, OpenAPI types regenerate without drift, and Compose configuration is checked without displaying resolved secrets.

Use the commands in the root README. `connectedDebugAndroidTest` needs a running emulator or connected physical phone. Run the two apps' instrumentation sequentially with `--no-parallel` when sharing one emulator. Instrumentation uses Android's JUnit 4 runner; JVM modules use JUnit 5. No DI framework or mocking library is required for the bootstrap.

## Verified bootstrap checks — 2026-11-07

- Gradle help, both debug APK builds and Android lint: passed.
- Backend: 3 tests passed; portal: 5 tests passed.
- Emulator: one Compose instrumentation test per app passed on `Pixel_Fold_API_36` (Android 16 / API 36), followed by visual inspection of both app screens.
- Portal: production build, strict typecheck, ESLint, formatting and generated OpenAPI types passed. Browser inspected at 1440px and 360px, with reduced motion and no console errors.
- PostgreSQL 17.11 development container reached healthy state; a direct SQL version query verified connectivity. API persistence is deliberately not implemented yet.
- Wrapper JAR and Gradle distribution checksums were verified.

Android lint has no errors. It reports newer-version notices and a third-party lifecycle lint registry referencing an unavailable Kotlin UAST API; that external custom check is skipped. These notices are not suppressed. The emulator initially booted slowly and showed a System UI ANR; after boot completion and dismissing the system dialog, both instrumentation suites and manual app inspection succeeded.

On the Pixel Fold emulator, `android screen capture` prepended a multiple-display warning to its PNG stream. Screenshots in `docs/screenshots/` were therefore captured with `adb exec-out screencap -d <observed-display-id> -p` and visually inspected.

No physical NFC, device Keystore, auth, transaction or production deployment acceptance is claimed by these checks.

## Verified phase 1 checks — 2026-11-07

- Backend: 21 tests passed. Disposable PostgreSQL 17.11 containers prove fresh migration, repeat migration, schema validation, amount/currency/tenant constraints, append-only ledger behavior, one funding row, readiness failure on history drift, rollback and idempotent Argon2id demo seeding.
- Railway PostgreSQL 18.6: TLS verified, both migrations applied, 13 application tables present, and live/readiness/status/contract endpoints returned 200 with the optional local database stopped.
- Railway seed verified: 3 users, 1 merchant, 2 memberships, 1 seed audit event, 0 virtual cards and 0 wallets. No passwords are printed or stored in source control.
- Portal: 5 tests passed; ESLint, strict typecheck, production build, formatting, OpenAPI validation and generated-type consistency passed.
- Tests never load the private runtime `.env`; Testcontainers databases are independent of Railway.
- No Android source behavior changed in phase 1. The emulator evidence above remains the bootstrap baseline, not a new NFC or authentication check.

## Verified phase 2 checks — 2026-11-08

- Backend: 42 tests passed on real disposable PostgreSQL. Auth coverage includes JWT signature/algorithm/issuer/audience/expiry, active users, absolute refresh expiry at PostgreSQL microsecond precision, hashed storage, atomic simultaneous refresh, replay-family revocation, isolated logout, cross-client token rejection, CSRF/cookie flags, strict inputs/body limits, account/IP/pairing throttles, registration gating, and tenant/owner guards.
- Both Android debug and unsigned release APKs build. App and shared-auth debug lint pass with no errors; upstream version/custom-check notices remain visible.
- Six Android instrumentation tests passed without skips on the separate `TapFlow_Auth_API_36` phone AVD (Android 16/API 36, 720×1600, 360dp). Tests include live customer/operator login, encrypted session restoration, logout, rejection of a merchant account in the customer app, UI identity/demo labels and Keystore ciphertext corruption recovery.
- `infra/scripts/test-auth-emulator.py` recreates the live mobile gate with a new PostgreSQL container, test-only users and loopback API. It overrides runtime settings, never reads `.env` and deletes only its own process/container/test volume. A single emulator is required; physical phones are excluded from this fixture script.
- Portal: 13 unit tests passed, covering login, session restoration/network failure, inaccessible merchant routes, cache clearing, concurrent refresh, failed/second-401 sign-out, stalled-request timeout and stale read rejection. OpenAPI validation, generated types, typecheck, lint, formatting and build are required before committing.
- Live browser inspection verified owner login, workspace metadata, reload restoration, logout and no token storage/readable refresh cookie. Layouts were inspected at 1440px and 360px with reduced motion, no horizontal overflow and visible keyboard focus. The default Railway runtime also passed readiness, seeded-owner sign-in, profile, refresh, logout and post-logout 401 checks without printing credentials. An unauthenticated browser restore returns an expected network 401; no application JavaScript exception was observed.
- The older Fold AVD repeatedly stalled in framework/app startup. A dedicated cold-booted phone AVD completed the same checks without an application workaround; the original AVD configuration/data was preserved. Android CLI captured the actual app screenshots under `docs/screenshots/phase2-*`.

The owner-only HTTP route in the auth tests is test-only and exercises the real `MerchantAccess.require(..., owner=true)` transaction guard. Pairing/refund handlers and their financial effects remain later gates. Emulator Keystore behavior does not establish physical hardware key protection or NFC interoperability.

## Test-first workflow

Start a behavior change with a failing test, verify its failure, implement the smallest change, and run the affected checks. Tests must assert observable behavior. Keep secrets out of fixtures and test output. Investigate a failing boundary before changing unrelated configuration.

## Later phase gates

Later phases must extend real PostgreSQL Testcontainers coverage to transactional wallet, refund, idempotency and tenant-authorization invariants. Mock repositories or H2 cannot establish transaction correctness. Phase 1 constraint tests and API contract validation do not establish those later service behaviors.

Android protocol/signature tests cover canonical bytes and malformed APDUs. Keystore instrumentation establishes API behavior on the tested runtime, not hardware-backed key security on every device. Physical NFC testing remains separate, with device/OS combinations recorded.

Browser E2E coverage will exercise sign-in, payment lookup, owner refund and updated totals against the real backend. Do not count a fabricated dashboard or a simulated transport as a physical NFC acceptance test.
