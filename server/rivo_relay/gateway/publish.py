"""Install an administrator-uploaded release, with bounded history and disk checks."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import time
from .update_store import validate, sha256, manifest
from ..codex import native_history_support as locks

RESERVE = 1024 ** 3
GRACE = 24 * 3600
KEEP = 3
MAX_STORE = 512 * 1024 * 1024


def atomic(path, data):
    fd, temporary = tempfile.mkstemp(dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data); stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, path)
        if os.name != 'nt':
            directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
            try: os.fsync(directory)
            finally: os.close(directory)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)


def collect(workspace, now=None):
    """Call under publish lock. Keep current + newest 3 and a 24h download grace."""
    now = time.time() if now is None else now
    root = Path(workspace) / 'releases'
    latest = manifest(workspace)
    history = []
    for path in (root / 'history').glob('*.json'):
        item = validate(json.loads(path.read_text()))
        history.append((item['versionCode'], path, item))
    history.sort(key=lambda x: x[0], reverse=True)
    protected = {latest[k]['path'].rsplit('/', 1)[-1] for k in ('app', 'node')}
    remove_history = []
    for index, (_, path, item) in enumerate(history):
        if index < KEEP or now - path.stat().st_mtime < GRACE:
            protected.update(item[k]['path'].rsplit('/', 1)[-1] for k in ('app', 'node'))
        else:
            remove_history.append(path)
    removed = 0
    for path in (root / 'files').iterdir():
        if path.name.startswith('.upload-') and not path.is_symlink() and path.is_file() and now - path.stat().st_mtime >= GRACE:
            removed += path.stat().st_size
            path.unlink()
            continue
        if (not path.is_symlink() and path.is_file() and re.fullmatch(r'[a-f0-9]{64}\.(apk|zip)', path.name)
                and path.name not in protected and now - path.stat().st_mtime >= GRACE):
            removed += path.stat().st_size
            path.unlink()
    for path in remove_history: path.unlink()
    return removed


def install(incoming, workspace):
    incoming, workspace = Path(incoming), Path(workspace)
    item = validate(json.loads((incoming / 'latest.json').read_text()))
    root = workspace / 'releases'
    root.mkdir(parents=True, exist_ok=True)
    with (root / '.publish.lock').open('a') as lock:
        locks.flock(lock, locks.LOCK_EX | locks.LOCK_NB)
        (root / 'files').mkdir(exist_ok=True)
        (root / 'history').mkdir(exist_ok=True)
        if (root / 'latest.json').exists():
            old = manifest(workspace)
            old_app = old['app']; new_app = item['app']
            old_app_code = old_app.get('versionCode', old['versionCode'])
            new_app_code = new_app.get('versionCode', item['versionCode'])
            if (new_app_code < old_app_code or new_app_code == old_app_code and
                    (new_app['sha256'], new_app['size'], new_app.get('version', item['version']),
                     new_app.get('notes', item['notes'])) !=
                    (old_app['sha256'], old_app['size'], old_app.get('version', old['version']),
                     old_app.get('notes', old['notes']))):
                raise ValueError('Refusing app downgrade or replacement at the same version')
            if (old['versionCode'] > item['versionCode']
                    or tuple(map(int, old['version'].split('.'))) > tuple(map(int, item['version'].split('.')))
                    or (old['versionCode'] == item['versionCode'] and old != item)):
                raise ValueError('Refusing downgrade or replacement of published version')
            collect(workspace)
        missing = []
        for kind in ('app', 'node'):
            asset = item[kind]
            name = asset['path'].rsplit('/', 1)[-1]
            source = incoming / name
            destination = root / 'files' / name
            if destination.exists():
                if (destination.is_symlink() or destination.stat().st_size != asset['size']
                        or sha256(destination) != asset['sha256']):
                    raise ValueError('Stored asset is corrupt')
            else:
                if source.is_symlink() or source.stat().st_size != asset['size'] or sha256(source) != asset['sha256']:
                    raise ValueError('Uploaded asset integrity mismatch')
                missing.append((source, destination))
        required = sum(source.stat().st_size for source, _ in missing)
        occupied = sum(p.stat().st_size for p in (root / 'files').iterdir() if p.is_file())
        if occupied + required > MAX_STORE:
            raise ValueError('Update storage exceeds 512 MiB; keep download grace and retry later')
        if shutil.disk_usage(root).free < required + RESERVE:
            raise ValueError('Insufficient disk space: preserve at least 1 GiB free')
        for source, destination in missing:
            fd, temporary = tempfile.mkstemp(dir=destination.parent, prefix='.upload-')
            try:
                with os.fdopen(fd, 'wb') as output, source.open('rb') as stream:
                    shutil.copyfileobj(stream, output); output.flush(); os.fsync(output.fileno())
                os.replace(temporary, destination)
            finally:
                if os.path.exists(temporary): os.unlink(temporary)
        raw = (json.dumps(item, ensure_ascii=False, sort_keys=True) + '\n').encode()
        history = root / 'history' / (str(item['versionCode']) + '.json')
        if not history.exists(): atomic(history, raw)
        if (root / 'latest.json').exists():
            old = manifest(workspace)
            if old['versionCode'] != item['versionCode']:
                # Grace begins when a release stops being current, not when published.
                previous = root / 'history' / (str(old['versionCode']) + '.json')
                if previous.exists(): os.utime(previous, None)
        atomic(root / 'latest.json', raw)
        return {'versionCode': item['versionCode'], 'removedBytes': collect(workspace), 'freeBytes': shutil.disk_usage(root).free}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--incoming', type=Path, required=True)
    parser.add_argument('--workspace', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(install(args.incoming, args.workspace)))


if __name__ == '__main__': main()
