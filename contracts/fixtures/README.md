# APDU v1 fixture

DEMO / NO REAL MONEY. `apdu-v1.json` fixes the exact proprietary SELECT,
68-byte challenge, 100-byte canonical signature input and proof response.
The byte vectors were independently encoded with Python `struct`/UUID bytes;
the public SPKI and SHA-256 ECDSA DER signature were produced with OpenSSL.
The JVM tests compare exact bytes and verify the signature with JCA.

The deliberately public **non-production** private key is only in
`packages/protocol-kotlin/src/test/resources/test-only-p256-private.pem`.
It exists to reproduce cryptographic test vectors, is never a runtime asset,
and must never be enrolled on Railway or installed as an Android card key.
API tests consume the public fixture only; Android instrumentation generates
fresh non-exportable AndroidKeyStore keys on the emulator.

Reproduce signature verification from the repository root:

```sh
python3 - <<'PY'
import base64, json
from pathlib import Path
f = json.loads(Path('contracts/fixtures/apdu-v1.json').read_text())
Path('/tmp/tapflow-fixture-canonical.bin').write_bytes(bytes.fromhex(f['canonicalHex']))
Path('/tmp/tapflow-fixture-signature.der').write_bytes(base64.b64decode(f['signatureDerBase64']))
Path('/tmp/tapflow-fixture-public.der').write_bytes(base64.b64decode(f['publicKeySpkiBase64']))
PY
openssl pkey -pubin -inform DER -in /tmp/tapflow-fixture-public.der -out /tmp/tapflow-fixture-public.pem
openssl dgst -sha256 -verify /tmp/tapflow-fixture-public.pem -signature /tmp/tapflow-fixture-signature.der /tmp/tapflow-fixture-canonical.bin
```

ECDSA signatures may vary on regeneration; verify the signature and canonical
bytes rather than expecting a newly signed proof to equal the fixed fixture.
