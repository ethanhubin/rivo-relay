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


def main():
    parser = argparse.ArgumentParser(description='Rivo IP gateway and desktop setup')
    parser.add_argument('command', choices=['gateway', 'node', 'issue-key', 'revoke-key'])
    parser.add_argument('--workspace', type=Path, default=Path('workspace/gateway'))
    parser.add_argument('--ip')
    parser.add_argument('--port', type=int, default=80)
    parser.add_argument('--slot')
    parser.add_argument('--local-port', type=int, default=18765)
    parser.add_argument('--direct-url', default=None)
    parser.add_argument('--mode', choices=['direct', 'relay', 'auto'])
    parser.add_argument('--update-source', action='store_true', help='Advertise this node as the app update source')
    parser.add_argument('--attach', action='store_true', help='Connect an already-running local Relay')
    parser.add_argument('--pair-only', action='store_true', help='Generate a fresh QR for an already configured/running node')
    parser.add_argument('--configure', action='store_true', help='Replace the saved access credential interactively')
    parser.add_argument('--no-pair', action='store_true', help='Background reconnect without creating a new pairing code')
    args = parser.parse_args()
    os.umask(0o077)
    logging.basicConfig(level=logging.INFO, format='%(levelname)s %(message)s')
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
        web.run_app(Gateway(args.workspace / 'keys.json').application(), host='0.0.0.0', port=args.port,
                    ssl_context=context, access_log=None, handler_cancellation=True)
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
        base, key, credential = node_gateway(stored, args.ip)
        stored.update(gateway=base, key=credential)
    if mode == 'direct' and not direct:
        raise ValueError('Direct mode requires a Tailscale URL')
    stored.update(mode=mode, direct=direct or '', updates=args.update_source or stored.get('updates', False))
    save_private(path, stored)
    relay = base + '/relay/' + key['slot'] if key else ''
    public = direct or relay
    config = Config(workspace=workspace, port=args.local_port, public_url=public)
    config.prepare()
    child = None
    connector_task = None
    try:
        import aiohttp
        async with aiohttp.ClientSession() as session:
            try:
                async with session.get(f'http://127.0.0.1:{args.local_port}/health', timeout=aiohttp.ClientTimeout(total=2)) as response:
                    identity = await response.json()
                    if identity.get('nodeId') != config.node_id:
                        raise ValueError('Local port belongs to another/older Relay; use its workspace or update that service first')
                    args.attach = True
            except (OSError, asyncio.TimeoutError):
                if args.attach:
                    raise RuntimeError('No running Relay to attach')
        if args.pair_only and not args.attach:
            raise RuntimeError('Start the node before generating a new pairing QR')
        if not args.attach:
            child = subprocess.Popen([sys.executable, '-m', 'rivo_relay', 'serve', '--workspace', str(workspace),
                                      '--host', '127.0.0.1', '--port', str(args.local_port), '--url', public])
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
                            break
                except (OSError, asyncio.TimeoutError):
                    pass
                await asyncio.sleep(.5)
            else:
                raise RuntimeError('Local Relay unavailable')
        if key and not args.pair_only:
            connector = Connector(base, key, args.local_port)
            connector_task = asyncio.create_task(connector.run())
            try:
                await asyncio.wait_for(connector.ready.wait(), 15)
            except TimeoutError:
                print('VPS 暂未连接，正在后台重试；可用直连入口不受影响。', flush=True)
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
        stop = asyncio.Event()
        for sig in (signal.SIGTERM, signal.SIGINT):
            asyncio.get_running_loop().add_signal_handler(sig, stop.set)
        while not stop.is_set():
            if child and child.poll() is not None:
                raise RuntimeError('Local Relay exited')
            try:
                await asyncio.wait_for(stop.wait(), 1)
            except TimeoutError:
                pass
    finally:
        if connector_task:
            connector_task.cancel()
            await asyncio.gather(connector_task, return_exceptions=True)
        if child and child.poll() is None:
            child.terminate()
            await asyncio.to_thread(child.wait, 10)


if __name__ == '__main__':
    main()
