#!/usr/bin/env python3
"""Encrypted PostgreSQL backup, authenticated empty-target restore and safe operational counters."""
import argparse
import configparser
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import uuid

CLIENT='postgres:18.6@sha256:e3d24f6906d0760bf4da0dae872415391ededefad23fd471c47dc53b0ddf3c42'

@dataclass
class Target:
    container:str|None=None
    service_file:Path|None=None
    user:str='tapflow'
    database:str|None=None
    def command(self,program,*extra):
        if bool(self.container)==bool(self.service_file):raise ValueError('Choose one container or service file')
        if self.container:
            if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}',self.container):raise ValueError('Invalid container name')
            command=['docker','exec','-i',self.container,program,f'--username={self.user}',f'--dbname={self.database or "tapflow"}']
        else:
            path=Path(self.service_file).resolve();config=configparser.ConfigParser(interpolation=None)
            with path.open() as source:config.read_file(source)
            if 'tapflow' not in config or config['tapflow'].get('sslmode') not in ['require','verify-ca','verify-full'] or not config['tapflow'].get('user'):
                raise ValueError('The tapflow PostgreSQL service must specify user and require TLS')
            command=['docker','run','--rm','-i','--read-only','--user',f'{os.getuid()}:{os.getgid()}','--tmpfs','/tmp','--tmpfs','/var/lib/postgresql','--mount',f'type=bind,src={path},dst=/run/secrets/service.conf,readonly','--env','PGSERVICEFILE=/run/secrets/service.conf','--env','PGSERVICE=tapflow','--entrypoint',program,CLIENT]
            if self.database:command.append(f'--dbname={self.database}')
        return command+list(extra)
    def sql(self,query):
        result=subprocess.run(self.command('psql','-X','-A','-t','-v','ON_ERROR_STOP=1','-c',query),capture_output=True,text=True)
        if result.returncode:raise RuntimeError('PostgreSQL query failed; credentials and server details were withheld')
        return result.stdout


def backup(target:Target,recipients:Path,directory:Path):
    directory.mkdir(mode=0o700,parents=True,exist_ok=True)
    name='tapflow-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'-'+uuid.uuid4().hex[:12]+'.dump.age'
    destination=directory/name
    with tempfile.NamedTemporaryFile(dir=directory,prefix='.backup-',delete=False) as output:
        temporary=Path(output.name)
        try:
            with tempfile.TemporaryFile() as errors:
                dump=subprocess.Popen(target.command('pg_dump','--format=custom','--no-owner','--no-acl'),stdout=subprocess.PIPE,stderr=errors)
                try:
                    encrypt=subprocess.run(['age','--encrypt','--recipients-file',str(recipients)],stdin=dump.stdout,stdout=output,stderr=errors)
                    dump.stdout.close()
                    if dump.wait()!=0 or encrypt.returncode:raise RuntimeError('Encrypted backup failed; no backup was published')
                finally:
                    if dump.poll() is None:dump.kill();dump.wait()
            output.flush();os.fsync(output.fileno())
            temporary.rename(destination)
        finally:
            temporary.unlink(missing_ok=True)
    with destination.open('rb') as source: digest=hashlib.file_digest(source,'sha256').hexdigest()
    checksum=destination.with_suffix(destination.suffix+'.sha256')
    fd=os.open(checksum,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'w') as out:out.write(digest+'  '+destination.name+'\n')
    return destination


def restore(target:Target,archive:Path,identity:Path):
    objects=int(target.sql("select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname not like 'pg_%' and n.nspname<>'information_schema' and c.relkind in ('r','p','S','v','m','f')"))
    if objects or int(target.sql("select count(*) from pg_namespace where nspname='tapflow'")):
        raise ValueError('Restore refused: target must be a new empty database; no objects were removed')
    # Authenticate all ciphertext before SQL; the anonymous file is reclaimed even on process exit.
    with tempfile.TemporaryFile() as plaintext,tempfile.TemporaryFile() as errors:
        decrypt=subprocess.run(['age','--decrypt','--identity',str(identity),str(archive)],stdout=plaintext,stderr=errors)
        if decrypt.returncode:raise RuntimeError('Backup authentication/decryption failed; target was not changed')
        plaintext.seek(0)
        result=subprocess.run(target.command('pg_restore','--exit-on-error','--single-transaction','--no-owner','--no-acl'),stdin=plaintext,stdout=subprocess.DEVNULL,stderr=errors)
        if result.returncode:raise RuntimeError('Restore failed; PostgreSQL rolled back the restore transaction')


def metrics(target:Target):
    query="""select json_build_object('event','database.metrics','approvedCount',(select count(*) from tapflow.payment_intents where status='APPROVED'),'declinedCount',(select count(*) from tapflow.payment_intents where status='DECLINED'),'expiredCount',(select count(*) from tapflow.payment_intents where status='EXPIRED'),'refundCount',(select count(*) from tapflow.refunds),'refundedMinor',(select coalesce(sum(amount_minor),0) from tapflow.refunds))"""
    return json.loads(target.sql(query))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation',choices=['backup','restore','metrics'])
    connection=parser.add_mutually_exclusive_group(required=True)
    connection.add_argument('--container');connection.add_argument('--service-file',type=Path)
    parser.add_argument('--user',default='tapflow');parser.add_argument('--database')
    parser.add_argument('--recipients-file',type=Path);parser.add_argument('--directory',type=Path)
    parser.add_argument('--archive',type=Path);parser.add_argument('--identity-file',type=Path)
    parser.add_argument('--confirm-empty-target',action='store_true')
    parser.add_argument('--retain-days',type=int);parser.add_argument('--offsite')
    args=parser.parse_args();target=Target(args.container,args.service_file,args.user,args.database)
    if args.operation=='metrics':print(json.dumps(metrics(target)));return
    if args.operation=='restore':
        if not(args.confirm_empty_target and args.archive and args.identity_file):parser.error('Restore needs --archive, --identity-file and --confirm-empty-target')
        restore(target,args.archive,args.identity_file);print('Restore committed to the empty target.');return
    if not(args.directory and args.recipients_file):parser.error('Backup needs --directory and --recipients-file')
    if args.retain_days is not None and args.retain_days<1:parser.error('Retention must be at least one day')
    archive=backup(target,args.recipients_file,args.directory)
    if args.offsite:
        if not re.fullmatch(r'[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+:/[A-Za-z0-9_./-]+',args.offsite):parser.error('Offsite must be user@host:/absolute/path')
        subprocess.run(['scp','-B',str(archive),str(archive)+'.sha256',args.offsite],check=True)
    if args.retain_days is not None:
        cutoff=time.time()-args.retain_days*86400
        for old in args.directory.glob('tapflow-*.dump.age'):
            if old!=archive and old.stat().st_mtime<cutoff:
                old.unlink();old.with_suffix(old.suffix+'.sha256').unlink(missing_ok=True)
    print('Encrypted backup created:',archive)

if __name__=='__main__':main()
