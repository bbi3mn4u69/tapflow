# Security behavior

DEMO / NO REAL MONEY. This is an educational simulator, not certified payment acceptance.

Phase 2 implements authentication and merchant access. Device card keys, NFC proof verification, financial transactions, refund enforcement and deployment hardening belong to later phases.

## Accounts and sessions

- Passwords use Argon2id (64 MiB, 3 iterations, 1 lane). Unknown accounts and wrong passwords return the same generic authentication error; dummy verification avoids skipping password work for unknown accounts.
- JWTs last 15 minutes and require HS256, issuer, audience, subject, expiration, issue time, token ID, family and client type. Active users and session families are checked in the database on authenticated requests.
- Refresh secrets are random 256-bit opaque values; only hashes reach PostgreSQL. Rotation/replay/logout serialize on the user row. Replay revokes the family, including its access tokens, and that write commits before the error response.
- Refresh expiry is absolute, defaulting to 14 days from login. Rotating does not extend it. Logout revokes one family rather than all of a user's independent sessions.
- Web and mobile refresh sessions cannot be exchanged. Mobile logout revokes its authenticated family and rejects known foreign refresh tokens or the wrong client type. Browser logout is safely repeatable with a permitted browser request.
- Demo registration is disabled by default. When explicitly enabled it creates only CUSTOMER accounts and never funds a wallet or assigns a merchant role.

## Browser and Android storage

Browser refresh cookies are host-only, HttpOnly, Secure on HTTPS, SameSite=Lax and path-scoped. Exact allowed origins and the custom CSRF header protect login, refresh and logout; missing, null or unlisted origins are rejected unless a valid same-origin Referer is supplied. Do not add wildcard credentialed CORS. Loopback HTTP development is the only Secure-cookie exception.

The portal never writes access/refresh tokens to localStorage or sessionStorage. Restoration and concurrent 401 handling share a single refresh request. Browser requests have a 20-second deadline, preserving caller cancellation and showing a retryable connection error on timeout. A failed refresh clears identity/cache; a second rejected access token also signs out. Merchant query keys include the merchant ID, and a late read from a previous identity is rejected.

Android saves only AES-GCM authenticated ciphertext in private preferences, using a non-exportable Android Keystore key. Access tokens and entered passwords stay in transient memory. Corrupt/lost encryption state requires sign-in. Backups are disabled. Release networking requires HTTPS; only debug emulator traffic to `10.0.2.2` permits HTTP. Emulator Keystore tests establish API behavior, not hardware-backed key protection on physical phones.

## Tenant guards and operational limits

`MerchantAccess.require` checks active user, merchant account type, current membership and active merchant in SQL. Owner operations must call it with `owner=true` inside the same transaction as their protected work. Missing/cross-tenant resources return 404; a member lacking the owner role returns 403. Pairing-code creation and disabling enforce the real owner guard; refund operations enforce the same owner guard in phase 6.

Auth/pairing throttles and the 16 KiB body limit are implemented now. Rate-limit responses include Retry-After. Buckets are bounded and process-local. Before public proxy deployment, configure trusted proxy identity and shared limits if using multiple replicas; raw forwarding headers are deliberately ignored today.

Never log passwords, JWTs, cookies, refresh secrets, pairing codes or request bodies. Safe HTTP logs include method/status/request ID; failures expose only a stable envelope to clients and exception class to server logs. Auth DTO string representations redact secrets. Private `.env` files stay ignored and owner-readable only.

To force sign-out everywhere, generate a new private JWT signing key and revoke refresh sessions in an operator-controlled transaction before restarting the API. Changing only the JWT key does not invalidate refresh tokens. Public deployment also requires least-privilege DB credentials, verified TLS, backups/restoration, proxy hardening and the later operational checks.

See [ADR-006](adr/ADR-006-web-and-mobile-sessions.md) and [testing evidence](testing.md).

## Card enrollment and HCE — phase 3

