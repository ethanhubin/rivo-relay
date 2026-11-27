from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
from pathlib import Path
import signal
import ssl
import subprocess
import sys
from urllib.parse import urlencode

from .config import initialize, issue_key, save_private, discover_direct_url, node_gateway
from .runtime_options import add_runtime_arguments, relay_config, runtime_arguments, runtime_matches, runtime_settings


def main():
    parser = argparse.ArgumentParser(description='Rivo IP gateway and desktop setup')
    parser.add_argument('command', choices=['gateway', 'node', 'managed-node', 'issue-key', 'revoke-key'])
    parser.add_argument('--workspace', type=Path, default=Path('workspace/gateway'))
    parser.add_argument('--ip')
    parser.add_argument('--port', type=int, default=80)
    parser.add_argument('--listen-host', default='0.0.0.0', help='Gateway bind address (use 127.0.0.1 behind a local proxy)')
    parser.add_argument('--slot')
    parser.add_argument('--local-port', type=int, default=18765)
    parser.add_argument('--direct-url', default=None)
    parser.add_argument('--mode', choices=['direct', 'relay', 'auto'])
    parser.add_argument('--update-source', action='store_true', help='Advertise this node as the app update source')
    parser.add_argument('--attach', action='store_true', help='Connect an already-running local Relay')
    parser.add_argument('--pair-only', action='store_true', help='Generate a fresh QR for an already configured/running node')
    parser.add_argument('--configure', action='store_true', help='Replace the saved access credential interactively')
    add_runtime_arguments(parser)
    parser.add_argument('--no-pair', action='store_true', help='Background reconnect without creating a new pairing code')
    args = parser.parse_args()
    if args.command == 'node' and args.ip is not None:
        parser.error('Node setup accepts only a rivo2 credential; --ip is for gateway administration')
    os.umask(0o077)
    handlers = None
    if args.command == 'managed-node':
        from logging.handlers import RotatingFileHandler
        logs = args.workspace.resolve() / 'auto-update'
        logs.mkdir(parents=True, exist_ok=True)
        handlers = [RotatingFileHandler(logs / 'agent.log', maxBytes=2 * 1024 * 1024, backupCount=2)]
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s', handlers=handlers)
    if args.command in {'gateway', 'issue-key', 'revoke-key'}:
        from aiohttp import web
        from .server import Gateway
        if args.command == 'revoke-key':
            path = args.workspace / 'keys.json'
            keys = json.loads(path.read_text())
            if not args.slot or args.slot not in keys:
                parser.error('Specify an existing --slot')
            keys.pop(args.slot)
            save_private(path, keys)
            print('Key revoked; restart the gateway to close existing sessions immediately')
            return
        ip = args.ip or input('VPS public IP: ').strip()
        pin = initialize(args.workspace, ip)
        keys = json.loads((args.workspace / 'keys.json').read_text())
        if args.command == 'issue-key' or not keys:
            host = f"[{ip}]" if ":" in ip else ip
            access = issue_key(args.workspace, pin, f"https://{host}:{args.port}")
            save_private(args.workspace / 'access-key.json', {'key': access})
            print('Node access credential (includes VPS address) saved to', args.workspace / 'access-key.json', flush=True)
            if sys.stdout.isatty():
                print(access, flush=True)
        if args.command == 'issue-key':
            return
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(args.workspace / 'server.pem', args.workspace / 'server.key')
        web.run_app(Gateway(args.workspace / 'keys.json').application(), host=args.listen_host, port=args.port,
                    ssl_context=context, access_log=None, handler_cancellation=True)
    elif args.command == 'managed-node':
        from .updater import run
        asyncio.run(run(args))
    else:
        asyncio.run(node(args))


