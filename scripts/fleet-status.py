#!/usr/bin/env python3
"""Administrator fleet monitor. Run on VPS or tunnel its HTTPS port over SSH."""
import argparse
import json
import os
import secrets
from pathlib import Path
import ssl
import sys
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='https://127.0.0.1:80')
    parser.add_argument('--token-file', type=Path, default=Path('/var/lib/rivo-gateway/monitor-token'))
    parser.add_argument('--ca-file', default='/var/lib/rivo-gateway/server.pem')
    parser.add_argument('--json', action='store_true')
    parser.add_argument('--init-token', action='store_true', help='Create local admin token once (0600); never overwrite')
    args = parser.parse_args()
    try:
        if args.init_token:
            fd = os.open(args.token_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, 'w') as stream:
                stream.write(secrets.token_hex(32) + '\n')
            print('管理员令牌已创建；请确保网关服务用户可读取。')
            return 0
        from urllib.parse import urlsplit
        if urlsplit(args.url).scheme != 'https':
            raise ValueError('HTTPS required')
        # Trust only the supplied certificate; localhost tunnels need not match its IP SAN.
        context = ssl.create_default_context(cafile=args.ca_file)
        context.check_hostname = False
        request = urllib.request.Request(args.url.rstrip('/') + '/admin/monitor',
                    headers={'Authorization': 'Bearer ' + args.token_file.read_text().strip()})
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *args, **kwargs):
                return None
        opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=context), NoRedirect())
        with opener.open(request, timeout=10) as response:
            data = json.load(response)
        if args.json:
            print(json.dumps(data, ensure_ascii=False, indent=2))
        else:
            print(f"槽位 {data['registeredSlots']} | 在线 {data['onlineSlots']} | 新鲜节点报告 {data['reportingRelayNodes']}")
            scope = '完整' if data['appCoverageComplete'] else '部分，未上报不计为零'
            print(f"活跃 App 配对 {data['activePairings']}（{scope}；跨节点不按物理设备去重）")
            print('在线\t节点/槽位\t版本码\t活跃配对\t请求/SSE\t数据通道\t更新状态')
            for row in data['nodes']:
                report, apps = row['report'], row['apps']
                update = report.get('update', {})
                print('\t'.join(map(str, [
                    '是' if row['online'] else '否', report.get('name') or report.get('nodeId') or row['slot'],
                    str(report.get('releaseCode', '?')) + ('' if row['telemetryFresh'] else '（过期/未知）'),
                    apps['activePairings'] if apps else '?', apps['inFlightRequests'] if apps else '?',
                    row['dataConnections'], update.get('phase', '?') + ':' + update.get('reason', '')])))
        return 0
    except (OSError, ValueError) as error:
        print('监控查询失败：' + type(error).__name__ + '；检查服务、证书与管理员令牌文件。', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