Only authenticated active CUSTOMER users can enroll/read/disable their own cards.
Canonical P-256 SPKI validation precedes database work. Enrollment and one-time
funding are atomic, with scoped idempotency/reconciliation, per-hour key quotas
and a bounded account card count. See [ADR-009](adr/ADR-009-atomic-card-enrollment.md).
No committed fixture key is enrolled on the runtime database.

Card private keys stay in AndroidKeyStore. In-memory wallet state is cleared when its screen is disposed. Missing keys are not silently replaced;
old cards must be disabled before replacing a lost device setup. HCE checks local
ACTIVE status, key availability, unlock and explicit session-bound tapping consent.
Offline HCE may not know that a remote card was blocked: the authorization server
checks current database card status before every new debit. Signature completion alone never means payment approval.

The emulator verifies Keystore API non-exportability and the actual asynchronous
APDU engine, not physical hardware protection or RF interoperability. Two-phone
NFC acceptance remains pending by the user's emulator-only instruction.

## Terminal pairing — phase 4

Owner role and active merchant membership are enforced in the API. Code hashes,
consumption, terminal installation uniqueness, audit and replay outcomes are
transactional. Known codes from a foreign merchant cannot be burned by a nonmember.
Retries and new valid codes cannot revive disabled installations. Terminal listing
and disable queries include the merchant ID; pagination is scoped and bounded.
Raw codes do not appear in server logs or idempotency JSON. Purpose-separated HMAC
reconstruction and signing-key rotation behavior are recorded in
[ADR-010](adr/ADR-010-pairing-and-pos-reader.md).

The portal keeps reveals in component memory and restores keyboard focus on close.
POS pending codes use a separate AES-GCM Keystore key and are scoped to the account;
other accounts cannot read them. Server terminal status is rechecked before creating a
sale. Decoding a signed proof does not prove authorization or a debit; approval
requires the server decision. Reader mode is foreground-only; I/O closes on cancellation, and late
results/duplicate tags cannot complete a new session. Physical timing, vendor
behavior and NFC routing remain unverified until the two-phone hardware gate.

## Payments — phase 5

Intent fields are immutable server state; a client cannot choose the signed amount,
merchant, nonce or expiry. Strict P-256 proof verification precedes any debit;
wallet/intent locks and the unique debit constraint prevent double spending.
Balance, ledger, decision, audit and replay response commit together. Exact proof
replays recover a decision even after terminal/card disabling; new pending
submission requires an active original terminal. Invalid proof never debits.

The server retains a SHA-256 proof/request digest, never raw signatures. POS keeps
an unresolved proof only in its encrypted local journal and clears it after a
confirmed final state. It does not log APDUs/proofs, clear unknown outcomes, create
fresh keys on timeout or approve on a local NFC response. Corrupt journal data
fails closed and requires server-history reconciliation before storage replacement.
See [ADR-011](adr/ADR-011-payments-and-recovery.md) for lock/recovery details.

## Refunds and reporting — phase 6

OWNER is enforced transactionally for every refund and audit read, including
replays. Merchant membership alone never grants access through another merchant's
payment path. Refund sums, original approved payment and original wallet are locked
and validated before a credit; refund/credit/audit/replay either all commit or all
roll back. Exact replay never creates a second credit. The original terminal/card
may be disabled without removing a customer's right to receive demo value back.

Dashboard and detail reads use consistent PostgreSQL snapshots. Audit responses
project typed IDs/event names/timestamps only, with empty details; passwords,
keys, tokens, signatures and arbitrary stored JSON are not exposed. Financial
fixtures and browser-generated signing keys exist only in disposable test runs.

The portal additionally stores unresolved refund key/amount/reason in scoped
sessionStorage, with no tokens or proofs. A later access failure preserves an
uncertain refund. Tab/storage loss requires reconciliation against server history
before another request. See [ADR-012](adr/ADR-012-refunds-and-merchant-reporting.md).