async def node(args):
    from .connector import Connector
    from ..config import Config
    from ..database import Database
    from ..pairing import PairingStore
    import qrcode
    workspace = args.workspace.resolve()
    path = workspace / 'gateway-client.json'
    stored = json.loads(path.read_text()) if path.exists() else {}
    managed = os.environ.get('RIVO_MANAGED_NODE') == '1'
    mode = args.mode or stored.get('mode') or 'auto'
    if mode not in ('auto', 'direct', 'relay'):
        raise ValueError('Invalid connection mode')
    direct = args.direct_url if args.direct_url is not None else stored.get('direct')
    if mode == 'relay':
        direct = ''
    elif direct is None:
        direct = discover_direct_url(args.local_port)
    if direct:
        Config(workspace=workspace, public_url=direct).validate()
    key, base = None, None
    if args.configure:
        stored.pop('key', None); stored.pop('gateway', None)
    if mode != 'direct':
        base, key, credential = node_gateway(stored)
        stored.update(gateway=base, key=credential)
    if mode == 'direct' and not direct:
        raise ValueError('Direct mode requires a Tailscale URL')
    runtime = runtime_settings(args, stored)
    stored.update(runtime)
    stored.update(mode=mode, direct=direct or '', updates=args.update_source or stored.get('updates', False))
    relay = base + '/relay/' + key['slot'] if key else ''
    public = direct or relay
    config = relay_config(workspace, args.local_port, runtime, public_url=public)
    runtime_identity = config.runtime_identity
    allow_legacy = 'runtime' not in runtime
    # Legacy connect-only deployments configured the Codex transport on the
    # already running Relay, so the connector cannot know that transport.
    allow_unconfigured_codex_attach = not managed and not runtime and not os.environ.get('RIVO_CODEX_RUNTIME')

    def matches_runtime(health, *, attached):
        if runtime_matches(health, config.runtime, runtime_identity, allow_legacy=allow_legacy):
            return True
        identity = health.get('runtimeIdentity')
        return (attached and allow_unconfigured_codex_attach and health.get('runtime') == 'codex'
                and isinstance(identity, str) and bool(identity))

    child = None
    connector_task = None
    previous_signals = {}
    loop = asyncio.get_running_loop()
    owner = asyncio.current_task()
    stopping = False

    def request_stop():
        nonlocal stopping
        if not stopping:
            stopping = True
            owner.cancel()

    from .update_status import ReadinessPublisher
    readiness = (ReadinessPublisher(os.environ['RIVO_MANAGED_READY'], os.environ['RIVO_UPDATE_TOKEN'])
                 if os.environ.get('RIVO_MANAGED_READY') else None)
    try:
        if not args.pair_only:
            # The supervisor can reject a trial as soon as its Relay exposes health.
            for sig in (signal.SIGTERM, signal.SIGINT):
                previous = signal.getsignal(sig)
                if os.name == 'nt':
                    signal.signal(sig, lambda *_: loop.call_soon_threadsafe(request_stop))
                else:
                    loop.add_signal_handler(sig, request_stop)
                previous_signals[sig] = previous
        import aiohttp
        async with aiohttp.ClientSession() as session:
            try:
                async with session.get(f'http://127.0.0.1:{args.local_port}/health', timeout=aiohttp.ClientTimeout(total=2)) as response:
                    identity = await response.json()
                    if identity.get('nodeId') != config.node_id:
                        raise ValueError('Local port belongs to another/older Relay; use its workspace or update that service first')
                    if managed:
                        raise ValueError('Managed node requires exclusive ownership; stop the old Relay service first')
                    if not matches_runtime(identity, attached=True):
                        raise ValueError('Local Relay runtime identity mismatch')
                    args.attach = True
            except (OSError, asyncio.TimeoutError):
                if args.attach:
                    raise RuntimeError('No running Relay to attach')
        if args.pair_only and not args.attach:
            raise RuntimeError('Start the node before generating a new pairing QR')
        if not args.attach:
            config.prepare()
        # A release trial uses the parent's launch configuration until it is committed.
        if not (managed and os.environ.get('RIVO_UPDATE_TRIAL') == '1'):
            save_private(path, stored)
        if not args.attach:
            command = [sys.executable, '-m', 'rivo_relay', 'serve', '--workspace', str(workspace),
                       '--host', '127.0.0.1', '--port', str(args.local_port), '--url', public,
                       *runtime_arguments(runtime)]
            if os.name == 'nt':
                from ..codex.windows_process import spawn_owned
                child = spawn_owned(command)
            else:
                child = subprocess.Popen(command)
        # Ensure the local service exists before advertising a usable QR.
        import aiohttp
        async with aiohttp.ClientSession() as session:
            for attempt in range(30):
                if child and child.poll() is not None:
                    raise RuntimeError('Relay failed to start; check workspace and local port')
                try:
                    async with session.get(f'http://127.0.0.1:{args.local_port}/health', timeout=aiohttp.ClientTimeout(total=2)) as response:
                        if response.status == 200:
                            identity = await response.json()
                            if identity.get('nodeId') != config.node_id:
                                raise ValueError('Local Relay workspace identity mismatch')
                            if not matches_runtime(identity, attached=args.attach):
                                raise ValueError('Local Relay runtime identity mismatch')
                            break
                except (OSError, asyncio.TimeoutError):
                    pass
                await asyncio.sleep(.5)
            else:
                raise RuntimeError('Local Relay unavailable')
        if key and not args.pair_only:
            def ready_changed(connected):
                if readiness:
                    readiness.publish(connected)
            connector = Connector(base, key, args.local_port, on_ready=ready_changed)
            connector_task = asyncio.create_task(connector.run())
            try:
                await asyncio.wait_for(connector.ready.wait(), 15)
            except TimeoutError:
                print('VPS 暂未连接，正在后台重试；可用直连入口不受影响。', flush=True)
        def publish_readiness():
            if readiness:
                readiness.publish(connector_task is not None and connector.ready.is_set())
        publish_readiness()
        if not args.no_pair:
            db = Database(config.database_path)
            try:
                pair = PairingStore(db).create(300)
            finally:
                db.close()
            fields = {'v': '2', 'code': pair['code'], 'node': config.node_id,
                      'direct': direct or '', 'relay': relay, 'pin': key['pin'] if key else '',
                      'updates': '1' if stored.get('updates') else '0'}
            link = public.rstrip('/') + '/pair#' + urlencode(fields)
            output = workspace / 'artifacts' / 'pairing.png'
            output.parent.mkdir(exist_ok=True, mode=0o700)
            qrcode.make(link).save(output)
            save_private(output.with_suffix('.json'), {'url': link, 'expiresIn': 300})
            if sys.stdout.isatty():
                qr = qrcode.QRCode(); qr.add_data(link); qr.print_ascii(invert=True)
            print('扫码配对，有效期 5 分钟。二维码：', output, flush=True)
        if args.pair_only:
            return
        while True:
            publish_readiness()
            if child and child.poll() is not None:
                raise RuntimeError('Local Relay exited')
            await asyncio.sleep(1)
    except asyncio.CancelledError:
        if not stopping:
            raise
    finally:
        stopping = True
        try:
            if connector_task:
                connector_task.cancel()
                await asyncio.gather(connector_task, return_exceptions=True)
            if child and getattr(child, '_rivo_job', None):
                try:
                    await asyncio.to_thread(child._rivo_job.terminate)
                    await asyncio.to_thread(child.wait, 10)
                finally:
                    child._rivo_job.close()
            elif child and child.poll() is None:
                child.terminate()
                await asyncio.to_thread(child.wait, 10)
        finally:
            for sig, previous in previous_signals.items():
                if os.name != 'nt':
                    loop.remove_signal_handler(sig)
                signal.signal(sig, previous)
            if readiness:
                readiness.publish(False)


if __name__ == '__main__':
    main()
