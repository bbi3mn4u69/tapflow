#!/usr/bin/env python3
"""Run real browser/API/refund tests using an isolated PostgreSQL fixture, never Railway."""
import os
import socket
import subprocess
import tempfile
import time
import urllib.request
from pathlib import Path
from disposable_api import disposable_api


def main():
    root=Path(__file__).resolve().parents[2]
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1',0))
        web_port=reservation.getsockname()[1]
    origin=f'http://127.0.0.1:{web_port}'
    with disposable_api(origin) as api_port:
        env=os.environ.copy()
        env.update(TAPFLOW_TEST_API_ORIGIN=f'http://127.0.0.1:{api_port}',TAPFLOW_E2E_BASE_URL=origin)
        with tempfile.TemporaryFile() as log:
            web=subprocess.Popen(['npx','--yes','pnpm@9.12.3','--dir','apps/web','dev','--port',str(web_port),'--strictPort'],cwd=root,env=env,stdout=log,stderr=log,start_new_session=True)
            try:
                for _ in range(60):
                    if web.poll() is not None:raise RuntimeError('Disposable Vite server exited')
                    try:
                        with urllib.request.urlopen(origin,timeout=2) as response:
                            if response.status==200:break
                    except OSError:pass
                    time.sleep(.5)
                else:raise RuntimeError('Disposable web readiness timeout')
                subprocess.run(['npx','--yes','pnpm@9.12.3','--dir','apps/web','test:e2e'],cwd=root,env=env,check=True)
            finally:
                import signal
                os.killpg(web.pid,signal.SIGTERM)
                web.wait(timeout=10)


if __name__=='__main__':
    main()
