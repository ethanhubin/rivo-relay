"""Git inspection and Relay-owned worktrees on the selected native host.

Commands use argv, inherited native permissions, and bounded output. A remote
daemon never causes this service to read or mutate the Relay machine's checkout.
"""
from __future__ import annotations
import hashlib
import json
import posixpath
import re
import time
import uuid

from .connection import CodexError
from .files import native_path, read_directory

CAP = 2 * 1024 * 1024


def command(connection, argv, cwd=None, *, allowed=(0,), cap=CAP):
    result = connection.request('command/exec', {'command': argv, 'cwd': cwd,
        'timeoutMs': 120000, 'outputBytesCap': cap}, timeout=130)
    if result.get('exitCode') not in allowed:
        raise CodexError((result.get('stderr') or '节点命令未完成')[:1000],
                         code='git_operation_failed', http_status=409)
    return result.get('stdout', '')


def git(connection, cwd, *args, cap=CAP, allowed=(0,)):
    return command(connection, ['git', '-c', 'core.quotePath=false', '-c', 'color.ui=false',
                   '-C', native_path(cwd), *args], cwd, cap=cap, allowed=allowed)


def diff_summary(patch):
    files, current = [], None
    for line in patch.splitlines():
        if line.startswith('diff --git '):
            path = line.rsplit(' b/', 1)[-1]
            current = {'path': path, 'added': 0, 'removed': 0, 'binary': False}
            files.append(current)
        elif current is not None:
            if line.startswith('+++ b/'):
                current['path'] = line[6:].split('\t', 1)[0]
            elif line.startswith('Binary files ') or line.startswith('GIT binary patch'):
                current['binary'] = True
            elif line.startswith('+') and not line.startswith('+++'):
                current['added'] += 1
            elif line.startswith('-') and not line.startswith('---'):
                current['removed'] += 1
    return files


def project_directory(connection, path=None):
    if not path:
        path = command(connection, ['pwd'], cap=32768).strip()
    result = read_directory(connection, native_path(path))
    result['parentPath'] = posixpath.dirname(path.rstrip('/')) or '/'
    return result


def project_git(connection, path):
    try:
        head = git(connection, path, 'rev-parse', '--verify', 'HEAD').strip()
    except CodexError as error:
        if error.code != 'git_operation_failed':
            raise
        return {'available': False, 'reason': '此目录没有可用的 Git 提交', 'refs': []}
    raw = git(connection, path, 'for-each-ref', '--count=200', '--format=%(objectname)\t%(refname:short)', 'refs/heads', 'refs/remotes')
    refs = [{'id': head, 'commit': head, 'name': '当前 HEAD'}]
    for line in raw.splitlines():
        commit, _, name = line.partition('\t')
        if re.fullmatch(r'[0-9a-f]{40,64}', commit):
            refs.append({'id': commit, 'commit': commit, 'name': name})
    return {'available': True, 'head': head, 'branch': git(connection, path, 'branch', '--show-current').strip(),
            'dirty': bool(git(connection, path, 'status', '--porcelain')), 'refs': refs}


def review_context(connection, path):
    info = project_git(connection, path)
    if not info['available']:
        raise CodexError(info['reason'], code='not_git_repository', http_status=409)
    raw = git(connection, path, 'log', '-30', '--format=%H%x09%s')
    return {'branch': info['branch'], 'branches': [r['name'] for r in info['refs'][1:]],
            'commits': [{'id': s.partition('\t')[0], 'subject': s.partition('\t')[2]} for s in raw.splitlines()]}


def workspace_diff(connection, path):
    patch = git(connection, path, 'diff', '--no-ext-diff', '--no-textconv', '--find-renames', 'HEAD', '--', cap=CAP + 1)
    truncated = len(patch.encode()) >= CAP
    # Include untracked files, without treating them as already staged changes.
    untracked = git(connection, path, 'ls-files', '--others', '--exclude-standard', '-z', cap=65536).split('\0')
    for name in [s for s in untracked if s][:100]:
        if len(patch.encode()) >= CAP:
            truncated = True
            break
        part = git(connection, path, 'diff', '--no-index', '--no-ext-diff', '--no-textconv', '--', '/dev/null', name,
                   allowed=(0, 1), cap=CAP + 1)
        patch += part
    if len([s for s in untracked if s]) > 100:
        truncated = True
    if len(patch.encode()) > CAP:
        patch = patch.encode()[:CAP].decode('utf-8', errors='ignore').rsplit('\n', 1)[0]
        truncated = True
    return {'patch': patch, 'revision': hashlib.sha256(patch.encode()).hexdigest(),
            'files': diff_summary(patch), 'truncated': truncated}


