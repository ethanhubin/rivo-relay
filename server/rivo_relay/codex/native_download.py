"""Validated single-range downloads from the selected native host."""
import base64
import binascii
import json
import ntpath
import posixpath
import re

from .connection import CodexError
from .files import MAX_TRANSFER_BYTES, TRANSFER_CHUNK_BYTES, _command, _powershell, fs_request, native_path


_SNAPSHOT = r'''
import hashlib,json,os,stat,sys
path=sys.argv[1]
def version(s): return [s.st_dev,s.st_ino,s.st_size,s.st_mtime_ns,s.st_ctime_ns]
with open(path,'rb') as stream:
 before=os.fstat(stream.fileno())
 if not stat.S_ISREG(before.st_mode): raise RuntimeError('not regular file')
 if before.st_size>1073741824:
  print(json.dumps({'size':before.st_size})); sys.exit(0)
 digest=hashlib.sha256()
 total=0
 for block in iter(lambda:stream.read(1048576),b''):
  total+=len(block)
  if total>1073741824: raise RuntimeError('file grew beyond limit')
  digest.update(block)
 after=os.fstat(stream.fileno())
 if version(before)!=version(after) or version(after)!=version(os.stat(path)): raise RuntimeError('file changed')
 print(json.dumps({'size':after.st_size,'version':version(after),'digest':digest.hexdigest()}))
'''

_CHUNK = r'''
import base64,json,os,stat,sys
path,raw,offset,count=sys.argv[1:]
expected=json.loads(raw)
def version(s): return [s.st_dev,s.st_ino,s.st_size,s.st_mtime_ns,s.st_ctime_ns]
with open(path,'rb') as stream:
 before=os.fstat(stream.fileno())
 if not stat.S_ISREG(before.st_mode) or version(before)!=expected: raise RuntimeError('file changed')
 stream.seek(int(offset)); data=stream.read(int(count))
 if len(data)!=int(count) or version(os.fstat(stream.fileno()))!=expected or version(os.stat(path))!=expected: raise RuntimeError('file changed')
 print(base64.b64encode(data).decode('ascii'))
'''


def byte_range(header, size):
    """Return an inclusive byte range; reject multipart and malformed ranges."""
    if header is None:
        return 0, size - 1, False
    match = re.fullmatch(r'bytes=(\d*)-(\d*)', header.strip())
    if not match or not any(match.groups()) or size == 0:
        raise CodexError('文件范围无效', code='invalid_range', http_status=416)
    first, last = match.groups()
    # Bound integer parsing as well as transfer size.
    if len(first) > 20 or len(last) > 20:
        raise CodexError('文件范围无效', code='invalid_range', http_status=416)
    if not first:
        suffix = int(last)
        if suffix == 0:
            raise CodexError('文件范围无效', code='invalid_range', http_status=416)
        start, end = max(0, size - suffix), size - 1
    else:
        start, end = int(first), int(last) if last else size - 1
        if start >= size or end < start:
            raise CodexError('文件范围超出长度', code='invalid_range', http_status=416)
        end = min(end, size - 1)
    return start, end, True


def prepare_download(connection, path, range_header=None, if_range=None):
    path = native_path(path)
    metadata = fs_request(connection, 'fs/getMetadata', {'path': path})
    if not metadata.get('isFile') or metadata.get('isSymlink'):
        raise CodexError('只能下载普通文件', code='not_regular_file', http_status=415)
    windows = bool(ntpath.splitdrive(path)[0]) or path.startswith('\\\\')
    cwd = (ntpath if windows else posixpath).dirname(path)
    quoted = "'" + path.replace("'", "''") + "'"
    if windows:
        snapshot = ('$ErrorActionPreference="Stop"; $p=' + quoted + '; '
            '$a=Get-Item -LiteralPath $p; if($a.Length -gt 1073741824){@{size=$a.Length}|ConvertTo-Json -Compress;exit 0}; '
            '$v=@($a.Length,$a.LastWriteTimeUtc.Ticks,$a.CreationTimeUtc.Ticks); '
            '$h=(Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLowerInvariant(); '
            '$b=Get-Item -LiteralPath $p; $w=@($b.Length,$b.LastWriteTimeUtc.Ticks,$b.CreationTimeUtc.Ticks); '
            'if(($v -join ",") -ne ($w -join ",")){throw "file changed"}; '
            '@{size=$b.Length;version=$w;digest=$h}|ConvertTo-Json -Compress')
        command = _powershell(snapshot)
    else:
        command = ['python3', '-c', _SNAPSHOT, path]
    try:
        snapshot = json.loads(_command(connection, command, cwd, 4096))
    except (TypeError, ValueError) as error:
        raise CodexError('文件版本不可用', code='native_protocol') from error
    size, digest, version = snapshot.get('size'), snapshot.get('digest'), snapshot.get('version')
    if isinstance(size, int) and size > MAX_TRANSFER_BYTES:
        raise CodexError('文件不能超过 1 GiB', code='file_too_large', http_status=413)
    if (not isinstance(size, int) or isinstance(size, bool) or not 0 <= size <= MAX_TRANSFER_BYTES
            or not isinstance(digest, str) or not re.fullmatch('[0-9a-f]{64}', digest)
            or not isinstance(version, list) or len(version) != (3 if windows else 5)
            or any(not isinstance(value, int) or isinstance(value, bool) for value in version)):
        raise CodexError('文件版本不可用', code='native_protocol')
    etag = '"sha256-' + digest + '"'
    # A stale validator must return the complete new representation. A caller
    # can safely resume only after checking both 206 and this exact ETag.
    effective_range = None if if_range is not None and if_range != etag else range_header
    try:
        start, end, partial = byte_range(effective_range, size)
    except CodexError as error:
        error.range_size, error.range_etag = size, etag
        raise

    def chunks():
        for offset in range(start, end + 1, TRANSFER_CHUNK_BYTES):
            count = min(TRANSFER_CHUNK_BYTES, end - offset + 1)
            if windows:
                expected = ','.join(str(value) for value in version)
                source = ('$ErrorActionPreference="Stop"; $p=' + quoted + '; '
                    'function Check { $f=Get-Item -LiteralPath $p; '
                    'if((@($f.Length,$f.LastWriteTimeUtc.Ticks,$f.CreationTimeUtc.Ticks) -join ",") -ne "' + expected + '"){throw "file changed"} }; '
                    'Check; $s=[IO.File]::Open($p,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read); try { '
                    f'$s.Position={offset}; $b=New-Object byte[] {count}; $n=0; '
                    'while($n -lt $b.Length){$r=$s.Read($b,$n,$b.Length-$n); if($r -eq 0){throw "truncated"}; $n+=$r}; '
                    'Check; [Convert]::ToBase64String($b) } finally {$s.Dispose()}')
                command = _powershell(source)
            else:
                command = ['python3', '-c', _CHUNK, path, json.dumps(version), str(offset), str(count)]
            try:
                data = base64.b64decode(''.join(_command(connection, command, cwd).split()), validate=True)
            except (ValueError, binascii.Error) as error:
                raise CodexError('文件块无效', code='native_protocol') from error
            if len(data) != count:
                raise CodexError('文件读取不完整', code='file_changed', http_status=409)
            yield data
    return {'size': size, 'start': start, 'end': end, 'partial': partial, 'etag': etag, 'chunks': chunks()}
