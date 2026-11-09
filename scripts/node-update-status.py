#!/usr/bin/env python3
"""Inspect sanitized update state, or explicitly reset a stopped agent's retry."""
import argparse
import json
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
from rivo_relay.gateway.update_status import public_status
from rivo_relay.gateway.agent_maintenance import retry_candidate

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--retry-sha256', help='Only with the agent service stopped; never replays user work')
    args = parser.parse_args()
    if args.retry_sha256:
        retry_candidate(args.workspace, args.retry_sha256)
        print('Retry reset for the selected package; start the existing agent service.')
    else:
        print(json.dumps(public_status(args.workspace), ensure_ascii=False, indent=2))
