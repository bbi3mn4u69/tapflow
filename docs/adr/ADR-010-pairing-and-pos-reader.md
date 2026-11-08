# ADR-010: Atomic terminal pairing and foreground POS reading

Status: Accepted for phase 4 software; physical NFC acceptance pending.

DEMO / NO REAL MONEY.

Owner code creation, code consumption and installation pairing use the existing
PostgreSQL tables. Code creation and pairing use user/merchant/route-scoped UUID-v4
idempotency claims. A code is consumed with its terminal/audit/replay changes in
one transaction; expiry is exclusive at exactly ten minutes. Merchant membership
is checked before consumption; owner role is required for creating codes and
disabling terminals. User/IP pairing attempts remain limited to ten per minute.

A human-enterable code has 13 Crockford Base32 characters (65 unpredictable bits).
Derive it with HMAC-SHA256 over the domain `TAPFLOW-PAIRING-V1`, authenticated user,
merchant and idempotency UUID, using the existing random JWT signing secret as
key with this separate purpose/domain. Take five bits from each of the first 13
HMAC bytes. This is a keyed pseudorandom derivation, not a hash of public UUIDs.
Store only SHA-256(code) in pairing records; replay JSON stores the code hash and
public expiry/merchant metadata. Reconstruct the code only in memory for an exact
creation retry. This avoids storing a raw code in an otherwise reusable JSON
response table or adding a second encrypted server secret store.

Key rotation makes unrevealed old creation responses unreconstructable: return
PAIRING_CODE_UNAVAILABLE and require a new idempotency key/code. Already revealed
codes still match their stored hash until consumed/expired. Retained idempotency
claims replay original outcomes after their retention timestamp; a cleanup job
remains operations work. A repeated creation never extends a code's expiry.

Installation IDs are unique. A fresh valid code may reconcile the same active
installation/merchant/label, but cannot reassign a foreign installation, rename
it silently or reactivate a disabled one. Bound each merchant to 100 terminal
records, including disabled ones. Terminal listing has validated keyset pagination.
POS refresh reads up to 100 records; no terminal is hidden behind that account cap.
Disable is repeatable and preserves history; a pairing replay is followed by a
fresh status read so its original ACTIVE response cannot reactivate the UI.

POS saves pending code/label/idempotency data with a separate AndroidKeyStore
AES-GCM key. Retry survives recreation/response loss with the same installation
and key. Other accounts cannot read the code. Clearing pending setup is explicit
and never unregisters a server terminal. Public terminal metadata is cached only
inside private app storage; current membership/status is rechecked before reading.

The shared JVM reader exchanges exactly proprietary SELECT then SIGN_CHALLENGE.
Its session guard allows one tag/read, retains the challenge for tag-loss retry,
expires waiting/reading sessions and rejects late completion after cancellation.
Android registers reader mode only while foreground and waiting; connects,
transceives and closes ISO-DEP off the main thread with a 3-second transceive
limit. Closing on cancellation unblocks platform I/O even during ViewModel
teardown. No raw APDU, code or signature is logged.

Phase 5 owns server intent creation and authorization. Until then Charge stays
disabled; only debug builds expose an explicit local 90-second connection test,
with fresh UUID/nonce and no backend payment request. Received proof framing is
not server signature verification or payment approval. Emulator tests cannot
satisfy physical SELECT, RF timing, tag-loss hardware behavior or two-phone gates.

Platform references: [Android IsoDep](https://developer.android.com/reference/android/nfc/tech/IsoDep),
[reader mode](https://developer.android.com/reference/android/nfc/NfcAdapter#enableReaderMode(android.app.Activity,%20android.nfc.NfcAdapter.ReaderCallback,%20int,%20android.os.Bundle)).
