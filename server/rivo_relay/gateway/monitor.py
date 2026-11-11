"""Allowlisted fleet telemetry. Credentials and client identifiers never leave nodes."""
import hmac
import time
from aiohttp import web


def clean_report(value):
    if not isinstance(value, dict) or value.get('kind') not in ('relay', 'service'):
        return None
    result = {'kind': value['kind']}
    for key in ('nodeId', 'name'):
        if isinstance(value.get(key), str):
            result[key] = ''.join(c for c in value[key][:128] if c.isprintable())
    if type(value.get('releaseCode')) is int and value['releaseCode'] >= 0:
        result['releaseCode'] = value['releaseCode']
    if type(value.get('nativeConnected')) is bool:
        result['nativeConnected'] = value['nativeConnected']
    apps = value.get('apps')
    if isinstance(apps, dict) and all(type(apps.get(k)) is int and 0 <= apps[k] <= 1000000
                                    for k in ('activePairings', 'inFlightRequests', 'activityWindowSeconds')):
        result['apps'] = {k: apps[k] for k in ('activePairings', 'inFlightRequests', 'activityWindowSeconds')}
    update = value.get('update')
    if isinstance(update, dict):
        result['update'] = {k: v for k, v in update.items()
                            if k in ('phase', 'reason', 'activeCode', 'latestCode', 'stale', 'agentRevision')
                            and ((k in ('phase', 'reason') and isinstance(v, str) and len(v) <= 128 and v.isprintable())
                                 or (k == 'stale' and type(v) is bool)
                                 or (k in ('activeCode', 'latestCode', 'agentRevision') and type(v) is int and v >= 0))}
    return result


def snapshot(gateway):
    import json
    now = time.time()
    rows = []
    for slot in json.loads(gateway.keys_file.read_text()):
        ws = gateway.nodes.get(slot)
        online = ws is not None and not ws.closed
        report, at = gateway.reports.get(slot, ({}, None))
        fresh = online and at is not None and 0 <= now - at < 45
        rows.append({'slot': slot, 'online': online, 'reportedAt': at, 'telemetryFresh': fresh,
                     'dataConnections': len(gateway.active.get(slot, ())),
                     'report': report, 'apps': report.get('apps') if fresh else None})
    return {'schema': 1, 'observedAt': now, 'registeredSlots': len(rows),
            'onlineSlots': sum(r['online'] for r in rows),
            'reportingRelayNodes': sum(r['telemetryFresh'] and r['report'].get('kind') == 'relay' for r in rows),
            'activePairings': sum((r['apps'] or {}).get('activePairings', 0) for r in rows),
            'appCoverageComplete': all(r['apps'] is not None for r in rows if r['online']),
            'appCountScope': 'sum_of_node_pairings_not_unique_physical_devices', 'nodes': rows}


async def handle(request):
    gateway = request.app['fleet_gateway']
    try:
        token = (gateway.keys_file.parent / 'monitor-token').read_text().strip()
    except OSError:
        token = ''
    if len(token) < 32 or not hmac.compare_digest(request.headers.get('Authorization', ''), 'Bearer ' + token):
        raise web.HTTPForbidden()
    return web.json_response(snapshot(gateway), headers={'Cache-Control': 'no-store'})
