"""Observe repository changes around native Review; never revert user files."""
import json
import threading

from .connection import CodexError
from .developer import command


_FINGERPRINT = r'''
import hashlib,json,subprocess,sys,threading
root=sys.argv[1]
def git(args, text=False):
 process=subprocess.Popen(['git','-C',root,*args],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
 timer=threading.Timer(30,process.kill); timer.start()
 digest=hashlib.sha256(); result=b''
 try:
  for block in iter(lambda:process.stdout.read(1048576),b''):
   digest.update(block)
   if text:
    result+=block
    if len(result)>4096: raise RuntimeError('invalid head')
  if process.wait()!=0: raise RuntimeError('git fingerprint unavailable')
 finally:
  timer.cancel(); process.stdout.close()
  if process.poll() is None: process.kill(); process.wait()
 return result.decode().strip() if text else digest.hexdigest()
head=git(['rev-parse','--verify','HEAD'],True)
index=git(['diff','--cached','--binary','--no-ext-diff','--no-textconv','HEAD','--'])
worktree=git(['diff','--binary','--no-ext-diff','--no-textconv','HEAD','--'])
status=git(['status','--porcelain=v1','-z','--untracked-files=all'])
print(json.dumps({'head':head,'index':index,'worktree':worktree,'status':status}))
'''


def fingerprint(connection, cwd):
    try:
        result = json.loads(command(connection, ['python3', '-c', _FINGERPRINT, cwd], cwd, cap=4096))
    except (ValueError, TypeError) as error:
        raise CodexError('无法确认审查前仓库状态', code='review_fingerprint_unavailable', http_status=409) from error
    if (not isinstance(result, dict) or set(result) != {'head', 'index', 'worktree', 'status'}
            or any(not isinstance(value, str) or not value for value in result.values())):
        raise CodexError('无法确认审查前仓库状态', code='review_fingerprint_unavailable', http_status=409)
    return result


class ReviewGuard:
    def __init__(self, connection, observe, observations_for, forget):
        self.connection, self.observe, self.observations_for, self.forget = connection, observe, observations_for, forget
        self.lock = threading.Lock()

    def begin(self, thread_id, cwd):
        baseline = {'cwd': cwd, 'before': fingerprint(self.connection, cwd)}
        self.observe(thread_id, 'reviewPending', '', baseline)
        return baseline

    def bind(self, thread_id, turn_id, baseline):
        self.observe(thread_id, 'reviewGuard', turn_id, {**baseline, 'status': 'pending'})
        self.forget(thread_id, 'reviewPending')

    def diagnostics(self, thread_id, turns):
        terminal = {turn['id'] for turn in turns if turn.get('status') in {'completed','failed','interrupted','canceled'}}
        with self.lock:
            records = self.observations_for(thread_id)
            for (kind, turn_id), record in records.items():
                if kind != 'reviewGuard' or record.get('status') != 'pending' or turn_id not in terminal:
                    continue
                try:
                    after = fingerprint(self.connection, record['cwd'])
                    changed = after != record['before']
                    record = {**record, 'after': after, 'status': 'changed' if changed else 'verified',
                              'readOnlyViolation': changed,
                              'message': '审查期间仓库发生变化；可能包含并行或外部修改，未自动回滚。' if changed else ''}
                except (CodexError, KeyError):
                    record = {**record, 'status': 'unavailable', 'readOnlyViolation': None,
                              'message': '审查后的仓库状态无法核对，不能确认只读边界；未修改或回滚文件。'}
                self.observe(thread_id, kind, turn_id, record)
            return [{'turnId': turn_id, **{key: record.get(key) for key in ('status','readOnlyViolation','message')}}
                    for (kind, turn_id), record in self.observations_for(thread_id).items()
                    if kind == 'reviewGuard' and record.get('status') != 'pending']
