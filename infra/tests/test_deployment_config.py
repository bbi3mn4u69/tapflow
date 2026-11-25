import sys
from pathlib import Path
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from deployment_config import create

class DeploymentConfigTest(unittest.TestCase):
    def test_creates_private_config_and_distinct_secrets_without_overwriting(self):
        with tempfile.TemporaryDirectory() as temp:
            target=Path(temp)/'deployment'
            create(target,'demo.example.test')
            self.assertEqual(target.stat().st_mode&0o777,0o700)
            self.assertEqual((target/'.env').stat().st_mode&0o777,0o600)
            paths=list((target/'secrets').iterdir())
            self.assertEqual(len(paths),5)
            values=[p.read_text().strip() for p in paths]
            self.assertEqual(len(set(values)),5)
            self.assertTrue(all(len(value)>=32 for value in values))
            self.assertNotIn(values[0],(target/'.env').read_text())
            with self.assertRaises(FileExistsError):create(target,'other.example.test')
            self.assertEqual(values,[p.read_text().strip() for p in paths])
    def test_invalid_host_does_not_create_any_files(self):
        with tempfile.TemporaryDirectory() as temp:
            for host in ['https://example.test','example.test/path','example.test:443','-bad.test','localhost\nBAD=true']:
                target=Path(temp)/'deployment'
                with self.assertRaises(ValueError):create(target,host)
                self.assertFalse(target.exists())

if __name__=='__main__':unittest.main()
