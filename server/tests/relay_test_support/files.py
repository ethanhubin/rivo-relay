"""Temporary filesystem and Git access through native RPC."""
from pathlib import Path
import subprocess
from rivo_relay.codex.connection import CodexError

class NativeHost:
    generation = 'test-host'

    def __init__(self, root):
        self.root = Path(root)
        self.projects = []
        self.threads = []

    def start(self):
        return {}

    def request(self, method, params, **kwargs):
        if method == 'command/exec':
            result = subprocess.run(params['command'], cwd=params.get('cwd') or self.root,
                                    capture_output=True, text=True, timeout=35)
            return {'exitCode': result.returncode, 'stdout': result.stdout, 'stderr': result.stderr}
        if method == 'fs/getMetadata':
            path = Path(params['path'])
            if not path.exists():
                raise CodexError('No such file', code='native_rejected', http_status=404, native_code=-1)
            return {'isFile': path.is_file(), 'isDirectory': path.is_dir(), 'isSymlink': path.is_symlink()}
        if method == 'project/list':
            return {'data': self.projects, 'nextCursor': None}
        if method == 'project/read':
            return {'project': next(project for project in self.projects if project['id'] == params['projectId'])}
        if method == 'thread/list':
            threads = self.threads
            if params.get('projectId'):
                threads = [thread for thread in threads if thread.get('projectId') == params['projectId']]
            return {'data': threads, 'nextCursor': None}
        if method == 'thread/goal/get':
            return {'goal': None}
        if method == 'thread/read':
            return {'thread': next(thread for thread in self.threads if thread['id'] == params['threadId'])}
        raise AssertionError((method, params))
