import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

from .common import BASE, atomic, digest, get_json, git, load, locked, output, require, save, verify_http


BUILD_FILE = 'client/android/app/build.gradle.kts'


def version(value):
    require(bool(re.fullmatch(r'\d+\.\d+\.\d+', value)), '版本必须是 x.y.z')
    return tuple(map(int, value.split('.')))


def build_version(root):
    text = (root / BUILD_FILE).read_text()
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    code = re.search(r'versionCode\s*=\s*(\d+)', text)
    require(name and code, '无法读取 Android 版本')
    return name[1], int(code[1])


def prepare(root, args):
    root = root.resolve()
    with locked(root / 'workspace/.release-cli.lock'):
        name, code = build_version(root)
        workspace = root / 'workspace/local-node'
        previous_path = workspace / 'updates/latest.json'
        require(previous_path.is_file(), '找不到上版清单，无法验证升级与签名连续性')
        previous = load(previous_path)
        latest = max(version(name), version(previous['version']))
        next_version = args.version or '.'.join(map(str, (*latest[:2], latest[2] + 1)))
        require(version(next_version) > latest, '新版本必须高于源码版本和已发布版本')
        next_code = max(code, previous['versionCode']) + 1
        directory = root / 'workspace/artifacts' / ('release-' + next_version)
        require(not directory.exists(), '发布目录已存在；请使用 status/resume，不能覆盖已有版本')
        require(args.android_test or args.server_test or args.checks_note,
                '请指定相关 --android-test / --server-test，或用 --checks-note 记录复用验证依据')
        require(all(re.fullmatch(r'[A-Za-z_]\w*(\.[A-Za-z_]\w*)*', test) for test in args.server_test),
                '服务端测试必须是 unittest 模块/类/方法名，不能传入命令选项')
        require(all(re.fullmatch(r'[A-Za-z_*$][A-Za-z0-9_.$*]*', test) for test in args.android_test),
                'Android 测试必须是 JUnit 类/方法匹配表达式')
        notes = (root / args.notes).resolve() if args.notes else root / 'docs/releases' / (next_version + '.md')
        require(notes.is_relative_to(root) and notes.suffix == '.md', '发布说明必须是仓库内 Markdown 文件')
        require(not notes.is_relative_to(root / 'workspace'), '发布说明必须纳入 Git，不能放在 workspace')
        plan = {'schema': 1, 'version': next_version, 'versionCode': next_code,
                'previous': previous, 'notes': str(notes.relative_to(root)),
                'androidTests': args.android_test, 'serverTests': args.server_test,
                'checksNote': args.checks_note or '', 'deviceScope': args.device_scope,
                'phase': 'prepared', 'steps': {}}
        # Save recovery intent before changing tracked version metadata.
        directory.mkdir(parents=True)
        save(directory / 'release-state.json', plan)
        if not notes.exists():
            atomic(notes, (f'# {next_version} / versionCode {next_code}\n\n- TODO：填写本次变更。\n\n'
                           f'真机/模拟器验证范围：{args.device_scope}\n').encode())
        elif f'真机/模拟器验证范围：{args.device_scope}' not in notes.read_text():
            atomic(notes, (notes.read_text().rstrip() + f'\n\n真机/模拟器验证范围：{args.device_scope}\n').encode())
        text = (root / BUILD_FILE).read_text()
        text = re.sub(r'(versionName\s*=\s*)"[^"]+"', rf'\g<1>"{next_version}"', text, count=1)
        text = re.sub(r'(versionCode\s*=\s*)\d+', rf'\g<1>{next_code}', text, count=1)
        atomic(root / BUILD_FILE, text.encode())
        return plan


