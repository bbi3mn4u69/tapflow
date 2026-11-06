# Testing strategy

DEMO / NO REAL MONEY.

## Bootstrap coverage

- Backend: JUnit 5 with Ktor `testApplication`; checks liveness, phase reporting and unavailable persistence readiness.
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

## Test-first workflow

Start a behavior change with a failing test, verify its failure, implement the smallest change, and run the affected checks. Tests must assert observable behavior. Keep secrets out of fixtures and test output. Investigate a failing boundary before changing unrelated configuration.

## Later phase gates

Real PostgreSQL Testcontainers tests must prove wallet, refund, idempotency and tenant-isolation invariants. Mock repositories or H2 cannot establish transaction correctness. API contract validation expands with the complete v1 schema in phase 1.

Android protocol/signature tests cover canonical bytes and malformed APDUs. Keystore instrumentation establishes API behavior on the tested runtime, not hardware-backed key security on every device. Physical NFC testing remains separate, with device/OS combinations recorded.

Browser E2E coverage will exercise sign-in, payment lookup, owner refund and updated totals against the real backend. Do not count a fabricated dashboard or a simulated transport as a physical NFC acceptance test.
