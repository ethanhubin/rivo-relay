#!/usr/bin/env python3
"""Bounded, payload-free Linux TCP sampling for the public HTTPS download port.

Correlate local/peer endpoints and timestamps with HAProxy request completion logs.
Counters are connection-wide; delta_bytes_acked is NOT a per-request body count.
"""
import argparse
import datetime
import json
import logging
from logging.handlers import RotatingFileHandler
import re
import subprocess
import time

COUNTERS = ('bytes_sent', 'bytes_acked', 'bytes_retrans', 'segs_out', 'data_segs_out',
            'delivered', 'lost', 'sacked', 'unacked', 'cwnd', 'ssthresh', 'snd_wnd',
            'notsent', 'rcv_space', 'rcv_ooopack', 'reordering')


def parse_ss(raw):
    result = []
    current = None
    for line in raw.splitlines():
        fields = line.split()
        if fields and fields[0] == 'ESTAB' and len(fields) >= 5:
            current = {'local': fields[3], 'peer': fields[4],
                       'recv_queue': int(fields[1]), 'send_queue': int(fields[2])}
            result.append(current)
        elif current is not None and line[:1].isspace() and fields:
            if fields[0] in ('bbr', 'cubic', 'reno'):
                current['algorithm'] = fields[0]
            for key in COUNTERS:
                m = re.search(r'\b' + key + r':(\d+)', line)
                if m:
                    current[key] = int(m[1])
            for key in ('rtt', 'minrtt', 'rto'):
                m = re.search(r'\b' + key + r':([\d.]+)', line)
                if m:
                    current[key + '_ms'] = float(m[1])
            for key in ('busy', 'rwnd_limited', 'sndbuf_limited'):
                m = re.search(r'\b' + key + r':(\d+)ms', line)
                if m:
                    current[key + '_ms'] = int(m[1])
            m = re.search(r'\bretrans:(\d+)/(\d+)', line)
            if m:
                current['retrans_inflight'], current['total_retrans'] = map(int, m.groups())
    return result


def changed_sample(current, previous, elapsed):
    result = dict(current)
    if previous is not None:
        result['sample_interval_ms'] = round(elapsed * 1000)
        for key in ('bytes_acked', 'bytes_sent', 'bytes_retrans', 'total_retrans',
                    'busy_ms', 'rwnd_limited_ms', 'sndbuf_limited_ms'):
            if key in current and key in previous and current[key] >= previous[key]:
                result['delta_' + key] = current[key] - previous[key]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=8443)
    parser.add_argument('--interval', type=float, default=1)
    parser.add_argument('--log', required=True)
    parser.add_argument('--once', action='store_true')
    args = parser.parse_args()
    if not 1 <= args.port <= 65535 or not 0.5 <= args.interval <= 60:
        parser.error('Invalid port or interval')
    logger = logging.getLogger('download-tcp')
    logger.setLevel(logging.INFO)
    handler = RotatingFileHandler(args.log, maxBytes=2 * 1024 * 1024, backupCount=3)
    handler.setFormatter(logging.Formatter('%(message)s'))
    logger.addHandler(handler)
    previous = {}
    def emit(event, **fields):
        logger.info(json.dumps({'time': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                                'event': event, **fields}, separators=(',', ':')))
    while True:
        started = time.monotonic()
        try:
            process = subprocess.run(['ss', '-tinH', 'state', 'established',
                                      '( sport = :' + str(args.port) + ' )'],
                                     capture_output=True, text=True, timeout=3, check=True)
            # With an explicit state filter ss omits the State column.
            raw = '\n'.join(('ESTAB ' + line if line and not line[0].isspace() else line)
                            for line in process.stdout.splitlines())
            rows = parse_ss(raw)
            now = time.monotonic()
            observed = {}
            for row in rows[:512]:
                key = (row['local'], row['peer'])
                prior = previous.get(key)
                observed[key] = (row, now)
                if prior is None or row != prior[0]:
                    emit('public_tcp_sample', **changed_sample(row, prior[0] if prior else None,
                                                              now - prior[1] if prior else 0))
            for key, (row, _) in previous.items():
                if key not in observed:
                    emit('public_tcp_closed', local=key[0], peer=key[1],
                         last_bytes_acked=row.get('bytes_acked'), last_total_retrans=row.get('total_retrans'))
            if len(rows) > 512:
                emit('public_tcp_truncated', count=len(rows), limit=512)
            previous = observed
        except (subprocess.SubprocessError, OSError, ValueError) as error:
            emit('public_tcp_sample_failed', error_type=type(error).__name__)
        if args.once:
            break
        time.sleep(max(0, args.interval - (time.monotonic() - started)))


if __name__ == '__main__':
    main()
