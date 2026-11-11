"""Read-only checks on the machine that actually executes a release."""
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import shlex
import shutil
import subprocess
import sys
import urllib.request

from . import configuration as config


def run(args, *, env=None):
    return subprocess.check_output([str(a) for a in args], env=env, text=True,
                                   stderr=subprocess.PIPE, timeout=25).strip()


def inspect(root, online=False):
    root = Path(root).resolve()
    checks = []
    def check(name, action, remedy):
        try:
            detail = action()
            checks.append({'name': name, 'ok': True, 'detail': detail})
            return detail
        except Exception as error:
            # Subprocess stderr may contain credentials; only expose a concise reason.
            detail = str(error) if not isinstance(error, subprocess.CalledProcessError) else '命令执行失败'
            checks.append({'name': name, 'ok': False, 'detail': detail, 'remedy': remedy})

    def require(condition, message):
        if not condition:
            raise RuntimeError(message)

    def file(filename):
        require(Path(filename).is_file(), '文件不存在：' + str(filename))
        return str(filename)

    cfg = config.settings(root)
    env = config.environment(root)
    interpreter = config.python(root)
    check('python', lambda: run([interpreter, '-c',
          'import sys; assert sys.version_info >= (3,11); import websockets,qrcode,PIL,zstandard,aiohttp; print(sys.executable)'], env=env),
          '配置可用的 python 并安装 server[gateway]；不强制使用仓库 .venv')
    java = Path(env.get('JAVA_HOME', '/missing')) / 'bin/java'
    def java_version():
        result = subprocess.run([str(java), '-version'], env=env, text=True, capture_output=True, timeout=25)
        match = re.search(r'version "(\d+)', result.stderr + result.stdout)
        required = int(re.search(r'jvmToolchain\((\d+)\)', (root/'client/android/app/build.gradle.kts').read_text())[1])
        require(result.returncode == 0 and match and int(match[1]) == required, '需要源码指定的 JDK ' + str(required))
        return {'java': str(java), 'major': int(match[1])}
    check('java', java_version, '通过 JAVA_HOME 或 javaHome 指定源码 jvmToolchain 要求的 JDK')
    sdk = Path(env['ANDROID_HOME'])
    tools = sorted((sdk / 'build-tools').glob('*/apksigner'),
                   key=lambda p: tuple(int(n) for n in p.parent.name.split('.'))
                   if re.fullmatch(r'\d+(\.\d+)*', p.parent.name) else ())
    def android():
        build = (root / 'client/android/app/build.gradle.kts').read_text()
        api = re.search(r'compileSdk\s*=\s*(\d+)', build)[1]
        platform = next((sdk / 'platforms' / ('android-' + api + suffix) / 'android.jar'
                         for suffix in ('', '.0')
                         if (sdk / 'platforms' / ('android-' + api + suffix) / 'android.jar').is_file()), None)
        require(platform is not None, '缺少 compileSdk 对应平台：' + api)
        require(bool(tools), '缺少 apksigner')
        file(tools[-1].parent / 'aapt')
        return {'sdk': str(sdk), 'compileSdk': int(api), 'buildTools': tools[-1].parent.name}
    check('android', android, '设置 androidHome/ANDROID_HOME，并安装源码要求的平台及 Build Tools')

    def baseline():
        directory = root / 'workspace/local-node/updates'
        previous = json.loads((directory / 'latest.json').read_text())
        digest = previous['sha256']
        require(isinstance(digest, str) and bool(re.fullmatch('[a-f0-9]{64}', digest)), '清单摘要无效')
        apk = directory / 'apks' / (digest + '.apk')
        require(apk.stat().st_size == previous['size'], '上版 APK 大小不一致')
        require(hashlib.sha256(apk.read_bytes()).hexdigest() == digest, '上版 APK 摘要不一致')
        return {'version': previous['version'], 'versionCode': previous['versionCode'], 'apk': str(apk)}
    previous = check('baseline', baseline, '在执行节点获取当前更新清单及对应 APK，校验后作为签名基准')
    def signing():
        require(previous is not None and bool(tools), '先修复 baseline/android 检查')
        certs = run([tools[-1], 'verify', '--print-certs', previous['apk']], env=env)
        signers = set(re.findall(r'certificate SHA-256 digest: ([0-9a-fA-F]{64})', certs))
        # The existing pipeline builds the default Android debug variant.
        store = Path(cfg['signingStore']) if cfg.get('signingStore') else Path.home() / '.android/debug.keystore'
        certificate = subprocess.check_output([str(java.parent / 'keytool'), '-exportcert',
            '-keystore', str(store), '-storepass', 'android', '-alias', 'androiddebugkey'],
            stderr=subprocess.PIPE, timeout=25)
        require(signers == {hashlib.sha256(certificate).hexdigest()}, '本机默认 Debug 签名与上一版不一致')
        return '与上一版 APK 一致'
    check('signing', signing, '在构建节点使用原发布签名；不要覆盖其他项目正在使用的签名文件')

    def deployment():
        require(sys.platform == 'darwin' and shutil.which('launchctl'), '服务部署执行节点需要 macOS launchd')
        plist = Path.home() / 'Library/LaunchAgents/local.rivo-relay.plist'
        value = plistlib.loads(plist.read_bytes())
        args = value['ProgramArguments']
        require(value['Label'] == 'local.rivo-relay' and '--workspace' in args, '不是主发布节点服务')
        actual = Path(args[args.index('--workspace') + 1]).resolve()
        require(actual == (root / 'workspace/local-node').resolve(), '当前电脑/仓库不是主发布节点')
        return str(plist)
    if cfg.get('mode') == 'manager':
        check('jobs', lambda: require(sys.platform == 'darwin' and shutil.which('launchctl'), '当前后台发布任务支持 macOS'),
              '当前版本在 macOS 安装管理节点；其他系统仍可作为普通节点')
    else:
        check('deployment', deployment, '旧发布方式需要主节点服务；新管理节点请运行 setup-manager')
    def repository():
        commit = run(['git', '-C', root, 'rev-parse', 'HEAD'])
        branch = run(['git', '-C', root, 'branch', '--show-current'])
        clean = not run(['git', '-C', root, 'status', '--porcelain'])
        return {'commit': commit, 'branch': branch, 'clean': clean}
    source = check('repository', repository, '执行节点需要 Git 仓库；源码状态与环境就绪分开判断')
    if online:
        gateway, identity = config.gateway_connection(root)
        # An SSH alias/agent is sufficient; no user-specific PEM path is required.
        ssh = ['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', '-o', 'StrictHostKeyChecking=yes']
        gateway_ssh = ssh + (['-i', config.path(root, identity), '-o', 'IdentitiesOnly=yes'] if identity else [])
        check('vps', lambda: run(gateway_ssh + [gateway,
              'test -x /opt/rivo-relay/.venv/bin/python && test -f /opt/rivo-relay/current/server/rivo_relay/gateway/publish.py']),
              '配置 gatewayHost SSH 别名，或 gatewayIdentity；需要管理员 SSH 发布权限')
        if cfg.get('asrHost') == 'local':
            check('asr', lambda: file('/Users/e2/Services/rivo-asr/.venv/bin/python'), '恢复本机独立 ASR 环境')
        else:
            check('asr', lambda: run(ssh + [cfg.get('asrHost', 'e2-mac-mini'),
                  'test -x /Users/e2/Services/rivo-asr/.venv/bin/python']), '配置管理节点到 E2 ASR 的非交互 SSH 登录')
        def compatibility_source():
            require(previous is not None, '先修复 baseline 检查')
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            url = 'http://100.124.150.1:18767/v1/app-updates/latest'
            with opener.open(url, timeout=20) as response:
                require(response.geturl() == url, '更新源发生重定向')
                remote = json.load(response)
            local = json.loads((root / 'workspace/local-node/updates/latest.json').read_text())
            require(all(local[k] == remote.get(k) for k in ('version', 'versionCode', 'sha256', 'size')),
                    '主节点 HTTP 清单与执行仓库不一致')
            return remote['version']
        if cfg.get('mode') == 'manager':
            def central():
                # Run through the configured dependency environment even when the
                # CLI itself was started with system Python.
                raw = run([interpreter, '-c', 'import json,sys; from pathlib import Path; '
                    'from rivo_relay.release_management.central import baseline; '
                    'from rivo_relay.release_management.configuration import settings; '
                    'p=Path(sys.argv[1]); print(json.dumps(baseline(p,settings(p))))', root], env=env)
                item = json.loads(raw)
                return {'version': item['version'], 'versionCode': item['versionCode']}
            check('centralSource', central, '恢复 VPS 连接并核对已信任的证书指纹')
        else:
            check('compatibilitySource', compatibility_source, '恢复主节点兼容更新源，确认仓库使用当前已发布清单')
    return {'role': 'manager' if cfg.get('mode') == 'manager' else 'legacy-publisher', 'root': str(root), 'ready': all(c['ok'] for c in checks),
            'onlineChecked': online, 'source': source, 'checks': checks}


def require_ready(report):
    failed = [c['name'] + '：' + c.get('remedy', c['detail']) for c in report['checks'] if not c['ok']]
    if failed:
        raise RuntimeError('发布执行环境未就绪；未修改版本或启动发布：' + '；'.join(failed))


def ssh(executor):
    return ['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', '-o', 'StrictHostKeyChecking=yes', executor['host']]
