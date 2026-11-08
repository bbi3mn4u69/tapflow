# ADR-009: Atomic and bounded demo card enrollment

Status: Accepted.

DEMO / NO REAL MONEY.

Lock the active customer row for new enrollment, then claim a customer-scoped
UUID-v4 idempotency key in the same short PostgreSQL transaction. Persist card,
wallet, one immutable DEMO_FUNDING ledger entry, audit event and replay response
together. Any failure rolls everything back. Validate SPKI before opening the
transaction; there is no network or expensive key generation while holding locks.

Hash the normalized label/algorithm/SPKI request. A matching retry replays its
original status/JSON, including canonical PostgreSQL jsonb ordering. A changed
payload returns 409. Stored replay claims have a 24-hour retention timestamp;
there is no cleanup job yet, so retained claims continue replaying rather than
being reused. The unique SPKI and existing owner/label reconciliation also prevent
repeat funding if a future cleanup removes an expired claim. Blocked keys remain
blocked and cannot receive new funds through retry or reconciliation.

Default funding is 10,000 integer AUD cents, bounded to 1–1,000,000 cents. Enrollment
is enabled by default only in development/test; other environments require an
explicit flag. Bound fresh keys to three per rolling hour/customer, cards to 100
per account, and endpoint attempts (including retries) to 60/minute/customer.
Same-key reconciliation remains possible after the fresh-key quota is reached.

The app saves one pending ID/label/key alias before making HTTP requests. Retry
reuses that setup; it never generates a replacement for an existing missing key.
The wallet balance is fetched from the server and is not reset on login/relaunch.
Disabling pauses local tapping first, then applies server BLOCKED status; failure
retains a retryable server action. No deletion or balance transfer is performed.
Owned card, wallet and activity reads enforce customer scope; cross-user IDs
return 404. Activity uses a card-bound, validated keyset cursor and bounded page.
