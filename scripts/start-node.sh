#!/bin/sh
set -eu
RIVO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$RIVO_ROOT"
if [ ! -x .venv/bin/python ]; then python3 -m venv .venv; fi
if ! .venv/bin/python -c 'import rivo_relay, aiohttp' >/dev/null 2>&1; then
  .venv/bin/pip install -e './server[gateway]'
fi
exec .venv/bin/python -m rivo_relay.gateway node --workspace workspace/local-node "$@"
