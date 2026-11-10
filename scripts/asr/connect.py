"""Dedicated outbound VPS connector; no dependency on Tailscale."""
import asyncio
import json
import logging
import sys
from pathlib import Path
sys.path.insert(0, '/Users/e2/Applications/Rivo-Relay/server')
from rivo_relay.gateway.config import decode_key
from rivo_relay.gateway.connector import Connector
root = Path(__file__).resolve().parent
key = decode_key(json.loads((root / 'gateway.json').read_text())['key'])
logging.basicConfig(level=logging.INFO)
asyncio.run(Connector(key['gateway'], key, 19092).run())
