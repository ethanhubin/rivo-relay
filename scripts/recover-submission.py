#!/usr/bin/env python3
"""Inspect or recover one unknown turn claim using reviewed evidence. Dry-run by default."""
import argparse
import json
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
from rivo_relay.submission_recovery import inspect, recover


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--submission', required=True)
    parser.add_argument('--evidence', type=Path)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    if args.apply and not args.evidence:
        parser.error('--apply requires --evidence')
    if args.evidence:
        result = recover(args.workspace, args.submission, json.loads(args.evidence.read_text()), apply=args.apply)
    else:
        result = inspect(args.workspace, args.submission)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