def environment(root):
    env = dict(os.environ)
    env.setdefault('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))
    studio = Path('/Applications/Android Studio.app/Contents/jbr/Contents/Home')
    if 'JAVA_HOME' not in env and studio.exists():
        env['JAVA_HOME'] = str(studio)
    env['PYTHONPATH'] = os.pathsep.join([str(root / 'server'), str(root / 'server/tests')])
    return env


def sdk_tool(env, name):
    candidates = [p for p in (Path(env['ANDROID_HOME']) / 'build-tools').glob('*/' + name)
                  if re.fullmatch(r'\d+(\.\d+)*', p.parent.name)]
    require(candidates, '缺少 Android SDK 工具：' + name)
    return max(candidates, key=lambda p: tuple(map(int, p.parent.name.split('.'))))


def apk_identity(apk, env):
    badging = output([sdk_tool(env, 'aapt'), 'dump', 'badging', apk], env=env)
    match = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    require(match, '无法识别 APK 版本')
    return match.groups()


def apk_signers(apk, env):
    text = output([sdk_tool(env, 'apksigner'), 'verify', '--print-certs', apk], env=env)
    signers = sorted(set(re.findall(r'certificate SHA-256 digest: ([0-9a-fA-F]{64})', text)))
    require(signers, 'APK 未包含可验证的签名')
    return signers


def verify_apk(apk, previous_apk, plan, env):
    require(apk_identity(apk, env) == ('dev.rivo.relay', str(plan['versionCode']), plan['version']),
            'APK 包名或版本不一致')
    previous = plan['previous']
    require(previous_apk.stat().st_size == previous['size'] and digest(previous_apk) == previous['sha256'],
            '上一版 APK 与清单不一致，不能作为签名基准')
    signers = apk_signers(apk, env)
    require(signers == apk_signers(previous_apk, env), '签名与上一版不一致')
    return {'version': plan['version'], 'versionCode': plan['versionCode'], 'size': apk.stat().st_size,
            'sha256': digest(apk), 'signerSha256': signers, 'matchesPreviousSigner': True}


def validation_key(root, plan, env):
    hasher = hashlib.sha256()
    for name in sorted(git(root, 'ls-files', '-z').split('\0')):
        if not name or name.startswith('docs/') or name.endswith('.md'):
            continue
        path = root / name
        if not path.is_file():
            continue
        raw = path.read_bytes()
        if name == BUILD_FILE:
            raw = re.sub(rb'versionName\s*=\s*"[^"]+"', b'versionName="VERSION"', raw)
            raw = re.sub(rb'versionCode\s*=\s*\d+', b'versionCode=CODE', raw)
        hasher.update(name.encode() + b'\0' + hashlib.sha256(raw).digest())
    toolchain = []
    for path in (root / '.venv/bin/python', Path(env.get('JAVA_HOME', '/missing')) / 'bin/java',
                 sdk_tool(env, 'aapt'), sdk_tool(env, 'apksigner')):
        resolved = path.resolve()
        toolchain.append((str(resolved), resolved.stat().st_size, resolved.stat().st_mtime_ns))
    hasher.update(json.dumps([plan['androidTests'], plan['serverTests'], plan['checksNote'], toolchain], sort_keys=True).encode())
    return hasher.hexdigest()


def no_other_build():
    # Also covers builds started outside this CLI's release lock.
    processes = output(['ps', '-axo', 'command'])
    require(not any('org.gradle.wrapper.GradleWrapperMain' in line for line in processes.splitlines()),
            '已有 Gradle 构建运行；等待其结束后 resume，避免重复构建')


class Release:
    def __init__(self, root, release_version):
        root = root.resolve()
        version(release_version)
        self.root = root
        self.directory = root / 'workspace/artifacts' / ('release-' + release_version)
        self.state_file = self.directory / 'release-state.json'
        self.state = load(self.state_file)
        require(self.state['schema'] == 1 and self.state['version'] == release_version, '发布状态版本不匹配')
        self.env = environment(root)
        self.python = root / '.venv/bin/python'
        self.apk = self.directory / ('rivo-relay-' + release_version + '-debug.apk')
        self.retry_deploy = False

    def record(self, phase, **fields):
        self.state.update(phase=phase, **fields)
        save(self.state_file, self.state)
        print(json.dumps({'phase': phase, 'version': self.state['version']}, ensure_ascii=False), flush=True)

    def command(self, args, log, *, cwd=None):
        path = self.directory / log
        with path.open('ab') as stream:
            result = subprocess.run([str(arg) for arg in args], cwd=cwd or self.root, env=self.env,
                                    stdin=subprocess.DEVNULL, stdout=stream, stderr=subprocess.STDOUT)
        require(result.returncode == 0, f'命令失败（{result.returncode}），请查看 {path}')

    def source_unchanged(self):
        require(not git(self.root, 'diff', '--name-only'), '工作区发生未暂存改动；停止发布')
        require(not git(self.root, 'ls-files', '--others', '--exclude-standard'), '存在未纳入提交的文件；请先处理后 resume')
        tree = git(self.root, 'write-tree')
        require(tree == self.state['sourceTree'], '源码与本次发布已锁定的版本不一致；请新建发布计划')
        if self.state.get('commit'):
            require(git(self.root, 'rev-parse', 'HEAD') == self.state['commit'], 'HEAD 已变化，不能继续此发布')

    def preflight(self, commit):
        require(build_version(self.root) == (self.state['version'], self.state['versionCode']), '源码版本与发布计划不一致')
        notes = self.root / self.state['notes']
        require(notes.is_file() and notes.read_text().strip() and 'TODO' not in notes.read_text(), '请先填写发布说明，移除 TODO')
        if not self.state.get('sourceTree'):
            if commit:
                # Only version metadata and release notes are automatically staged.
                # Other changes must already be deliberately staged by the operator.
                git(self.root, 'add', '--', BUILD_FILE, self.state['notes'])
            else:
                require(not git(self.root, 'status', '--porcelain'), '源码未提交；先提交，或暂存改动后使用 run --commit')
            self.state['sourceTree'] = git(self.root, 'write-tree')
            self.source_unchanged()
            self.record('preflight', allowCommit=commit)
        self.source_unchanged()
        # Fail before switching services if another release has overtaken this one.
        current = load(self.root / 'workspace/local-node/updates/latest.json')
        own = self.state.get('apk')
        require(current == self.state['previous'] or own and all(current.get(k) == own[k]
                for k in ('version', 'versionCode', 'sha256', 'size')), '更新源已有其他发布，停止此计划')
        if self.state.get('deploymentRequested') and not self.state['steps'].get('deployment'):
            return  # Reconcile the pending switch before requiring its HTTP server.
        remote = get_json(BASE + '/v1/app-updates/latest')
        require(all(remote.get(k) == current[k] for k in ('version', 'versionCode', 'sha256', 'size')),
                '主节点 HTTP 清单与本地 workspace 不一致')

    def validate(self):
        key = validation_key(self.root, self.state, self.env)
        cached = self.root / 'workspace/artifacts/release-checks' / (key + '.json')
        if cached.exists():
            report = load(cached)
            if report.get('key') == key and all(Path(p).is_file() and digest(p) == checksum
                                               for p, checksum in report.get('logs', {}).items()):
                self.state['steps']['validation'] = report
                self.record('validated')
                return
        self.record('validating')
        logs = {}
        if self.state['serverTests']:
            self.command([self.python, '-m', 'unittest', *self.state['serverTests'], '-q'], 'server-tests.log')
            logs[str(self.directory / 'server-tests.log')] = digest(self.directory / 'server-tests.log')
        if self.state['androidTests']:
            no_other_build()
            args = [self.root / 'client/android/gradlew', ':app:testDebugUnitTest']
            for test in self.state['androidTests']:
                args += ['--tests', test]
            self.command(args + ['--console=plain'], 'android-tests.log', cwd=self.root / 'client/android')
            logs[str(self.directory / 'android-tests.log')] = digest(self.directory / 'android-tests.log')
            shutil.copytree(self.root / 'client/android/app/build/test-results/testDebugUnitTest',
                            self.directory / 'android-unit-results', dirs_exist_ok=True)
        self.source_unchanged()
        report = {'key': key, 'logs': logs, 'androidTests': self.state['androidTests'],
                  'serverTests': self.state['serverTests'], 'manualEvidence': self.state['checksNote']}
        # A manual note is retained as a note, never claimed to be an executed check.
        save(cached, report)
        self.state['steps']['validation'] = report
        self.record('validated')

    def build(self):
        previous = self.root / 'workspace/local-node/updates/apks' / (self.state['previous']['sha256'] + '.apk')
        if not self.state.get('apk'):
            no_other_build()
            self.record('building')
            self.command([self.root / 'client/android/gradlew', ':app:assembleDebug', '--console=plain'],
                         'android-build.log', cwd=self.root / 'client/android')
            self.source_unchanged()
            shutil.copyfile(self.root / 'client/android/app/build/outputs/apk/debug/app-debug.apk', self.apk)
        report = verify_apk(self.apk, previous, self.state, self.env)
        if self.state.get('apk'):
            require(report == self.state['apk'], '保存的 APK 已改变，不能重建或覆盖同版本发布')
        save(self.directory / 'apk-verification.json', report)
        self.record('built', apk=report)

    def commit(self):
        self.source_unchanged()
        if git(self.root, 'diff', '--cached', '--name-only'):
            require(self.state.get('allowCommit'), '存在未提交改动，未授权提交')
            git(self.root, 'commit', '-m', 'Release ' + self.state['version'])
        # Also recovers an interrupted commit whose exact tree is already HEAD.
        require(git(self.root, 'rev-parse', 'HEAD^{tree}') == self.state['sourceTree'], '提交内容与构建源码不同')
        self.record('committed', commit=git(self.root, 'rev-parse', 'HEAD'))

    def publish(self):
        self.source_unchanged()
        self.record('publishing')
        self.command([self.python, self.root / 'scripts/publish-update.py',
                      '--workspace', self.root / 'workspace/local-node', '--apk', self.apk,
                      '--version', self.state['version'], '--version-code', self.state['versionCode'],
                      '--notes', self.root / self.state['notes']], 'publication.log')
        expected = {key: self.state['apk'][key] for key in ('version', 'versionCode', 'sha256', 'size')}
        expected['notes'] = (self.root / self.state['notes']).read_text()
        self.record('verifying-http')
        report = verify_http(expected)
        save(self.directory / 'http-verification.json', report)
        self.state['steps']['http'] = report
        self.record('complete', error=None, failedPhase=None)

    def run(self, commit=False):
        with locked(self.root / 'workspace/.release-cli.lock') as lock:
            # Another worker may have finished since this object was created.
            self.state = load(self.state_file)
            lock.seek(0)
            lock.truncate()
            lock.write(json.dumps({'version': self.state['version'], 'pid': os.getpid()}))
            lock.flush()
            try:
                self.state.pop('error', None)
                self.preflight(commit)
                from .deployment import deploy
                if self.state.get('deploymentRequested') and not self.state['steps'].get('deployment'):
                    require(self.state.get('apk') and self.state.get('commit'), '待恢复部署缺少已验证构建记录')
                    deploy(self)
                    self.preflight(commit)  # HTTP must pass after reconciliation.
                if self.state['phase'] == 'complete':
                    require(self.state['steps'].get('deployment'), '已完成发布缺少服务验证记录')
                    deploy(self)
                    self.build()  # Verify the existing signed artifact, never rebuild it.
                    self.publish()  # Idempotent publication and fresh HTTP verification.
                    return
                self.validate()
                self.build()
                self.commit()
                from .deployment import deploy
                deploy(self)
                self.publish()
            except Exception as error:
                self.record('failed', failedPhase=self.state['phase'], error=str(error))
                raise
