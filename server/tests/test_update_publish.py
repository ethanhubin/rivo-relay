import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('publish_update', Path(__file__).resolve().parents[2] / 'scripts/publish-update.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class UpdatePublishTests(unittest.TestCase):
    def test_publish_is_complete_and_rejects_downgrade_or_changed_version(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            apk = root / 'app.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('AndroidManifest.xml', 'fixture')
                archive.writestr('assets/api-compatibility.json', '{"apiVersions":[1]}')
            first = module.publish(apk, root, '0.2.13', 25, 'notes')
            self.assertEqual({'apiVersions': [1]}, first['compatibility'])
            self.assertEqual(first, json.loads((root / 'updates/latest.json').read_text()))
            self.assertEqual(apk.read_bytes(), (root / 'updates/apks' / (first['sha256'] + '.apk')).read_bytes())
            with self.assertRaises(ValueError): module.publish(apk, root, '0.2.12', 24, '')
            with zipfile.ZipFile(apk, 'a') as archive: archive.writestr('changed', 'different')
            with self.assertRaises(ValueError): module.publish(apk, root, '0.2.13', 25, '')
            self.assertEqual(first, json.loads((root / 'updates/latest.json').read_text()))

    def test_concurrent_publication_is_rejected_before_manifest_changes(self):
        import fcntl
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            updates = root / 'updates'
            updates.mkdir()
            with (updates / '.publish.lock').open('a') as lock:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                with self.assertRaises(BlockingIOError):
                    module.publish(root / 'missing.apk', root, '0.2.24', 36, '')
            self.assertFalse((updates / 'latest.json').exists())
