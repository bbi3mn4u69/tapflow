# API contract

DEMO / NO REAL MONEY. No bank-card data or actual funds are processed.

`contracts/openapi.yaml` is the v1 contract. It contains 32 operations on 31 paths and 47 schemas, with explicit authentication mechanisms, tenant-scoped identifiers, integer amount bounds, idempotency headers, errors and cursor pagination. UTC timestamps use RFC 3339. Money is integer AUD cents.

Run `corepack pnpm api:lint` from `apps/web` to validate it, then `corepack pnpm api:generate` to regenerate `src/api/generated/schema.d.ts`. Commit both the contract and generated types. CI rejects validation failures and generated drift.

Phase 2 implements all eight authentication/identity operations (registration remains explicitly flag-gated), merchant metadata, health, status and the contract endpoint. Other domain operations are planned, with `x-implementation-phase` showing their milestone. Enrollment, NFC, payments, pairing and refunds do not currently work.

Web cookie endpoints require `X-TapFlow-CSRF: 1` plus an allowed Origin/Referer; refresh tokens never appear in browser response JSON. Mobile endpoints return opaque refresh tokens for Keystore-backed storage. Refresh replay revokes the token family; logout revokes a single family. Access JWTs expire after 900 seconds; refresh rotation preserves the original absolute expiry. All API responses carry `Cache-Control: no-store`. Bodies above 16 KiB return 413.

## Response behavior

- `X-Request-ID`: a valid canonical UUID is preserved, otherwise the server generates a replacement. It appears in response headers, JSON errors and request log metadata.
- Errors: `{error: {code, message, requestId, details}, demo: true}`. No stack traces, SQL messages or credentials are returned.
- Authorisation: a valid business decision, including insufficient funds or blocked card declines, is HTTP 200. Invalid or malformed proof is a client error, not a business decline.
- Idempotency: stable UUID v4 keys for intent creation, authorisation, refunds, pairing-code creation, terminal pairing and enrollment. Exact retries replay the original persisted status/body; changed payload under the same key conflicts.
- Reporting: group approved payments by authorisation time in the selected half-open UTC interval, then subtract all refunds associated with those payments, including later refunds. Intervals are bounded to 366 days.
- Current merchant metadata and the reusable owner guard check membership in SQL. Later pairing/refund routes must use that guard inside their transaction; cross-tenant resources return 404 instead of exposing their existence.

The OpenAPI server URL is relative so the same contract works behind the specified same-origin reverse proxy and in local API development.
