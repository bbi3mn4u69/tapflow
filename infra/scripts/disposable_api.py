"""Isolated API fixture shared by browser and emulator integration gates. Never reads .env."""
import contextlib
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

@contextlib.contextmanager
def disposable_api(origin="http://localhost:5173"):
    root=Path(__file__).resolve().parents[2]
    os.chdir(root)
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else shutil.which("java")
    if not java:
        raise SystemExit("Set JAVA_HOME to JDK 21.")
    subprocess.run(["./gradlew", ":services:payment-api:installDist", "--console=plain"], check=True)
    name = "tapflow-api-test-" + uuid.uuid4().hex[:12]
    env = os.environ.copy()
    env["POSTGRES_PASSWORD"] = secrets.token_urlsafe(24)
    container = None
    api = None
    log = tempfile.NamedTemporaryFile(prefix="tapflow-api-test-", suffix=".log", delete=False)
    try:
        container = subprocess.check_output([
            "docker", "run", "-d", "--name", name, "--label", "com.tapflow.verification=integration",
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
                   APP_PUBLIC_URL=origin, ALLOWED_ORIGINS=origin,
                   DEMO_CUSTOMER_REGISTRATION_ENABLED="false",
                   DEMO_CARD_ENROLLMENT_ENABLED="true", DEMO_INITIAL_BALANCE_MINOR="10000")
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
        yield api_port
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
