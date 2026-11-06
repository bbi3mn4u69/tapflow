# ADR-008: Railway runtime database and isolated migrations

Status: accepted, 2026-11-07.

The user supplied a deployed Railway PostgreSQL database and requested replacing the runtime database connection. Runtime development now uses that database through the ignored root `.env`. No endpoint credentials are committed. A read-only connection confirmed TLS and PostgreSQL 18.6 before application changes, with no user tables present.

Keep all application tables and Flyway history in a dedicated `tapflow` schema. Flyway owns DDL, validates checksums, migrates before the HTTP listener starts, and has cleaning disabled. Use HikariCP with a bounded pool, JDBC statement/lock timeouts and UTC sessions. Blocking health checks run on a bounded IO dispatcher. Use plain prepared SQL rather than adding an ORM.

Readiness compares stored migration versions/checksums with the startup-validated snapshot in one bounded SQL query. Calling Flyway validation and metadata discovery on every remote probe took about 14.8 seconds; the single-query probe measured about 0.74 seconds on the development connection. These are observations, not a latency guarantee. History drift or database failure still makes readiness fail.

PostgreSQL 17 remains the minimum test baseline. Testcontainers tests start independent databases pinned to the same PostgreSQL 17.11 digest as the optional local fallback. Tests do not read the Railway connection or mutate it. Keep `LOCAL_DATABASE_PASSWORD` separate from runtime credentials; the existing local Docker volume is preserved.

Pin Flyway 13.12.1, HikariCP 7.1.0, pgJDBC 42.7.14, Testcontainers 2.0.5 and argon2-jvm 2.12. Demo seeding uses Argon2id (64 MiB, 3 iterations, 1 lane), accepts locally supplied passwords, serializes seed reruns, preserves existing credentials and creates no device keys or wallets.

The schema enforces positive bounded amounts, AUD wallets, tenant-consistent terminal/payment/refund references, unique ledger references, one initial funding row per wallet and immutable ledger/audit rows. Payment/refund aggregate invariants and merchant authorization still require transactional services in later phases; schema constraints alone do not implement those features.

The full v1 API contract is published now. Its phase annotations distinguish implemented system endpoints from future domain operations. Enrollment and terminal pairing also require a stable `Idempotency-Key`, following the retry/reconciliation requirements in specification sections 8.2 and 17.2. These operations are not exposed as working routes yet.

References: [Railway PostgreSQL](https://docs.railway.com/databases/postgresql), [pgJDBC connection properties](https://jdbc.postgresql.org/documentation/use/), [Testcontainers PostgreSQL module](https://java.testcontainers.org/modules/databases/postgres/), and [specification](../../overall.md).
