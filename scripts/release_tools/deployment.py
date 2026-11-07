import hashlib
import io
import json
import os
from pathlib import Path
import plistlib
import sqlite3
import subprocess
import sys
import tarfile
import time

from .common import BASE, atomic, get_json, git, output, require, save


def tree_digest(directory):
    result = hashlib.sha256()
    for path in sorted(directory.rglob('*')):
        relative = path.relative_to(directory)
        runtime = relative.parts[0] == 'rivo_relay' or relative == Path('pyproject.toml')
        if runtime and path.is_file() and '__pycache__' not in path.parts and path.suffix != '.pyc':
            result.update(str(path.relative_to(directory)).encode() + b'\0' + hashlib.sha256(path.read_bytes()).digest())
    return result.hexdigest()


def _probe_native(socket_path, codex_home):
    # Use the same read-only transport/host attestation as Relay, never resume a thread.
    server = str(Path(__file__).resolve().parents[2] / 'server')
    sys.path.insert(0, server)
    try:
        from rivo_relay.codex.connection import NativeConnection
    finally:
        sys.path.remove(server)
    connection = NativeConnection(socket_path=socket_path, codex_home=codex_home)
    try:
        connection.start()
        return connection.native_host_info()
    finally:
        connection.close()


def native_identity(configuration):
    arguments = configuration['ProgramArguments']
    def option(name, default=None):
        if name not in arguments:
            return default
        index = arguments.index(name) + 1
        require(index < len(arguments), '缺少原生连接参数：' + name)
        return arguments[index]
    require(option('--codex-runtime', 'app_server') == 'app_server',
            '发布连续性检查需要明确的 app_server 运行时配置')
    socket_path = option('--native-socket')
    require(socket_path and Path(socket_path).is_absolute(), '发布连续性检查需要明确的原生 socket 路径')
    home = option('--codex-home') or configuration.get('EnvironmentVariables', {}).get('CODEX_HOME')
    host = _probe_native(socket_path, home)
    fields = ('pid', 'processStart', 'processStartMethod', 'executable', 'codexHome', 'platform')
    require(all(host.get(key) for key in fields) and type(host['pid']) is int
            and host['pid'] > 1 and host['processStartMethod'] == 'ps-lstart',
            '无法核实所选原生运行时身份')
    # Generation changes on every new RPC connection; auxiliary PIDs are irrelevant.
    return {'socketPath': str(Path(socket_path).resolve()), **{key: host[key] for key in fields}}


def verify_native_identity(configuration, before):
    require(isinstance(before.get('nativeHost'), dict),
            '旧发布计划缺少所选原生主机基准，不能自动确认连续性；请核查后新建发布计划')
    require(native_identity(configuration) == before['nativeHost'], '所选原生运行时身份发生变化')


