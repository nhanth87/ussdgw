#!/bin/bash
# Optional host setup — same logic as compose "init" service (for SCTP check / no-compose use).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Running host-init via one-shot container (same as docker compose init service)..."
docker run --rm --user 0:0 \
  -v /opt/ussdgw:/opt/ussdgw \
  -v "${SCRIPT_DIR}/config-seed:/bundled-seed:ro" \
  -v "${SCRIPT_DIR}/standalone.conf:/bundled/standalone.conf:ro" \
  -v "${SCRIPT_DIR}/scripts/host-init.sh:/host-init.sh:ro" \
  alpine:3.19 \
  /bin/sh /host-init.sh

echo ""
if lsmod 2>/dev/null | grep -q sctp; then
    echo "SCTP kernel module: loaded"
else
    echo "WARN: SCTP not loaded on host — run: sudo modprobe sctp"
fi

echo ""
echo "Host ready. Start gateway with:"
echo "  docker compose up -d"
echo "  docker compose -f docker-compose.dev.yml up -d"
