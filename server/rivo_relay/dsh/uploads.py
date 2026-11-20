"""Bounded Relay-owned staging for session-bound DSH file-upload receipts."""
import hashlib
import json
import os
from pathlib import Path
import re
import threading
import time
import uuid

from ..codex.connection import CodexError
from ..operation_gates import ThreadGates

MAX_FILE_BYTES = 20 * 1024 * 1024
MAX_STAGED_BYTES = 256 * 1024 * 1024
PREFIX = 'dsh-upload:'
_ID = re.compile(r'[a-f0-9]{32}')


class UploadStore:
    def __init__(self, root, *, clock=time.time):
        self.root, self.clock = Path(root), clock
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        if self.root.is_symlink() or not self.root.is_dir():
            raise ValueError('DSH attachment staging must be a private directory')
        self.root.chmod(0o700)
        self._lock, self._gates = threading.RLock(), ThreadGates()

    def _write(self, record):
        temporary = self.root / (uuid.uuid4().hex + '.tmp')
        try:
            descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, 'w') as stream:
                json.dump(record, stream, ensure_ascii=False, separators=(',', ':'))
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.root / (record['id'] + '.json'))
            directory = os.open(self.root, os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        finally:
            temporary.unlink(missing_ok=True)

    def _read(self, identity):
        path = self.root / (identity + '.json')
        try:
            if path.is_symlink() or path.stat().st_size > 1024 * 1024:
                raise ValueError()
            record = json.loads(path.read_text())
            if record['id'] != identity or record['version'] != 1:
                raise ValueError()
            return record
        except (OSError, ValueError, KeyError, TypeError) as error:
            raise CodexError('附件已过期，请重新选择文件', code='attachment_expired', http_status=409) from error

    def _prune(self):
        total, now = 0, self.clock()
        for metadata in self.root.glob('*.json'):
            identity = metadata.stem
            if not _ID.fullmatch(identity):
                continue
            record = self._read(identity)
            data = self.root / (identity + '.bin')
            if now - record.get('lastUsed', record.get('createdAt', now)) > 86400:
                # Only names created by this store are eligible for cleanup.
                data.unlink(missing_ok=True)
                metadata.unlink(missing_ok=True)
        for data in self.root.glob('*.bin'):
            if not _ID.fullmatch(data.stem) or data.is_symlink():
                continue
            info = data.stat()
            metadata = self.root / (data.stem + '.json')
            if not metadata.exists() and now - info.st_mtime > 86400:
                data.unlink()
            else:
                total += info.st_size
        return total

    def stage(self, scope, name, stream, size):
        if (not isinstance(name, str) or not name.strip() or len(name) > 200
                or name in ('.', '..') or any(c in name for c in '/\\:')
                or any(ord(c) < 32 for c in name)):
            raise CodexError('文件名无效', code='invalid_filename', http_status=400)
        if type(size) is not int or not 0 <= size <= MAX_FILE_BYTES:
            raise CodexError('DSH 单个附件最大支持 20 MiB', code='file_too_large', http_status=413)
        with self._lock:
            if self._prune() + size > MAX_STAGED_BYTES:
                raise CodexError('DSH 附件暂存空间已满', code='attachment_storage_full', http_status=409)
            identity = uuid.uuid4().hex
            data = self.root / (identity + '.bin')
            now = self.clock()
            record = {'version': 1, 'id': identity, 'scope': scope, 'name': name, 'size': size,
                      'createdAt': now, 'lastUsed': now, 'receipts': {}}
            try:
                descriptor = os.open(data, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                digest, remaining = hashlib.sha256(), size
                with os.fdopen(descriptor, 'wb') as output:
                    while remaining:
                        chunk = stream.read(min(1024 * 1024, remaining))
                        if not chunk or len(chunk) > remaining:
                            raise CodexError('文件上传不完整', code='incomplete_upload', http_status=400)
                        output.write(chunk)
                        digest.update(chunk)
                        remaining -= len(chunk)
                    output.flush()
                    os.fsync(output.fileno())
                record['sha256'] = digest.hexdigest()
                self._write(record)
            except Exception:
                data.unlink(missing_ok=True)
                raise
            return {'type': 'mention', 'name': name, 'path': PREFIX + identity, 'size': size}

    def receipt(self, source, session_id, request_id, *, project_contains, upload):
        if not isinstance(source, str) or not source.startswith(PREFIX) or not _ID.fullmatch(source[len(PREFIX):]):
            raise CodexError('请选择此节点上传的附件', code='invalid_attachment', http_status=400)
        identity = source[len(PREFIX):]
        receipt_key = json.dumps([session_id, request_id], separators=(',', ':'))
        with self._gates.hold(identity):
            with self._lock:
                record = self._read(identity)
                if self.clock() - record['lastUsed'] > 86400:
                    raise CodexError('附件已过期，请重新选择文件', code='attachment_expired', http_status=409)
                scope = record['scope']
            if not (scope.get('sessionId') == session_id or
                    (scope.get('projectId') and project_contains(scope['projectId'], session_id))):
                raise CodexError('附件不属于此会话或项目', code='attachment_scope', http_status=409)
            with self._lock:
                record['lastUsed'] = self.clock()
                previous = record['receipts'].get(receipt_key)
                if previous:
                    if previous.get('state') == 'accepted':
                        self._write(record)
                        return previous['receiptId']
                    raise CodexError('附件原生上传结果未知，请重新选择文件', code='attachment_upload_unknown', http_status=409)
                data = self.root / (identity + '.bin')
                try:
                    if data.is_symlink() or data.stat().st_size != record['size']:
                        raise ValueError()
                    raw = data.read_bytes()
                except (OSError, ValueError) as error:
                    raise CodexError('附件暂存内容已变化', code='attachment_changed', http_status=409) from error
                if hashlib.sha256(raw).hexdigest() != record['sha256']:
                    raise CodexError('附件暂存内容已变化', code='attachment_changed', http_status=409)
                record['receipts'][receipt_key] = {'state': 'dispatching'}
                self._write(record)
            try:
                result = upload(raw, record['name'])
                if not isinstance(result, dict) or not isinstance(result.get('receiptId'), str) or not result['receiptId']:
                    raise CodexError('DSH 附件回执缺失', code='dsh_protocol', request_sent=True)
            except Exception as error:
                with self._lock:
                    if isinstance(error, CodexError) and error.definite:
                        record['receipts'].pop(receipt_key, None)
                    else:
                        record['receipts'][receipt_key] = {'state': 'unknown'}
                    self._write(record)
                # No prompt has been sent. A failed attachment preparation is
                # definite for the chat submission, even if its upload is unknown.
                raise CodexError(str(error), code=getattr(error, 'code', 'attachment_upload_failed'),
                                 http_status=getattr(error, 'http_status', 409), request_sent=False) from error
            with self._lock:
                record['receipts'][receipt_key] = {'state': 'accepted', 'receiptId': result['receiptId']}
                self._write(record)
            return result['receiptId']
