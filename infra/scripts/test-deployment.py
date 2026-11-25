#!/usr/bin/env python3
"""Verify an isolated four-service HTTPS stack. Only this run's disposable bind storage is cleaned."""
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid
from deployment_config import create
from database_ops import Target,backup,restore,metrics

ROOT=Path(__file__).resolve().parents[2]
PROJECT='tapflow-stage-'+uuid.uuid4().hex[:10]

def port():
    with socket.socket() as sock:sock.bind(('127.0.0.1',0));return sock.getsockname()[1]

def main():
    os.chdir(ROOT)
    https_port=port();http_port=port();origin=f'https://localhost:{https_port}'
    with tempfile.TemporaryDirectory(prefix='tapflow-stage-') as name:
        work=Path(name);config=work/'deployment';create(config,'localhost')
        # All storage belongs to this fixture; do not attach or delete existing named volumes.
        for directory in ['db','caddy-data','caddy-config']:(work/directory).mkdir(mode=0o777)
        for path in [work/'caddy-data',work/'caddy-config']:path.chmod(0o777)
        for seed in ['owner','operator','customer']:
            path=config/'secrets'/f'{seed}_password';path.chmod(0o600);path.write_text('instrumentation-only-passphrase\n');path.chmod(0o444)
        edge=work/'Caddyfile';edge.write_text('''{
    admin off
}
http://127.0.0.1:8081 {
    respond /healthz "ready" 200
}
https://localhost {
    tls internal
    header Strict-Transport-Security "max-age=31536000"
    reverse_proxy web:8080
}
''')
        override=work/'override.yml'
        override.write_text(f'''services:
  db:
    volumes: !override
      - {work/'db'}:/var/lib/postgresql/data
  api:
    environment:
      APP_ENV: staging
      APP_PUBLIC_URL: {origin}
      ALLOWED_ORIGINS: {origin}
  caddy:
    ports: !override ['127.0.0.1:{http_port}:80', '127.0.0.1:{https_port}:443']
    volumes: !override
      - {edge}:/etc/caddy/edge.Caddyfile:ro
      - {work/'caddy-data'}:/data
      - {work/'caddy-config'}:/config
volumes: !reset {{}}
''')
        env=os.environ.copy();env.update(API_IMAGE='tapflow-api:phase7',WEB_IMAGE='tapflow-web:phase7',DB_IMAGE='tapflow-db:phase7')
        compose=['docker','compose','--env-file',str(config/'.env'),'-p',PROJECT,'-f','infra/compose/prod.yml','-f',str(override)]
        def run(*args,**kwargs):return subprocess.run(compose+list(args),env=env,check=True,**kwargs)
        def text(*args):return subprocess.check_output(compose+list(args),env=env,text=True).strip()
        # PostgreSQL requires ownership, not just writable mode, for initdb on a bind directory.
        subprocess.run(['docker','run','--rm','--user','0','--entrypoint','sh','--mount',f'type=bind,src={work},dst=/fixture','postgres:17.11@sha256:748a18a54d3842d43bc156f0840d78029b8d349e0feca98c12b9c4ce3404a6b3','-c','chown 999:999 /fixture/db; chmod 700 /fixture/db'],check=True)
        try:
            run('config','--quiet')
            run('up','-d','--no-build','--wait','--wait-timeout','180')
            ids={service:text('ps','-q',service) for service in ['db','api','web','caddy']}
            for service,identifier in ids.items():
                info=json.loads(subprocess.check_output(['docker','inspect',identifier]))[0]
                assert info['State']['Health']['Status']=='healthy',service
                assert info['Config']['User'] not in ['', '0','root'],service
                if service in ['db','api','web']:assert not info['HostConfig']['PortBindings'],service
            ca=work/'stage-ca.crt'
            subprocess.run(['docker','cp',ids['caddy']+':/data/caddy/pki/authorities/local/root.crt',str(ca)],check=True)
            context=ssl.create_default_context(cafile=str(ca))
            for path in ['/','/api/v1/status','/login']:
                with urllib.request.urlopen(origin+path,context=context,timeout=10) as response:
                    assert response.status==200
                    assert response.headers['X-Content-Type-Options']=='nosniff'
                    assert "script-src 'self'" in response.headers['Content-Security-Policy']
            try:urllib.request.urlopen(origin+'/health/ready',context=context,timeout=5);raise AssertionError('Public health path must not serve SPA')
            except urllib.error.HTTPError as error:assert error.code==404
            run('run','--rm','--no-deps','--entrypoint','java','-e','APP_ENV=test',
                '-e','SEED_OWNER_PASSWORD_FILE=/seed/owner_password','-e','SEED_OPERATOR_PASSWORD_FILE=/seed/operator_password','-e','SEED_CUSTOMER_PASSWORD_FILE=/seed/customer_password',
                '-v',f'{config/"secrets"}:/seed:ro','api','-cp','/app/lib/*','com.tapflow.api.db.SeedDemoKt')
            browser_env=env|{'TAPFLOW_E2E_BASE_URL':origin,'TAPFLOW_E2E_TLS':'true','TAPFLOW_RECORD_DEMO':'true'}
            subprocess.run(['npx','--yes','pnpm@9.12.3','--dir','apps/web','test:e2e'],env=browser_env,check=True)
            source=Target(container=ids['db'],database='tapflow')
            counters=metrics(source);assert counters['approvedCount']==3 and counters['refundCount']==3,counters
            identity=work/'restore.agekey';recipient=work/'recipients'
            subprocess.run(['age-keygen','-o',str(identity)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            with recipient.open('wb') as out:subprocess.run(['age-keygen','-y',str(identity)],stdout=out,check=True)
            archive=backup(source,recipient,work/'backups')
            source.sql('create database tapflow_restore')
            restored=Target(container=ids['db'],database='tapflow_restore');restore(restored,archive,identity)
            assert metrics(restored)==counters
            assert restored.sql("select count(*) from tapflow.wallets w where w.balance_minor<>(select coalesce(sum(case when entry_type='DEBIT' then -amount_minor else amount_minor end),0) from tapflow.wallet_ledger l where l.wallet_id=w.id)").strip()=='0'
            run('stop','-t','15','api')
            logs=text('logs','--no-color','api');assert 'Shutdown completed' in logs,'API must drain and close its pool on SIGTERM'
            run('up','-d','--wait','--wait-timeout','120','api')
            assert metrics(source)==counters
            print('Verified HTTPS stack, CSP, non-root isolation, browser money flows, encrypted restore and graceful restart.')
        finally:
            log_path=Path('/tmp')/(PROJECT+'.log')
            descriptor=os.open(log_path,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
            with os.fdopen(descriptor,'w') as log:subprocess.run(compose+['logs','--no-color'],env=env,stdout=log,stderr=log,check=False)
            print('Private deployment log:',log_path,flush=True)
            run('down','--remove-orphans')
            if sys.platform != 'darwin':
                subprocess.run(['docker','run','--rm','--user','0','--entrypoint','sh','--mount',f'type=bind,src={work},dst=/fixture','postgres:17.11@sha256:748a18a54d3842d43bc156f0840d78029b8d349e0feca98c12b9c4ce3404a6b3','-c',f'chown -R {os.getuid()}:{os.getgid()} /fixture/db /fixture/caddy-data /fixture/caddy-config'],check=True)

if __name__=='__main__':main()
