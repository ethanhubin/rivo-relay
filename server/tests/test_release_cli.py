"""Release CLI tests use temporary Git repos/HTTP fixtures, never real services."""
import argparse
from contextlib import redirect_stderr, redirect_stdout
import hashlib
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import io
from pathlib import Path
import plistlib
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
from release_tools import common as c, deployment as d, workflow as w
import release as cli


class ReleaseCliTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        c.git(self.root, 'init', '-q')
        c.git(self.root, 'config', 'user.name', 'Release fixture')
        c.git(self.root, 'config', 'user.email', 'fixture@example.invalid')
        (self.root / '.gitignore').write_text('workspace/\n.venv/\nsdk/\njava/\n')
        build = self.root / w.BUILD_FILE
        build.parent.mkdir(parents=True)
        build.write_text('versionCode = 39\nversionName = "0.2.27"\n')
        server = self.root / 'server/rivo_relay'
        server.mkdir(parents=True)
        (server / '__main__.py').write_text('print("fixture")\n')
        c.git(self.root, 'add', '.')
        c.git(self.root, 'commit', '-qm', 'fixture')
        self.previous = {'version': '0.2.27', 'versionCode': 39, 'sha256': hashlib.sha256(b'previous').hexdigest(),
                         'size': 8, 'notes': 'old'}
        c.save(self.root / 'workspace/local-node/updates/latest.json', self.previous)
        previous_apk = self.root / 'workspace/local-node/updates/apks' / (self.previous['sha256'] + '.apk')
        previous_apk.parent.mkdir()
        previous_apk.write_bytes(b'previous')
        self.args = argparse.Namespace(version=None, notes=None, android_test=[], server_test=['test_example'],
                                       checks_note=None, device_scope='未进行真机或模拟器验证')

    def ready(self):
        state = w.prepare(self.root, self.args)
        note = self.root / state['notes']
        note.write_text(note.read_text().replace('TODO：填写本次变更。', '会话同步优化。'))
        return w.Release(self.root, state['version'])

    def test_central_publication_failure_is_not_complete_and_can_resume(self):
        release = self.ready()
        release.state['apk'] = self.previous
        release.state['commit'] = c.git(self.root, 'rev-parse', 'HEAD')
        def command(args, log, **kwargs):
            if log == 'gateway-publication.log': raise RuntimeError('VPS unavailable')
        with patch.object(release, 'source_unchanged'), patch.object(w, 'verify_http', return_value={}), \
                patch.object(release, 'command', side_effect=command):
            with self.assertRaisesRegex(RuntimeError, 'VPS unavailable'): release.publish()
        self.assertEqual(release.state['phase'], 'publishing-gateway')
        self.assertNotIn('gateway', release.state['steps'])
        with patch.object(release, 'source_unchanged'), patch.object(w, 'verify_http', return_value={}), \
                patch.object(release, 'command'):
            release.publish()
        self.assertEqual(release.state['phase'], 'complete')
        self.assertTrue(release.state['steps']['gateway']['verified'])

    def test_retry_recovers_crash_after_log_rename(self):
        release = self.ready()
        release.state['deploymentRequested'] = True
        plist = release.directory / 'current.plist'
        plist.write_bytes(b'baseline')
        (release.directory / 'launchagent-before.plist').write_bytes(b'baseline')
        log = release.directory / 'deployment.log'
        log.write_text('{"event":"rollback_complete"}\n')
        original = release.record
        def interrupted(phase, **fields):
            if phase == 'deployment-prepared':
                raise RuntimeError('injected crash')
            original(phase, **fields)
        with patch.object(release, 'record', side_effect=interrupted):
            with self.assertRaisesRegex(RuntimeError, 'injected crash'):
                d.prepare_retry(release, plist, log)
        resumed = w.Release(self.root, release.state['version'])
        self.assertTrue(resumed.state['deploymentRequested'])
        self.assertFalse(log.exists())
        d.prepare_retry(resumed, plist, log)
        self.assertFalse(resumed.state['deploymentRequested'])
        self.assertIsNone(resumed.state['deploymentRetryArchive'])

    def test_pending_deployment_reconciled_before_build_and_publication(self):
        release = self.ready()
        release.record('deploying', deploymentRequested=True, apk={'verified': True}, commit='fixture')
        order = []
        def deploy(_):
            order.append('deploy')
            release.state['steps']['deployment'] = {'verified': True}
        with patch.object(release, 'preflight', side_effect=lambda _: order.append('preflight')), \
                patch.object(d, 'deploy', side_effect=deploy), \
                patch.object(release, 'validate', side_effect=lambda: order.append('validate')), \
                patch.object(release, 'build'), patch.object(release, 'commit'), patch.object(release, 'publish'):
            release.run()
        self.assertEqual(order[:4], ['preflight', 'deploy', 'preflight', 'validate'])

    def test_pending_recovery_failure_prevents_build_and_publication(self):
        release = self.ready()
        release.record('deploying', deploymentRequested=True, apk={'verified': True}, commit='fixture')
        with patch.object(release, 'preflight'), patch.object(release, 'build') as build, \
                patch.object(release, 'publish') as publish, \
                patch.object(d, 'deploy', side_effect=RuntimeError('rollback_failed')):
            with self.assertRaisesRegex(RuntimeError, 'rollback_failed'):
                release.run()
            build.assert_not_called()
            publish.assert_not_called()

    def test_completed_release_refuses_publication_when_service_changed(self):
        release = self.ready()
        release.state['steps']['deployment'] = {'verified': True}
        release.record('complete')
        with patch.object(release, 'preflight'), patch.object(release, 'build') as build, \
                patch.object(release, 'publish') as publish, \
                patch.object(d, 'deploy', side_effect=RuntimeError('service changed')):
            with self.assertRaisesRegex(RuntimeError, 'service changed'):
                release.run()
            build.assert_not_called()
            publish.assert_not_called()

    def test_pending_preflight_does_not_require_http(self):
        release = self.ready()
        release.state.update(sourceTree='fixture', deploymentRequested=True)
        with patch.object(release, 'source_unchanged'), patch.object(w, 'get_json', side_effect=OSError('offline')) as http:
            release.preflight(False)
            http.assert_not_called()
            release.state['steps']['deployment'] = {'verified': True}
            with self.assertRaises(OSError):
                release.preflight(False)

    def test_prepare_only_changes_version_notes_and_plan(self):
        original_head = c.git(self.root, 'rev-parse', 'HEAD')
        state = w.prepare(self.root, self.args)
        self.assertEqual(('0.2.28', 40), w.build_version(self.root))
        self.assertEqual('prepared', state['phase'])
        self.assertEqual(original_head, c.git(self.root, 'rev-parse', 'HEAD'))
        self.assertEqual(self.previous, c.load(self.root / 'workspace/local-node/updates/latest.json'))
        self.assertIn('TODO', (self.root / state['notes']).read_text())
        self.assertIn(self.args.device_scope, (self.root / state['notes']).read_text())

    def test_prepare_requires_checks_and_rejects_downgrades_without_mutation(self):
        self.args.server_test = []
        with self.assertRaisesRegex(RuntimeError, '指定相关'):
            w.prepare(self.root, self.args)
        self.args.checks_note = 'Existing focused checks documented separately'
        self.args.version = '0.2.26'
        with self.assertRaisesRegex(RuntimeError, '必须高于'):
            w.prepare(self.root, self.args)
        self.assertEqual(('0.2.27', 39), w.build_version(self.root))
        self.assertFalse((self.root / 'workspace/artifacts').exists())

    def test_test_selectors_cannot_turn_a_check_into_help_or_other_options(self):
        self.args.server_test = ['--help']
        with self.assertRaisesRegex(RuntimeError, '不能传入命令选项'):
            w.prepare(self.root, self.args)
        self.assertEqual(('0.2.27', 39), w.build_version(self.root))

    def test_cli_submission_is_detached_and_status_checks_the_release_lock(self):
        release = self.ready()
        python = self.root / '.venv/bin/python'
        python.parent.mkdir(parents=True)
        python.write_text('fixture')
        with patch.object(cli, 'ROOT', self.root), patch.object(cli.subprocess, 'run') as spawn, redirect_stdout(io.StringIO()):
            spawn.return_value.returncode = 0
            spawn.return_value.stderr = ''
            self.assertEqual(0, cli.main(['run', '0.2.28', '--commit']))
            self.assertEqual(spawn.call_args.args[0][:2], ['launchctl', 'bootstrap'])
            submitted = __import__('plistlib').loads(Path(spawn.call_args.args[0][-1]).read_bytes())
            self.assertIs(submitted['KeepAlive'], False)
            self.assertIn('--worker', submitted['ProgramArguments'])
            self.assertIn('--commit', submitted['ProgramArguments'])
            spawn.return_value.returncode = 5
            spawn.return_value.stderr = 'submission rejected'
            with redirect_stderr(io.StringIO()):
                self.assertEqual(1, cli.main(['run', '0.2.28']))
        with c.locked(self.root / 'workspace/.release-cli.lock') as stream:
            stream.write(json.dumps({'version': '0.2.28'}))
            stream.flush()
            with patch.object(cli, 'ROOT', self.root), redirect_stdout(io.StringIO()) as printed:
                self.assertEqual(0, cli.main(['status', '0.2.28']))
                self.assertTrue(json.loads(printed.getvalue())['running'])
            with self.assertRaisesRegex(RuntimeError, '另一个发布进程'):
                with c.locked(self.root / 'workspace/.release-cli.lock'):
                    self.fail('lock should be exclusive')
        self.assertEqual('prepared', c.load(release.state_file)['phase'])

    def test_test_only_server_changes_do_not_require_service_restart(self):
        server = self.root / 'server'
        before = d.tree_digest(server)
        (server / 'tests').mkdir()
        (server / 'tests/test_new.py').write_text('test fixture')
        self.assertEqual(before, d.tree_digest(server))
        (server / 'rivo_relay/__main__.py').write_text('runtime changed')
        self.assertNotEqual(before, d.tree_digest(server))

    def test_version_code_advances_past_published_version_and_refuses_existing_artifacts(self):
        previous = dict(self.previous, version='0.2.29', versionCode=45)
        c.save(self.root / 'workspace/local-node/updates/latest.json', previous)
        (self.root / 'workspace/artifacts/release-0.2.30').mkdir(parents=True)
        with self.assertRaisesRegex(RuntimeError, '发布目录已存在'):
            w.prepare(self.root, self.args)
        self.args.version = '0.2.31'
        state = w.prepare(self.root, self.args)
        self.assertEqual(46, state['versionCode'])

    def test_unstaged_or_untracked_code_is_not_silently_committed(self):
        release = self.ready()
        (self.root / 'server/rivo_relay/__main__.py').write_text('changed\n')
        with self.assertRaisesRegex(RuntimeError, '未暂存'):
            release.preflight(True)
        self.assertEqual('fixture', c.git(self.root, 'log', '-1', '--format=%s'))
        c.git(self.root, 'add', 'server')
        (self.root / 'new.py').write_text('new\n')
        with self.assertRaisesRegex(RuntimeError, '未纳入提交'):
            release.preflight(True)

    def test_resume_recovers_commit_without_creating_duplicate(self):
        release = self.ready()
        with patch.object(w, 'get_json', return_value=self.previous):
            release.preflight(True)
        release.commit()
        commit = c.git(self.root, 'rev-parse', 'HEAD')
        # Simulate losing the state write after git commit succeeded.
        release.state.pop('commit')
        release.commit()
        self.assertEqual(commit, release.state['commit'])
        (self.root / 'server/rivo_relay/__main__.py').write_text('later edit\n')
        with self.assertRaisesRegex(RuntimeError, '未暂存'):
            release.source_unchanged()

    def test_check_key_reuses_version_only_changes_but_not_source_or_test_changes(self):
        release = self.ready()
        for name in ('.venv/bin/python', 'java/bin/java', 'sdk/build-tools/37.0.0/aapt', 'sdk/build-tools/37.0.0/apksigner'):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture')
        env = {'JAVA_HOME': str(self.root / 'java'), 'ANDROID_HOME': str(self.root / 'sdk')}
        first = w.validation_key(self.root, release.state, env)
        (self.root / w.BUILD_FILE).write_text('versionCode = 99\nversionName = "0.9.9"\n')
        self.assertEqual(first, w.validation_key(self.root, release.state, env))
        (self.root / 'server/rivo_relay/__main__.py').write_text('new source')
        second = w.validation_key(self.root, release.state, env)
        self.assertNotEqual(first, second)
        release.state['serverTests'].append('test_other')
        self.assertNotEqual(second, w.validation_key(self.root, release.state, env))

    def test_validation_reuse_requires_unchanged_evidence_logs(self):
        release = self.ready()
        log = release.directory / 'past.log'
        log.write_text('passed')
        evidence = {'key': 'fixture', 'logs': {str(log): c.digest(log)}}
        c.save(self.root / 'workspace/artifacts/release-checks/fixture.json', evidence)
        with patch.object(w, 'validation_key', return_value='fixture'), patch.object(release, 'command') as command:
            release.validate()
            command.assert_not_called()
        log.write_text('modified')
        with patch.object(w, 'validation_key', return_value='fixture'), patch.object(release, 'command', side_effect=RuntimeError('rerun')):
            with self.assertRaisesRegex(RuntimeError, 'rerun'):
                release.validate()

    def test_signature_mismatch_rejects_build_and_cached_apk_is_never_rebuilt(self):
        release = self.ready()
        release.apk.write_bytes(b'new apk')
        previous = self.root / 'workspace/local-node/updates/apks' / (self.previous['sha256'] + '.apk')
        with patch.object(w, 'apk_identity', return_value=('dev.rivo.relay', '40', '0.2.28')):
            with patch.object(w, 'apk_signers', side_effect=[['a'], ['b']]):
                with self.assertRaisesRegex(RuntimeError, '签名与上一版'):
                    w.verify_apk(release.apk, previous, release.state, {})
            with patch.object(w, 'apk_signers', return_value=['a']):
                release.state['apk'] = w.verify_apk(release.apk, previous, release.state, {})
                release.apk.write_bytes(b'tampered')
                with patch.object(release, 'command') as command:
                    with self.assertRaisesRegex(RuntimeError, '保存的 APK 已改变'):
                        release.build()
                    command.assert_not_called()

    def test_failure_before_publication_does_not_publish(self):
        release = self.ready()
        with patch.object(release, 'preflight'), patch.object(release, 'validate'), \
                patch.object(release, 'build'), patch.object(release, 'commit'), \
                patch.object(d, 'deploy', side_effect=RuntimeError('rollback')), patch.object(release, 'publish') as publish:
            with self.assertRaisesRegex(RuntimeError, 'rollback'):
                release.run()
            publish.assert_not_called()
        self.assertEqual('failed', c.load(release.state_file)['phase'])

    def test_existing_gradle_build_is_not_duplicated(self):
        with patch.object(w, 'output', return_value='java org.gradle.wrapper.GradleWrapperMain :app:assembleDebug'):
            with self.assertRaisesRegex(RuntimeError, '已有 Gradle'):
                w.no_other_build()

    def test_snapshot_cannot_be_modified_or_extended_after_export(self):
        commit = c.git(self.root, 'rev-parse', 'HEAD')
        target = self.root / 'workspace/snapshot'
        path = target / 'server/rivo_relay/__main__.py'
        path.parent.mkdir(parents=True)
        path.write_text('print("fixture")\n')
        d.verify_snapshot(self.root, commit, target)
        path.write_text('modified')
        with self.assertRaisesRegex(RuntimeError, '快照与发布提交'):
            d.verify_snapshot(self.root, commit, target)
        path.write_text('print("fixture")\n')
        (path.parent / 'extra.py').write_text('extra')
        with self.assertRaisesRegex(RuntimeError, '额外文件'):
            d.verify_snapshot(self.root, commit, target)

    def test_resume_after_service_completed_never_submits_another_restart(self):
        release = self.ready()
        release.state['commit'] = c.git(self.root, 'rev-parse', 'HEAD')
        release.state['deploymentRequested'] = True
        release.state['deploymentBefore'] = {'nativeHost': {'pid': 123, 'processStart': 'original'}}
        target = self.root / 'workspace/deployments' / release.state['commit'] / 'server/rivo_relay'
        target.mkdir(parents=True)
        (target / '__main__.py').write_text('print("fixture")\n')
        home = self.root / 'home'
        plist = home / 'Library/LaunchAgents/local.rivo-relay.plist'
        plist.parent.mkdir(parents=True)
        config = {'Label': 'local.rivo-relay', 'ProgramArguments': ['/python', '--workspace', str(self.root / 'workspace/local-node')],
                  'EnvironmentVariables': {'PYTHONPATH': str(target.parent)}}
        plist.write_bytes(plistlib.dumps(config))
        (release.directory / 'candidate.plist').write_bytes(plist.read_bytes())
        (release.directory / 'deployment.log').write_text('{"event":"complete"}\n')
        with patch.object(d.Path, 'home', return_value=home), patch.object(d, 'native_identity', return_value={'pid': 123, 'processStart': 'original'}), \
                patch.object(d, 'get_json', return_value={'status': 'ok', 'releaseCode': release.state['versionCode']}), patch.object(d, 'preserved_connections', return_value=7), \
                patch.object(release, 'command') as command:
            d.deploy(release)
            command.assert_not_called()
        self.assertEqual(7, release.state['steps']['deployment']['existingAuthorizationsPreserved'])

    def test_release_health_rejects_missing_and_stale_version(self):
        for code in (None, 0, 104):
            with self.subTest(code=code), patch.object(d, 'get_json', return_value={'status': 'ok', 'releaseCode': code}):
                with self.assertRaisesRegex(RuntimeError, '运行版本'):
                    d.verify_release_health({'versionCode': 105})
        with patch.object(d, 'get_json', return_value={'status': 'ok', 'releaseCode': 105}):
            d.verify_release_health({'versionCode': 105})

    def test_native_identity_uses_configured_socket_and_ignores_connection_generation(self):
        config = {'ProgramArguments': ['/python', '--native-socket', '/chosen.sock',
                                      '--codex-runtime', 'app_server', '--codex-home', '/chosen-home']}
        host = {'pid': 123, 'processStart': 'start', 'processStartMethod': 'ps-lstart',
                'executable': '/codex', 'codexHome': '/chosen-home', 'platform': 'darwin', 'generation': 'one'}
        with patch.object(d, '_probe_native', return_value=host) as probe, \
             patch.object(d, 'output', side_effect=AssertionError('must not scan all processes')):
            before = d.native_identity(config)
            probe.assert_called_with('/chosen.sock', '/chosen-home')
            host['generation'] = 'two'
            host['auxiliaryPids'] = [456, 789]
            d.verify_native_identity(config, {'nativeHost': before})
            for field, changed in [('pid', 456), ('processStart', 'reused pid'),
                                   ('executable', '/new-codex'), ('codexHome', '/other-home')]:
                with self.subTest(field=field):
                    original = host[field]
                    host[field] = changed
                    with self.assertRaisesRegex(RuntimeError, '身份发生变化'):
                        d.verify_native_identity(config, {'nativeHost': before})
                    host[field] = original

    def test_native_identity_fails_closed_without_selected_host_evidence(self):
        with self.assertRaisesRegex(RuntimeError, '缺少所选原生主机基准'):
            d.verify_native_identity({}, {'native': ['legacy process list']})
        config = {'ProgramArguments': ['/python', '--native-socket', '/chosen.sock']}
        with patch.object(d, '_probe_native', side_effect=RuntimeError('unreachable')):
            with self.assertRaisesRegex(RuntimeError, 'unreachable'):
                d.native_identity(config)
        with patch.object(d, '_probe_native', return_value={'pid': 123}):
            with self.assertRaisesRegex(RuntimeError, '无法核实'):
                d.native_identity(config)

    def test_ambiguous_deployment_waits_and_rollback_is_explicit(self):
        path = self.root / 'deployment.log'
        path.write_text('{"event":"submitted"}\n')
        with patch.object(d.time, 'monotonic', side_effect=[0, 151]):
            with self.assertRaisesRegex(RuntimeError, '不会重复重启'):
                d.wait_deployment(path)
        path.write_text('{"event":"submission_failed"}\n')
        with self.assertRaisesRegex(RuntimeError, '部署任务提交失败'):
            d.wait_deployment(path)
        path.write_text('{"event":"worker_failed"}\n')
        with self.assertRaisesRegex(RuntimeError, '部署任务失败'):
            d.wait_deployment(path)
        path.write_text('{"event":"deployment_failed"}\n{"event":"rollback_complete"}\n')
        with self.assertRaisesRegex(RuntimeError, '已回滚'):
            d.wait_deployment(path)


class ReleaseHttpTest(unittest.TestCase):
    def test_public_download_hash_and_path_are_verified_over_real_http(self):
        payload = b'apk fixture' * 1000
        expected = {'version': '0.2.28', 'versionCode': 40, 'size': len(payload),
                    'sha256': hashlib.sha256(payload).hexdigest(), 'notes': 'fixture'}
        manifest = dict(expected, apkPath='/v1/app-updates/apks/' + expected['sha256'] + '.apk')
        response_body = [payload]

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                body = json.dumps(manifest).encode() if self.path.endswith('/latest') else response_body[0]
                self.send_response(200)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        server = HTTPServer(('127.0.0.1', 0), Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            base = f'http://127.0.0.1:{server.server_port}'
            self.assertTrue(c.verify_http(expected, base=base)['httpDownloadVerified'])
            response_body[0] = b'x' * len(payload)
            with self.assertRaisesRegex(RuntimeError, '完整性校验失败'):
                c.verify_http(expected, base=base)
            manifest['apkPath'] = 'http://another.invalid/file.apk'
            with self.assertRaisesRegex(RuntimeError, '路径不正确'):
                c.verify_http(expected, base=base)
        finally:
            server.shutdown()
            server.server_close()
            worker.join()
