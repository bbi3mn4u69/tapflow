# ADR-011 — Atomic demo payments and durable POS recovery

Date: 2026-11-09. Status: accepted. DEMO / NO REAL MONEY.

## Decision

The API creates an immutable, random 16-byte challenge with a 90-second lifetime
and millisecond expiry. A terminal has at most one unexpired pending intent.
Create requests lock the tenant's terminal exclusively; authorisation takes a
shared terminal lock, locks the intent, reads/locks the card, then locks the wallet.
Merchant/user/membership shared locks keep the authorising operator's access
stable for the transaction. Wallet locks serialize different terminals spending
the same balance without serializing every sale across a merchant.

The server reconstructs signing bytes from stored intent fields and the submitted
card ID, verifies strict P-256 DER, then rechecks expiry after acquiring the wallet.
Approval commits the balance, one DEBIT ledger row, intent decision, receipt,
audit event and scoped idempotency response together. Insufficient funds and a
blocked card are final business declines (200); invalid proof is 400; expiry is a
committed EXPIRED decision with 410. Cancellation and authorisation lock the same
intent, so exactly one can win. No raw signature is stored by the server.

Idempotency keys are UUID v4. Create replay preserves the original response;
clients always follow it with GET. Authorisation replay returns the original
receipt; the same exact proof with a new key cannot debit again. Changed proof
conflicts after a decision. Disabling a terminal/card cannot rewrite a committed
result. Status and cancellation remain available after terminal disabling.

## POS recovery

Before HTTP, POS persists separate creation/submission keys, amount and scope in
one AndroidKeyStore AES-GCM encrypted journal. It saves the proof before submitting
and retains it only while unresolved. A lost create response reuses its creation
key. A lost submission response queries the existing intent; a retry first queries
status and can submit only the original proof/key. A timeout never becomes a local
approval, decline or expiry. Foreground reader mode signs only the server challenge.

Recreation, backgrounding and sign-out preserve the journal. A final result must
be acknowledged before a new sale; another account cannot overwrite an unresolved
sale. Stale workers cannot overwrite a newer journal. Corruption/key loss fails
closed rather than deleting recovery data. Operators must reconcile server history
before clearing app storage. Full merchant history UI and refunds follow in phase 6.

## Verification boundary

PostgreSQL integration tests exercise money, races and rollback. A loopback-only
fault proxy suppresses a committed authorisation response and two status responses
for a disposable emulator test. That test generates its own non-exportable signing
key and registers only public SPKI; it exercises the production payment HTTP and
POS recovery paths. It does not emulate radio traffic or prove two-phone NFC.
No fixture payment, enrollment or funding is sent to the Railway runtime.

Existing V001/V002 schema constraints suffice; no applied migration is modified.
Operational idempotency retention/cleanup remains phase 7 work.