def validate_review(target):
    if not isinstance(target, dict):
        raise CodexError('请选择审查范围', code='invalid_review', http_status=400)
    kind = target.get('type')
    if kind == 'uncommittedChanges':
        return {'type': kind}
    field = {'baseBranch': 'branch', 'commit': 'sha', 'custom': 'instructions'}.get(kind)
    value = target.get(field) if field else None
    if not isinstance(value, str) or not value.strip() or len(value) > (20000 if kind == 'custom' else 500):
        raise CodexError('审查范围无效', code='invalid_review', http_status=400)
    result = {'type': kind, field: value.strip()}
    if kind == 'commit':
        result['title'] = None
    return result


def directory_fingerprint(connection, path):
    # Hash every regular file, ignored files included. Never follow symlinks.
    # Diff/stat output is insufficient: binary changes and truncated patches
    # can be identical while file contents differ.
    script = '''import os,sys,hashlib,stat
root=sys.argv[1]; digest=hashlib.sha256(); count=0
for parent,dirs,files in os.walk(root,followlinks=False):
 dirs.sort(); files.sort()
 if parent==root:
  dirs[:]=[d for d in dirs if d!='.git']; files=[f for f in files if f!='.git']
 for name in sorted(set(dirs+files)):
  path=os.path.join(parent,name); before=os.lstat(path)
  digest.update(os.path.relpath(path,root).encode('utf-8','surrogateescape')+b'\\0')
  digest.update(str(stat.S_IMODE(before.st_mode)).encode()+b'\\0')
  if stat.S_ISLNK(before.st_mode): digest.update(b'L'+os.readlink(path).encode('utf-8','surrogateescape'))
  elif stat.S_ISREG(before.st_mode):
   digest.update(b'F')
   with open(path,'rb') as stream:
    for block in iter(lambda:stream.read(1048576),b''): digest.update(block)
   after=os.lstat(path)
   if (before.st_ino,before.st_size,before.st_mtime_ns)!=(after.st_ino,after.st_size,after.st_mtime_ns): raise RuntimeError('文件在校验期间变化')
  elif stat.S_ISDIR(before.st_mode): digest.update(b'D')
  else: raise RuntimeError('工作树包含特殊文件，不能清理')
  digest.update(b'\\0'); count+=1
print(digest.hexdigest())'''
    return command(connection, ['python3', '-c', script, path], path, cap=4096).strip()


def worktree_presence(connection, source, path):
    """Read registration and directory existence without changing Git metadata."""
    listing = git(connection, source, 'worktree', 'list', '--porcelain', '-z')
    # Git canonicalizes /var to /private/var on macOS and resolves parent
    # symlinks. Normalize on the native host rather than the Relay machine.
    raw = command(connection, ['python3', '-c',
        'import json,os,sys; print(json.dumps({"path":os.path.realpath(sys.argv[1]),"exists":os.path.lexists(sys.argv[1])}))',
        path], source, cap=40000)
    try:
        metadata = json.loads(raw)
    except (TypeError, ValueError) as error:
        raise CodexError('无法核对工作树目录状态', code='native_protocol') from error
    if not isinstance(metadata, dict) or not isinstance(metadata.get('path'), str) or not isinstance(metadata.get('exists'), bool):
        raise CodexError('无法核对工作树目录状态', code='native_protocol')
    registered = metadata['path'] in [field[9:] for field in listing.split('\0') if field.startswith('worktree ')]
    return registered, metadata['exists']


