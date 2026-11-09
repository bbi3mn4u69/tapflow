# Implementation progress

DEMO / NO REAL MONEY. The complete v1 is not implemented.

## Phase 0 — Bootstrap

Complete locally on 2026-11-07. Both Android modules compile, JVM/web checks pass, and both starter screens pass instrumentation on the API 36 Pixel Fold emulator. GitHub CI configuration is included; its remote execution is tracked separately from local results.

- Root Gradle project: Android Card, Android POS, protocol JVM library, Ktor API.
- Stable dependency pins, checked Gradle distribution checksum, web lockfile.
- React workspace with live API status and honest loading/error states.
- Compose starter screens and emulator instrumentation tests.
- OpenAPI bootstrap contract with generated TypeScript types.
- PostgreSQL development Compose, CI workflows, setup/testing guidance and skill map.

## Phase 1 — Database and API foundation

Complete locally on 2026-11-07. Railway PostgreSQL 18.6 is the runtime database; credentials stay in the ignored, owner-only `.env`. The optional local database is stopped and its volume is preserved.

- Flyway V001/V002 create the 13 application tables and indexes in the dedicated `tapflow` schema.
- Startup validates and migrates before serving HTTP. Readiness checks connectivity and validated migration history; liveness stays independent of database availability.
- Bounded connection pool, structured logs, request IDs and safe error envelopes.
- Idempotent demo seed creates one merchant, owner, operator and customer with private, locally supplied passwords; no card, key or wallet is seeded.
- Complete planned v1 OpenAPI contract with generated TypeScript types. At this milestone only the four system endpoints were implemented; phase annotations identify later operations.
- 21 backend tests, including fresh PostgreSQL Testcontainers migrations, constraints, readiness, rollback and seed reruns; 5 portal tests.

The phase gate passed locally: fresh migrations, real Railway readiness and OpenAPI validation. Authentication followed in phase 2; NFC and payments remain later work.

## Phase 2 — Authentication and tenant isolation

Complete locally on 2026-11-08. Browser and both Android apps sign in against the API, restore rotating refresh sessions and sign out. Tests use disposable PostgreSQL, not Railway.

- HS256 access JWTs, active-user/family checks, Argon2id verification, hashed opaque refresh tokens, atomic rotation/replay revocation and logout.
- Browser HttpOnly cookie, exact-origin/custom-header CSRF guard, in-memory access tokens, one refresh per concurrent 401 burst and private query-cache clearing.
- Native shared Android auth library with Keystore AES-GCM ciphertext storage, coroutine/ViewModel state and separate customer/merchant account checks. No card keys or funds are created.
- Tenant-scoped merchant metadata and reusable transactional owner guard, with cross-merchant, wrong-role, inactive-user and suspended-merchant tests. Pairing/refund domain handlers still come later.
- Auth/account/IP throttles, prewired pairing IP/user throttles, bounded buckets and request bodies, and API no-store headers.
- Complete current contract, generated portal types and reproducible isolated emulator auth test script.

The phase gate passed locally: web and mobile sign-in/restoration/logout, operator denied by the real owner guard, and cross-merchant requests rejected. Physical NFC acceptance is not part of this phase.

## Phase 3 — Card protocol and enrollment software

Implemented and verified on 2026-11-09 using the emulator only. The user requested
physical NFC be recorded as pending; the complete phase gate is therefore open.

- Pure JVM challenge/APDU codecs, canonical signing bytes, strict P-256 SPKI/DER
  verification and generation-guarded HCE session state with independent fixtures.
- Device-generated non-exportable AndroidKeyStore signing keys, stable pending
  enrollment retries, customer wallet/activity, explicit demo tapping and disabling.
- Atomic one-time DEMO_FUNDING, customer-scoped idempotency/reconciliation, bounded
  enrollment and owner-scoped card/wallet/activity API routes.
- Async HCE service with BIND_NFC_SERVICE/custom AID, unlock/screen requirements,
  no network/DB in APDUs, stale response rejection and session-bound tapping consent.
- Public contract/types, protocol/key/enrollment decisions and test-only fixture
  provenance. Runtime Railway credentials and data are preserved.

Software checks are recorded in [testing](testing.md). Pending: SELECT on a
supported physical phone and the phase 4 two-phone RF exchange. Pairing/POS software
continued below at the user’s request.
An emulator engine response does not satisfy those physical gates.

## Phase 4 — Terminal pairing and POS reader software

Implemented and verified on 2026-11-09 after the user requested the next phase.
The phase 3 physical gate and phase 4 two-phone exchange remain pending.

- Owner-only 65-bit, ten-minute code creation; scoped idempotency without raw code
  storage in replay records, atomic code consumption/terminal/audit and safe retry.
