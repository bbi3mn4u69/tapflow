# ADR-012 — Refunds, merchant reporting and browser recovery

Date: 2026-11-10. Status: accepted. DEMO / NO REAL MONEY.

## Refund transaction

An active merchant OWNER is checked and held with shared membership/user/merchant
locks. The API claims the actor/merchant/payment idempotency key, locks the approved
payment, computes its refunded sum, then locks the original wallet. It rejects a
nonpositive, unsupported-reason or excessive amount. Refund, wallet credit, one
CREDIT ledger entry, audit and replay response commit together. Original card or
terminal disabling does not prevent refunding an already approved payment.

Different refund keys serialize on the payment, so concurrent requests cannot
exceed its original amount. Exact replays return their original result, including
an earlier PARTIAL result after a later FULL refund. PostgreSQL deadlock and
serialization failures retry the entire rolled-back transaction at most three
times with the original request/key; other failures propagate without a credit.
The existing authorisation lock order is preserved. No new money-state tables or
changes to applied migrations are needed.

## Reporting consistency

Dashboard windows are [from,to) in UTC and group by **authorisation time**. All
refunds of those payments are subtracted, even when issued outside the window.
Daily approved buckets include zero days; recent payments use that same window.
Dates are bounded to 366 days; the default is the last 30 days. Dashboard and
payment-detail multi-query reads use one repeatable-read snapshot so totals and
histories cannot observe different concurrent refund states. PostgreSQL's snapshot
semantics are described in its [isolation documentation](https://www.postgresql.org/docs/current/transaction-iso.html).

V003 adds the tenant/authorisation-time/keyset index used by these report reads.
Existing creation-time and audit pagination indexes remain. List filters retain
creation-time semantics; the portal labels the distinction. Browser calendar dates
are UTC, while receipt/event timestamps are shown in Australia/Melbourne.

Audit reads require OWNER and expose only typed event/resource/actor/request IDs
and timestamps. `details` is deliberately empty; arbitrary stored JSON is never
forwarded to clients. This avoids accidentally exposing a future credential field.

## Browser behavior

TanStack Query keys include merchant/payment/filter values and are cleared when
identity changes. Refund submission invalidates detail, payment lists, dashboard
and audit queries. Native dialogs retain keyboard behavior and the visible demo
notice. Integer cent parsing rejects fractional cents/exponents/out-of-range input.

A refund request is saved to **sessionStorage before HTTP**, scoped to owner,
merchant and payment. It contains only the request key, amount and reason, never
authentication credentials or a card proof. Unknown outcomes lock these fields;
retry/reload reuses the original key. Permission loss on a recovery attempt does
not erase an earlier unknown outcome. Fresh definitive rejections refresh the
remaining amount. A tab can be closed visually without discarding an unresolved
request, but closing the browser tab or clearing its storage loses that recovery
record: reconcile server refund history before issuing another refund elsewhere.
Cross-device request lookup is intentionally outside this phase.

## Reproducible acceptance

JVM tests use disposable PostgreSQL for partial/full refunds, competing requests,
replay, rollback, bounded retry and access controls. Browser tests run Chromium
against a dedicated real API/database and Vite proxy, using a fresh test-only
P-256 signer to create approved payment fixtures. No fixture is sent to Railway.
They cover refund/history/KPI reconciliation, owner/operator routes, narrow-screen
layouts, and a committed response suppressed before reload/retry. The existing
emulator gate reuses the same isolated API lifecycle helper.
