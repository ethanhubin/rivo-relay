"""Contract discovery and APK metadata; no production services or devices."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from unittest.mock import patch

from relay_test_support.http import RelayHTTPFixture
from rivo_relay.compatibility import apk_compatibility, validate_app
from rivo_relay.updates import latest_update


class NodeContractTests(RelayHTTPFixture, unittest.TestCase):
    def test_discovery_preserves_auth_identity_and_existing_capabilities(self):
        self.assertEqual(401, self.request('GET', '/v1/node')[0])
        with patch.dict('os.environ', {'RIVO_RELEASE_CODE': '999'}):
            status, info, _ = self.request('GET', '/v1/node', token=self.token)
        self.assertEqual(200, status)
        self.assertEqual(self.config.node_id, info['id'])
        self.assertEqual([1], info['compatibility']['apiVersions'])
        self.assertIn('session.goals', info['compatibility']['features']['1'])
        self.assertTrue(info['capabilities']['nativeTestStub'])
        self.assertEqual(999, info['releaseCode'])

    def test_old_client_without_negotiation_keeps_basic_reads_and_deduplicated_text(self):
        for path in ('/v1/projects', '/v1/threads?projectId=project-1',
                     '/v1/threads/thread-1', '/v1/threads/thread-1/history'):
            self.assertEqual(200, self.request('GET', path, token=self.token)[0], path)
        body = {'submissionId': 'compat-text', 'text': 'hello'}
        first = self.request('POST', '/v1/threads/thread-1/turns', body, token=self.token)
        second = self.request('POST', '/v1/threads/thread-1/turns', body, token=self.token)
        self.assertEqual(200, first[0])
        self.assertEqual('accepted', first[1]['status'])
        self.assertEqual(first[1], second[1])
        self.assertEqual(1, len(self.native.sent))
        self.assertEqual(first[1], self.request('GET', '/v1/submissions/compat-text', token=self.token)[1])


class AppContractTests(unittest.TestCase):
    def test_invalid_metadata_is_not_coerced(self):
        for value in (None, {}, {'apiVersions': []}, {'apiVersions': [True]},
                      {'apiVersions': [1, 1]}, {'apiVersions': ['1']}, {'apiVersions': [1.0]}, {'apiVersions': [0]}):
            with self.subTest(value=value), self.assertRaises(ValueError):
                validate_app(value)

    def test_publication_reads_exact_apk_contract_and_local_http_projection_preserves_it(self):
        spec = importlib.util.spec_from_file_location('publish_compat', Path(__file__).resolve().parents[2] / 'scripts/publish-update.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / 'app.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('AndroidManifest.xml', 'fixture')
                archive.writestr('assets/api-compatibility.json', '{"apiVersions":[1,3]}')
            item = module.publish(apk, root, '1.0.0', 1, '')
            self.assertEqual({'apiVersions': [1, 3]}, item['compatibility'])
            self.assertEqual(item['compatibility'], latest_update(root)['compatibility'])
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('AndroidManifest.xml', 'fixture')
            with self.assertRaises(KeyError):
                module.publish(apk, root, '1.0.1', 2, '')
            self.assertEqual(item['compatibility'], latest_update(root)['compatibility'])

    def test_gateway_bundle_preserves_apk_contract_and_rejects_manifest_mismatch(self):
        import hashlib
        import io
        root_source = Path(__file__).resolve().parents[2]
        spec = importlib.util.spec_from_file_location('gateway_compat', root_source / 'scripts/publish-gateway-update.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        source = io.BytesIO()
        with zipfile.ZipFile(source, 'w') as archive:
            archive.writestr('server/rivo_relay/__init__.py', '')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / 'app.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('assets/api-compatibility.json', '{"apiVersions":[1,3]}')
            item = {'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'size': apk.stat().st_size,
                    'version': '1.0.0', 'versionCode': 1, 'notes': '', 'compatibility': {'apiVersions': [1, 3]}}
            for mismatch in (False, True):
                with patch.object(module.subprocess, 'check_output', side_effect=[
                        'versionCode = 1\nversionName = "1.0.0"', source.getvalue()]):
                    if mismatch:
                        item['compatibility'] = {'apiVersions': [2]}
                        with self.assertRaisesRegex(ValueError, 'compatibility differs'):
                            module.bundle(root_source, 'fixture', apk, item, root)
                    else:
                        result = module.bundle(root_source, 'fixture', apk, item, root)
                        self.assertEqual(item['compatibility'], result['compatibility'])

    def test_legacy_gateway_manifest_remains_readable_without_inventing_compatibility(self):
        from test_central_updates import release
        from rivo_relay.gateway.update_store import validate
        with tempfile.TemporaryDirectory() as directory:
            item = release(Path(directory), 1)
            item.pop('compatibility')
            self.assertNotIn('compatibility', validate(item))
