# ADR-013: Non-root containers and Railway deployment

Status: accepted, 2026-11-10. **DEMO / NO REAL MONEY.**

Build API/protocol from the root with JDK 21 and web with the frozen pnpm lockfile;
ship JRE/static assets only. Pin base-image digests and run application images as
UID 10001. Pre-extract the installed Argon2 and JNA native libraries into immutable
`/app/native`, selecting Linux ARM64/AMD64 at build time. JNA's normal temporary
extraction fails when hardened Compose mounts `/tmp` with `noexec`; making it
executable would broaden the runtime unnecessarily. The HTTPS fixture reproduced
the failure before the immutable-library fix.

One public web origin reverse-proxies `/api/*`; Railway handles public TLS and
private service networking, while standalone Compose uses Caddy as a TLS edge.
Keep remote database TLS mandatory in both topologies. The optional local database
wrapper adds TLS rather than weakening connection validation for Docker hostnames.
Preserve the existing development volume name and Railway database service/volume.
Migrations remain Flyway-owned; deployments never reset financial state.

Support a narrow set of `_FILE` secrets for Compose and protected variable
injection for Railway. Refuse ambiguous value/file pairs and public reset/signup
outside development. New production JWT signing material is independent of local
configuration. One API replica preserves the existing process-local rate limits.

Back up PostgreSQL custom-format dumps through age encryption; check both pipeline
processes before publishing. Restore authenticates ciphertext before SQL, refuses
nonempty targets and commits in one transaction. The staging gate exercises real
browser debits/refunds, aggregate/ledger reconciliation, encrypted restoration and
SIGTERM/restart. Every fixture uses its own database and bind storage; cleanup
never removes an existing named volume. Daily offsite scheduling still requires
an always-on backup host and destination; the initial host-to-developer backup
must not be represented as an active schedule.

Use the installed Railway MCP for project/service/configuration management and
pin deployments to verified commits. GitHub CI verifies Linux AMD64 containers,
secret detection (only the exact hash-checked protocol fixture key is excluded),
image vulnerability reports and HTTPS recovery. Vulnerability reports are advisory;
this is not a security certification. A manual deployment workflow requires a
project-scoped GitHub environment secret and successful application checks.
