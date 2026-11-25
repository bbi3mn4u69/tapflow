"""Validate the opt-in local stack without starting or altering existing volumes."""
import json
import os
from pathlib import Path
import subprocess
import unittest

class DevComposeTest(unittest.TestCase):
    def test_stack_preserves_local_database_and_uses_private_tls(self):
        root=Path(__file__).resolve().parents[2]
        env=os.environ|{'LOCAL_DATABASE_PASSWORD':'config-check-only','JWT_SIGNING_SECRET':'config-check-only'}
        config=json.loads(subprocess.check_output(['docker','compose','-f','infra/compose/dev.yml','--profile','stack','config','--format','json'],cwd=root,env=env))
        services=config['services'];api=services['api'];web=services['web']
        self.assertIn('sslmode=require',api['environment']['DATABASE_URL'])
        self.assertEqual(api['environment']['DATABASE_PASSWORD'],'config-check-only')
        self.assertEqual(api['depends_on']['db']['condition'],'service_healthy')
        self.assertEqual(web['environment']['API_UPSTREAM'],'api:8080')
        self.assertEqual(config['volumes']['tapflow_dev_pgdata']['name'],'tapflow-dev_tapflow_dev_pgdata')
        self.assertTrue(all(port['host_ip']=='127.0.0.1' for service in services.values() for port in service.get('ports',[])))

if __name__=='__main__':unittest.main()
