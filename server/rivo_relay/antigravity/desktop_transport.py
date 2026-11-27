"""Restricted loopback client for the running Antigravity App language server."""

from dataclasses import dataclass, field
from hashlib import sha256
import ctypes
import http.client
import json
import os
from pathlib import Path
import plistlib
import re
import ssl
import stat
import subprocess


SERVICE = '/exa.language_server_pb.LanguageServerService/'
SUPPORTED_APP_VERSIONS = frozenset({'2.35.2'})
TOKEN_PATTERN = re.compile(r'[A-Za-z0-9_-]{16,200}')
MAX_REGISTRY_BYTES = 16384
MAX_RPC_BYTES = 32 * 1024 * 1024
PROC_PIDTBSDINFO = 3


class DesktopError(RuntimeError):
    def __init__(self, message, *, code='antigravity_desktop_unavailable', http_status=503, sent=False):
        super().__init__(message)
        self.code = code
        self.http_status = http_status
        self.definite = not sent


def _file_stamp(info):
    return (info.st_dev, info.st_ino, info.st_mtime_ns, info.st_size)


@dataclass(frozen=True)
class _Registry:
    path: Path
    stamp: tuple
    pid: int
    port: int
    token: str = field(repr=False)
    ls_version: str = ''


class _ProcessInfo(ctypes.Structure):
    # Darwin sys/proc_info.h, struct proc_bsdinfo.
    _fields_ = [
        ('flags_and_state', ctypes.c_uint32 * 3), ('pid', ctypes.c_uint32),
        ('parent_pid', ctypes.c_uint32), ('uid', ctypes.c_uint32),
        ('other_ids', ctypes.c_uint32 * 6), ('names', ctypes.c_char * 48),
        ('process_fields', ctypes.c_uint32 * 6),
        ('started_seconds', ctypes.c_uint64), ('started_microseconds', ctypes.c_uint64),
    ]


def _private_registry_file(path):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, 'rb') as file:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o077:
            return None
        raw = file.read(MAX_REGISTRY_BYTES + 1)
    if len(raw) > MAX_REGISTRY_BYTES:
        return None
    row = json.loads(raw)
    if not isinstance(row, dict):
        return None
    pid, port, token = row.get('pid'), row.get('httpsPort'), row.get('csrfToken')
    ls_version = row.get('lsVersion')
    if (type(pid) is not int or pid <= 0 or type(port) is not int or not 1 <= port <= 65535
            or not isinstance(token, str) or not TOKEN_PATTERN.fullmatch(token)
            or not isinstance(ls_version, str) or len(ls_version) > 128):
        return None
    return _Registry(path, _file_stamp(info), pid, port, token, ls_version)


def _launch_identity(pid, executable):
    """Identify the same-user Mac process without reading its arguments."""
    libproc = ctypes.CDLL('/usr/lib/libproc.dylib', use_errno=True)
    buffer = ctypes.create_string_buffer(4096)
    if libproc.proc_pidpath(pid, ctypes.byref(buffer), ctypes.sizeof(buffer)) <= 0:
        raise OSError('Antigravity process not found')
    if Path(os.fsdecode(buffer.value)).resolve() != executable:
        raise ValueError('Unexpected Antigravity process path')
    info = _ProcessInfo()
    length = libproc.proc_pidinfo(pid, PROC_PIDTBSDINFO, ctypes.c_uint64(0),
                                ctypes.byref(info), ctypes.sizeof(info))
    if length != ctypes.sizeof(info) or info.uid != os.geteuid() or info.pid != pid or not info.started_seconds:
        raise ValueError('Unexpected Antigravity process owner')
    return info.started_seconds, info.started_microseconds


def _loopback_listener(pid, port):
    result = subprocess.run(
        ['/usr/sbin/lsof', '-nP', '-a', '-p', str(pid), '-iTCP:' + str(port), '-sTCP:LISTEN', '-Fn'],
        stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=2, check=True,
    )
    return f'n127.0.0.1:{port}' in result.stdout.splitlines()


def _holds_profile_directory(pid, directory):
    result = subprocess.run(
        ['/usr/sbin/lsof', '-nP', '-a', '-p', str(pid), '+d', str(directory), '-Fn'],
        stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=2,
    )
    # A restricted directory scan can report a positive descriptor alongside
    # an exit warning for other entries in that directory.
    return result.returncode in (0, 1) and f'n{directory}' in result.stdout.splitlines()


