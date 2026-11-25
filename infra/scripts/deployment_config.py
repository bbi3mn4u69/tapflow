#!/usr/bin/env python3
"""Create new local Compose deployment configuration without reading or changing runtime .env."""
import argparse
import os
from pathlib import Path
import re
import secrets


def create(target:Path,hostname:str):
    if len(hostname)>253 or not all(re.fullmatch(r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?',label) for label in hostname.split('.')):
        raise ValueError('Supply a hostname without scheme, port or path')
    target.mkdir(mode=0o700,parents=False,exist_ok=False)
    secret_dir=target/'secrets'
    secret_dir.mkdir(mode=0o700)
    for name in ['database_password','jwt_signing_secret','owner_password','operator_password','customer_password']:
        path=secret_dir/name
        descriptor=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(descriptor,'w') as output:output.write(secrets.token_urlsafe(48)+'\n')
        # Compose bind secrets retain host modes; the enclosing directory prevents host-user access.
        path.chmod(0o444)
    content=f'''# DEMO / NO REAL MONEY. Replace image tags with verified immutable releases.
PUBLIC_HOSTNAME={hostname}
API_IMAGE=tapflow-api:local
WEB_IMAGE=tapflow-web:local
DB_IMAGE=tapflow-db:local
DATABASE_PASSWORD_FILE={(secret_dir/'database_password').resolve()}
JWT_SIGNING_SECRET_FILE={(secret_dir/'jwt_signing_secret').resolve()}
'''
    descriptor=os.open(target/'.env',os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(descriptor,'w') as output:output.write(content)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory',type=Path)
    parser.add_argument('--hostname',required=True)
    args=parser.parse_args()
    create(args.directory,args.hostname)
    print('Deployment configuration created. Secret values were not printed.')