- Tenant-isolated terminal pages and repeatable owner disabling; unique installation
  reconciliation, disabled/foreign installation rejection and 100-record merchant cap.
- Portal terminal navigation, owner reveal/countdown modal, operator read-only view,
  confirmed disabling, no code in query caches/browser storage, close/expiry behavior
  and keyboard focus restoration. Native dialog and reduced-motion layouts reviewed.
- POS encrypted pending setup, stable installation/idempotency, pairing restoration,
  fresh terminal status, exact cent input and honest NFC diagnostics on the emulator.
- Shared SELECT/SIGN reader, foreground-only ISO-DEP I/O, duplicate/stale response
  guards, same-challenge retry, expiry, and cancellation while status is still loading.
- At this milestone Charge remained disabled; the phase 5 flow below replaces
  the local connection diagnostic with server-backed payments.

See [testing](testing.md) and [ADR-010](adr/ADR-010-pairing-and-pos-reader.md).

## Phase 5 — Payments and ledger software

Implemented and verified on 2026-11-09 with disposable PostgreSQL and the emulator.
The phase 3/4 physical NFC gates remain pending by the user's instruction.

- Merchant-scoped 90-second immutable intents, exact-cent validation, active-terminal
  guard, authoritative status/expiry, repeatable cancel and bounded payment list/detail.
- Server-state P-256 proof verification, terminal/intent/card/wallet lock ordering,
  atomic debit/ledger/decision/audit/idempotency response, final business declines,
  committed expiry errors and receipt recovery after terminal disabling.
- Twenty concurrent identical submissions and twenty different submission keys each
  debit once; competing terminals cannot overdraw one wallet. Tampering, invalid
  inputs, tenant boundaries, cancellation races and post-debit rollback are tested.
- POS encrypted account-scoped sale journal, stable request keys, saved-proof retry,
  recovery after unknown outcomes/recreation, explicit cancellation/expiry and
  server-authoritative approval/decline. Foreground reader uses the server challenge.
- Disposable emulator test signs an actual intent with a fresh non-exportable test
  key, confirms A$100 → A$75, hides the committed response and two status responses,
  then recovers APPROVED without another debit. It also checks recreation and cancel.
- OpenAPI/types/status, setup/security guidance and [ADR-011](adr/ADR-011-payments-and-recovery.md)
  describe the implemented behavior. [Actual approval screen](screenshots/phase5-approved-api36.png).

Checks: 85 backend, 12 protocol, 18 portal and 15 Android instrumentation tests
(8 Card, 7 POS); debug/release builds, Android lint, web checks and fault-proxy
self-check pass locally. Full evidence and limitations are in [testing](testing.md).

## Phase 6 — Merchant portal and refunds software

Implemented and verified locally on 2026-11-10 (Australia/Melbourne).

- Live merchant KPIs, UTC daily approved chart with accessible raw data, recent
  authorisations and explicit reporting-window semantics. Date/status filters and
  keyset pagination are shareable in transaction URLs; timestamps show Melbourne time.
- Merchant-scoped receipt/detail and refund history, owner partial/full refund
  confirmation, integer-cent validation and server-enforced operator restrictions.
- Atomic original-wallet credit/refund/ledger/audit/replay, payment-before-wallet
  lock order, bounded transient transaction retries and tenant-aware authorisation index.
- Account/payment-scoped refund recovery across reloads in the same tab; unknown
  outcomes and later permission errors retain the exact request/key. Query caches
  refresh after success; arbitrary audit metadata is withheld from responses.
- Owner audit filters/pagination, read-only settings and two-phone setup help;
  terminal pairing/disable workflows reuse the existing accessible native dialog.
- Shared disposable API fixture for emulator and Chromium acceptance. The browser
  gate signs actual intents with fresh test-only keys and verifies partial/full
  credit, role restrictions, dashboard/audit, mobile layout and a lost committed
  refund response followed by reload/retry without another credit.

Local checks: 96 backend, 12 protocol, 28 portal unit, 3 Chromium E2E and 15 Android
instrumentation tests; debug/release APK builds, Android lint, web/contract/type
checks and fault-proxy self-check pass. See [testing](testing.md) and
[ADR-012](adr/ADR-012-refunds-and-merchant-reporting.md).

## Remaining ordered work

1. Finish the phase 3 physical NFC SELECT acceptance gate when hardware is available.
2. Finish phase 4 physical proof exchange and vendor/OS checks when two phones are available.
3. Phase 7: containerized API/web, production topology, operations, security hardening and recorded demo.

A signed challenge alone never authorizes a payment; only the API decision does.
