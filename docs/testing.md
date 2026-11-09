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

## Phase 3 software checks — 2026-11-09

- Protocol: 7 JVM tests compare independent fixed SELECT/challenge/canonical/proof
  vectors, strict APDU/DER validation, signed-field tampering and stale generations.
- Backend: 55 tests pass on disposable PostgreSQL 17.11, including 13 new card/
  key/config tests. Enrollment covers exact replay, changed-payload conflict,
  concurrent retries, same-SPKI reconciliation, customer/role isolation, repeated
  disabling, invalid curves/encoding, hourly/account caps, environment gating,
  cursor bounds and rollback after a failure following wallet/ledger insertion.
  The server verifies a fixed signed challenge using the shared verifier.
- Android: 10 instrumentation tests pass without skips on `TapFlow_Auth_API_36`
  (8 Card, 2 POS), using `python3 infra/scripts/test-auth-emulator.py`. Added checks
  cover actual non-exportable Keystore P-256 signing, enrollment, A$100.00 wallet,
  activity recreation/restoration, refresh, disabling and the always-visible demo notice; the actual asynchronous
  HCE engine signs/verifies a challenge and rejects stale/signed-out requests.
  Installed HCE metadata is checked for its system permission, unlock and custom AID.
- Both debug and unsigned release app builds and Card/POS/shared-auth debug lint
  pass with zero errors. Existing upstream notices remain; lint also notes that
  the explicit screen-on XML attribute is recognized from API 31 (older HCE
  versions require screen-on by default).
- Portal: 13 tests plus generated types, contract lint, typecheck, ESLint, formatting
  and production build pass with the phase-3 status contract.
- Runtime JARs and debug/release APKs are checked to exclude the test-only fixture
  private key. Changed files are scanned against the ignored runtime credentials.
  No enrollment/funding fixture is sent to Railway.

The [wallet screenshot](screenshots/phase3-wallet-api36.png) was captured from the live disposable enrollment test and visually inspected. The demo header remains visible while scrolling.

The HCE engine harness runs with fresh emulator-generated test keys and an injected
unlock predicate. It does **not** prove system NFC routing, radio timings, device
unlock enforcement on physical hardware, hardware key protection, SELECT on a
physical phone or two-phone ISO-DEP interoperability. Those acceptance checks are
pending by the user's emulator-only instruction. The phase 5 authorizer/POS software is implemented below; a valid signature alone
is not approval or a successful payment.

## Phase 4 software checks — 2026-11-09

- Backend: 67 tests on disposable PostgreSQL, including 12 terminal tests. New
  coverage checks owner/operator/tenant boundaries, code entropy format and exact
  ten-minute expiry, plaintext-free replay records, concurrent identical pairing,
  consumed/unknown/expired codes, active-installation reconciliation, disabled
  installation rejection, account cap, keyset pagination ties, audit-failure
  rollback and signing-key rotation. Expiry is checked again before consumption,
  including a clock crossing the boundary during pairing.
- Protocol: 12 JVM tests. The reader sends exactly SELECT then SIGN against the
  independent signed fixture, rejects incompatible SELECT/malformed proof and
  guards duplicate claims, same-challenge retries, expiry and stale completion.
- Android: 13 instrumentation tests without skips (8 Card, 5 POS) on the API 36
  phone AVD. POS pairs against the disposable API using a synthetic owner code,
  restores after activity recreation, validates A$25.00 exactly, observes owner
  disabling, checks encrypted pending setup/account isolation and cancellation
  while terminal status is still loading. Actual RF exchange is not simulated.
- Both debug and unsigned release APK builds and Card/POS/shared-auth debug lint
  pass. Upstream dependency/lint notices remain documented rather than suppressed.
- Portal: 18 tests, contract/types, typecheck, ESLint, production build and formatting.
  Coverage includes owner reveal/close/focus restoration, operator read-only view,
  same-key retry after response loss, refreshed countdown time and authenticated
  POST retry preserving the original body/idempotency key.
- Native [POS screenshot](screenshots/phase4-pos-api36.png) was captured from the
  live disposable fixture and visually inspected. The portal/native layouts retain
  explicit DEMO / NO REAL MONEY and disabled payment controls.
- Portal visual checks used a temporary QA tab with synthetic fetch responses,
  not Railway pairing data. Native dialogs were inspected at 1440×1000 and
  360×800 with reduced motion: centered, readable, visible demo notice, no horizontal
  overflow, close clears the code and restores trigger focus, no localStorage
  entries or application JavaScript errors. The temporary tab was closed afterward.
  These layout checks are not claimed as a full browser/API E2E test.

The phase 3 physical SELECT gate and phase 4 two-phone exchange/vendor constraints
remain pending by user choice. Reader timeout/real tag loss, OEM routing, physical
Keystore properties and hardware unlock behavior need two NFC-capable phones.
The debug connection test only parses a card response; server signature verification
and payment decisions arrive in phase 5. No debit, payment success or full NFC
acceptance is claimed. Android Studio sync remains untested with the older IDE.

## Phase 5 software checks — 2026-11-09

- Backend: 85 tests pass on disposable PostgreSQL 17.11, including 18 payment tests.
  A$25 debits A$100 to A$75 with one receipt/ledger row. Twenty same-key concurrent
  requests and twenty different-key retries return the same decision and one debit.
  Different terminals competing for one balance cannot overdraw it. Tests cover
  proof replay/tampered signed fields, blocked cards, insufficient funds, pending
  terminal disabling, tenant/role/input boundaries, creation races, cancellation
  races, persisted/rechecked expiry, bounded listing and full rollback after a
  failure following the debit. Existing migration/auth/card/pairing tests pass.
- Protocol: 12 JVM tests pass. Both Android debug and unsigned release builds and
  Card/POS/shared-auth debug lint pass without errors; upstream notices remain.
- Android: 15 instrumentation tests pass without skips (8 Card, 7 POS) on the
  API 36 `TapFlow_Auth_API_36` emulator. Encrypted journal checks cover stable keys,
  account isolation, unfinished-sale acknowledgement refusal and stale writes.
  The live payment test creates a fresh non-exportable Keystore signer, enrolls
  its public SPKI on disposable PostgreSQL and signs an actual server intent.
  The loopback proxy suppresses the committed authorisation response and two
  subsequent status responses; POS stays in recovery, then retrieves APPROVED.
  Wallet remains A$75 with exactly funding + debit activity. Recreation restores
  the final sale; a later cancelled sale leaves that balance unchanged.
- `python3 infra/scripts/test-auth-emulator.py` runs this isolated fixture. It never
  loads the runtime `.env`, targets physical phones or sends fixture enrollment/
  funding/payments to Railway. `python3 infra/scripts/payment_test_proxy.py` checks
  the fault proxy itself; this self-check also runs in CI.
- Portal: 18 tests, OpenAPI validation/type generation, ESLint, strict typecheck,
  formatting and production build pass with the phase-5 contract. Full reporting,
  refunds and browser payment E2E belong to phase 6.
- The [approval screenshot](screenshots/phase5-approved-api36.png) was captured
  from the disposable test and visually inspected at 360dp. Amount, authoritative
  decision, receipt, recovery action and fixed demo label remain readable. Native
  Material feedback/live regions follow the requested restrained design direction.

The payment signer is an instrumentation harness; it bypasses radio transport.
The existing HCE-engine and protocol tests are separate evidence. These checks
prove neither physical NFC routing/timings nor hardware-backed key protection on
phones. Two-phone NFC and Android Studio sync with the pinned AGP remain unverified.
