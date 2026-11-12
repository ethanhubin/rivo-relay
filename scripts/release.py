#!/usr/bin/env python3
"""Initialize, prepare, run and resume a local release (no GitHub publication)."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import os
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
from rivo_relay.release_management import configuration, diagnostics


ROOT = Path(__file__).resolve().parents[1]


def parser():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('--local', action='store_true', help=argparse.SUPPRESS)
    commands = cli.add_subparsers(dest='command', required=True)
    from rivo_relay.release_management.setup import add_arguments
    add_arguments(commands.add_parser('setup-manager', help='安装本机发布管理入口；不发布、不重启节点'))
    doctor = commands.add_parser('doctor', help='只读检查实际执行节点，不递增版本、不构建、不部署')
    doctor.add_argument('--online', action='store_true', help='额外检查 VPS 与 ASR SSH 连接')
    planning = commands.add_parser('prepare', help='递增版本并生成发布计划；不会构建、提交或上线')
    planning.add_argument('--version', help='默认递增 patch 版本；versionCode 自动递增')
    planning.add_argument('--node-only', action='store_true', help='仅发布节点程序，复用已验证的 APK 与客户端版本')
    planning.add_argument('--notes', help='仓库内已有发布说明；省略则生成含 TODO 的模板')
    planning.add_argument('--android-test', action='append', default=[], help='相关 JUnit 类；可重复')
    planning.add_argument('--server-test', action='append', default=[], help='相关 unittest 模块；可重复')
    planning.add_argument('--checks-note', help='人工提供的既有验证依据；不会被标记为 CLI 执行通过')
    planning.add_argument('--device-scope', default='未进行真机或模拟器验证', help='实际设备验证范围')
    for name in ('run', 'resume'):
        command = commands.add_parser(name, help='后台执行发布')
        command.add_argument('version')
        command.add_argument('--worker', action='store_true', help=argparse.SUPPRESS)
        command.add_argument('--commit', action='store_true', help='验证后提交已暂存的改动；仅自动暂存版本和发布说明')
        command.add_argument('--retry-deploy', action='store_true', help='明确重试已完成回滚的服务切换')
    status = commands.add_parser('status', help='查看持久化步骤与后台日志位置')
    status.add_argument('version')
    return cli


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    args = parser().parse_args(argv)
    root = ROOT
    try:
        if args.command != 'setup-manager':
            cfg = configuration.settings(root)
            python = configuration.python(root)
            if cfg.get('mode') == 'manager' and os.path.abspath(python) != os.path.abspath(sys.executable):
                os.execv(python, [python, str(Path(__file__).resolve()), *argv])
        if args.command == 'setup-manager':
            from rivo_relay.release_management.setup import install
            print(json.dumps(install(args), ensure_ascii=False, indent=2))
            return 0
        if args.command == 'doctor':
            report = diagnostics.inspect(root, args.online)
            print(json.dumps(report, ensure_ascii=False, indent=2))
            return 0 if report['ready'] else 1
        import fcntl
        from release_tools.common import load, require, locked
        from release_tools.jobs import submit
        from release_tools.workflow import Release, prepare, version
        manager_root = root
        if configuration.settings(root).get('mode') == 'manager':
            from rivo_relay.release_management import sources
            if args.command in ('run', 'resume', 'status'):
                root = sources.selected(root, args.version)
        if args.command == 'prepare':
            if configuration.settings(root).get('mode') == 'manager':
                with locked(manager_root / 'workspace/.release-cli.lock'):
                    root = sources.create(root)
                    state = prepare(root, args)
                    sources.register(manager_root, root, state)
                state['checkout'] = str(root)
            else:
                state = prepare(root, args)
            print(json.dumps(state, ensure_ascii=False, indent=2))
            return 0
        version(args.version)
        directory = root / 'workspace/artifacts' / ('release-' + args.version)
        state = load(directory / 'release-state.json')
        if args.command == 'status':
            running = False
            lock = Path(state.get('managerRoot', root)) / 'workspace/.release-cli.lock'
            if lock.exists():
                with lock.open() as stream:
                    try:
                        fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    except BlockingIOError:
                        try:
                            running = json.load(stream).get('version') == args.version
                        except ValueError:
                            pass
            print(json.dumps({'version': args.version, 'phase': state['phase'], 'commit': state.get('commit'),
                              'running': running,
                              'error': state.get('error'), 'failedPhase': state.get('failedPhase'),
                              'compatibilityPending': state['steps'].get('compatibility', {}).get('pending', False),
                              'steps': list(state['steps']), 'workerLog': str(directory / 'release.log'),
                              'stateFile': str(directory / 'release-state.json')}, ensure_ascii=False, indent=2))
            return 0
        if args.worker:
            os.chdir(root)
            release = Release(root, args.version)
            release.retry_deploy = args.retry_deploy
            release.run(commit=args.commit)
            return 0
        python = Path(configuration.python(root))
        require(python.is_file(), '发布 Python 解释器不可用')
        command = [str(python), str(Path(__file__).resolve()), '--local', 'run', args.version, '--worker']
        if args.commit:
            command.append('--commit')
        if args.retry_deploy:
            command.append('--retry-deploy')
        label = 'local.rivo-relay.release.' + uuid.uuid4().hex
        log = str(directory / 'release.log')
        result = submit(label, command, log)
        require(result.returncode == 0, '发布任务提交失败：' + result.stderr.strip())
        print(json.dumps({'submitted': True, 'label': label, 'version': args.version,
                          'log': log,
                          'note': '提交后台任务不代表发布成功；请用 status 查看 complete'}, ensure_ascii=False))
        return 0
    except Exception as error:
        print(json.dumps({'error': str(error)}, ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
