# ADR-003: Device-held P-256 card keys and shared protocol

Status: Accepted for phase 3 software; physical NFC acceptance pending.

DEMO / NO REAL MONEY.

Use AndroidKeyStore EC P-256 keys and SHA256withECDSA. Private keys are never
exported, uploaded, seeded or logged. Only canonical uncompressed SPKI public
keys are registered. No biometric prompt is required for each background tap;
the installed HCE service requires screen on/unlock and explicit local demo
permission. Emulators cannot establish physical hardware key protection.

Keep codecs, canonical signing bytes, strict DER/key validation and the APDU
session state machine in the existing pure JVM module. The backend and Android
use the same implementation, with independent OpenSSL/Python fixture vectors
preventing a shared encoder mistake from becoming its own test oracle. The
fixture private key is explicitly non-production and test-only.

The thin HostApduService delegates to an asynchronous engine. SELECT/deactivation
invalidate pending responses. Key/prefs work runs on IO; no network or database
operation belongs to an APDU exchange. Tapping consent is bound to the current
encrypted refresh session; a login or refresh pauses it until explicitly enabled
again. A signature is proof of key possession, never proof of payment approval.

See [protocol](../nfc-protocol.md). The phase 3 physical SELECT gate and phase 4
two-phone ISO-DEP exchange remain unverified; the user requested emulator-only
work for now. Do not substitute the engine harness for those gates.
