"""Pin each new publication to fresh origin/main in an isolated worktree."""
import json
from pathlib import Path
import re
import subprocess
import uuid

from .configuration import settings
from .setup import atomic


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()


def create(root):
    root = Path(root).resolve()
    # Explicit refspec also refreshes origin/main on repositories with a narrow
    # fetch configuration. Existing working files and branches are untouched.
    git(root, 'fetch', 'origin', '+refs/heads/main:refs/remotes/origin/main')
    commit = git(root, 'rev-parse', 'refs/remotes/origin/main^{commit}')
    try:
        git(root, 'cat-file', '-e', commit + ':server/rivo_relay/release_management/__init__.py')
    except subprocess.CalledProcessError:
        raise RuntimeError('最新 origin/main 尚未包含管理节点发布支持；先合并该功能，再准备正式发布') from None
    name = 'release-' + commit[:12] + '-' + uuid.uuid4().hex[:8]
    checkout = root / 'workspace/release-sources' / name
    checkout.parent.mkdir(parents=True, exist_ok=True)
    git(root, 'worktree', 'add', '-b', 'codex/' + name, str(checkout), commit)
    git(checkout, 'config', 'branch.codex/' + name + '.gh-merge-base', 'main')
    config = settings(root)
    atomic(checkout / 'workspace/release-config.json', (json.dumps(config, ensure_ascii=False, indent=2) + '\n').encode())
    cached_apks = root / 'workspace/local-node/updates/apks'
    cached_apks.mkdir(parents=True, exist_ok=True)
    updates = checkout / 'workspace/local-node/updates'
    updates.mkdir(parents=True)
    (updates / 'apks').symlink_to(cached_apks, target_is_directory=True)
    source = {'ref': 'origin/main', 'commit': commit, 'checkout': str(checkout), 'repository': str(root)}
    atomic(checkout / 'workspace/release-source.json', (json.dumps(source, indent=2) + '\n').encode())
    return checkout


def register(root, checkout, state):
    item = {'version': state['version'], 'checkout': str(checkout), 'baseCommit': state['baseCommit']}
    path = Path(root) / 'workspace/release-plans' / (state['version'] + '.json')
    if path.exists():
        raise RuntimeError('已有同版本发布计划；使用 status/resume，不能替换其源码')
    atomic(path, (json.dumps(item, indent=2) + '\n').encode())


def selected(root, version):
    if not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise ValueError('版本必须是 x.y.z')
    item = json.loads((Path(root) / 'workspace/release-plans' / (version + '.json')).read_text())
    checkout = Path(item['checkout'])
    source = json.loads((checkout / 'workspace/release-source.json').read_text())
    if source['commit'] != item['baseCommit'] or source['repository'] != str(Path(root).resolve()):
        raise RuntimeError('发布源码记录不一致；不能切换到其他源码继续发布')
    return checkout
