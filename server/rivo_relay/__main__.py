from __future__ import annotations

import datetime

import argparse
from .codex import native_history_support as fcntl
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
    from .release_management.setup import add_arguments
    add_arguments(commands.add_parser('setup-manager', help='将本机初始化为发布管理节点'))
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--workspace", type=Path, default=Path("workspace"))
    for name in ("serve", "pair", "connections", "revoke"):
        sub = commands.add_parser(name, parents=[common])
        if name == "serve":
            sub.add_argument("--host", default="127.0.0.1")
            sub.add_argument("--port", type=int, default=8765)
            sub.add_argument("--url", default="http://10.0.2.2:8765")
            sub.add_argument("--cert", type=Path)
            sub.add_argument("--key", type=Path)
            sub.add_argument("--native-socket", type=Path)
            sub.add_argument("--codex-home", type=Path, default=Path.home() / ".codex")
            sub.add_argument('--codex-runtime', choices=('auto', 'desktop', 'app_server', 'stdio'),
                             default=os.environ.get('RIVO_CODEX_RUNTIME', 'auto'))
            sub.add_argument('--desktop-bridge-dir', type=Path)
            sub.add_argument('--codex-executable', type=Path, help='Explicit local Codex executable for stdio mode')
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
    if args.command == 'setup-manager':
        from .release_management.setup import install
        try:
            print(json.dumps(install(args), ensure_ascii=False, indent=2))
            return 0
        except Exception as error:
            print(json.dumps({'error': str(error)}, ensure_ascii=False), file=sys.stderr)
            return 1
    config = Config(workspace=args.workspace.resolve(), host=getattr(args, "host", "127.0.0.1"),
        port=getattr(args, "port", 8765),
        public_url=getattr(args, "url", "http://10.0.2.2:8765"),
        cert=getattr(args, "cert", None), key=getattr(args, "key", None),
        codex_home=getattr(args, "codex_home", Path.home() / ".codex"),
        codex_runtime=getattr(args, 'codex_runtime', 'auto'),
        desktop_bridge_dir=getattr(args, 'desktop_bridge_dir', None),
        codex_executable=getattr(args, 'codex_executable', None))
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
                link = args.url.rstrip("/") + "/pair#" + urlencode({"v": "2", "code": result["code"], "node": config.node_id,
                    "direct": args.url.rstrip("/"), "relay": "", "pin": ""})
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
                if changed:
                    from .github_watch import GithubWatches
                    watches = GithubWatches(config.workspace / 'data' / 'github-watch.sqlite3')
                    try:
                        watches.purge_connection(args.connection_id)
                    finally:
                        watches.close()
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
        native = CodexService(socket_path=native_socket, codex_home=config.codex_home, on_event=bus.publish,
                              runtime_mode=config.codex_runtime, desktop_bridge_dir=config.desktop_bridge_dir, codex_executable=config.codex_executable)
        app = RelayApp(config, native, bus)
        server = None
        try:
            try:
                native.start()
            except Exception:
                print(json.dumps({"time": datetime.datetime.now(datetime.timezone.utc).isoformat(), "event": "native_unavailable"}), file=sys.stderr, flush=True)
            native.activity.start()
            app.queue.start()
            app.github_watches.start()
            tls = None
            if config.cert:
                tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
                tls.minimum_version = ssl.TLSVersion.TLSv1_2
                tls.load_cert_chain(config.cert, config.key)
            server = BoundedHTTPServer((config.host, config.port), Handler, app=app, tls_context=tls)
            signal.signal(signal.SIGTERM, lambda *_: threading.Thread(target=server.shutdown, daemon=True).start())
            print(json.dumps({"time": datetime.datetime.now(datetime.timezone.utc).isoformat(), "event": "service_started", "pid": os.getpid(), "port": config.port}), flush=True)
            server.serve_forever(poll_interval=0.2)
        except KeyboardInterrupt:
            pass
        finally:
            if server:
                server.server_close()
            app.close()
            print(json.dumps({"time": datetime.datetime.now(datetime.timezone.utc).isoformat(), "event": "service_stopped", "pid": os.getpid()}), flush=True)


if __name__ == "__main__":
    raise SystemExit(main())
