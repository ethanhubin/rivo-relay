"""Read-only Git membership on the connected native host, never Relay's host."""
import json
import threading
import time

from .connection import CodexError
from .host_commands import node_command


_DISCOVER = r'''
import json, os, subprocess, sys, tempfile
request=json.loads(sys.argv[1])
def canonical(path): return os.path.normcase(os.path.realpath(path))
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
        self._cache = None

    def associations(self, threads, projects):
        candidates = [thread for thread in threads if isinstance(thread.get('cwd'), str)]
        roots = [{'id': p['id'], 'roots': [r['path'] for r in p.get('roots', []) if r.get('path')]} for p in projects]
        exact_roots = {path for project in roots for path in project['roots']}
        paths = sorted({thread['cwd'] for thread in candidates if thread['cwd'] not in exact_roots})
        if not paths:
            return {}
        request = {'projects': roots, 'paths': paths}
        key = (str(self.connection.generation), json.dumps(request, sort_keys=True))
        with self._lock:
            if self._cache and self._cache[0] == key and self._cache[1] > time.monotonic():
                result = self._cache[2]
            else:
                try:
                    response = node_command(self.connection, {'command': ['python3', '-c', _DISCOVER, key[1]],
                        'timeoutMs': 30000, 'outputBytesCap': 1024 * 1024}, timeout=35)
                    result = json.loads(response.get('stdout', '')) if response.get('exitCode') == 0 else {}
                    if not isinstance(result, dict) or not isinstance(result.get('matches'), dict) or not isinstance(result.get('missing'), list):
                        result = {}
                except (CodexError, ValueError, TypeError):
                    result = {}
                # Failed native inspection never invents membership or missing paths.
                self._cache = (key, time.monotonic() + 15, result)
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
                association = valid[0]
                self.observe(thread['id'], 'externalWorkspace', '', association)
                found[thread['id']] = {**association, 'status': 'ready', 'managed': False}
            elif path in result.get('missing', []):
                previous = self.observations_for(thread['id']).get(('externalWorkspace', ''))
                if (previous and previous.get('path') == path and
                        (not thread.get('projectId') or thread['projectId'] == previous.get('projectId')) and
                        previous.get('projectRoot') in project_roots.get(previous.get('projectId'), [])):
                    found[thread['id']] = {**previous, 'status': 'missing', 'managed': False}
        return found
