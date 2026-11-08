#!/bin/sh
# Run on the VPS as root after cloning the repository.
set -eu
RIVO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
RIVO_IP=${1:?Usage: scripts/install-gateway-service.sh PUBLIC_IP [PORT]}
RIVO_PORT=${2:-80}
cd "$RIVO_ROOT"
if [ ! -x .venv/bin/python ]; then python3 -m venv .venv; fi
.venv/bin/pip install -e './server[gateway]'
if ! id rivo-gateway >/dev/null 2>&1; then useradd --system --home /var/lib/rivo-gateway --shell /sbin/nologin rivo-gateway; fi
install -d -m 700 -o rivo-gateway -g rivo-gateway /var/lib/rivo-gateway
cat > /etc/systemd/system/rivo-gateway.service <<EOF
[Unit]
Description=Rivo Relay IP Gateway
After=network-online.target
Wants=network-online.target
[Service]
User=rivo-gateway
Group=rivo-gateway
WorkingDirectory=$RIVO_ROOT
ExecStart=$RIVO_ROOT/.venv/bin/python -m rivo_relay.gateway gateway --workspace /var/lib/rivo-gateway --ip $RIVO_IP --port $RIVO_PORT
Restart=on-failure
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/rivo-gateway
AmbientCapabilities=CAP_NET_BIND_SERVICE
CapabilityBoundingSet=CAP_NET_BIND_SERVICE
[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now rivo-gateway.service
printf 'Access key is in /var/lib/rivo-gateway/access-key.json (owner-only).\n'
