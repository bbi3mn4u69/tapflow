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
supported physical phone; then phase 4 pairing/POS and the two-phone RF exchange.
An emulator engine response does not satisfy those physical gates.

## Remaining ordered work

1. Finish the phase 3 physical NFC SELECT acceptance gate when hardware is available.
2. Phase 4: terminal pairing, POS reader and physical proof exchange.
3. Phase 5: atomic payments, ledger, idempotency, recovery and concurrency tests.
4. Phase 6: full merchant portal, refunds, audit and browser E2E.
5. Phase 7: containerized API/web, production topology, operations, security hardening and recorded demo.

Do not treat a signed challenge or a funded demo wallet as payment authorization.
