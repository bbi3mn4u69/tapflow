# TapFlow coding contract

Read `overall.md` before changing behavior. Follow its phase order and acceptance gates.
This repository is a DEMO / NO REAL MONEY simulator, never real card acceptance.

- Use Kotlin for both Android apps and the Ktor backend; the NFC protocol module stays pure JVM.
- Money uses integer minor units. Tenant checks, idempotency and atomic ledger changes belong on the backend.
- Keep private keys on the customer device; never log credentials, signatures, APDUs or pairing codes.
- Use constructor composition. Add DI frameworks only when a demonstrated need justifies them.
- Pin compatible stable dependencies and commit the web lockfile. Record material choices in `docs/adr/`.
- Apply Ponytail: the smallest complete change, meaningful tests for behavior, no speculative abstractions.
- Write behavioral tests before implementations. Investigate root causes before fixes; verify before claiming success or committing.
- Apply Apple design principles with native Android/Material conventions: immediate feedback, clear hierarchy, system typography and accessibility.
- Read [testing guidance](docs/testing.md), [progress](docs/progress.md) and [setup](docs/setup.md).
- Make coherent, meaningful commits after checks pass; never commit `.env`, build outputs or local signing keys.

The requested skill map and project-specific precedence are in [docs/skills.md](docs/skills.md).
