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

`MerchantAccess.require` checks active user, merchant account type, current membership and active merchant in SQL. Owner operations must call it with `owner=true` inside the same transaction as their protected work. Missing/cross-tenant resources return 404; a member lacking the owner role returns 403. The guard is tested through a test-only owner route; pairing and refund business operations are not enabled yet.

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
must check current database status before any future debit. That payment handler
is not implemented yet. Signature completion never means payment approval.

The emulator verifies Keystore API non-exportability and the actual asynchronous
APDU engine, not physical hardware protection or RF interoperability. Two-phone
NFC acceptance remains pending by the user's emulator-only instruction.