class Workspaces:
    def __init__(self, db, service):
        self.db, self.service = db, service

    def create(self, project_id, source, ref):
        with self.service._operations:
            return self._create(project_id, source, ref)

    def _create(self, project_id, source, ref):
        info = project_git(self.service.connection, source)
        if not any(r['id'] == ref for r in info['refs']):
            raise CodexError('来源版本已变化，请重新选择', code='invalid_worktree_ref', http_status=409)
        identity = uuid.uuid4().hex
        parent = posixpath.join(posixpath.dirname(source.rstrip('/')), '.rivo-worktrees')
        path = posixpath.join(parent, identity)
        self.service.connection.request('fs/createDirectory', {'path': parent, 'recursive': True})
        with self.db.transaction() as c:
            c.execute('INSERT INTO workspaces VALUES(?,?,NULL,?,?,?,?,NULL,?)',
                      (identity, project_id, source, path, ref, 'creating', time.time()))
        try:
            git(self.service.connection, source, 'worktree', 'add', '--detach', '--', path, ref)
        except Exception as error:
            # A lost native reply may still be checking out files. Do not race
            # that command or automatically replay it with another directory.
            uncertain = not isinstance(error, CodexError) or not error.definite
            retained = self.abandon(identity, uncertain=uncertain)
            if retained['status'] != 'removed':
                raise CodexError('工作树创建未完成，已保留目录待核对：' + path,
                                 code='workspace_create_unknown' if uncertain else 'workspace_create_failed',
                                 http_status=409, request_sent=uncertain) from error
            raise
        with self.db.transaction() as c:
            c.execute("UPDATE workspaces SET status='ready' WHERE id=?", (identity,))
        return identity, path

    def link(self, identity, thread_id):
        with self.service._operations, self.db.transaction() as c:
            row = c.execute('SELECT thread_id,status FROM workspaces WHERE id=?', (identity,)).fetchone()
            if not row or row['status'] != 'ready' or row['thread_id'] not in (None, thread_id):
                raise CodexError('工作树状态已变化，不能关联新会话', code='workspace_changed', http_status=409)
            c.execute('UPDATE workspaces SET thread_id=? WHERE id=?', (thread_id, identity))

    def abandon(self, identity, *, uncertain=False):
        """Compensate a failed thread/start, without deleting possible native work.

        The caller must treat a missing/invalid thread ID or lost response as
        uncertain. Linked, modified, or uncertain directories are retained.
        Cleanup failure never replaces the original thread creation failure.
        """
        with self.service._operations:
            with self.db.read() as c:
                stored = c.execute('SELECT * FROM workspaces WHERE id=?', (identity,)).fetchone()
                row = dict(stored) if stored else None
            if row is None:
                return {'id': identity, 'status': 'missing', 'path': None}
            if row['thread_id'] is not None or row['status'] == 'removed':
                return row
            status = 'creation_unknown' if uncertain else 'orphaned'
            with self.db.transaction() as c:
                c.execute('UPDATE workspaces SET status=? WHERE id=?', (status, identity))
            if not uncertain:
                try:
                    registered, exists = worktree_presence(self.service.connection, row['source_path'], row['path'])
                    if not registered and not exists:
                        status = 'removed'
                    elif registered and exists:
                        dirty = git(self.service.connection, row['path'], 'status', '--porcelain=v1', '--ignored=matching', '-z')
                        head = git(self.service.connection, row['path'], 'rev-parse', '--verify', 'HEAD').strip()
                        if not dirty and head == row['commit_id']:
                            # Never use --force when rolling back creation.
                            git(self.service.connection, row['source_path'], 'worktree', 'remove', '--', row['path'])
                            status = 'removed'
                except Exception:
                    # Leave any partial checkout/edits available for recovery.
                    status = 'orphaned'
            with self.db.transaction() as c:
                c.execute('UPDATE workspaces SET status=? WHERE id=?', (status, identity))
            return {**row, 'status': status}

    def row(self, thread_id):
        with self.db.read() as c:
            row = c.execute('SELECT * FROM workspaces WHERE thread_id=?', (thread_id,)).fetchone()
            return dict(row) if row else None

    def inspect(self, thread_id):
        row = self.row(thread_id)
        if row is None:
            return None
        if row['status'] == 'removing':
            # Read-only reconciliation for a lost remove reply. A directory
            # that still exists stays blocked; never retry a destructive call.
            with self.service._operations:
                row = self.row(thread_id)
                if row['status'] == 'removing':
                    registered, exists = worktree_presence(self.service.connection, row['source_path'], row['path'])
                    if not registered and not exists:
                        with self.db.transaction() as c:
                            c.execute("UPDATE workspaces SET status='removed' WHERE id=? AND status='removing'", (row['id'],))
                        row['status'] = 'removed'
                    else:
                        return {'id': row['id'], 'path': row['path'], 'sourcePath': row['source_path'], 'commit': row['commit_id'],
                                'status': 'removing', 'dirty': None, 'revision': '', 'archivePath': row['archive_path']}
        status = '' if row['status'] == 'removed' else git(self.service.connection, row['path'], 'status', '--porcelain=v1', '--ignored=matching', '-z')
        diff = '' if row['status'] == 'removed' else directory_fingerprint(self.service.connection, row['path'])
        revision = hashlib.sha256((row['id'] + status + diff).encode()).hexdigest()
        return {'id': row['id'], 'path': row['path'], 'sourcePath': row['source_path'], 'commit': row['commit_id'],
                'status': row['status'], 'dirty': bool(status), 'revision': revision, 'archivePath': row['archive_path']}

    def cleanup(self, thread_id, revision, discard):
        # Keep compensation inside the same lock lifetime. Otherwise a failed
        # call can reset a later call's 'cleaning' state after it begins.
        with getattr(self.service, "queue_lock", self.service._operations), self.service._operations:
            try:
                return self._cleanup(thread_id, revision, discard)
            finally:
                with self.db.transaction() as c:
                    c.execute("UPDATE workspaces SET status='ready' WHERE thread_id=? AND status='cleaning'", (thread_id,))

    def _cleanup(self, thread_id, revision, discard):
        with self.db.read() as c:
            queued = c.execute("SELECT 1 FROM queued_tasks WHERE thread_id=? AND status NOT IN ('done','cancelled') LIMIT 1", (thread_id,)).fetchone()
        if queued:
            raise CodexError('请先处理此会话的执行队列', code='workspace_busy', http_status=409)
        row = self.row(thread_id)
        if not row:
            raise CodexError('此会话不是 Relay 创建的工作树', code='not_managed_worktree', http_status=409)
        current = self.inspect(thread_id)
        if current['revision'] != revision or current['status'] != 'ready':
            raise CodexError('工作树状态已变化，请刷新', code='workspace_changed', http_status=409)
        snapshot = self.service.get_session(thread_id)
        if snapshot.get('readOnly') or snapshot.get('activeTurnId') or snapshot.get('requests'):
            raise CodexError('会话仍在执行或被其他运行时占用', code='workspace_busy', http_status=409)
        if current['dirty'] and not discard:
            raise CodexError('工作树有未保存改动，请明确确认归档后清理', code='workspace_dirty', http_status=409)
        with self.db.transaction() as c:
            c.execute("UPDATE workspaces SET status='cleaning' WHERE id=?", (row['id'],))
        # Native archive closes our thread writer before removing its cwd.
        try:
            self.service.archive_session(thread_id, True)
        except Exception:
            with self.db.transaction() as c:
                c.execute("UPDATE workspaces SET status='ready' WHERE id=?", (row['id'],))
            raise
        archive = None
        if current['dirty']:
            archive = row['path'] + '-archive-' + uuid.uuid4().hex + '.tar.gz'
            command(self.service.connection, ['tar', '-czf', archive, '-C', row['path'], '.'], row['source_path'])
            with self.db.transaction() as c:
                c.execute('UPDATE workspaces SET archive_path=? WHERE id=?', (archive, row['id']))
        # Check again after archive creation. Never discard edits newer than consent.
        if self.inspect(thread_id)['revision'] != revision:
            raise CodexError('归档期间文件发生变化，已保留工作树', code='workspace_changed', http_status=409)
        args = ['worktree', 'remove'] + (['--force'] if current['dirty'] else []) + ['--', row['path']]
        with self.db.transaction() as c:
            c.execute("UPDATE workspaces SET status='removing' WHERE id=?", (row['id'],))
        try:
            git(self.service.connection, row['source_path'], *args)
        except CodexError as error:
            if error.definite:
                with self.db.transaction() as c:
                    c.execute("UPDATE workspaces SET status='ready' WHERE id=?", (row['id'],))
            raise
        with self.db.transaction() as c:
            c.execute("UPDATE workspaces SET status='removed' WHERE id=?", (row['id'],))
        return {'threadId': thread_id}
