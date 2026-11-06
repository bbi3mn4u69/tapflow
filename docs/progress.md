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
- Complete planned v1 OpenAPI contract with generated TypeScript types. Only the four system endpoints are implemented; phase annotations identify later operations.
- 21 backend tests, including fresh PostgreSQL Testcontainers migrations, constraints, readiness, rollback and seed reruns; 5 portal tests.

The phase gate passed locally: fresh migrations, real Railway readiness and OpenAPI validation. Authentication, NFC and payments remain future phases.

## Remaining ordered work

1. Phase 2: authentication, refresh rotation and tenant isolation.
2. Phase 3: shared APDU fixtures/codecs, device keys, card enrollment and HCE.
3. Phase 4: terminal pairing, POS reader and physical proof exchange.
4. Phase 5: atomic payments, ledger, idempotency, recovery and concurrency tests.
5. Phase 6: full merchant portal, refunds, audit and browser E2E.
6. Phase 7: containerized API/web, production topology, operations, security hardening and recorded demo.

Do not treat starter screens, a healthy process, or passing bootstrap tests as acceptance of the payment system. The next phase is authentication and tenant isolation, after the phase 1 foundation gate.
