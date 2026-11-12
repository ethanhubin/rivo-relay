"""Focused regression for portable publishing and immutable main snapshots."""
import argparse
from contextlib import redirect_stdout
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from rivo_relay.release_management import configuration as config, diagnostics, setup, sources
from rivo_relay import __main__ as node

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
from release_tools import workflow, deployment, asr, compatibility
import test_release_cli as fixtures


class ManagementTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def configure(self, root, value):
        p = root / 'workspace/release-config.json'
        p.parent.mkdir(exist_ok=True)
        p.write_text(json.dumps(value))

    def git(self, root, *args):
        return subprocess.check_output(['git', '-C', str(root), *args], text=True, stderr=subprocess.PIPE).strip()

    def commit(self, root, text):
        (root/'code.txt').write_text(text)
        self.git(root, 'add', 'code.txt', '.gitignore')
        self.git(root, 'commit', '-qm', text)
        return self.git(root, 'rev-parse', 'HEAD')

    def test_fresh_main_excludes_local_work_and_resume_keeps_original_sha(self):
        upstream = self.root/'upstream'
        upstream.mkdir()
        self.git(upstream, 'init', '-q', '-b', 'main')
        self.git(upstream, 'config', 'user.name', 'Fixture')
        self.git(upstream, 'config', 'user.email', 'fixture@invalid')
        (upstream/'.gitignore').write_text('workspace/\n')
        package = upstream/'server/rivo_relay/release_management'
        package.mkdir(parents=True)
        (package/'__init__.py').write_text('')
        self.git(upstream, 'add', 'server')
        self.commit(upstream, 'first')
        local = self.root/'local'
        subprocess.run(['git', 'clone', '-q', str(upstream), str(local)], check=True)
        self.git(local, 'switch', '-qc', 'codex/unrelated')
        (local/'code.txt').write_text('uncommitted local work')
        self.configure(local, {})
        latest = self.commit(upstream, 'fresh main')
        checkout = sources.create(local)
        self.assertEqual('fresh main', (checkout/'code.txt').read_text())
        self.assertEqual(latest, self.git(checkout, 'rev-parse', 'HEAD'))
        self.assertEqual('uncommitted local work', (local/'code.txt').read_text())
        self.assertEqual('codex/unrelated', self.git(local, 'branch', '--show-current'))
        sources.register(local, checkout, {'version':'0.2.200', 'baseCommit':latest})
        self.commit(upstream, 'later main')
        self.assertEqual(checkout, sources.selected(local, '0.2.200'))
        self.assertEqual(latest, self.git(checkout, 'rev-parse', 'HEAD'))
        with self.assertRaisesRegex(RuntimeError, '已有同版本'):
            sources.register(local, checkout, {'version':'0.2.200', 'baseCommit':latest})
        with self.assertRaises(ValueError):
            sources.selected(local, '../escape')

    def test_environment_configuration_survives_background_job_and_preserves_other_signing(self):
        self.configure(self.root, {'python':'/chosen/python', 'javaHome':'/chosen/java', 'androidHome':'/chosen/sdk',
                                 'signingStore':'/private/rivo.keystore', 'gatewayHost':'fixture-vps'})
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual('/chosen/python', config.python(self.root))
            env = config.environment(self.root)
            self.assertEqual('/chosen/sdk', env['ANDROID_HOME'])
            self.assertEqual('/private/rivo.keystore', env['RIVO_RELEASE_KEYSTORE'])
            self.assertEqual(['--host', 'fixture-vps'], config.gateway_args(self.root))
        self.configure(self.root, {})
        self.assertEqual(sys.executable, config.python(self.root))

    def test_untrusted_configuration_is_rejected(self):
        for value in [{'gatewayHost':'-oProxyCommand=bad'}, {'mode':'manager','gatewayPin':'bad'}, {'unknown':True}]:
            with self.subTest(value=value):
                self.configure(self.root, value)
                with self.assertRaises(ValueError):
                    config.settings(self.root)

    def test_manager_deployment_never_touches_a_running_node(self):
        release = type('ReleaseFixture', (), {'root':self.root, 'directory':self.root,
                        'state':{'mode':'manager','steps':{}}, 'record':lambda self,*a,**kw:None})()
        with patch.object(deployment, 'native_identity', side_effect=AssertionError('must not inspect/restart node')):
            deployment.deploy(release)
        self.assertFalse(release.state['steps']['deployment']['serviceRestarted'])
        self.assertFalse(list(self.root.iterdir()))

    def test_node_setup_does_not_prepare_database_or_change_node_identity(self):
        with patch.object(node.Config, 'prepare', side_effect=AssertionError('must not initialize node')), \
                patch.object(setup, 'install', return_value={'installed':True}) as install, redirect_stdout(io.StringIO()):
            self.assertEqual(0, node.main(['setup-manager', '--source-node', 'fixture']))
            install.assert_called_once()

    def test_missing_authorization_does_not_mark_node_as_manager(self):
        from rivo_relay.gateway.config import encode_key
        workspace = self.root/'node'
        workspace.mkdir()
        (workspace/'gateway-client.json').write_text(json.dumps({'key':encode_key({
            'v':2,'slot':'a'*32,'secret':'s'*32,'pin':'b'*64,'gateway':'https://127.0.0.1:80'})}))
        args = argparse.Namespace(source_node='fixture', gateway_host='fixture-vps', workspace=workspace,
                                  home=self.root/'manager', repository=self.root/'repo', gateway_identity=None, asr_host=None)
        with patch.object(setup, 'discover', side_effect=RuntimeError('SSH not authorized')):
            with self.assertRaisesRegex(RuntimeError, 'not authorized'):
                setup.install(args)
        self.assertFalse((args.home/'installation.json').exists())
        self.assertFalse((args.home/'.setup-lock').exists())
        self.assertEqual(['gateway-client.json'], [p.name for p in workspace.iterdir()])


class ManagerPipelineTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.ReleaseCliTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)

    def test_manager_publishes_without_legacy_http_or_runtime_dependency(self):
        release = self.fixture.ready()
        release.state.update(mode='manager', apk=self.fixture.previous, commit='fixture')
        calls = []
        with patch.object(asr, 'deploy'), patch.object(release, 'source_unchanged'), \
                patch.object(release, 'command', side_effect=lambda args,log,**kw:calls.append(log)), \
                patch.object(workflow, 'verify_http', side_effect=AssertionError('no legacy HTTP requirement')), \
                patch.object(compatibility, 'mirror') as mirror:
            release.publish()
        self.assertEqual(['publication.log','gateway-publication.log'], calls)
        mirror.assert_called_once_with(release)
        self.assertEqual('complete', release.state['phase'])

    def test_vps_failure_never_becomes_complete_or_mirrors_unpublished_version(self):
        release = self.fixture.ready()
        release.state.update(mode='manager', apk=self.fixture.previous, commit='fixture')
        def command(args,log,**kw):
            if log == 'gateway-publication.log':
                raise RuntimeError('VPS failure')
        with patch.object(asr, 'deploy'), patch.object(release, 'source_unchanged'), \
                patch.object(release, 'command', side_effect=command), patch.object(compatibility, 'mirror') as mirror:
            with self.assertRaisesRegex(RuntimeError, 'VPS failure'):
                release.publish()
        self.assertNotEqual('complete', release.state['phase'])
        mirror.assert_not_called()

    def test_resume_accepts_local_staging_after_failed_vps_upload(self):
        release = self.fixture.ready()
        cfg = {'mode':'manager'}
        old = release.state['previous']
        own = dict(old, version='0.2.28',versionCode=40,sha256='a'*64)
        release.state.update(mode='manager',sourceTree='fixture',apk=own,
                             baseCommit=workflow.git(release.root, 'rev-parse', 'HEAD'))
        from release_tools.common import save
        save(release.root/'workspace/local-node/updates/latest.json',own)
        with patch.object(config,'settings',return_value=cfg), patch.object(release,'source_unchanged'), \
                patch('rivo_relay.release_management.central.baseline',return_value=old):
            release.preflight(False)
        with patch.object(config,'settings',return_value=cfg), patch.object(release,'source_unchanged'), \
                patch('rivo_relay.release_management.central.baseline',return_value=dict(own,versionCode=99)):
            with self.assertRaisesRegex(RuntimeError,'VPS 已有其他发布'):
                release.preflight(False)

    def test_manager_cannot_include_feature_edits_after_selecting_main(self):
        release = self.fixture.ready()
        release.state.update(mode='manager',baseCommit=workflow.git(release.root,'rev-parse','HEAD'))
        (release.root/'server/rivo_relay/__main__.py').write_text('unreleased local feature')
        with patch.object(config,'settings',return_value={'mode':'manager'}):
            with self.assertRaisesRegex(RuntimeError,'偏离已锁定'):
                release.preflight(True)

    def test_manager_rejects_non_version_build_changes(self):
        release = self.fixture.ready()
        release.state.update(mode='manager',baseCommit=workflow.git(release.root,'rev-parse','HEAD'))
        build = release.root/workflow.BUILD_FILE
        build.write_text(build.read_text() + '\ndependencies { implementation("unreleased:library:1.0") }\n')
        with patch.object(config,'settings',return_value={'mode':'manager'}), \
                patch('rivo_relay.release_management.central.baseline',return_value=release.state['previous']):
            with self.assertRaisesRegex(RuntimeError,'构建文件只允许版本号变化'):
                release.preflight(True)

    def test_legacy_mirror_is_atomic_and_outage_only_marks_pending(self):
        release = self.fixture.ready()
        previous = self.fixture.previous
        release.apk = release.root/'workspace/local-node/updates/apks'/(previous['sha256']+'.apk')
        target = release.root/'old-node'
        (target/'workspace').mkdir(parents=True)
        cfg = {'compatibility':{'host':'fixture','root':str(target),'python':sys.executable}}
        original_run = subprocess.run
        def local_ssh(args, **kwargs):
            return original_run([sys.executable,'-'], **kwargs)
        with patch.object(compatibility,'settings',return_value=cfg), \
                patch.object(compatibility.subprocess,'run',side_effect=local_ssh), \
                patch('release_tools.common.verify_http',return_value={'httpDownloadVerified':True}):
            compatibility.mirror(release)
        self.assertFalse(release.state['steps']['compatibility']['pending'])
        latest = target/'workspace/local-node/updates/latest.json'
        self.assertEqual(previous, json.loads(latest.read_text()))
        with patch.object(compatibility,'settings',return_value=cfg), \
                patch.object(compatibility.subprocess,'run',side_effect=OSError('offline')):
            compatibility.mirror(release)
        self.assertTrue(release.state['steps']['compatibility']['pending'])
        self.assertEqual(previous, json.loads(latest.read_text()))
