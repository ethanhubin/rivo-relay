"""Same-user DSH status and direct pairing over a private Unix listener."""
from __future__ import annotations

from base64 import b64encode
from hashlib import sha256
from http.server import BaseHTTPRequestHandler
from io import BytesIO
import json
import os
import re
import socket
from socketserver import TCPServer
import stat
import threading
from urllib.parse import urlsplit

import qrcode
from qrcode.exceptions import DataOverflowError

from ..pairing import direct_pairing_link


class LocalAdminServer(TCPServer):
    address_family = socket.AF_UNIX

    def get_request(self):
        client, address = super().get_request()
        client.settimeout(2)
        return client, address


class LocalAdminHandler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def reply(self, status, value):
        data = json.dumps(value).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        if self.path not in ('/status', '/pair'):
            self.reply(404, {'state': 'invalid_request'})
            return
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 < length <= 1024 or self.headers.get_content_type() != 'application/json':
                raise ValueError
            request = json.loads(self.rfile.read(length))
            generation = request['generation']
            pairing_id = request.get('pairingId')
            if not isinstance(generation, str) or not re.fullmatch(r'[0-9a-f-]{36}', generation):
                raise ValueError
            if pairing_id is not None and (not isinstance(pairing_id, str) or not re.fullmatch(r'[0-9a-f]{32}', pairing_id)):
                raise ValueError
        except (KeyError, TypeError, ValueError):
            self.reply(400, {'state': 'invalid_request'})
            return
        app = self.server.app
        url = app.config.public_url.rstrip('/')
        state = app.backend.pairing_status(generation)
        direct_binding = sha256((url + '\n\n').encode()).hexdigest()
        if state == 'ready' and urlsplit(url).path:
            state = 'unsupported_route'
        result = {'state': state, 'directUrl': url if state == 'ready' else ''}
        if self.path == '/status':
            if state == 'ready' and pairing_id:
                result['pairingState'] = app.pairing.code_status(pairing_id)
            self.reply(200, result)
            return
        if state != 'ready':
            self.reply(409, result)
            return
        if not app.update_gate.enter():
            self.reply(503, {'state': 'updating'})
            return
        try:
            # Verify capacity with a same-length code before issuing a credential.
            qr = qrcode.QRCode()
            qr.add_data(direct_pairing_link(app.config.node_id, url, 'xxxx-xxxx-xxxx-xxxx'), optimize=0)
            try:
                qr.best_fit()
            except DataOverflowError:
                self.reply(409, {'state': 'unsupported_route'})
                return
            pairing = app.pairing.create(route_binding=direct_binding)
            qr.clear()
            qr.add_data(direct_pairing_link(app.config.node_id, url, pairing['code']), optimize=0)
            with BytesIO() as image:
                qr.make_image().save(image, format='PNG')
                result.update(pairingId=pairing['pairing_id'], expiresAt=pairing['expires_at'],
                              png=b64encode(image.getvalue()).decode('ascii'))
            self.reply(200, result)
        finally:
            app.update_gate.leave()


def serve_local_admin(app, path):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    parent = path.parent.lstat()
    if not stat.S_ISDIR(parent.st_mode) or parent.st_uid != os.getuid() or parent.st_mode & 0o077:
        raise ValueError('DSH admin socket requires a private directory owned by the current user (0700)')
    if path.exists() or path.is_symlink():
        raise ValueError('DSH admin socket already exists. Verify that the owner is stopped before removing it.')
    server = LocalAdminServer(str(path), LocalAdminHandler, bind_and_activate=False)
    server.app = app
    identity = None

    def cleanup():
        server.server_close()
        try:
            current = path.lstat()
            if identity and (current.st_dev, current.st_ino) == (identity.st_dev, identity.st_ino):
                path.unlink()
        except FileNotFoundError:
            pass

    try:
        server.server_bind()
        identity = path.lstat()
        path.chmod(0o600)
        server.server_activate()
        worker = threading.Thread(target=server.serve_forever, name='dsh-admin', daemon=True)
        worker.start()
    except BaseException:
        cleanup()
        raise

    def close():
        server.shutdown()
        worker.join()
        cleanup()

    return close
