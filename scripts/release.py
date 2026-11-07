#!/usr/bin/env python3
"""Prepare, submit, inspect and resume a local-node Android release (no GitHub)."""
import argparse
import fcntl
import json
from pathlib import Path
import subprocess
import sys

from release_tools.common import load, require
from release_tools.workflow import Release, prepare, version


ROOT = Path(__file__).resolve().parents[1]


def parser():
    cli = argparse.ArgumentParser(description=__doc__)
    commands = cli.add_subparsers(dest='command', required=True)
    planning = commands.add_parser('prepare', help='递增版本并生成发布计划；不会构建、提交或上线')
    planning.add_argument('--version', help='默认递增 patch 版本；versionCode 自动递增')
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
    args = parser().parse_args(argv)
    try:
        if args.command == 'prepare':
            state = prepare(ROOT, args)
            print(json.dumps(state, ensure_ascii=False, indent=2))
            return 0
        version(args.version)
        directory = ROOT / 'workspace/artifacts' / ('release-' + args.version)
        state = load(directory / 'release-state.json')
        if args.command == 'status':
            running = False
            lock = ROOT / 'workspace/.release-cli.lock'
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
                              'steps': list(state['steps']), 'workerLog': str(directory / 'release.log'),
                              'stateFile': str(directory / 'release-state.json')}, ensure_ascii=False, indent=2))
            return 0
        if args.worker:
            release = Release(ROOT, args.version)
            release.retry_deploy = args.retry_deploy
            release.run(commit=args.commit)
            return 0
        python = ROOT / '.venv/bin/python'
        require(python.is_file(), '需要仓库 .venv/bin/python')
        with (directory / 'release.log').open('ab', buffering=0) as stream:
            command = [str(python), str(Path(__file__).resolve()), 'run', args.version, '--worker']
            if args.commit:
                command.append('--commit')
            if args.retry_deploy:
                command.append('--retry-deploy')
            process = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.DEVNULL,
                                       stdout=stream, stderr=stream, start_new_session=True, close_fds=True)
        print(json.dumps({'submitted': True, 'pid': process.pid, 'version': args.version,
                          'log': str(directory / 'release.log'),
                          'note': '提交后台任务不代表发布成功；请用 status 查看 complete'}, ensure_ascii=False))
        return 0
    except Exception as error:
        print(json.dumps({'error': str(error)}, ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