class DesktopPeer:
    def __init__(self, registry, app_version, identity, executable):
        self._registry = registry
        self._executable = executable
        self._identity = identity
        self._tls = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        # Authenticate the local owner/port before capturing the server's leaf.
        # This setting is private to this peer and never used for remote HTTP.
        self._tls.check_hostname = False
        self._tls.verify_mode = ssl.CERT_NONE
        self._fingerprint = None
        self.version = app_version
        self.ls_version = registry.ls_version
        self.generation = ''

    @classmethod
    def connect(cls, profile_dir, app_path):
        try:
            info_path = app_path / 'Contents' / 'Info.plist'
            with info_path.open('rb') as descriptor:
                info = plistlib.load(descriptor)
            app_version = info.get('CFBundleShortVersionString')
            if not isinstance(app_version, str) or app_version not in SUPPORTED_APP_VERSIONS:
                raise DesktopError('Antigravity App 版本不在已验证范围内', code='antigravity_desktop_version')
            directory = profile_dir / 'daemon'
            info = directory.lstat()
            if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o077:
                raise OSError('Invalid native daemon registry')
            executable = (app_path / 'Contents' / 'Resources' / 'bin' / 'language_server').resolve(strict=True)
            paths = tuple(directory.glob('hub_*.json')) + tuple(directory.glob('ls_*.json'))
            candidates = {}
            for path in paths:
                try:
                    registry = _private_registry_file(path)
                    if registry is None or registry.ls_version != app_version:
                        continue
                    identity_key = (registry.pid, registry.port)
                    if identity_key in candidates:
                        if candidates[identity_key][0].token != registry.token:
                            raise DesktopError('Antigravity App 本机服务登记不一致')
                        continue
                    identity = _launch_identity(registry.pid, executable)
                    if _holds_profile_directory(registry.pid, directory) and _loopback_listener(registry.pid, registry.port):
                        candidates[identity_key] = (registry, identity)
                except (OSError, ValueError, subprocess.SubprocessError):
                    continue
            if len(candidates) != 1:
                raise OSError('No uniquely identified Antigravity App peer')
            registry, identity = next(iter(candidates.values()))
            peer = cls(registry, app_version, identity, executable)
            peer._call('GetStatus', {}, limit=65536)
            peer._verify_process()
            generation = [app_version, registry.ls_version, registry.pid, registry.port, *registry.stamp,
                          identity, peer._fingerprint.hex()]
            peer.generation = sha256(json.dumps(generation).encode()).hexdigest()
            return peer
        except (OSError, ValueError, subprocess.SubprocessError, plistlib.InvalidFileException) as error:
            raise DesktopError('未找到受支持的 Antigravity App 本机服务') from None

    def _verify_registry(self):
        try:
            info = self._registry.path.lstat()
            return stat.S_ISREG(info.st_mode) and info.st_uid == os.geteuid() and not info.st_mode & 0o077 \
                and _file_stamp(info) == self._registry.stamp
        except OSError:
            return False

    def alive(self):
        try:
            return (self._verify_registry()
                    and _launch_identity(self._registry.pid, self._executable) == self._identity)
        except (OSError, ValueError):
            return False

    def _verify_process(self):
        try:
            pid, port = self._registry.pid, self._registry.port
            if (_launch_identity(pid, self._executable) == self._identity
                    and _holds_profile_directory(pid, self._registry.path.parent)
                    and _loopback_listener(pid, port)):
                return
        except (OSError, ValueError, subprocess.SubprocessError):
            pass
        raise DesktopError('Antigravity App 本机服务身份已变化')

    def _call(self, method, payload, *, limit=MAX_RPC_BYTES, mutation=False):
        if not self._verify_registry():
            raise DesktopError('Antigravity App 本机服务已变化')
        if mutation:
            self._verify_process()
        link = http.client.HTTPSConnection('127.0.0.1', self._registry.port, timeout=3, context=self._tls)
        sent = False
        try:
            link.connect()
            cert = link.sock.getpeercert(binary_form=True)
            fingerprint = sha256(cert).digest()
            if self._fingerprint is None:
                self._fingerprint = fingerprint
            elif self._fingerprint != fingerprint:
                raise DesktopError('Antigravity App 本机证书已变化')
            if not self._verify_registry():
                raise DesktopError('Antigravity App 本机服务已变化')
            body = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode()
            sent = mutation
            link.request('POST', SERVICE + method, body=body, headers={
                'Content-Type': 'application/json',
                'Connect-Protocol-Version': '1',
                'Accept-Encoding': 'identity',
                'x-codeium-csrf-token': self._registry.token,
            })
            reply = link.getresponse()
            if reply.status != 200 or reply.getheader('Content-Encoding', 'identity') != 'identity':
                raise DesktopError('Antigravity App 未确认本机请求', sent=sent)
            content = reply.read(limit + 1)
            if len(content) > limit:
                raise DesktopError('Antigravity App 响应超过读取上限', code='antigravity_desktop_response_limit', sent=sent)
            if not content and mutation:
                return {}
            result = json.loads(content)
            if not isinstance(result, dict):
                raise ValueError('Invalid Connect response')
            return result
        except (OSError, ValueError, TypeError, http.client.HTTPException):
            raise DesktopError('无法核验 Antigravity App 本机请求的结果', sent=sent) from None
        finally:
            link.close()

    def summaries(self):
        return self._call('GetAllCascadeTrajectories', {})

    def trajectory(self, session_id):
        return self._call('GetCascadeTrajectory', {'cascadeId': session_id})

    def send_text(self, session_id, text, client_message_id, cascade_config):
        return self._call('SendUserCascadeMessage', {
            'cascadeId': session_id, 'items': [{'text': text}],
            'clientMessageId': client_message_id, 'deliveryStrategy': 2,
            'cascadeConfig': cascade_config,
        }, limit=65536, mutation=True)

    def answer_command(self, session_id, trajectory_id, index, command, allow):
        return self._call('HandleCascadeUserInteraction', {
            'cascadeId': session_id,
            'interaction': {'trajectoryId': trajectory_id, 'stepIndex': index,
                            'runCommand': {'confirm': allow, 'submittedCommandLine': command}},
        }, limit=65536, mutation=True)

    def answer_command_permission(self, session_id, trajectory_id, index, allow):
        return self._call('HandleCascadeUserInteraction', {
            'cascadeId': session_id,
            'interaction': {'trajectoryId': trajectory_id, 'stepIndex': index,
                            'permission': {'allow': allow, 'scope': 1}},
        }, limit=65536, mutation=True)
