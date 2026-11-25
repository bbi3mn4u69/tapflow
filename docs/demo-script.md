# Three-to-five-minute demonstration

**DEMO / NO REAL MONEY.** Explain that only proprietary TapFlow test cards are supported.
No bank cards, real payment credentials or real money are involved.

1. **0:00–0:45 — setup.** Show the live portal's demo banner and owner dashboard.
   On two compatible Android phones, sign in to Card (customer) and POS (merchant).
   On an emulator, explicitly explain that NFC radio acceptance remains pending.
2. **0:45–1:30 — provision.** Owner creates an expiring pairing code; enter it on
   POS, then dismiss it. Customer enrolls a device-held test key, sees A$100 demo
   funding and explicitly enables tapping while unlocked. Do not record passwords
   or raw pairing codes in the shared demo.
3. **1:30–2:30 — payment.** POS enters A$25.00. With physical phones, tap the
   unlocked Card to POS; show the API's APPROVED receipt and A$75 wallet. This step
   cannot be demonstrated by an emulator radio. The isolated developer fixtures
   sign genuine server challenges and demonstrate HTTP/ledger behavior separately.
4. **2:30–3:30 — merchant operations.** Portal opens the receipt, owner refunds
   A$10 then A$15, shows PARTIAL then FULL and A$100 wallet, and checks audit/KPIs.
   Operator sign-in demonstrates that owner actions remain unavailable.
5. **3:30–4:30 — recovery.** In the disposable browser fixture, hide a committed
   refund response, reload, retry the saved request and show one credit. Briefly
   show HTTPS staging, encrypted restore and restart verification output.

[Recorded owner browser flow](demo/phase7-owner-refund.webm) is an actual Chromium
recording against the built web/API containers over local HTTPS, using synthetic
accounts, a disposable database and a test-only signer. It is not a physical NFC
recording. The acceptance runner is `infra/scripts/test-deployment.py`.

Physical two-phone footage, vendor/OS interoperability and the final hardware gate
are pending until phones are available. Never imply that this recording verifies them.
