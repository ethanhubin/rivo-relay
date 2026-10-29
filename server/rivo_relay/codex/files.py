"""Read the selected native host filesystem, including remote daemon hosts.

Native filesystem RPCs are host-level Desktop operations, not turn tools. They
have no threadId/sandbox override. Resource bounds are response limits, not a
second project permission policy.
"""
from __future__ import annotations

import base64
import binascii
import io
import mimetypes
import ntpath
import posixpath

from .connection import CodexError

MAX_FILE_BYTES = 4 * 1024 * 1024
MAX_TRANSFER_BYTES = 1024 * 1024 * 1024
TRANSFER_CHUNK_BYTES = 4 * 1024 * 1024
MAX_DIRECTORY_ENTRIES = 2000


def native_path(path):
    if (not isinstance(path, str) or not path or "\0" in path or len(path) > 32768
            or not (posixpath.isabs(path) or ntpath.isabs(path))):
        raise CodexError("文件路径必须是节点上的绝对路径", code="invalid_path", http_status=400)
    return path


def fs_request(connection, method, params):
    try:
        return connection.request(method, params)
    except CodexError as error:
        if error.native_code == -32601 or error.code == "native_method_unsupported":
            raise CodexError("此 Codex 版本不支持原生文件接口", code="native_fs_unsupported",
                             http_status=501) from error
        raise


def read_file(connection, path, *, max_bytes=MAX_FILE_BYTES):
    data = download_file(connection, path, max_bytes=max_bytes)
    result = {"path": path, "size": len(data), "mimeType": mimetypes.guess_type(path)[0] or "application/octet-stream"}
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        text = None
    if text is not None and "\0" not in text:
        result.update({"encoding": "utf-8", "text": text})
        if result["mimeType"] == "application/octet-stream":
            result["mimeType"] = "text/plain"
    else:
        result.update({"encoding": "base64", "dataBase64": base64.b64encode(data).decode("ascii")})
    return result


def _command(connection, command, cwd, cap=6 * 1024 * 1024):
    result = connection.request('command/exec', {'command': command, 'cwd': cwd,
        'timeoutMs': 120000, 'outputBytesCap': cap}, timeout=130)
    if result.get('exitCode') != 0:
        raise CodexError('节点无法读取文件，请检查文件及权限', code='file_read_failed', http_status=409)
    return result.get('stdout', '')


def _powershell(source):
    return ['powershell.exe', '-NoProfile', '-NonInteractive', '-EncodedCommand',
            base64.b64encode(source.encode('utf-16-le')).decode('ascii')]


