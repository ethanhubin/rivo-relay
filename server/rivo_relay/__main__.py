from __future__ import annotations

import argparse
import fcntl
import json
import os
import signal
import ssl
import sys
import threading
from pathlib import Path
from urllib.parse import urlencode

from .config import Config
from .database import Database
from .pairing import PairingStore


def parser():
    root = argparse.ArgumentParser(prog="rivo-relay")
    commands = root.add_subparsers(dest="command", required=True)
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--workspace", type=Path, default=Path("workspace"))
    for name in ("serve", "pair", "connections", "revoke"):
        sub = commands.add_parser(name, parents=[common])
        if name == "serve":
            sub.add_argument("--host", default="127.0.0.1")
            sub.add_argument("--port", type=int, default=8765)
            sub.add_argument("--name", default="Rivo Relay")
            sub.add_argument("--url", default="http://10.0.2.2:8765")
            sub.add_argument("--cert", type=Path)
            sub.add_argument("--key", type=Path)
            sub.add_argument("--native-socket", type=Path)
            sub.add_argument("--codex-home", type=Path, default=Path.home() / ".codex")
        if name == "pair":
            sub.add_argument("--url", required=True)
            sub.add_argument("--ttl", type=int, default=300)
            sub.add_argument("--output", type=Path, help="Write QR PNG")
            sub.add_argument("--json-output", type=Path, help="Write pairing details to owner-only file")
        if name == "connections":
            sub.add_argument("--all", action="store_true")
        if name == "revoke":
            sub.add_argument("connection_id")
    return root


def main(argv=None):
    args = parser().parse_args(argv)
    os.umask(0o077)
    config = Config(workspace=args.workspace.resolve(), host=getattr(args, "host", "127.0.0.1"),
        port=getattr(args, "port", 8765), name=getattr(args, "name", "Rivo Relay"),
        public_url=getattr(args, "url", "http://10.0.2.2:8765"),
        cert=getattr(args, "cert", None), key=getattr(args, "key", None),
        codex_home=getattr(args, "codex_home", Path.home() / ".codex"))
    try:
        config.prepare()
        if args.command == "serve":
            serve(config, args.native_socket)
            return 0
        db = Database(config.database_path)
        pairing = PairingStore(db)
        try:
            if args.command == "pair":
                import qrcode
                result = pairing.create(args.ttl)
                link = args.url.rstrip("/") + "/pair#" + urlencode({"v": "1", "code": result["code"]})
                result["url"] = link
                image = qrcode.make(link).convert("RGB")
                if args.output:
                    args.output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                    image.save(args.output)
                if args.json_output:
                    args.json_output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                    args.json_output.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
                    args.json_output.chmod(0o600)
                    print("Pairing details written; expires in", args.ttl, "seconds")
                else:
                    print("Node:", args.url)
                    print("Code:", result["code"])
                    print("Link:", link)
                    print("Expires in", args.ttl, "seconds; usable once")
                    qr = qrcode.QRCode()
                    qr.add_data(link)
                    qr.print_ascii(invert=True)
                if args.output:
                    print("QR image:", args.output)
            elif args.command == "connections":
                print(json.dumps(pairing.list_connections(args.all), ensure_ascii=False, indent=2))
            elif args.command == "revoke":
                changed = pairing.revoke(args.connection_id)
                print("Connection revoked" if changed else "Connection not found or already revoked")
            return 0
        finally:
            db.close()
    except (ValueError, OSError) as error:
        print(str(error), file=sys.stderr)
        return 1


def serve(config: Config, native_socket: Path | None):
    from .api import Handler
    from .app import RelayApp
    from .codex import CodexService
    from .events import EventBus
    from .http_server import BoundedHTTPServer

    with (config.workspace / "data" / "serve.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError("A Relay service already owns this workspace")
        bus = EventBus()
        native = CodexService(socket_path=native_socket, codex_home=config.codex_home, on_event=bus.publish)
        app = RelayApp(config, native, bus)
        server = None
        try:
            try:
                native.start()
            except Exception:
                print("Native runtime unavailable; Relay will expose its connection status", file=sys.stderr)
            app.queue.start()
            tls = None
            if config.cert:
                tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
                tls.minimum_version = ssl.TLSVersion.TLSv1_2
                tls.load_cert_chain(config.cert, config.key)
            server = BoundedHTTPServer((config.host, config.port), Handler, app=app, tls_context=tls)
            signal.signal(signal.SIGTERM, lambda *_: threading.Thread(target=server.shutdown, daemon=True).start())
            print(f"Rivo Relay listening on {config.host}:{config.port}", flush=True)
            server.serve_forever(poll_interval=0.2)
        except KeyboardInterrupt:
            pass
        finally:
            if server:
                server.server_close()
            app.close()


if __name__ == "__main__":
    raise SystemExit(main())