def verify_snapshot(root, commit, target):
    algorithm = git(root, 'rev-parse', '--show-object-format')
    expected = set()
    records = git(root, 'ls-tree', '-r', '-z', commit, 'server').split('\0')
    for record in filter(None, records):
        metadata, name = record.split('\t', 1)
        mode, kind, object_id = metadata.split()
        path = target / name
        require(kind == 'blob' and mode in ('100644', '100755') and not path.is_symlink(), '快照含不支持的文件类型')
        raw = path.read_bytes()
        actual = hashlib.new(algorithm, b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
        require(actual == object_id, '服务快照与发布提交不一致：' + name)
        expected.add(path)
    actual = {p for p in (target / 'server').rglob('*') if p.is_file()
              and '__pycache__' not in p.parts and p.suffix != '.pyc'}
    require(actual == expected, '服务快照包含额外文件')


def preserved_connections(backup_path, database_path):
    query = 'SELECT id,token_hash FROM connections WHERE revoked_at IS NULL'
    with sqlite3.connect(backup_path) as before, sqlite3.connect(database_path) as after:
        original, current = dict(before.execute(query)), dict(after.execute(query))
        require(all(current.get(key) == value for key, value in original.items()), '已有授权发生变化，请检查后再发布')
        return len(original)


def events(path):
    if not path.exists():
        return []
    result = []
    for line in path.read_text().splitlines():
        try:
            result.append(json.loads(line).get('event'))
        except (ValueError, AttributeError):
            continue  # A worker can still be writing the final line.
    return result


def wait_deployment(path, timeout=150):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        observed = events(path)
        if 'rollback_failed' in observed:
            raise RuntimeError('服务恢复失败，请检查 deployment.log 和 .rollback.plist；不会重复卸载服务')
        if 'rollback_complete' in observed:
            raise RuntimeError('服务切换失败，已回滚；排查后可 resume --retry-deploy')
        if 'submission_failed' in observed:
            raise RuntimeError('部署任务提交失败，请检查 deployment-submission.log；不会盲目重试')
        if 'worker_failed' in observed:
            raise RuntimeError('部署任务失败，请检查 deployment.log；不会重复卸载服务')
        if 'complete' in observed:
            return
        time.sleep(1)
    raise RuntimeError('服务切换结果尚未确认；稍后 resume 将继续等待，不会重复重启')


def prepare_retry(release, plist, log):
    state, directory = release.state, release.directory
    pending = state.get('deploymentRetryArchive')
    if not pending:
        require('rollback_complete' in events(log), '只有已完成回滚的部署可使用 --retry-deploy')
        require(plist.read_bytes() == (directory / 'launchagent-before.plist').read_bytes(), '当前服务配置不同于回滚基准')
        pending = f'deployment-rollback-{time.time_ns()}.log'
        release.record('deployment-retry-prepared', deploymentRetryArchive=pending)
    require(Path(pending).name == pending, '无效的部署日志路径')
    archive = directory / pending
    require(plist.read_bytes() == (directory / 'launchagent-before.plist').read_bytes(), '当前服务配置不同于回滚基准')
    if not archive.exists():
        require('rollback_complete' in events(log), '缺少已完成回滚的记录')
        log.rename(archive)
    else:
        require(not log.exists() and 'rollback_complete' in events(archive), '部署重试日志状态不一致')
    release.record('deployment-prepared', deploymentRequested=False, deploymentRetryArchive=None)


def deploy(release):
    root, directory, state = release.root, release.directory, release.state
    target = root / 'workspace/deployments' / state['commit']
    if not target.exists():
        # The archive is made from the exact built commit, not the working tree.
        data = subprocess.check_output(['git', '-C', str(root), 'archive', state['commit'], 'server'])
        staging = directory / 'server-snapshot'
        if staging.exists():
            # A partial extraction is harmless: archive members overwrite it before rename.
            require(not staging.is_symlink(), '快照临时路径不能是符号链接')
        staging.mkdir(exist_ok=True)
        with tarfile.open(fileobj=io.BytesIO(data)) as archive:
            archive.extractall(staging, filter='data')
        target.parent.mkdir(parents=True, exist_ok=True)
        staging.rename(target)
    verify_snapshot(root, state['commit'], target)
    plist = Path.home() / 'Library/LaunchAgents/local.rivo-relay.plist'
    configuration = plistlib.loads(plist.read_bytes())
    require(configuration['Label'] == 'local.rivo-relay', '服务身份不正确')
    arguments = configuration['ProgramArguments']
    require('--workspace' in arguments and Path(arguments[arguments.index('--workspace') + 1]).resolve()
            == root / 'workspace/local-node', 'LaunchAgent 指向其他 workspace，不能部署')
    source = Path(configuration.get('EnvironmentVariables', {}).get('PYTHONPATH', '/missing'))
    require(source.is_dir() and (target / 'server/rivo_relay/__main__.py').is_file(), '服务源码快照不存在')
    expected = tree_digest(target / 'server')
    already_verified = state['steps'].get('deployment')
    if already_verified:
        require(tree_digest(source) == expected, '已部署服务被其他版本替换，停止发布')
        require(get_json(BASE + '/health').get('status') == 'ok', '主节点健康检查失败')
        return
    if not state.get('deploymentRequested') and tree_digest(source) == expected:
        require(get_json(BASE + '/health').get('status') == 'ok', '主节点健康检查失败')
        state['steps']['deployment'] = {'skipped': True, 'reason': 'server content unchanged'}
        release.record('deployed')
        return
    database = root / 'workspace/local-node/data/relay.sqlite3'
    if 'deploymentBefore' not in state:
        native = native_identity(configuration)
        with sqlite3.connect(database) as connection, sqlite3.connect(directory / 'relay-before-update.sqlite3') as backup:
            os.chmod(directory / 'relay-before-update.sqlite3', 0o600)
            connection.backup(backup)
            require(backup.execute('PRAGMA integrity_check').fetchone()[0] == 'ok', '数据库备份完整性检查失败')
        atomic(directory / 'launchagent-before.plist', plist.read_bytes())
        configuration.setdefault('EnvironmentVariables', {})['PYTHONPATH'] = str(target / 'server')
        atomic(directory / 'candidate.plist', plistlib.dumps(configuration))
        release.record('deployment-prepared', deploymentBefore={'nativeHost': native})
    log = directory / 'deployment.log'
    if state.get('deploymentRetryArchive') or (state.get('deploymentRequested') and release.retry_deploy):
        prepare_retry(release, plist, log)
    if not state.get('deploymentRequested'):
        verify_native_identity(configuration, state['deploymentBefore'])
        # Durable intent is written before submission. A crash here is ambiguous:
        # resume waits for the detached worker; it never blindly submits again.
        release.record('deploying', deploymentRequested=True)
        release.command([release.python, root / 'scripts/restart-service.py',
                         '--candidate', directory / 'candidate.plist',
                         '--expected-plist', directory / 'launchagent-before.plist',
                         '--submit', '--log-dir', directory],
                        'deployment-submission.log')
    wait_deployment(log)
    require(plist.read_bytes() == (directory / 'candidate.plist').read_bytes(), '实际部署配置与候选配置不同')
    verify_native_identity(plistlib.loads(plist.read_bytes()), state['deploymentBefore'])
    require(get_json(BASE + '/health').get('status') == 'ok', '主节点健康检查失败')
    count = preserved_connections(directory / 'relay-before-update.sqlite3', database)
    report = {'commit': state['commit'], 'serverSha256': expected, 'nativeIdentityUnchanged': True, 'nativeHost': state['deploymentBefore']['nativeHost'],
              'existingAuthorizationsPreserved': count, 'backupIntegrity': 'ok'}
    save(directory / 'node-update.json', report)
    state['steps']['deployment'] = report
    release.record('deployed')