def stream_download(connection, path, *, max_bytes=MAX_TRANSFER_BYTES):
    """Read bounded ranges on the native host; never materialize a GiB RPC."""
    path = native_path(path)
    if not isinstance(max_bytes, int) or not 1 <= max_bytes <= MAX_TRANSFER_BYTES:
        raise CodexError('文件读取大小限制无效', code='invalid_file_limit', http_status=400)
    metadata = fs_request(connection, 'fs/getMetadata', {'path': path})
    if not metadata.get('isFile'):
        raise CodexError('只能读取普通文件', code='not_regular_file', http_status=415)
    windows = bool(ntpath.splitdrive(path)[0]) or path.startswith('\\\\')
    paths = ntpath if windows else posixpath
    cwd = paths.dirname(path)
    quoted = "'" + path.replace("'", "''") + "'"
    command = (_powershell('$ErrorActionPreference="Stop"; (Get-Item -LiteralPath ' + quoted + ').Length') if windows else
               ['/bin/sh', '-c', 'wc -c < "$1"', 'rivo-size', path])
    try:
        size = int(_command(connection, command, cwd, 1024).strip())
    except ValueError as error:
        raise CodexError('文件长度响应无效', code='native_protocol') from error
    if size < 0 or size > max_bytes:
        raise CodexError('文件超出读取大小限制', code='file_too_large', http_status=413)

    def chunks():
        for offset in range(0, size, TRANSFER_CHUNK_BYTES):
            expected = min(TRANSFER_CHUNK_BYTES, size - offset)
            if windows:
                source = ('$ErrorActionPreference="Stop"; $s=[IO.File]::OpenRead(' + quoted + '); try { '
                    f'$s.Position={offset}; $b=New-Object byte[] {expected}; $n=0; '
                    'while($n -lt $b.Length){$r=$s.Read($b,$n,$b.Length-$n); if($r -eq 0){throw "truncated"}; $n+=$r}; '
                    '[Convert]::ToBase64String($b) } finally {$s.Dispose()}')
                command = _powershell(source)
            else:
                command = ['/bin/sh', '-c', 'dd if="$1" bs=4194304 skip="$2" count=1 2>/dev/null | base64',
                           'rivo-read', path, str(offset // TRANSFER_CHUNK_BYTES)]
            encoded = _command(connection, command, cwd)
            try:
                data = base64.b64decode(''.join(encoded.split()), validate=True)
            except (ValueError, binascii.Error) as error:
                raise CodexError('文件块编码无效', code='native_protocol') from error
            if len(data) != expected:
                raise CodexError('文件读取不完整或已变化', code='file_changed', http_status=409)
            yield data
    return size, chunks()


def download_file(connection, path, *, max_bytes=MAX_FILE_BYTES):
    # Only bounded previews use a materialized byte array; HTTP downloads iterate.
    size, chunks = stream_download(connection, path, max_bytes=max_bytes)
    return b''.join(chunks)


def read_directory(connection, path):
    path = native_path(path)
    result = fs_request(connection, "fs/readDirectory", {"path": path})
    entries = result.get("entries")
    if not isinstance(entries, list):
        raise CodexError("原生目录响应格式无效", code="native_protocol")
    if len(entries) > MAX_DIRECTORY_ENTRIES:
        raise CodexError("目录内容超出预览数量限制", code="directory_too_large", http_status=413)
    return {"path": path, "entries": entries}


def upload_attachment(connection, cwd, name, encoded):
    """Stage a phone file on the native host; never overwrite a project file."""
    import uuid
    cwd = native_path(cwd)
    if (not isinstance(name, str) or not name.strip() or len(name) > 200
            or name in {".", ".."} or any(c in name for c in '/\\:')
            or any(ord(c) < 32 for c in name)):
        raise CodexError("文件名无效", code="invalid_filename", http_status=400)
    if not isinstance(encoded, str) or len(encoded) > ((MAX_TRANSFER_BYTES + 2) // 3) * 4:
        raise CodexError("文件不能超过 1 GiB", code="file_too_large", http_status=413)
    try:
        data = base64.b64decode(encoded, validate=True)
    except (binascii.Error, ValueError) as error:
        raise CodexError("文件编码无效", code="invalid_file_data", http_status=400) from error
    if len(data) > MAX_TRANSFER_BYTES:
        raise CodexError("文件不能超过 1 GiB", code="file_too_large", http_status=413)
    return upload_attachment_stream(connection, cwd, name, io.BytesIO(data), len(data))


def upload_attachment_stream(connection, cwd, name, stream, size):
    import uuid
    cwd = native_path(cwd)
    if (not isinstance(name, str) or not name.strip() or len(name) > 200
            or name in {'.', '..'} or any(c in name for c in '/\\:')
            or any(ord(c) < 32 for c in name)):
        raise CodexError('文件名无效', code='invalid_filename', http_status=400)
    if not isinstance(size, int) or not 0 <= size <= MAX_TRANSFER_BYTES:
        raise CodexError('文件最大支持 1 GiB', code='file_too_large', http_status=413)
    def read_exact(count):
        data = stream.read(count)
        if len(data) != count:
            raise CodexError('文件上传不完整', code='incomplete_upload', http_status=400)
        return data
    paths = ntpath if ntpath.splitdrive(cwd)[0] or cwd.startswith('\\\\') else posixpath
    directory = paths.join(cwd, '.rivo-attachments', str(uuid.uuid4()))
    target = paths.join(directory, name)
    fs_request(connection, 'fs/createDirectory', {'path': directory, 'recursive': True})
    if size <= 8 * 1024 * 1024:
        try:
            fs_request(connection, 'fs/writeFile', {'path': target, 'dataBase64': base64.b64encode(read_exact(size)).decode('ascii')})
        except Exception:
            fs_request(connection, 'fs/remove', {'path': directory, 'recursive': True})
            raise
    else:
        # Native WebSocket frames/messages are bounded independently of HTTP.
        # Keep each FS RPC small, then assemble on that same native host using
        # its configured command permissions. No sandbox/profile override.
        parts_dir = paths.join(directory, '.parts')
        fs_request(connection, 'fs/createDirectory', {'path': parts_dir, 'recursive': True})
        chunks = []
        try:
            for offset in range(0, size, TRANSFER_CHUNK_BYTES):
                chunk = paths.join(parts_dir, str(len(chunks)))
                fs_request(connection, 'fs/writeFile', {'path': chunk,
                    'dataBase64': base64.b64encode(read_exact(min(TRANSFER_CHUNK_BYTES, size - offset))).decode('ascii')})
                chunks.append(chunk)
            if paths is ntpath:
                script = paths.join(parts_dir, 'assemble.ps1')
                source = ('param([string]$Target, [Parameter(ValueFromRemainingArguments=$true)][string[]]$Parts)\n'
                    '$ErrorActionPreference = "Stop"; $out = [IO.File]::Open($Target, [IO.FileMode]::CreateNew); '
                    'try { foreach ($p in $Parts) { $inputFile = [IO.File]::OpenRead($p); '
                    'try { $inputFile.CopyTo($out) } finally { $inputFile.Dispose() } } } finally { $out.Dispose() }')
                fs_request(connection, 'fs/writeFile', {'path': script, 'dataBase64': base64.b64encode(source.encode()).decode('ascii')})
                command = ['powershell.exe', '-NoProfile', '-NonInteractive', '-File', script, target, *chunks]
            else:
                command = ['/bin/sh', '-c', 'set -eu; set -C; out=$1; shift; cat "$@" > "$out"', 'rivo-assemble', target, *chunks]
            result = connection.request('command/exec', {'command': command, 'cwd': cwd,
                'timeoutMs': 120000, 'outputBytesCap': 1024}, timeout=130)
            if result.get('exitCode') != 0:
                raise CodexError('节点未能合并文件，请检查工作区写入权限', code='attachment_assembly_failed', http_status=409)
        except Exception:
            # Only this newly generated upload directory is eligible for cleanup.
            try:
                fs_request(connection, 'fs/remove', {'path': directory, 'recursive': True})
            except Exception:
                pass
            raise
        else:
            fs_request(connection, 'fs/remove', {'path': parts_dir, 'recursive': True})
    return {'type': 'mention', 'name': name, 'path': target, 'size': size}
