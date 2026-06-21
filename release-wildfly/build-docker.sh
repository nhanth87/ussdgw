#!/bin/bash
set -euo pipefail

VERSION="${USSD_VERSION:-7.2.1-SNAPSHOT}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAIN_SLEE_AS7_DIR="${SCRIPT_DIR}/../../jain-slee/jain-slee/container/build/as7"

echo "=== Step 0: Rebuild SLEE AS7 modules (Maven) ==="
cd "${JAIN_SLEE_AS7_DIR}"
mvn -q clean package -Dmaven.test.skip=true

echo ""
echo "=== Step 1: Build Linux release package ==="
cd "${SCRIPT_DIR}"
ant -f build-linux.xml clean release

echo ""
echo "=== Step 2: Build Docker image ==="
docker build \
  --build-arg "USSD_VERSION=${VERSION}" \
  -t "restcomm-ussd:${VERSION}" \
  -t "restcomm-ussd:latest" \
  .

echo ""
echo "=== Done ==="
echo "Image: restcomm-ussd:${VERSION}"
echo ""
echo "Host setup:  sudo ./setup-server.sh"
echo "Production:  docker compose up -d"
echo "Dev:         docker compose -f docker-compose.dev.yml up -d"
