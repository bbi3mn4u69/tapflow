#!/usr/bin/env python3
"""Run mobile integration tests against a disposable API/database, never the runtime .env."""
import os
from pathlib import Path
import shutil
import subprocess
from payment_test_proxy import PaymentTestProxy
from disposable_api import disposable_api


def main():
    root = Path(__file__).resolve().parents[2]
    os.chdir(root)
    adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb") if "ANDROID_HOME" in os.environ else shutil.which("adb")
    if not adb:
        raise SystemExit("Set ANDROID_HOME or put adb on PATH; start one emulator first.")
    devices = [line.split() for line in subprocess.check_output([adb, "devices"], text=True).splitlines()[1:] if line.strip()]
    if len(devices) != 1 or not devices[0][0].startswith("emulator-") or devices[0][1] != "device":
        raise SystemExit("Connect exactly one ready emulator and no physical devices for this fixture run.")
    with disposable_api() as api_port:
        proxy=PaymentTestProxy(api_port)
        try:
            subprocess.run([
                "./gradlew", ":apps:android-card:connectedDebugAndroidTest", ":apps:android-pos:connectedDebugAndroidTest",
                f"-PtapflowApiBaseUrl=http://10.0.2.2:{proxy.port}/api/v1",
                "-Pandroid.testInstrumentationRunnerArguments.tapflowLiveAuth=true", "--no-parallel", "--console=plain",
            ], check=True)
            assert proxy.injected and proxy.recovery_reads>=3, "Payment recovery fault was not exercised"
            print("Live authentication, card enrollment, POS pairing/restore/disable, interrupted-payment recovery, encrypted stores and NFC engine checks passed against disposable PostgreSQL.")
        finally:
            proxy.close()


if __name__ == "__main__":
    main()
