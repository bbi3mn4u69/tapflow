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

## Remaining ordered work

1. Phase 1: complete schema, Flyway migrations, PostgreSQL integration/readiness, seed tooling and full OpenAPI v1 contract.
2. Phase 2: authentication, refresh rotation and tenant isolation.
3. Phase 3: shared APDU fixtures/codecs, device keys, card enrollment and HCE.
4. Phase 4: terminal pairing, POS reader and physical proof exchange.
5. Phase 5: atomic payments, ledger, idempotency, recovery and concurrency tests.
6. Phase 6: full merchant portal, refunds, audit and browser E2E.
7. Phase 7: containerized API/web, production topology, operations, security hardening and recorded demo.

Do not treat starter screens, a healthy process, or passing bootstrap tests as acceptance of the payment system. The next phase starts only after the phase 0 build gate passes.
