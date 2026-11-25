"""Real PostgreSQL/age acceptance, isolated from Compose and Railway."""
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import time
import unittest
import uuid
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
import database_ops as ops

IMAGE='postgres:17.11@sha256:748a18a54d3842d43bc156f0840d78029b8d349e0feca98c12b9c4ce3404a6b3'
class BackupTest(unittest.TestCase):
    def test_encrypted_backup_authenticated_restore_and_nonempty_guard(self):
        names=['tapflow-backup-test-'+uuid.uuid4().hex[:12] for _ in range(2)]
        env=os.environ.copy();env['POSTGRES_PASSWORD']=secrets.token_urlsafe(32)
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp);identity=directory/'identity';recipients=directory/'recipients'
            subprocess.run(['age-keygen','-o',str(identity)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True)
            with recipients.open('wb') as out:subprocess.run(['age-keygen','-y',str(identity)],stdout=out,check=True)
            try:
                for name in names:
                    subprocess.run(['docker','run','-d','--name',name,'--label','com.tapflow.verification=backup','--tmpfs','/var/lib/postgresql/data:uid=999,gid=999,mode=0700','--env','POSTGRES_PASSWORD','--env','POSTGRES_DB=tapflow',IMAGE],env=env,stdout=subprocess.DEVNULL,check=True)
                    for _ in range(60):
                        if subprocess.run(['docker','exec',name,'pg_isready','-U','postgres','-d','tapflow'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:break
                        time.sleep(.25)
                    else:self.fail('Disposable PostgreSQL did not start')
                source=ops.Target(container=names[0],user='postgres',database='tapflow');target=ops.Target(container=names[1],user='postgres',database='tapflow')
                source.sql("create schema tapflow; create table tapflow.marker(value text); insert into tapflow.marker values ('private-backup-marker');")
                archive=ops.backup(source,recipients,directory/'backups')
                self.assertNotIn(b'private-backup-marker',archive.read_bytes());self.assertEqual(archive.stat().st_mode&0o777,0o600)
                broken=directory/'broken.age';raw=bytearray(archive.read_bytes());raw[-1]^=1;broken.write_bytes(raw)
                with self.assertRaises(RuntimeError):ops.restore(target,broken,identity)
                self.assertEqual(target.sql("select count(*) from pg_namespace where nspname='tapflow'").strip(),'0')
                ops.restore(target,archive,identity)
                self.assertEqual(target.sql('select value from tapflow.marker').strip(),'private-backup-marker')
                with self.assertRaises(ValueError):ops.restore(target,archive,identity)
                self.assertEqual(target.sql('select count(*) from tapflow.marker').strip(),'1')
            finally:
                for name in names:subprocess.run(['docker','rm','-f',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=False)
if __name__=='__main__':unittest.main()
