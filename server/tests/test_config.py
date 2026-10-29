"""Config, real TLS transport, and process-lock tests with isolated fixtures."""

from dataclasses import replace
import http.client
import json
import os
from pathlib import Path
import select
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import unittest

from rivo_relay.api import Handler
from rivo_relay.app import RelayApp
from rivo_relay.config import Config
from rivo_relay.http_server import BoundedHTTPServer


class UnusedNative:
    def status(self):
        return {"connected": False, "capabilities": {}}

    def close(self):
        pass


class ConfigTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.config = Config(workspace=Path(self.directory.name))

    def test_non_loopback_listener_requires_tls_and_certificate_pair(self):
        for host in ("0.0.0.0", "::", "192.0.2.10"):
            with self.subTest(host=host), self.assertRaises(ValueError):
                replace(self.config, host=host).validate()
        for options in ({"cert": Path("cert.pem")}, {"key": Path("key.pem")}):
            with self.assertRaises(ValueError):
                replace(self.config, **options).validate()
        replace(self.config, host="0.0.0.0", cert=Path("cert.pem"), key=Path("key.pem"),
                public_url="https://relay.example.test").validate()

    def test_formal_public_urls_require_https_and_reject_embedded_credentials(self):
        for url in ("http://relay.example.test", "http://192.0.2.10", "ftp://relay.example.test",
                    "https://user:secret@relay.example.test", "https://@relay.example.test",
                    "https://relay.example.test?token=secret", "https://relay.example.test#secret",
                    "https://relay.example.test:not-a-port", "https://relay.example.test:70000"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                replace(self.config, public_url=url).validate()
        replace(self.config, public_url="https://relay.example.test:9443").validate()

    def test_plain_http_is_limited_to_explicit_local_development_addresses(self):
        for url in ("http://127.0.0.1:8765", "http://localhost:8765", "http://[::1]:8765", "http://10.0.2.2:8765"):
            with self.subTest(url=url):
                replace(self.config, public_url=url).validate()
        with self.assertRaises(ValueError):
            replace(self.config, public_url="http://127.0.0.1.example.test").validate()

    def test_listener_port_and_workspace_identity(self):
        for port in (0, -1, 65536):
            with self.assertRaises(ValueError):
                replace(self.config, port=port).validate()
        self.config.prepare()
        self.assertEqual(self.config.node_id, replace(self.config, name="Different label").node_id)
        self.assertNotEqual(self.config.node_id, replace(self.config, workspace=self.config.workspace / "other").node_id)
        self.assertTrue(self.config.database_path.parent.is_dir())

    def test_two_real_serve_processes_cannot_own_one_workspace(self):
        # The HTTP listener and workspace lock are real. Native startup is a
        # no-op in both children, so this never probes the user's runtime.
        bootstrap = """
import sys
from unittest.mock import patch
from rivo_relay.__main__ import main
class NoNative:
    def __init__(self, **kwargs): pass
    def start(self): pass
    def status(self): return {'connected': False, 'capabilities': {}}
    def close(self): pass
with patch('rivo_relay.codex.CodexService', NoNative):
    sys.exit(main())
"""
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            port = reservation.getsockname()[1]
        env = dict(os.environ)
        env["PYTHONPATH"] = str(Path(__file__).resolve().parents[1])
        command = [sys.executable, "-c", bootstrap, "serve", "--workspace", str(self.config.workspace),
                   "--port", str(port), "--url", f"http://127.0.0.1:{port}",
                   "--codex-home", str(self.config.workspace / "unused-codex-home")]
        first = subprocess.Popen(command, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            ready, _, _ = select.select([first.stdout], [], [], 5)
            self.assertTrue(ready, "First isolated serve did not become ready")
            self.assertIn("Rivo Relay listening", first.stdout.readline())
            second = subprocess.run(command, env=env, capture_output=True, text=True, timeout=5)
            self.assertEqual(second.returncode, 1)
            self.assertIn("already owns this workspace", second.stderr)
            client = http.client.HTTPConnection("127.0.0.1", port, timeout=2)
            try:
                client.request("GET", "/health")
                response = client.getresponse()
                self.assertEqual(response.status, 200)
                self.assertEqual(json.loads(response.read()), {"status": "ok"})
            finally:
                client.close()
        finally:
            first.terminate()
            try:
                first.wait(timeout=5)
            except subprocess.TimeoutExpired:
                first.kill()
                first.wait(timeout=2)
            first.stdout.close()
            first.stderr.close()


@unittest.skipUnless(shutil.which("openssl"), "openssl is required for temporary TLS certificates")
class TLSHTTPTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.directory.cleanup)
        cls.root = Path(cls.directory.name)
        cls.ca = cls.root / "ca.pem"
        cls.cert = cls.root / "server.pem"
        cls.key = cls.root / "server.key"
        ca_config = cls.root / "ca.cnf"
        ca_config.write_text("""[req]
distinguished_name=dn
x509_extensions=v3_ca
prompt=no
[dn]
CN=Rivo isolated test CA
[v3_ca]
basicConstraints=critical,CA:TRUE
keyUsage=critical,keyCertSign,cRLSign
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid:always
""")
        server_extensions = cls.root / "server.ext"
        server_extensions.write_text("""basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:localhost
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid:always,issuer
""")

        def openssl(*args):
            subprocess.run([shutil.which("openssl"), *map(str, args)], check=True,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)

        for prefix in ("ca", "wrong-ca"):
            openssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-sha256",
                    "-config", ca_config, "-keyout", cls.root / f"{prefix}.key", "-out", cls.root / f"{prefix}.pem")
        openssl("req", "-new", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=localhost",
                "-keyout", cls.key, "-out", cls.root / "server.csr")
        openssl("x509", "-req", "-in", cls.root / "server.csr", "-CA", cls.ca,
                "-CAkey", cls.root / "ca.key", "-CAcreateserial", "-days", "1", "-sha256",
                "-extfile", server_extensions, "-out", cls.cert)

    def setUp(self):
        self.workspace = tempfile.TemporaryDirectory()
        self.addCleanup(self.workspace.cleanup)
        config = Config(workspace=Path(self.workspace.name), public_url="https://localhost",
                        cert=self.cert, key=self.key, codex_home=Path(self.workspace.name) / "unused")
        self.app = RelayApp(config, UnusedNative())
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(self.cert, self.key)
        self.server = BoundedHTTPServer(("127.0.0.1", 0), Handler, app=self.app,
                                        max_workers=4, max_streams=1, tls_context=context)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever,
                                       kwargs={"poll_interval": 0.01}, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop)

    def stop(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)
        self.app.close()

    def request(self, host, ca_file):
        # An explicit per-client trust file; no system trust-store modifications.
        context = ssl.create_default_context(cafile=str(ca_file))
        client = http.client.HTTPSConnection(host, self.port, timeout=3, context=context)
        try:
            client.request("GET", "/health")
            response = client.getresponse()
            return response.status, json.loads(response.read())
        finally:
            client.close()

    def test_explicitly_trusted_ca_and_correct_hostname_complete_real_https(self):
        self.assertEqual(self.request("localhost", self.ca), (200, {"status": "ok"}))

    def test_wrong_hostname_is_rejected_without_disabling_verification(self):
        with self.assertRaises(ssl.SSLCertVerificationError):
            self.request("127.0.0.1", self.ca)
        self.assertEqual(self.request("localhost", self.ca)[0], 200)

    def test_wrong_ca_is_rejected_without_disabling_verification(self):
        with self.assertRaises(ssl.SSLCertVerificationError):
            self.request("localhost", self.root / "wrong-ca.pem")
        self.assertEqual(self.request("localhost", self.ca)[0], 200)


if __name__ == "__main__":
    unittest.main()
