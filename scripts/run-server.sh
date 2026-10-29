#!/bin/sh
set -eu
RIVO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$RIVO_ROOT"
if [ ! -x .venv/bin/python ]; then
  echo 'Create the environment first: python3.11+ -m venv .venv && .venv/bin/pip install -e ./server' >&2
  exit 1
fi
exec .venv/bin/python -m rivo_relay serve "$@"
