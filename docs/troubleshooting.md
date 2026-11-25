# Troubleshooting

**DEMO / NO REAL MONEY.** Preserve database volumes and unknown payment/refund request keys.

- **API startup fails:** check required variable names, exact HTTPS origins,
  PostgreSQL TLS and `/health/ready`. Error messages intentionally withhold
  credentials. Never paste `.env` or variable values into logs/issues.
- **Railway web 502:** confirm the private API is healthy, listening on `::` and
  port 8080, and `API_UPSTREAM` references the API private domain. Public `/health/*`
  returning 404 is intentional; service health is checked privately.
- **Native crypto fails in read-only containers:** the image installs Argon2/JNA
  libraries under `/app/native`. Rebuild from the current Dockerfile rather than
  making `/tmp` executable or granting root privileges.
- **TLS staging fails:** run `python3 infra/scripts/test-deployment.py` after
  building its three `:phase7` images. It creates a private test CA and validates
  the certificate independently before the browser tests. Never disable certificate
  checks for public deployments or install the fixture CA globally.
- **Backup fails:** no ciphertext is published on a dump/encryption failure. Check
  Docker, network reachability, client/server PostgreSQL versions and a private
  service file with `key=value` syntax. Do not print that file. Retry into the same
  backup directory; existing archives are preserved.
- **Restore refused:** use a new empty database. The tool deliberately cannot clean
  or overwrite a nonempty target. A wrong identity or changed ciphertext must fail.
- **Emulator has no NFC:** enrollment, pairing, checkout recovery and HTTP tests
  work there; physical SELECT/signature exchange requires two compatible phones.
- **Android Studio sync:** use JDK 21 and an IDE supporting AGP 9.5.1. The installed
  older IDE has not been verified; CLI/emulator evidence does not establish IDE sync.

See [setup](setup.md), [testing](testing.md) and [operations](operations.md).
