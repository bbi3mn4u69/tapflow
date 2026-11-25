# Deployment and recovery

**DEMO / NO REAL MONEY.** This is a single-replica portfolio simulator. Physical NFC acceptance is pending. Never run financial test fixtures against the runtime database.

## Railway

Project: `0ca7489c-2e7d-47c8-a594-a6628c1e25b0`, environment `production`.
The existing Postgres service and volume remain in place. `tapflow-api` listens on
`[::]:8080` privately; `tapflow-web` serves built assets and proxies `/api/*` to
`tapflow-api.railway.internal:8080`. Railway terminates public TLS at
[the portal](https://tapflow-web-production.up.railway.app). Use the same origin
plus `/api/v1/` for Android. There are no client secrets in the web bundle or APK.

The API uses PostgreSQL variable references, a separate random JWT secret, exact
HTTPS origins, secure refresh cookies, TLS for its database connection, and one
replica. Customer registration and public demo reset must remain disabled. Card
enrollment is explicitly enabled for existing customer accounts. Existing seed
passwords are preserved; deployments do not seed users or reset balances.

Service settings are managed through the installed `railway mcp` server. The API
Dockerfile is `services/payment-api/Dockerfile`; web uses `apps/web/Dockerfile`;
both build from the repository root. Health paths are `/health/ready` and
`/healthz`, with a 180-second deployment health deadline and 15-second draining.
The private API also has `/health/live`. Public `/health/*` deliberately returns
404 rather than the SPA. `/api/v1/status` is the public demo diagnostic. The web
health check alone does not establish database health.

For a later manual GitHub deployment, configure a project-scoped `RAILWAY_TOKEN`
as a secret on the GitHub `production` environment, configure required reviewers
if desired, and dispatch **Deploy Railway** at a commit with successful JVM/web
and Android CI. The workflow reruns the container/recovery gate before uploading
that exact checkout. The token is not committed or copied from local credentials.
Until configured, use the authenticated local Railway MCP/CLI. Pinned GitHub
sources require reconnecting the source to the next verified commit; ordinary
pushes must not silently bypass verification.

## Local containers and VPS

Keep the existing native `.env` intact. To use the complete development stack,
provide `LOCAL_DATABASE_PASSWORD` and a strong `JWT_SIGNING_SECRET`, stop any
native API occupying 8080, then run:

```sh
docker compose --env-file .env -f infra/compose/dev.yml --profile stack up -d --build --wait
```

Open `http://localhost:8088`. The default profile still starts only PostgreSQL;
the existing `tapflow-dev_tapflow_dev_pgdata` volume and its password are retained.
The PostgreSQL wrapper adds TLS to the pinned PostgreSQL 17 image. A self-signed
internal certificate is used with `sslmode=require`; use `verify-full` plus a
managed trusted certificate where server identity verification is required.

For a new VPS deployment, create a private directory outside this repository:

```sh
python3 infra/scripts/deployment_config.py /absolute/new-deployment --hostname demo.example.com
```

Its `.env` contains image references and secret-file paths; the generator never
overwrites an existing directory. Set `API_IMAGE`, `WEB_IMAGE` and `DB_IMAGE` to
verified immutable image tags/digests. To build locally, add
`-f infra/compose/build.yml` to the following Compose commands and use `--build`.
The root context builds only the API/protocol and web; no Android SDK is required.

```sh
docker compose --env-file /absolute/new-deployment/.env -f infra/compose/prod.yml config --quiet
docker compose --env-file /absolute/new-deployment/.env -f infra/compose/prod.yml up -d --wait
```

Point the hostname at the VPS and permit 80/443. Caddy obtains public certificates;
keep its data/config volumes. Only the TLS edge publishes ports. API, web and
PostgreSQL run as non-root with read-only root filesystems, bounded memory/logs,
and private internal networking. Do not delete volumes to fix startup errors.

## Backups

Install checksum-verified `age` 1.3.2 and Docker. Generate an encryption identity
once in a private directory; keep a separate recovery copy off the server:

```sh
umask 077
age-keygen -o /private/backup.agekey
age-keygen -y /private/backup.agekey > /private/recipients
python3 infra/scripts/database_ops.py backup --container YOUR_DB_CONTAINER --directory /private/backups --recipients-file /private/recipients
```

For Railway, use `--service-file /private/railway-pg-service.conf` instead of
`--container`. It must have mode 0600 and contain `[tapflow]`, then `host=...`,
`port=...`, `dbname=...`, `user=...`, `password=...`, `sslmode=require` (no spaces
around `=`). Values belong only in this private file. The pinned PostgreSQL 18.6
client is compatible with the hosted PostgreSQL 18 service. Do not place a URL
containing a password in command arguments or logs.

The script streams custom-format `pg_dump` into age, checks both processes and
publishes a mode-0600 ciphertext plus SHA-256 sidecar only after success. It never
writes a named plaintext dump. `--offsite user@host:/absolute/path` copies both
files with noninteractive SCP; `--retain-days 14` prunes this tool's old local
archives only after backup/upload success. Configure SSH and host verification
first; never disable host-key checking. Keep offsite retention independently.

For a daily 02:15 backup on an always-on host, add a private crontab entry using
absolute interpreter/script/config paths, `--offsite` and `--retain-days 14`.
Supply Docker/age in that job's PATH, log to a private file, alert on nonzero exit,
and confirm an offsite copy before relying on the schedule. No offsite destination
or always-on backup host has been supplied; no recurring job is claimed active.
The initial Railway archive is stored on the developer machine, outside Git.

## Restore, updates and rollback

Create a **separate empty database** and use its own service file or container:

```sh
python3 infra/scripts/database_ops.py restore --service-file /private/staging-pg-service.conf --archive /private/backups/tapflow-TIMESTAMP-ID.dump.age --identity-file /private/backup.agekey --confirm-empty-target
python3 infra/scripts/database_ops.py metrics --service-file /private/staging-pg-service.conf
```

Never restore over the live database. Restore refuses existing application
objects, authenticates the entire ciphertext before SQL, then restores in one
transaction. Authentication failures leave the target untouched; SQL failures
roll back. Check Flyway history, wallet/ledger equality, payment/refund totals,
readiness and sign-in on the restored instance before switching traffic. Keep
restores private: backups contain personal data and password hashes.

Before an update, take and verify an encrypted backup, record the active commit,
review migration compatibility, deploy one API replica, wait for readiness, then
check portal sign-in and reports. Flyway alone owns migrations, validates applied
checksums and never runs clean. Prefer additive migrations. To roll back application
code, redeploy the preceding verified commit only if the current schema remains
compatible; otherwise stop writes and rehearse a separate-database recovery first.
Do not reverse ledger entries or delete idempotency records during rollback.

## Monitoring and limits

JSON application logs carry validated request IDs and method/status/duration without
request bodies, query strings, credentials, signatures or pairing codes. Existing
rejected pairing/proof events are safe operational signals. Keep Railway logs and
HTTP request metadata private. `database_ops.py metrics` reports aggregate
approved/declined/expired/refund counts and refunded minor units without replay
inflation. Intent expiry is finalized when accessed; this is not a clock-based
expiry counter. Inspect health failures, sustained 5xx, rate limits, pool timeouts
and container restarts. No hosted alerting or latency dashboard is configured.

Process-local limits require one API replica. Financial idempotency keys are
retained to reconcile unknown outcomes; do not apply blanket TTL deletion. Define
an archival policy before increasing demo data retention. Image vulnerability
reports are recorded by CI; findings are advisory and require human review.
