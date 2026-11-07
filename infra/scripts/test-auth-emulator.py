#!/usr/bin/env python3
"""Run mobile sign-in tests against a disposable API/database, never the runtime .env."""
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.request
import uuid


def main():
    root = Path(__file__).resolve().parents[2]
    os.chdir(root)
    adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb") if "ANDROID_HOME" in os.environ else shutil.which("adb")
    if not adb:
        raise SystemExit("Set ANDROID_HOME or put adb on PATH; start one emulator first.")
    devices = [line.split() for line in subprocess.check_output([adb, "devices"], text=True).splitlines()[1:] if line.strip()]
    if len(devices) != 1 or not devices[0][0].startswith("emulator-") or devices[0][1] != "device":
        raise SystemExit("Connect exactly one ready emulator and no physical devices for this fixture run.")
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else shutil.which("java")
    if not java:
        raise SystemExit("Set JAVA_HOME to JDK 21.")
    subprocess.run(["./gradlew", ":services:payment-api:installDist", "--console=plain"], check=True)
    name = "tapflow-auth-test-" + uuid.uuid4().hex[:12]
    env = os.environ.copy()
    env["POSTGRES_PASSWORD"] = secrets.token_urlsafe(24)
    container = None
    api = None
    log = tempfile.NamedTemporaryFile(prefix="tapflow-auth-test-", suffix=".log", delete=False)
    try:
        container = subprocess.check_output([
            "docker", "run", "-d", "--name", name, "--label", "com.tapflow.verification=auth-emulator",
            "--publish", "127.0.0.1:0:5432", "--env", "POSTGRES_PASSWORD", "--env", "POSTGRES_DB=tapflow",
            "postgres@sha256:748a18a54d3842d43bc156f0840d78029b8d349e0feca98c12b9c4ce3404a6b3",
        ], env=env, text=True).strip()
        for _ in range(40):
            if subprocess.run(["docker", "exec", container, "pg_isready", "-U", "postgres", "-d", "tapflow"], stdout=log, stderr=log).returncode == 0:
                break
            time.sleep(0.5)
        else:
            raise RuntimeError("Disposable PostgreSQL did not become ready")
        info = json.loads(subprocess.check_output(["docker", "inspect", container]))[0]
        db_port = info["NetworkSettings"]["Ports"]["5432/tcp"][0]["HostPort"]
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            api_port = reservation.getsockname()[1]
        env.update(APP_ENV="test", API_HOST="127.0.0.1", API_PORT=str(api_port), DEMO_MODE="true",
                   DATABASE_URL=f"jdbc:postgresql://localhost:{db_port}/tapflow", DATABASE_USER="postgres",
                   DATABASE_PASSWORD=env["POSTGRES_PASSWORD"], DATABASE_POOL_SIZE="3",
                   JWT_SIGNING_SECRET=secrets.token_urlsafe(48), JWT_ISSUER="tapflow-instrumentation",
                   JWT_AUDIENCE="tapflow-clients", ACCESS_TOKEN_TTL_SECONDS="900", REFRESH_TOKEN_TTL_DAYS="14",
                   APP_PUBLIC_URL="http://localhost:5173", ALLOWED_ORIGINS="http://localhost:5173",
                   DEMO_CUSTOMER_REGISTRATION_ENABLED="false")
        for key in ("SEED_OWNER_PASSWORD", "SEED_OPERATOR_PASSWORD", "SEED_CUSTOMER_PASSWORD"):
            env[key] = "instrumentation-only-passphrase"
        classpath = str(root / "services/payment-api/build/install/payment-api/lib/*")
        subprocess.run([java, "-cp", classpath, "com.tapflow.api.db.SeedDemoKt"], env=env, stdout=log, stderr=log, check=True)
        api = subprocess.Popen([java, "-cp", classpath, "com.tapflow.api.ApplicationKt"], env=env, stdout=log, stderr=log)
        for _ in range(40):
            if api.poll() is not None:
                raise RuntimeError("Disposable API exited before readiness")
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{api_port}/health/ready", timeout=2) as response:
                    if response.status == 200:
                        break
            except (OSError, TimeoutError):
                pass
            time.sleep(0.5)
        else:
            raise RuntimeError("Disposable API did not become ready")
        subprocess.run([
            "./gradlew", ":apps:android-card:connectedDebugAndroidTest", ":apps:android-pos:connectedDebugAndroidTest",
            f"-PtapflowApiBaseUrl=http://10.0.2.2:{api_port}/api/v1",
            "-Pandroid.testInstrumentationRunnerArguments.tapflowLiveAuth=true", "--no-parallel", "--console=plain",
        ], check=True)
        print("Live sign-in, encrypted restoration and logout passed against disposable PostgreSQL.")
    finally:
        if api is not None and api.poll() is None:
            api.terminate()
            try:
                api.wait(timeout=10)
            except subprocess.TimeoutExpired:
                api.kill()
                api.wait(timeout=5)
        if container is not None:
            subprocess.run(["docker", "rm", "-fv", container], stdout=subprocess.DEVNULL, timeout=15, check=False)
        log.close()
        print(f"Disposable API log: {log.name}")


if __name__ == "__main__":
    main()
