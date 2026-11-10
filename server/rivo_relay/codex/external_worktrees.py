"""Read-only Git membership on the connected native host, never Relay's host."""
from collections import OrderedDict
from contextlib import contextmanager
import json
import threading
import time

from .connection import CodexError
from .host_commands import node_command
from ..read_budget import checkpoint, read_lock


_DISCOVER = r'''
import json, os, subprocess, sys, tempfile
from functools import lru_cache
request=json.loads(sys.argv[1])
# Reuse probes only inside this helper invocation, never across native scans.
# Duplicate roots still append each project's membership below so ambiguity
# cannot be hidden by sharing their filesystem/Git lookup results.
@lru_cache(maxsize=None)
def canonical(path): return os.path.normcase(os.path.realpath(path))
@lru_cache(maxsize=None)
def git(root, *args):
 with tempfile.TemporaryFile() as output:
  try:
   process=subprocess.Popen(['git','-C',root,*args],stdout=output,stderr=subprocess.DEVNULL)
   try: code=process.wait(timeout=5)
   except subprocess.TimeoutExpired:
    process.kill(); process.wait(); return ''
   output.seek(0); data=output.read(1048577)
   return os.fsdecode(data) if code==0 and len(data)<=1048576 else ''
  except OSError: return ''
registrations={}
for project in request['projects']:
 for root in project['roots']:
  registered=[field[9:] for field in git(root,'worktree','list','--porcelain','-z').split('\0') if field.startswith('worktree ')]
  roots={canonical(value) for value in registered}
  # A Desktop project that is only a subdirectory cannot import its parent repo.
  if canonical(root) not in roots: continue
  common=git(root,'rev-parse','--path-format=absolute','--git-common-dir').removesuffix('\n')
  if not common: continue
  for path in request['paths']:
   if canonical(path) not in roots or canonical(path)==canonical(root): continue
   candidate=git(path,'rev-parse','--path-format=absolute','--git-common-dir').removesuffix('\n')
   if candidate and canonical(candidate)==canonical(common):
    commit=git(path,'rev-parse','--verify','HEAD').strip()
    registrations.setdefault(path,[]).append({'projectId':project['id'],'projectRoot':root,'path':path,'commit':commit})
print(json.dumps({'matches':registrations,'missing':[path for path in request['paths'] if not os.path.lexists(path)]}))
'''


class ExternalWorktrees:
    def __init__(self, connection, observe, observations_for):
        self.connection, self.observe, self.observations_for = connection, observe, observations_for
        self._lock = threading.Lock()
        self._cache = OrderedDict()
        self._flights = {}

    def _generation(self, expected):
        if str(self.connection.generation) != expected:
            raise CodexError('原生运行时已变化，请重新读取工作区', code='history_changed', http_status=409)

    @contextmanager
    def _flight(self, key):
        # Keep locks alive while readers hold or await them, independently of LRU eviction.
        with self._lock:
            entry = self._flights.setdefault(key, [threading.Lock(), 0])
            entry[1] += 1
        try:
            with read_lock(entry[0]):
                yield
        finally:
            with self._lock:
                entry[1] -= 1
                if not entry[1]:
                    self._flights.pop(key)

    def _cached(self, key):
        with self._lock:
            cached = self._cache.get(key)
            if cached is not None and cached[0] > time.monotonic():
                self._cache.move_to_end(key)
                return cached[1]
        return None

    def associations(self, threads, projects):
        checkpoint()
        candidates = [thread for thread in threads if isinstance(thread.get('cwd'), str)]
        roots = [{'id': p['id'], 'roots': [r['path'] for r in p.get('roots', []) if r.get('path')]} for p in projects]
        exact_roots = {path for project in roots for path in project['roots']}
        paths = sorted({thread['cwd'] for thread in candidates if thread['cwd'] not in exact_roots})
        if not paths:
            return {}
        request = {'projects': roots, 'paths': paths}
        key = (str(self.connection.generation), json.dumps(request, sort_keys=True))
        result = self._cached(key)
        if result is None:
            with self._flight(key):
                self._generation(key[0])
                result = self._cached(key)
                if result is None:
                    try:
                        response = node_command(self.connection, {'command': ['python3', '-c', _DISCOVER, key[1]],
                            'timeoutMs': 30000, 'outputBytesCap': 1024 * 1024}, timeout=35)
                        result = json.loads(response.get('stdout', '')) if response.get('exitCode') == 0 else {}
                        if not isinstance(result, dict) or not isinstance(result.get('matches'), dict) or not isinstance(result.get('missing'), list):
                            result = {}
                    except (CodexError, ValueError, TypeError):
                        result = {}
                    self._generation(key[0])
                    checkpoint()
                    # Failed native inspection never invents membership or missing paths.
                    with self._lock:
                        self._cache[key] = (time.monotonic() + 15, result)
                        self._cache.move_to_end(key)
                        while len(self._cache) > 8:
                            self._cache.popitem(last=False)
        checkpoint()
        if str(self.connection.generation) != key[0]:
            raise CodexError('原生运行时已变化，请重新读取工作区', code='history_changed', http_status=409)
        project_roots = {project['id']: project['roots'] for project in roots}
        found = {}
        for thread in candidates:
            path = thread['cwd']
            matches = result.get('matches', {}).get(path, [])
            valid = [entry for entry in matches if isinstance(entry, dict) and entry.get('path') == path
                     and entry.get('projectRoot') in project_roots.get(entry.get('projectId'), [])
                     and (not thread.get('projectId') or thread['projectId'] == entry.get('projectId'))]
            identities = {(entry['projectId'], entry['projectRoot']) for entry in valid}
            if len(identities) == 1:
                association = dict(valid[0])
                self.observe(thread['id'], 'externalWorkspace', '', association)
                found[thread['id']] = {**association, 'status': 'ready', 'managed': False}
            elif path in result.get('missing', []):
                previous = self.observations_for(thread['id']).get(('externalWorkspace', ''))
                if (previous and previous.get('path') == path and
                        (not thread.get('projectId') or thread['projectId'] == previous.get('projectId')) and
                        previous.get('projectRoot') in project_roots.get(previous.get('projectId'), [])):
                    found[thread['id']] = {**previous, 'status': 'missing', 'managed': False}
        return found
