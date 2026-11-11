"""Install an isolated, versioned management client without touching a node."""
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile

from . import configuration, diagnostics


def default_home():
    return Path.home() / '.local/share/rivo-release-manager'


def add_arguments(parser):
    parser.add_argument('--source-node', required=True, help='首次配置时，从这个已授权 SSH 节点导入现有签名及缺少的工具；以后发布不依赖它')
    parser.add_argument('--workspace', type=Path, default=Path('workspace'), help='已安装普通节点的 workspace，仅读取 VPS 地址和证书指纹')
    parser.add_argument('--repository', type=Path, help='复用本机源码仓库；省略则自动 clone 到管理目录')
    parser.add_argument('--gateway-host', default='root@134.175.156.219', help='VPS 管理 SSH 别名或 user@host')
    parser.add_argument('--gateway-identity', type=Path, help='已有的 VPS SSH 私钥；省略则使用 SSH 配置/agent')
    parser.add_argument('--asr-host', help='ASR 所在节点的 SSH 别名；本机已有独立 ASR 时自动使用 local')
    parser.add_argument('--home', type=Path, default=default_home(), help='独立管理工具目录')


def discover(args):
    configuration.host(args.executor)
    probe = '''import json, pathlib, plistlib, sys
root, python = sys.argv[1:]
if not root:
    config = plistlib.loads((pathlib.Path.home()/'Library/LaunchAgents/local.rivo-relay.plist').read_bytes())
    arguments = config['ProgramArguments']
    workspace = pathlib.Path(arguments[arguments.index('--workspace')+1]).resolve()
    assert workspace.parts[-2:] == ('workspace','local-node'), 'Not the primary publishing node'
    root = str(workspace.parent.parent)
root = pathlib.Path(root)
assert root.is_absolute() and (root/'scripts/release.py').is_file(), 'Release CLI missing'
python = python or str(root/'.venv/bin/python')
assert pathlib.Path(python).is_absolute() and pathlib.Path(python).is_file(), 'Executor Python missing'
print(json.dumps({'root':str(root),'python':python}))
'''
    target = {'host': args.executor}
    result = subprocess.run(diagnostics.ssh(target) + [shlex.join([
        'python3', '-c', probe, args.executor_root or '', args.executor_python or ''])],
        text=True, capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError('无法识别签名和工具来源节点；检查 --source-node 的 SSH 授权及已有发布环境；未修改节点服务')
    target.update(json.loads(result.stdout))
    return target


def atomic(path, data, mode=0o600):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, temporary = tempfile.mkstemp(dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            os.fchmod(stream.fileno(), mode)
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def remote_bytes(host, path):
    command = ['python3', '-c', 'import pathlib,sys; sys.stdout.buffer.write(pathlib.Path(sys.argv[1]).read_bytes())', path]
    result = subprocess.run(diagnostics.ssh({'host': host}) + [shlex.join(command)],
                            capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError('无法读取配置来源节点上的必要文件：' + path)
    return result.stdout


def import_directory(host, source, destination):
    import io
    import tarfile
    if destination.exists():
        return
    command = ['tar', '-C', str(Path(source).parent), '-czf', '-', Path(source).name]
    result = subprocess.run(diagnostics.ssh({'host': host}) + [shlex.join(command)],
                            capture_output=True, timeout=300)
    if result.returncode:
        raise RuntimeError('工具导入失败：' + source)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=destination.parent) as temporary:
        with tarfile.open(fileobj=io.BytesIO(result.stdout), mode='r:gz') as archive:
            archive.extractall(temporary, filter='data')
        Path(temporary, Path(source).name).rename(destination)


def install(args):
    if sys.platform != 'darwin':
        raise RuntimeError('当前管理节点初始化支持 macOS；普通节点仍支持其他系统')
    for tool in ('ssh', 'git'):
        if not shutil.which(tool):
            raise RuntimeError('缺少系统工具 ' + tool + '；请先安装系统命令行工具')
    if sys.version_info < (3, 11):
        raise RuntimeError('请使用已安装节点的 Python 3.11+ 执行初始化')
    from argparse import Namespace
    from rivo_relay.gateway.config import decode_key
    configuration.host(args.source_node)
    configuration.host(args.gateway_host)
    node = json.loads((args.workspace.expanduser().resolve() / 'gateway-client.json').read_text())
    trust = decode_key(node['key'])
    home = args.home.expanduser().resolve()
    home.mkdir(parents=True, exist_ok=True, mode=0o700)
    lock = home / '.setup-lock'
    try:
        lock.mkdir()
    except FileExistsError:
        raise RuntimeError('另一初始化仍在执行；若已中断，确认进程结束后移走 .setup-lock 再重试') from None
    try:
        donor = discover(Namespace(executor=args.source_node, executor_root=None, executor_python=None))
        probe = 'import json,pathlib; h=pathlib.Path.home(); print(json.dumps({"sdk":str(h/"Library/Android/sdk"),"java":"/Applications/Android Studio.app/Contents/jbr/Contents/Home","store":str(h/".android/debug.keystore")}))'
        result = subprocess.run(diagnostics.ssh(donor) + [shlex.join(['python3', '-c', probe])],
                                text=True, capture_output=True, check=True, timeout=30)
        source = json.loads(result.stdout)
        root = args.repository.expanduser().resolve() if args.repository else home / 'repository'
        if not root.exists():
            subprocess.run(['git', 'clone', 'https://github.com/ethanhubin/rivo-relay.git', str(root)], check=True)
            subprocess.run(['git', '-C', str(root), 'switch', '--no-track', '-c', 'codex/release-manager', 'origin/main'], check=True)
        if not (root / 'scripts/release.py').is_file() or not (root / 'server/rivo_relay/release_management').is_dir():
            raise RuntimeError('源码仓库不包含管理节点命令；请使用包含本功能的源码版本')
        base_python = str(Path(getattr(sys, '_base_executable', sys.executable)).resolve())
        python = home / 'venv/bin/python'
        if not python.is_file():
            subprocess.run([base_python, '-m', 'venv', str(home / 'venv')], check=True)
        log = home / 'setup.log'
        with log.open('ab') as stream:
            os.chmod(log, 0o600)
            subprocess.run([str(python), '-m', 'pip', 'install', '-e', str(root / 'server') + '[gateway]'],
                           check=True, stdout=stream, stderr=stream, timeout=300)
        java_candidates = [os.environ.get('JAVA_HOME', '/missing'),
            '/Applications/Android Studio.app/Contents/jbr/Contents/Home',
            str(Path.home()/'.local/share/reverse-tools/java/openjdk.jdk/Contents/Home')]
        java_candidates += list(Path('/Applications/Unity/Hub/Editor').glob('*/PlaybackEngines/AndroidPlayer/OpenJDK'))
        java_candidates += list((Path.home()/'.gradle/jdks').glob('*/Contents/Home'))
        import re
        build = (root/'client/android/app/build.gradle.kts').read_text()
        required_java = re.search(r'jvmToolchain\((\d+)\)', build)[1]
        def matching_jdk(candidate):
            candidate = Path(candidate)
            release = candidate/'release'
            return (candidate/'bin/java').is_file() and release.is_file() and bool(
                re.search(r'JAVA_VERSION="' + required_java + r'[.\"]', release.read_text()))
        java = next((Path(p) for p in java_candidates if matching_jdk(p)), home/'tools/jdk')
        if not (java/'bin/java').is_file():
            probe = ('import pathlib,re,sys; h=pathlib.Path.home(); n=sys.argv[1]; '
                     'c=[pathlib.Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home"),'
                     '*list((h/".gradle/jdks").glob("*/Contents/Home")),'
                     '*list(pathlib.Path("/Library/Java/JavaVirtualMachines").glob("*/Contents/Home"))]; '
                     'print(next(str(p) for p in c if (p/"release").is_file() and '
                     're.search(\'JAVA_VERSION="\'+n+r\'[.\\"]\', (p/"release").read_text())))')
            source_java = subprocess.check_output(diagnostics.ssh(donor) + [shlex.join([
                'python3','-c',probe,required_java])], text=True, timeout=30).strip()
            import_directory(args.source_node, source_java, java)
        # Keep the publishing SDK isolated from other projects and copy only the
        # required platform/build-tools from an already authorized installation.
        api = re.search(r'compileSdk\s*=\s*(\d+)', build)[1]
        sdk = home/'tools/android-sdk'
        probe = ('import pathlib,sys; p=pathlib.Path(sys.argv[1]); n=sys.argv[2]; '
                 'print(next(str(x) for x in (p/("android-"+n),p/("android-"+n+".0")) if (x/"android.jar").is_file()))')
        platform = subprocess.check_output(diagnostics.ssh(donor) + [shlex.join([
            'python3', '-c', probe, source['sdk']+'/platforms', api])], text=True, timeout=30).strip()
        import_directory(args.source_node, platform, sdk/'platforms'/Path(platform).name)
        import_directory(args.source_node, source['sdk']+'/build-tools/'+api+'.0.0', sdk/('build-tools/'+api+'.0.0'))
        import_directory(args.source_node, source['sdk']+'/licenses', sdk/'licenses')
        store = home/'private/rivo.keystore'
        data = remote_bytes(args.source_node, source['store'])
        if store.exists() and store.read_bytes() != data:
            raise RuntimeError('签名来源变化；保留原签名，不自动替换')
        if not store.exists():
            atomic(store, data)
        cfg = {'mode':'manager', 'python':str(python), 'javaHome':str(java), 'androidHome':str(sdk),
               'signingStore':str(store), 'gatewayHost':args.gateway_host,
               'gatewayUrl':trust['gateway'], 'gatewayPin':trust['pin'],
               'asrHost':args.asr_host or ('local' if Path('/Users/e2/Services/rivo-asr/.venv/bin/python').is_file() else 'e2-mac-mini'),
               'compatibility':donor}
        if args.gateway_identity:
            cfg['gatewayIdentity'] = str(args.gateway_identity.expanduser().resolve())
        config_file = root/'workspace/release-config.json'
        candidate = home/'candidate-config.json'
        atomic(candidate, (json.dumps(cfg,ensure_ascii=False,indent=2)+'\n').encode())
        env = dict(os.environ, RIVO_RELEASE_CONFIG=str(candidate), PYTHONPATH=str(root/'server'))
        # Fetch public artifacts with the node's already trusted certificate pin.
        fetch = 'from pathlib import Path; from rivo_relay.release_management.central import baseline; from rivo_relay.release_management.configuration import settings; import sys; p=Path(sys.argv[1]); baseline(p,settings(p),download=True)'
        subprocess.run([str(python), '-c', fetch, str(root)], env=env, check=True, timeout=300)
        checked = subprocess.run([str(python), str(root/'scripts/release.py'), '--local', 'doctor', '--online'],
                                 env=env, text=True, capture_output=True, timeout=180)
        report = json.loads(checked.stdout)
        atomic(home/'environment-verification.json', (json.dumps(report,ensure_ascii=False,indent=2)+'\n').encode())
        diagnostics.require_ready(report)
        atomic(config_file, candidate.read_bytes())
        launcher = home/'bin/rivo-release'
        atomic(launcher, ('#!/bin/sh\nunset RIVO_RELEASE_CONFIG\nexec '+shlex.join([str(python),str(root/'scripts/release.py'),'--local'])+' "$@"\n').encode(), 0o700)
        record = {'role':'release-manager','repository':str(root),'command':[str(launcher)],
                  'environmentReady':True,'verification':report}
        atomic(home/'installation.json',(json.dumps(record,ensure_ascii=False,indent=2)+'\n').encode())
        return {'installed':True,'role':'release-manager','home':str(home),'repository':str(root),
                'command':[str(launcher)],'environmentReady':True,
                'note':'本机可独立构建、验签并发布到 VPS；未发布、未重启服务；旧下载入口仅作兼容镜像'}
    finally:
        lock.rmdir()
