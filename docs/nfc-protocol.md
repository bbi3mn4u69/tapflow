# TapFlow proprietary card protocol

DEMO / NO REAL MONEY. This is a test protocol, not EMV or real card acceptance.

The pure JVM `protocol-kotlin` module is used by the API and Android Card app.
Its exact public vectors are in [the fixture](../contracts/fixtures/apdu-v1.json).
Only the proprietary category `other` AID `F0544150464C4F5731` is registered.

1. SELECT `00a4040009f0544150464c4f573100` returns `019000` when unlocked.
2. SIGN uses `8020000044` + 68 challenge bytes + `00`.
3. Challenge bytes: version `01`, intent UUID (16), merchant UUID (16),
   positive AUD cents (8), ASCII `AUD` (3), nonce (16), positive expiry Unix
   milliseconds (8). Integers are big endian; maximum amount is 1,000,000 cents.
4. Signed bytes: ASCII `TAPFLOW-DEMO-V1`, zero delimiter, challenge bytes,
   then card UUID (16), for exactly 100 bytes. Sign with P-256 SHA256withECDSA.
5. Proof: version `01`, card UUID (16), signature length (1), strictly validated
   ECDSA DER signature (8–72), `9000`. P1363, trailing bytes and noncanonical DER
   integers are rejected. Registration accepts canonical uncompressed P-256 SPKI.

Status words: `9000` success, `6a82` unknown AID, `6a80` malformed input,
`6985` not ready, `6d00` unsupported command, `6f00` internal signing failure.
The syntactic expiry check does not establish that an intent remains valid;
the future authorization handler must reconstruct the challenge from its stored
intent and enforce expiry, card status and merchant/terminal ownership.

SELECT and deactivation invalidate in-flight signing. SIGN requires a successful
SELECT and permits one response. The HCE adapter performs key/prefs work off the
main thread and sends an asynchronous response only for the current generation.
There is no network or database request during an APDU exchange. The manifest
requires BIND_NFC_SERVICE, screen on and device unlock.

The user explicitly enables demo tapping for the current encrypted login session.
Login/refresh invalidates that consent, as do local pause and sign-out. Missing
keys and local BLOCKED cards cannot sign. Remote disabling is authoritative at
the server even when an offline phone has not yet learned the new card status.
The last signed challenge is a best-effort local event, never payment approval.

Emulator tests exercise the actual APDU engine, Keystore signatures, stale-response
rejection and installed service metadata. They do not exercise Android NFC RF
routing, physical unlock behavior, hardware-backed key protection or another
phone's ISO-DEP reader. Physical NFC acceptance remains pending by user choice.
